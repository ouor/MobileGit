# 코드 리뷰 1차 — git 서버 / 미러 / JGit (02)

- 리뷰어 관점: JGit 기반 git 호스팅·미러링 운영 경험. smart HTTP, 미러 동기화·아카이브 보존, ref 원자성, 대용량 레포 성능, 동시 접근, 리소스 해제.
- 대상: `app/src/main/java/com/ouor/mobilegit/core/` (GitServer, MirrorEngine, Sync, RepoStore, Ops, RepoBrowser, JGitEnv, Util), 연결 지점으로 `app/Work.kt`.
- JGit 7.8.0, Ktor 3.6.0 기준. 저장소 코드는 수정하지 않았다.
- 스크래치 실험(JGit 7.8.0 jar로 직접 실행)으로 확인한 항목은 **[검증됨]**으로 표시했다.

## 요약

| 심각도 | 개수 | ID |
|---|---|---|
| 치명 | 0 | — |
| 높음 | 4 | GIT-01 ~ GIT-04 |
| 중간 | 5 | GIT-05 ~ GIT-09 |
| 낮음 | 7 | GIT-10 ~ GIT-16 |

---

## 높음

### GIT-01 프로세스가 죽은 뒤 남은 `*.lock` 파일 때문에 그 레포의 미러 업데이트와 push가 영구히 실패한다 [검증됨]
- 위치: `MirrorEngine.kt:112-131` (atomic `BatchRefUpdate`), `GitServer.kt:371-389` (ReceivePack), `RepoStore.kt:50-64` (load에 정리 단계 없음)
- 문제: JGit은 ref를 갱신할 때 `refs/heads/x.lock` 같은 잠금 파일을 `createNewFile`로 만든다. Android는 프로세스를 수시로 강제 종료한다(OOM killer, 배터리 정책, 업데이트 설치). ref를 쓰는 도중에 프로세스가 죽으면 lock 파일이 남고, JGit은 오래된 lock을 스스로 지우지 않는다.
- 실패 상황: 스크래치에서 `refs/heads/m0.lock`을 남겨 두고 `m0` 삭제와 `x` 생성을 atomic 배치로 실행하면 `m0=LOCK_FAILURE, x=REJECTED_OTHER_REASON`이 나오고 배치 전체가 거부된다. 이후 이 미러는 업데이트마다 `ref update failed: …LOCK_FAILURE`로 실패하고, `classify`는 이를 `REMOTE_ERROR`로 분류한다. 사용자는 원본 쪽 문제로 오해한다. 그사이 원본이 force-push하거나 삭제되면 **아카이브가 만들어지지 않아 제품의 핵심 약속(F-11)이 깨진다.** 로컬 레포의 push도 같은 이유로 계속 실패한다.
- 권장: 이 앱은 단일 프로세스이고 레포마다 Mutex를 쓰므로, 앱이 시작될 때 남아 있는 lock은 모두 오래된 것이다. `RepoStore.load()`에서(또는 `withRepoLock` 안에서 쓰기 작업을 시작하기 전에) 레포 디렉터리 아래의 `*.lock`(`refs/**`, `packed-refs.lock`, `config.lock`, `HEAD.lock`)을 지운다. `LOCK_FAILURE`는 별도 카테고리로 분류해 다음 시도 전에 정리하게 한다.

### GIT-02 커밋 수 세기와 FF 판정이 전체 히스토리를 본문째 메모리에 올린다. 큰 레포를 처음 미러하면 OOM으로 앱(서버 포함)이 죽는다
- 위치: `MirrorEngine.kt:137`, `MirrorEngine.kt:149-155` (`countNewCommits`), `MirrorEngine.kt:141-147` (`isFastForward`)
- 문제: `countNewCommits`는 `RevWalk` 기본값(`retainBody = true`)으로 새 tip 전체(refs/pull/* 포함)에서 기존 tip을 뺀 범위를 끝까지 센다. 첫 받기에는 기존 tip이 없으므로 **전체 히스토리의 모든 커밋**을 RevCommit 객체와 원문 버퍼째 보관한다. commit-graph도 없다(GIT-03). `isFastForward`는 force-push된 ref마다 `isMergedInto`로 새 tip의 히스토리를 끝까지 걷는다.
- 실패 상황: 커밋이 수십만 개 이상인 레포(linux, chromium, 큰 모노레포)를 처음 미러하면 객체 수 × (약 100B + 커밋 원문)만큼 힙을 써서 Android 앱 힙(256~512MB)을 넘는다. `OutOfMemoryError`는 `wrap`의 `catch (e: Exception)`에 걸리지 않아 프로세스가 죽는다. **같은 프로세스의 git 서버도 함께 죽는다.** ref는 이미 커밋된 뒤라 데이터는 남지만, 이벤트·`initialFetchDone`·아카이브 알림이 기록되지 않는다.
- 권장: `rw.isRetainBody = false`로 둔다. 첫 받기(`local.isEmpty()`)에서는 세지 않거나, 상한(예: 10,000에서 멈추고 "10000+"로 표시)을 둔다. 리뷰용 ref(refs/pull/*)는 세는 대상에서 뺀다. FF 판정에도 `retainBody=false`를 쓰고, 레포 크기가 크면 상한을 둔다.

### GIT-03 GC·repack이 전혀 없다. 업데이트·push마다 pack이 쌓여 시간이 지날수록 성능이 무너진다
- 위치: 전체(`gc`/`repack` 호출 없음). PRD 9장 "내부 자동 처리는 허용"은 미구현. 관련 설정은 `JGitEnv.kt:28-34`
- 문제: JGit fetch와 ReceivePack은 전송할 때마다 새 pack 파일을 만든다. 미러는 주기 확인 뒤 자동 업데이트하므로 레포마다 pack이 계속 쌓인다. 비트맵과 commit-graph가 없어서 clone할 때 객체를 세는 단계가 매번 전체 히스토리를 걷는다.
- 실패 상황: 몇 달 운영하면 레포 하나에 pack이 수백~수천 개 생긴다. 객체를 찾을 때마다 pack 목록을 선형 탐색한다. 기본 `packedGitOpenFiles`(128)를 넘으면 파일을 계속 열고 닫는다. WindowCache(32MB)도 계속 밀려난다. 그 결과 clone, fetch, 웹 브라우징, 아카이브 FF 판정이 모두 크게 느려진다. SC-7(학생 30명 동시 clone)에서 특히 심하다. stateless upload-pack이 want 도달 가능성을 검사할 때도 비트맵 없이 걷는다.
- 권장: 충전 중이고 유휴일 때 WorkManager로 `GC(repo).setPackExpireAgeMillis(…)`를 돌린다. pack 수가 임계값(예: 50)을 넘으면 실행하고, `withRepoLock` 안에서 실행해야 한다. 아카이브가 목적이므로 **prune은 끄거나 매우 보수적으로**(`gc.pruneExpire=never`) 설정한다. 비트맵(`pack.buildBitmaps`)을 켠다. GC를 넣을 때 `MirrorEngine.update`의 fetch와 ref 적용 사이에 pack이 지워지지 않도록 같은 락 안에 두는 것을 지킨다(현재 `conn.packLocks`는 lock 메시지를 설정하지 않아 비어 있다).

### GIT-04 요청마다 `FileRepository`를 새로 열어 pack index(.idx)를 매번 다시 읽는다. 큰 레포의 동시 clone이나 웹 탐색에서 메모리가 배로 늘어난다
- 위치: `RepoStore.kt:74` (`open`), 호출부 `GitServer.kt:271, 296, 334, 370, 391, 438`, `Sync.kt:120, 177`
- 문제: `FileRepositoryBuilder().build()`는 매번 새 `ObjectDirectory`와 새 `Pack` 인스턴스를 만든다. `.idx`는 `Pack` 인스턴스마다 메모리에 올라가므로, info/refs, upload-pack, 웹 API 호출(트리, README, 커밋)마다 해당 pack의 인덱스를 디스크에서 다시 읽어 파싱한다. JGit의 servlet 구현은 `RepositoryCache`로 이를 공유한다.
- 실패 상황: 객체가 수백만 개인 레포는 .idx만 수십~수백 MB다. 동시 pack 슬롯 4개에 웹 요청까지 겹치면 같은 인덱스가 힙에 4~N벌 올라가 OOM에 이르거나 GC가 멈춘다. 작은 레포에서도 요청마다 pack 디렉터리 스캔과 idx 로드 비용이 든다(웹 UI 한 화면에 API 호출이 여러 개).
- 권장: `RepositoryCache.FileKey.exact(dir, FS.DETECTED)`와 `RepositoryCache.open(key)`(참조 카운트 기반 `close`)로 레포마다 하나의 인스턴스를 공유한다. 삭제·fork CONVERT 때 `RepositoryCache.unregister`를 호출한다.

---

## 중간

### GIT-05 삭제가 크래시에 안전하지 않다. 중간에 죽으면 깨진 레포가 "로컬 레포"로 되살아난다
- 위치: `RepoStore.kt:126-132` (`deleteData`), `RepoStore.kt:54-60` (load의 adopt), `RepoStore.kt:87` (create의 `deleteRecursively`)
- 문제: `deleteRecursively()`는 자식부터 임의 순서로 지우고, 반환값(실패 여부)은 무시된다. `mobilegit/meta.json`이 `HEAD`나 `objects/`보다 먼저 지워질 수 있다.
- 실패 상황: 큰 레포를 삭제하는 도중에 프로세스가 죽으면 HEAD는 있고 meta가 없는 디렉터리가 남는다. 다음 시작 때 `load()`가 이를 `LOCAL / createdBy=PUSH`로 adopt한다. 그러면 지웠던 레포가 객체 일부가 빠진 상태로 목록과 서버에 다시 나타난다(clone하면 `missing object`). 권한 문제 등으로 삭제가 일부만 실패해도 공간은 반환되지 않고 같은 일이 생긴다.
- 권장: 먼저 `<name>.git`을 `.trash-<uuid>`로 원자적으로 rename한 뒤 지운다. 시작할 때 `.trash-*`와 `.tmp-*`(fork 잔여물, `Ops.kt:54`)를 정리한다. `deleteRecursively()`가 false를 반환하면 기록해 둔다.

### GIT-06 `receive.fsckobjects=true`(엄격 모드) 때문에 오래된 실제 레포의 push가 거부된다 [검증됨]
- 위치: `RepoStore.kt:104`
- 문제: JGit `ObjectChecker` 기본값은 zero-padded file mode(`040000`)와 이메일이 빠진 ident를 오류로 처리한다. 스크래치 결과: `mode starts with '0'`, `missing email`. GitHub의 오래된 레포 중 이런 객체가 있는 경우가 꽤 있다. 같은 레포를 미러로 받을 때는 fsck가 없어서 통과한다.
- 실패 상황: PC에 있는 오래된 레포를 `git push --mirror phone`으로 폰에 올리면 "unpack error"로 **모든 ref가 거부**된다(atomic). 미러를 clone해서 새 이름으로 push해도 같다. 사용자 입장에서는 이유를 알기 어렵다.
- 권장: fsck는 유지하되 흔한 레거시 항목은 무시하도록 설정한다(`fsck.allowLeadingZeroFileMode=true`, `fsck.allowInvalidPersonIdent=true`, 또는 `receive.fsck.zeroPaddedFilemode=ignore` 등). 이미 만들어진 레포도 `tuneConfig`를 다시 적용하는 마이그레이션을 넣는다.

### GIT-07 로컬(분리된) 레포에서 `refs/archive/*`가 보호되지 않고 reflog도 없어, push 한 번으로 보존 기록이 복구 불가능하게 사라진다
- 위치: `GitServer.kt:371-385` (PreReceiveHook 없음), `RepoStore.kt:103` (`logallrefupdates=false`), `Ops.kt:43-46` (CONVERT는 아카이브를 유지)
- 문제: F-20 AC3은 분리할 때 "보존 ref를 유지"한다고 하지만, 분리된 레포는 push를 받으므로 `refs/archive/*`를 삭제하거나 덮어쓸 수 있다. reflog는 꺼져 있다. stateless upload-pack(ADVERTISED 정책)은 도달할 수 없는 SHA를 want로 받지 않으므로, `pushes.json`에 old SHA가 기록되어 있어도 프로토콜로는 되찾을 수 없다.
- 실패 상황: 사용자가 분리한 레포에 PC에서 `git push --mirror phone`을 실행한다. 클라이언트에는 `refs/archive/*`가 없으므로 서버 쪽 아카이브 ref가 모두 삭제된다. 원본이 이미 사라진 레포라면 마지막 사본이 없어진다.
- 권장: 모든 레포에서 `refs/archive/` 아래의 갱신과 삭제를 PreReceiveHook으로 거부한다(`REJECTED_OTHER_REASON`, 메시지 포함). 또는 ReceivePack 광고에서 숨긴다(`setAdvertiseRefsHook`/`hideRefs`). 로컬 레포의 force-push·삭제에는 `logallrefupdates=true`를 켜는 방안을 검토한다(reflog는 GC 만료 정책과 함께).

### GIT-08 큰 레포의 첫 받기가 끝나지 못하고 처음부터 다시 받기를 반복할 수 있으며, 받는 중에는 저장 공간 하한을 지키지 않는다
- 위치: `Work.kt:111` (`runCatching { setForeground(...) }`), `MirrorEngine.kt:95-99`, `Sync.kt:159-164` (공간 확인은 시작할 때만)
- 문제: (1) Android 12 이상에서 백그라운드 상태로 `setForeground`가 실패하면 일반 Worker의 실행 시간 제한(약 10분)에 걸린다. JGit fetch는 이어 받기가 되지 않아, 중단되면 임시 pack을 버리고 다음 시도에서 처음부터 다시 받는다. (2) fetch 중에는 pack 크기 제한이나 공간 확인이 없다.
- 실패 상황: (1) 모바일망에서 수 GB 레포를 일괄 미러하면 10분마다 잘리고, 백오프 뒤 또 처음부터 받으면서 데이터를 계속 소모한다. 슬롯(2개)을 기다리는 시간도 10분 제한에 포함된다. (2) 남은 공간보다 큰 레포를 받으면 기기 저장소가 0바이트까지 차서 다른 레포의 push, meta 기록, 다른 앱까지 실패한다(fetch가 실패하면 임시 파일은 지워진다).
- 권장: 첫 받기는 expedited/foreground가 확실히 보장될 때만 시작하고, 실패하면 `WAITING`으로 표시한다. `Monitor.isCancelled()`에서 `env.freeBytes() < floor`를 함께 확인해 공간 하한에 닿으면 취소한다. 중단이 반복되는 레포는 사용자에게 알린다.

### GIT-09 웹 커밋 API는 걷는 범위에 상한이 없고 웹 API 전체에 동시성 제한이 없다. 익명 읽기 상태에서도 폰을 멈출 수 있다
- 위치: `RepoBrowser.kt:245-261` (`commits`), `GitServer.kt:424-477` (pack 슬롯이나 시간 제한 없음)
- 문제: `page`에 상한이 없어 `(page-1)*perPage`개를 건너뛰며 걷는다. RevWalk는 `retainBody=true`로 지나간 커밋을 모두 보관한다. 경로 필터(`ANY_DIFF`)를 쓰면 커밋마다 tree diff를 수행한다.
- 실패 상황: `GET /api/v1/repos/linux/commits?page=100000&path=rare/file` 요청 몇 개만으로 CPU를 점유하고 수백 MB를 할당해 OOM에 이른다(같은 프로세스의 git 서버도 죽는다). 익명 읽기가 켜져 있으면 인증 없이 가능하다.
- 권장: 웹 API 무거운 경로에 별도 Semaphore(예: 2)와 `withTimeout`을 두고, `retainBody=false`(DTO에 필요한 커밋만 `parseBody`)를 쓴다. `page × perPage`에 상한을 두거나 커서 방식(`?after=<sha>`)으로 바꾼다.

---

## 낮음

### GIT-10 HEAD가 존재하지 않는 브랜치를 가리킬 수 있다 (push로 생성된 레포에서만 보정)
- 위치: `GitServer.kt:377`, `GitServer.kt:407-411`, `RepoStore.kt:82` (`initialBranch = "main"`)
- 문제와 상황: 앱에서 만든 빈 레포(`APP_EMPTY`)에 `master`만 push하거나, 기본 브랜치를 push로 삭제하면 HEAD가 `refs/heads/main`(없음)에 남는다. clone하면 "remote HEAD refers to nonexistent ref"가 나오고 웹 UI는 빈 레포처럼 보인다. 미러도 원격이 HEAD symref를 광고하지 않으면 같은 상태가 된다.
- 권장: push 뒤 HEAD가 가리키는 ref가 없으면(생성 여부와 무관하게) `chooseHead`를 적용한다.

### GIT-11 타입·이름 검사가 레포 락과 store 동기화 밖에서 이루어진다
- 위치: `GitServer.kt:355-357, 366-367` (미러 여부 검사가 락 밖), `Ops.kt:50-66` (fork COPY의 대상 이름 예약 없음), `RepoStore.kt:86-87`
- 실패 상황: (a) 같은 이름으로 push 생성과 미러 추가가 동시에 일어나면, push가 락을 얻었을 때 이미 MIRROR가 된 레포에 pack과 ref가 들어간다. (b) fork COPY가 `tmp → <target>.git` rename을 마친 뒤 `adopt`하기 전에 같은 이름으로 push 생성이 들어오면, `create()`가 `dir.exists()`를 보고 **방금 복제한 데이터를 `deleteRecursively`**한다. 그 뒤 `adopt`가 FORK meta로 덮어쓴다. 원본 미러는 남아 있어 복구할 수는 있다.
- 권장: 락 안에서 `store.get(name)?.type`을 다시 확인한다. fork 대상 이름도 `withRepoLock(target)`로 잡는다. `create()`는 meta가 없는 기존 디렉터리를 지우지 말고 `NAME_TAKEN`으로 거부한다.

### GIT-12 아카이브 시각이 초 단위라 충돌할 수 있고, 취소 시점에 따라 이미 적용된 아카이브의 기록이 사라진다 [충돌은 검증됨]
- 위치: `MirrorEngine.kt:111, 121`, `Sync.kt:176-187`
- 문제와 상황: 같은 초에 두 번 업데이트되어 같은 ref를 다시 아카이브하면 `refs/archive/<같은 stamp>/…` 생성이 `LOCK_FAILURE`로 실패하고 배치 전체가 거부된다(스크래치 확인). 수동 업데이트를 연타하거나 테스트용 Clock을 쓸 때 생길 수 있다. 또 `withContext(Dispatchers.IO)` 블록이 ref를 적용한 직후 코루틴이 취소되면 prompt cancellation으로 `CancellationException`이 던져진다. 그러면 ref는 바뀌었는데 이력, `ARCHIVED` 알림, meta가 기록되지 않는다. 아카이브가 조용히 일어나는 셈이다.
- 권장: stamp에 밀리초를 넣거나, 이미 있으면 `-2` 같은 접미사를 붙인다. ref를 적용한 뒤의 기록은 `withContext(NonCancellable)`로 감싼다.

### GIT-13 내보내기의 일관성과 형식 문제
- 위치: `Ops.kt:143-147` (manifest와 bundle이 ref를 따로 읽음), `Ops.kt:138-157` (DEFLATE), `Ops.kt:120-131` (source zip)
- 문제와 상황: (a) 전체 백업은 manifest에 쓸 ref 목록을 읽은 뒤 `writeBundle`에서 ref를 다시 읽는다. 락이 없으므로 그 사이에 업데이트가 끼면 manifest와 bundle이 어긋난다. (b) 이미 압축된 pack을 zip DEFLATE로 다시 압축해 폰 CPU와 배터리를 낭비한다(`setLevel(Deflater.NO_COMPRESSION)` 권장). (c) source zip은 심볼릭 링크와 실행 비트를 잃는다. fetch에 fsck가 없으므로 악의적인 원본이 `..` 같은 tree 항목 이름을 넣으면 zip 항목 경로에 그대로 들어간다(zip-slip). 항목 이름을 검증해야 한다.
- 권장: bundle에 넣은 ref를 manifest에 그대로 쓴다(한 번만 읽는다). 레포별 bundle은 `withRepoLock` 안에서, 또는 ref 스냅샷을 고정해서 쓴다. zip 경로에 `..`·절대경로가 있으면 건너뛴다.

### GIT-14 raw 다운로드가 ref를 두 번 해석해 Content-Length와 실제 본문이 어긋날 수 있다
- 위치: `GitServer.kt:449, 460-461`, `RepoBrowser.kt:163-174`
- 상황: `rawSize`와 `raw` 사이에 미러 업데이트로 브랜치가 움직이면 다른 blob을 스트리밍한다. 응답이 잘리거나 연결 오류가 난다.
- 권장: blob ObjectId를 한 번 해석해 size와 스트림에 같이 쓴다.

### GIT-15 meta.json을 읽지 못하면 미러가 조용히 "로컬 레포"로 바뀌고 meta가 덮어써진다
- 위치: `Util.kt:15` (파싱 실패 시 null), `RepoStore.kt:52-60`
- 상황: 앱을 다운그레이드해 모르는 enum 값이 들어 있거나 스키마가 바뀌면 파싱이 실패한다. 그러면 `LOCAL/PUSH` meta로 덮어쓰여 원격 URL과 미러 설정이 영구히 사라지고 push가 허용된다.
- 권장: 파싱 실패와 파일 없음을 구분한다. 실패하면 원본을 `meta.json.bad`로 보존하고, 그 레포를 "확인 필요" 상태로 둔다(덮어쓰지 않는다).

### GIT-16 분리(CONVERT)와 이미 대기 중인 업데이트가 경합하면 NPE가 나고, 확인이 실행 중이면 수동 업데이트가 무시된다
- 위치: `Sync.kt:171` (`meta.mirror!!`), `Work.kt:41`, `Sync.kt:236`
- 상황: CONVERT가 락을 쥐고 있는 동안 락을 기다리던 update는, 락을 얻은 뒤 `mirror == null`이라 `NullPointerException`을 던지고 Worker가 실패한다. 또 `isRunning`이 CHECK 실행 중에도 true라서, 그때 누른 "지금 업데이트"는 아무 반응 없이 버려진다.
- 권장: 락 안에서 `mirror ?: return`(NOT_A_MIRROR 이벤트)으로 처리한다. 업데이트를 건너뛰는 조건은 `kind == UPDATE`일 때만 적용한다.

---

## 잘 된 점

- **아카이브 갱신 설계가 정확하다.** 객체를 먼저 받고, 아카이브 ref 생성과 미러 ref 갱신을 하나의 atomic `BatchRefUpdate`로 적용한다(`PackedBatchRefUpdate`). 중간에 실패하면 ref는 그대로이고 참조되지 않은 객체만 남는다. 브랜치 삭제와 `foo/bar` 생성이 겹치는 D/F 충돌도 같은 배치에서 정상 처리되는 것을 확인했다.
- 원격의 `refs/archive/*`는 미러 대상에서 빠지므로, 원본이 보존 영역을 덮어쓸 수 없다. 태그가 움직이면 항상 아카이브하고, FF 판정이 실패하면 보수적으로 아카이브 쪽을 택한다.
- 미러 업데이트 중에 clone해도 안전하다. force-push 이전 tip은 아카이브 ref에서 도달할 수 있으므로, stateless want 검증에서 거의 실패하지 않는다.
- smart HTTP 처리가 JGit servlet 동작을 충실히 따른다(v0/v2, `Git-Protocol` 전달, gzip 요청 본문, 미러 push를 ERR 패킷으로 거부). push로 레포를 만들 때 InMemory 레포로 광고하고, 실제 pack이 도착했을 때만 생성하며, ref가 없으면 롤백한다. ReceivePack은 `isAtomic=true`이고 디스크 여유에 맞춘 `maxPackSizeLimit`를 둔다.
- 레포별 코루틴 `Mutex`(lowercase 키)로 push, 업데이트, fork, 삭제를 직렬화한다. pack 슬롯 Semaphore와 대기 시간 제한이 있고, `PackConfig.threads=1`, mmap을 끈 WindowCache 등 폰 환경에 맞춘 JGit 설정이 들어가 있다.
- meta와 이력 JSON은 tmp → fsync → atomic rename으로 저장한다. 내보내기는 `.part` 파일을 쓴 뒤 rename한다. 레포 이름 검증과 `.git` 접미사 처리, 대소문자 무시 유일성도 견고하다.

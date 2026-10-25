# 코어 명세 (UI/웹 개발자용)

기준: PRD v1(`docs/planning/prd.md`), 코드 커밋 `ec1b752` 이후. 패키지 `com.ouor.mobilegit`.
코어(순수 JVM): `core/`. 안드로이드 연결부: `app/`. 조사와 선택 근거: `research.md`. 진행 상황: `progress.md`.

## 0. 진입점과 스레드 규칙

```kotlin
val core = MobileGitApp.core(context)   // 앱 전역 싱글턴 그래프 (app/Core.kt)
```

| 멤버 | 타입 | 용도 |
|---|---|---|
| `core.server` | `ServerController` | 서버 켜기/끄기, `state: StateFlow<ServerState>` |
| `core.network` | `NetworkMonitor` | `addresses: StateFlow<List<ServerAddress>>` (3초 폴링, ngrok 주소 포함) |
| `core.settings` | `SettingsStore` | `state: StateFlow<Settings>`, setter들 |
| `core.repos` | `RepoStore` | `repos: StateFlow<List<RepoMeta>>`, 이력 `eventsFlow(name)` / `events(name)` |
| `core.repoItems` | `Flow<List<RepoListItem>>` | 목록 화면용(배지 + 진행 상황, 1분마다 재계산) |
| `core.mirrors` | `MirrorController` | 미러 추가·일괄·업데이트 |
| `core.coordinator` | `SyncCoordinator` | `running: StateFlow<Map<String, RepoProgress>>` |
| `core.credentials` | `CredentialStore` | 원격 토큰, `items: StateFlow<List<RemoteCredential>>` |
| `core.bulkJobs` / `core.catalog` | `BulkJobStore` / `RemoteCatalog` | 일괄 미러 작업 상태 / 계정 레포 목록 조회 |
| `core.ops` | `RepoOps` | 빈 레포, fork, 삭제, 기본 브랜치 |
| `core.scheduler` | `WorkScheduler` | 내보내기 작업, 주기 확인 |
| `core.exporter` | `Exporter` | bundle/zip/전체 백업 스트림 쓰기(직접 호출 시) |
| `core.tunnel` | `TunnelManager` | `state: StateFlow<TunnelState>` |
| `core.accessLog` | `AccessLogStore` | `entries: StateFlow<List<AccessLogEntry>>` |
| `core.gitServer` | `GitServer` | 내부용(서비스가 시작). UI는 직접 호출하지 않음 |

- `suspend` 함수는 내부에서 `Dispatchers.IO`로 전환하므로 `viewModelScope.launch`에서 바로 호출해도 된다.
- 표시된 "네트워크/디스크" 동기 함수(`MirrorController.probe/preview/add/addMany`, `RepoStore.refreshDerived`, `RepoOps.branches`)는 **메인 스레드에서 호출 금지**. `withContext(Dispatchers.IO)`로 감쌀 것.
- 오류는 예외로 전달된다. 사용자에게 보여줄 한국어 메시지가 `message`에 들어 있다.

## 1. 기능별 API

### F-01 서버 on/off
- `core.server.start(): Boolean`: `settings.serverEnabled = true`로 저장하고 `ServerService`(포그라운드, specialUse)를 시작한다. 알림 권한이 없어도 동작한다.
- `core.server.stop()`: 서비스 종료. 진행 중인 clone/push는 최대 몇 초 기다린 뒤 끊는다.
- `core.server.state: StateFlow<ServerState>`: `Stopped | Starting | Running(port) | Stopping | Failed(portInUse, message, kind, detail)`.
  - `message`는 사용자에게 그대로 보여 줄 한국어 문장이다. `detail`은 원문 기술 정보(예외 메시지)라 "자세히"나 로그에만 쓴다.
  - `kind: ServerFailure`:
    - `PORT_IN_USE`(= `portInUse == true`): 스위치 꺼짐. 포트 변경 화면으로 안내(AC3/AC4). 포트가 사용 중이어도 앱은 죽지 않는다(QA-01). 포트 설정이 바뀌면 이 실패는 `Stopped`로 정리된다(QA-13). UI 수정은 필요 없다.
    - `BACKGROUND_START_BLOCKED`(QA-02): 앱 업데이트 직후 등 백그라운드에서 Android가 포그라운드 서비스 시작을 거부함(Android 12+). `settings.serverEnabled`는 **켜진 채** 남고, "mobilegit 서버가 꺼져 있어요 / 앱을 열면 서버가 다시 켜져요" 알림이 뜬다. 앱의 어떤 화면이든 열리면(`Activity.onStart`) 코어가 자동으로 다시 켠다(`ServerController.resumeIfBlocked()`). UI는 메인 카드에 "앱을 열면 다시 켜져요" 류의 안내만 보이면 되고, 스위치는 켜짐 의도로 보여도 된다. 재부팅(`BOOT_COMPLETED`)과 프로세스 kill 복구는 기존대로 동작한다.
    - `OTHER`: 그 밖의 시작 실패. `message`는 "서버를 시작하지 못했어요".
  - 기존 `Failed(portInUse, message)` 생성/분해 코드는 그대로 컴파일된다(새 필드는 기본값이 있음).
- 프로세스가 강제 종료되면 START_STICKY로 재시작되고, 같은 부팅 안이면 `restoreIfEnabled()`가 서버를 복구한다(AC7).
- 알림의 "서버 끄기" 버튼은 `ServerService.ACTION_STOP`이다.
- 포트 변경(CR-1): 실행 중에 `settings.setPort(p)`를 부르면 서비스가 알아서 새 포트로 다시 연다. `state`는 `Stopping` → `Running(p)`로 바뀌고, 새 포트가 사용 중이면 `Failed(portInUse = true)`가 되며 서버는 꺼진다. 진행 중인 전송은 끄기와 같은 유예(최대 수 초)를 받는다. ngrok 터널도 다시 연결된다.
  - 직접 호출: `suspend fun ServerController.restart(): ServerState`. 꺼져 있거나 이미 그 포트면 아무것도 하지 않고 현재 상태를 돌려준다.

### F-02 접속 주소 표시
- `core.network.addresses: StateFlow<List<ServerAddress>>`. `ServerAddress(kind: AddressKind{WIFI,HOTSPOT,USB_TETHER,TAILSCALE,NGROK}, host, baseUrl)`(USB_TETHER는 2차 R2-15).
  - Tailscale은 100.64.0.0/10 대역으로 판별한다.
  - 목록이 비면 "접속 가능한 네트워크 없음" 안내를 띄운다(AC3).
- `ServerAddress.cloneUrl(repo)`는 `<baseUrl>/<repo>.git`을 돌려준다.

### F-03 HTTP Basic 인증
- 설정 필드: `authEnabled`(기본 true), `username`(기본 `git`), `anonymousReadOnLan`, 그리고 파생값 `effectiveAuthEnabled = authEnabled || ngrokEnabled`.
- 계정 변경:
  - `settings.setUsername(name)`: `SettingsException` 발생 가능.
  - `settings.setPassword(pw)`: 8자 미만이면 `SettingsException`.
  - `SettingsStore.generatePassword(16)`.
- 규칙:
  - push는 항상 인증이 필요하다(인증을 끈 경우는 예외).
  - 익명 읽기는 인증 켜짐 + 익명 허용("push만")일 때 가능하다. ngrok 경로도 같은 규칙이다(R2-06, 8절).
  - 같은 IP에서 1분에 10회 넘게 실패하면 5분 차단(HTTP 429)한다.
- 비밀번호는 PBKDF2 해시로만 저장한다.

### F-04 ngrok 외부 접속
- `settings.setNgrokAuthtoken(token?)`: 토큰을 Keystore로 암호화해 저장한다.
- 켜기: `settings.update { it.copy(ngrokEnabled = true, ngrokWarningAccepted = true) }`.
  - 서버가 실행 중이면 서비스가 설정 변화를 감지해 터널을 열고 닫는다(AC3).
- `core.tunnel.state: StateFlow<TunnelState>`: `Off | Connecting(attempt) | Connected(publicUrl) | Retrying(atMillis, reason) | TokenRejected(reason)`.
  - `TokenRejected`면 자동 재시도하지 않는다. "토큰 확인 필요"로 표시한다.
  - 그 밖의 실패는 2초부터 최대 5분까지 지수 백오프로 재시도한다.
- 2차(R2-06): ngrok이 켜져도 인증을 강제하지 않는다. 사용자의 "비밀번호 요구" 설정을 ngrok 경로에도 그대로 적용한다. 위험 조합은 `Settings.exposedWithoutPassword`.
- 재부팅·앱 업데이트 뒤에는 ngrok이 꺼진 채로 시작한다(R2-05 AC4, 8절).
- 접속 기록의 경로는 `NGROK`으로 남는다. 별도 루프백 커넥터로 구분한다.
- 공개 URL은 `addresses`에 `AddressKind.NGROK`로 추가된다.

### F-05 레포 목록
- `core.repoItems: Flow<List<RepoListItem>>`. `RepoListItem(meta, badges: Set<Badge>, progress: RepoProgress?)`, `lastActivityAt`.
- 정렬·필터·검색: `RepoQuery.apply(items, RepoSort{NAME,RECENT}, RepoFilter{ALL,MIRROR,LOCAL,PROBLEM}, search)`.
- `Badge` 값:
  - `UPDATE_AVAILABLE`, `UPDATING`, `FAILED`, `ORIGIN_GONE`, `AUTH_REQUIRED`, `LFS_MISSING`.
  - `DELAYED`: 마지막 확인이 확인 주기의 3배보다 오래됨.

### F-06 미러 추가
1. 입력란 또는 공유 시트 텍스트를 `RemoteUrls.extractCandidates(text): List<String>`로 나눈다.
   - 텍스트에 URL이 있으면 URL만 뽑는다. 없으면 줄 단위로 나눈다.
   - `MainActivity`가 `ACTION_SEND text/plain`을 받는 예시를 구현해 두었다.
2. `mirrors.preview(input): AddPreview(input, normalized, suggestedName, existing, error)`, 그리고 `errorKind: AddPreviewError?`(QA-03).
   - `errorKind == ALREADY_MIRRORED`이면 `existing`이 그 레포다. "이미 미러됨" + "기존 레포로 이동"(`existing.name`)을 보여 준다. `error` 문구("이미 미러됨: <이름>")를 "지원하지 않는 주소"로 바꿔 보여 주면 안 된다.
   - `errorKind == UNSUPPORTED_URL`: 정규화 실패(https/http git 주소가 아님).
   - `null`이면 추가 가능.
   - 브라우저 주소(`/tree/..`, `/-/blob/..`, `.git` 생략)를 정규화한다.
   - 이미 미러 중이면 `existing`을 채운다(AC5).
3. (선택) `mirrors.probe(url, credentialId?): ProbeResult`: `Reachable(refCount, defaultBranch, pullRefCount) | AuthRequired | NotFound | Failed(category, detail)`.
   - `AuthRequired`면 토큰 입력으로 안내한다.
   - `pullRefCount`: `refCount` 중 PR/MR ref(`refs/pull/…`, `refs/merge-requests/…`) 수. PR ref를 받지 않는 설정이면 "PR ref N개 제외"로 보여 줄 수 있다.
4. `mirrors.add(url, name, credentialId?, createdBy = MIRROR_ADD, includePullRefs: Boolean? = null): RepoMeta`.
   - 등록 즉시 첫 fetch를 WorkManager로 예약한다.
   - 예외는 `RepoException`이다: `INVALID_NAME`, `NAME_TAKEN`, `ALREADY_MIRRORED`, `STORAGE_LOW`.
5. 여러 줄 입력은 `mirrors.addMany(text, credentialId?): BulkJob`으로 처리한다. 진행 상황은 `bulkJobs.jobs`에 나온다.
- 이름 규칙: `RepoNames.validate(name): String?`(null이면 유효), `RepoNames.suggest(remote, taken)`.

### F-07 원격 자격 증명
- `credentials.add(host, token, username?, label)`, `update(id, token?, username?, label?)`, `delete(id)`.
  - 목록은 `items`로 받는다. 표시에는 `RemoteCredential.tokenPrefix`(토큰 앞 4자)만 쓴다.
- `credentials.usageCount(id, repos)`: 삭제 전 사용 중인 레포 수를 보여 줄 때 쓴다.
- 레포에 토큰 지정: `mirrors.setCredential(name, credentialId?)`.
  - 지정하지 않으면 같은 호스트의 첫 토큰을 자동으로 쓴다.
- 기본 사용자명: GitHub는 `x-access-token`, GitLab은 `oauth2`, Bitbucket은 `x-token-auth`.
- 오류 문구와 이력에는 토큰과 URL 자격 증명이 제거된 값만 남는다(`Redact`).

### F-08 미러 읽기 전용
- 서버가 처리한다. 미러로 push하면 git 클라이언트에 다음이 표시된다:
  `remote error: This repository is a read-only mirror of <url>. Fork it in the mobilegit app to push.`
- 웹 API는 `RepoDetailDto.readOnly`.

### F-09 수동 업데이트
- `mirrors.updateNow(name)`: 레포 하나. `mirrors.updateAll()`: 모든 미러.
- 확인만(CR-4): `mirrors.checkNow(name)`, `mirrors.checkAll()`. WorkManager로 바로 실행되며(모든 네트워크, 백오프 무시) 객체를 받지 않고 업데이트도 하지 않는다. 결과는 `MirrorSource.pendingChanges`/`lastCheckAt`과 `UPDATE_AVAILABLE` 배지로 나타난다. 미러가 아니면 `RepoException(NOT_A_MIRROR)`.
  - 조건(와이파이, 충전)을 무시한다. 저장 공간 하한은 MANUAL이면 무시하지 않고 실패 처리한다.
- 진행 상황: `coordinator.running[name]`의 `RepoProgress(kind, trigger, progress: SyncProgress(task, done, total))`.
- 결과는 이력(`repos.events(name)`)과 알림으로 전달된다.

### F-10 자동 확인과 자동 업데이트
- 설정 필드:
  - `checkIntervalHours`: `settings.setCheckIntervalHours(h)`, 1, 6, 12, 24 중 하나.
  - `autoUpdate`, `updateWifiOnly`, `updateChargingOnly`, `checkWifiOnly`.
  - `storageFloorBytes`: 기본 1GB.
- 레포 제외: `mirrors.setAutoUpdateExcluded(name, true)`.
- PR ref 미러(CR-2): 전역 `Settings.mirrorPullRefs`(기본 **false**), 레포별 `mirrors.setIncludePullRefs(name, Boolean?)`(null이면 전역을 따름, `MirrorSource.includePullRefs`). 다음 확인/업데이트부터 적용된다. 끄면 이미 받은 PR ref는 다음 업데이트에서 지워진다(아카이브하지 않음).
  - 기본값이 꺼짐인 근거: F-11 아카이브 대상은 heads/tags이고 PR ref는 원래 보존하지 않는다. 인기 레포는 PR ref가 ref 수와 받을 양의 대부분을 차지한다(Spoon-Knife 63,808개, 첫 받기 수 분). PR 브랜치 내용은 원본 호스트의 기능이며 heads/tags만으로 clone·빌드가 된다. 그 밖의 ref(`refs/notes` 등)는 계속 받는다.
  - 한계: ref 광고 목록(ls-remote) 자체는 전체를 받은 뒤 걸러낸다(63k ref면 수 MB). 객체는 받지 않는다.
- 동작:
  - WorkManager `periodic-check`가 미러별 `CheckWorker`를 최대 주기 10% 지터로 실행한다.
  - 변경이 있고 자동 업데이트가 켜져 있으면 `SyncWorker`를 조건부로 예약한다.
  - 조건이 맞지 않으면 `MirrorSource.waitingReason`이 `WIFI | CHARGING | STORAGE`가 된다(AC4 대기 사유 표시).
- 동시 실행은 최대 2개. 실패하면 백오프 `min(24h, 2×주기, 1h·2^(n−1))`.

### F-11 아카이브 보존
- 원본에서 강제 push나 삭제로 사라지는 ref는 `refs/archive/<UTC 시각>/<heads|tags/...>`로 보존된다.
  - 원자적으로 기록된다. PR ref(`refs/pull/…`)는 받더라도 보존하지 않는다.
- 개수: `RepoMeta.archivedRefCount`. 이력: `SyncEvent.archivedRefCount`, `refChanges`.
- 알림 `NoticeKind.ARCHIVED`.
- 아카이브 ref는 clone 시 기본 refspec에 포함되지 않는다. `git fetch origin 'refs/archive/*:refs/archive/*'`로 받는다.
- 웹 API는 `RepoDetailDto.archivedRefCount`. 목록은 `GET /api/v1/repos/{name}/archives`(2차 R2-03, 8절).

### F-12 동기화 이력, 원본 상태, 실패 알림
- `repos.eventsFlow(name): Flow<List<SyncEvent>>`(CR-3): **최신이 처음**. 구독 시 한 번, 그 레포에 이력이 추가되거나 레포가 삭제될 때마다 다시 보낸다. IO에서 읽는다. 최대 200개.
- `repos.events(name)`: 같은 내용의 동기 버전(디스크 읽기, 메인 스레드 금지). 이전 문서의 "최신이 마지막"은 잘못이었다. 코드는 처음부터 최신이 앞이다.
  - 필드: `startedAt`, `durationMs`, `trigger`, `kind{CHECK,UPDATE}`, `result{NO_CHANGE,SUCCESS,FAILED,WAITING}`, `refChanges`, `newCommitCount`, `archivedRefCount`, `errorCategory`, `errorDetail`, `waitingReason`.
- 원본 상태: `MirrorSource.originStatus{OK,GONE,AUTH_REQUIRED,UNKNOWN}`, `lastCheckAt`, `lastSuccessUpdateAt`, `consecutiveFailures`, `nextRetryAt`, `lastError`.
- `ErrorCategory`: `NETWORK`, `AUTH_REQUIRED`, `ORIGIN_GONE`, `STORAGE_FULL`, `REMOTE_ERROR`, `TIMEOUT`.
  - 한국어 문구는 `Notifier.categoryText(c)`.
- 알림은 상태가 바뀔 때만 보낸다(`NotificationRules`): `FAILED`, `ORIGIN_GONE`, `AUTH_REQUIRED`, `ARCHIVED`, `RECOVERED`, `UPDATED`(성공 알림 설정 시).
- 알림을 탭하면 `MainActivity`가 열리고 다음 extra를 받는다:
  - `Notifier.EXTRA_REPO`: 레포 이름.
  - `Notifier.EXTRA_TARGET`: `"repo"`, `"credentials"`, `"battery"`, `"main"`.

### push 이력 (CR-7)
- `repos.pushEventsFlow(name): Flow<List<PushEvent>>`: 받은 push를 ref 하나당 한 항목으로, **최신이 처음**. push나 삭제 뒤 다시 보낸다. IO에서 읽는다. 동기 버전 `repos.pushEvents(name)`(메인 스레드 금지).
- `PushEvent(at, ref, oldSha?, newSha?, route: AccessRoute, clientIp, forced)`, 파생 `branch`(refs/heads/…일 때 브랜치 이름, 아니면 null).
  - `oldSha == null`: 새로 만든 ref. `newSha == null`: 삭제. `forced`: fast-forward가 아닌 갱신(force-push).
  - 같은 push의 여러 ref는 같은 `at`을 가진다. 실제로 적용된 ref만 기록된다(거부된 push는 없음).
- 레포마다 최근 200개(`RepoStore.MAX_PUSH_EVENTS`)만 `<repo>/mobilegit/pushes.json`에 보관한다. 미러는 push를 받지 않으므로 비어 있다. 분리(fork COPY)한 레포는 빈 이력으로 시작한다.
- 접속 기록(F-23)을 재사용하지 않은 이유: ref와 sha가 없고, 전역 2000개 한도라 레포별 이력이 밀려나며, 사용자가 지울 수 있다.

### F-13 push로 레포 생성
- 서버가 처리한다. `git push http://<주소>/<새이름>.git main`이면 첫 POST에서 LOCAL 레포가 만들어진다(`CreatedBy.PUSH`).
- ref가 하나도 들어오지 않으면 다시 지운다.
- HEAD는 main, master, 처음 push된 브랜치 순으로 정한다.
- 이름이 잘못되면 400 응답과 사유 문구를 돌려준다(QA-08). git 클라이언트에는 `remote: Invalid repository name '<이름>': <사유>`로 보인다. 사유는 `RepoNames.validate`와 같다(비어 있음, 100자 초과, 영문/숫자로 시작 안 함, 허용되지 않는 문자 목록, `.git`으로 끝남).

### F-14 앱에서 빈 레포 생성
- `ops.createEmpty(name, description): RepoMeta`. 예외는 `RepoException`.
- `ops.pushCommands(cloneUrl): List<String>`: 화면에 보여 줄 `git remote add` / `git push` 명령.

### F-15 계정·조직 단위 일괄 미러
1. `catalog.list(kind: BulkSourceKind, account, host, token?, onRateLimited): List<CatalogRepo>`: suspend.
   - `kind`: `GITHUB_USER`, `GITHUB_ORG`, `GITLAB_USER`, `GITLAB_GROUP`.
   - 예외는 `CatalogException(status)`이다(404, 401 등).
2. `catalog.alreadyMirrored(list, repos)`: 이미 있는 항목을 표시할 때 쓴다.
3. `mirrors.addFromCatalog(kind, account, host, selected, credentialId?): BulkJob`. 카탈로그의 설명이 `RepoMeta.description`으로 저장된다(QA-09). URL 하나로 추가할 때는 `mirrors.add(..., description = "")`로 직접 넣을 수 있고, 코어가 호스트 API를 따로 부르지는 않는다.
4. 상태: `bulkJobs.jobs`. `BulkJob(items[BulkItem(status QUEUED/RUNNING/DONE/FAILED, error)], rateLimitedUntil, status)`, `count(status)`.
5. 실패 항목 재시도: `mirrors.retryFailed(jobId)`.

### F-16 clone 주소 복사와 QR
- `core.cloneUrls(repo): List<Pair<ServerAddress, String>>`. URL에 자격 증명은 넣지 않는다.
- `QrCodes.bitmap(text, size=768): Bitmap`.

### F-17 저장 공간 표시
- `core.storageSummary(): StorageSummary(appUsageBytes, freeBytes, totalBytes, floorBytes, reposBySize, belowFloor)`.
- `core.refreshSizes()`: suspend, 다시 측정.
- 레포별 크기는 `RepoMeta.sizeBytes`, `sizeMeasuredAt`.

### F-18 브라우저용 읽기 전용 웹 화면
- 아래 3절(JSON API)과 4절(정적 파일)을 참조한다.

### F-19 백업 내보내기
- `scheduler.enqueueExport(kind: ExportKind{BUNDLE,SOURCE_ZIP,FULL_BACKUP}, repos, ref?, targetUri?): UUID`.
  - `targetUri`는 SAF `ACTION_CREATE_DOCUMENT`로 받은 Uri다.
  - 제안 파일명: `Exporter.suggestedFileName(name, kind, now)`.
  - 작업은 cache에 쓴 뒤 Uri로 복사한다. 실패하면 문서를 지운다.
- 결과 확인: `WorkManager.getWorkInfoByIdFlow(id)`.
  - 출력 데이터 `ExportWorker.K_OUT_FILE`: Uri가 없을 때 cache 파일 경로. FileProvider `${applicationId}.files`로 공유한다.
  - `ExportWorker.K_ERROR`: 실패 사유.
- 전체 백업은 zip 안에 레포별 `.bundle`과 `manifest.json`(이름, 종류, 원본 URL, 기본 브랜치, 아카이브 수, 앱 버전)을 담는다.

### F-20 미러를 로컬 레포로 분리(fork)
- `ops.fork(name, mode = ForkMode.COPY | CONVERT, newName?): RepoMeta`: suspend.
  - COPY: 미러는 그대로 두고 새 LOCAL 레포를 만든다.
  - CONVERT: 미러를 LOCAL로 바꾸고 동기화를 멈춘다.
- `forkedFromUrl`, `forkedAt`을 기록한다. COPY는 레포 디렉터리 전체를 복사하므로 아카이브 ref도 함께 옮겨진다. 미러가 아니면 `NOT_A_MIRROR`, 공간이 부족하면 `STORAGE_LOW`.

### F-21 레포 삭제
- `ops.delete(name)`: suspend.
  - 진행 중인 동기화를 취소하고, 열린 git 연결을 끊고, 레포 락을 잡은 뒤 지운다.
- `ops.setDefaultBranch(name, branch)`, `ops.branches(name)`: 상세 화면용.

### F-22 백그라운드 유지, 배터리 안내, 부팅 후 자동 시작
- 서버는 specialUse 포그라운드 서비스로 실행되고, partial wake lock과 Wi-Fi 락을 잡는다.
- 배터리 최적화:
  - `BatteryHelper.isIgnoringOptimizations(ctx)`, `requestIntent(ctx)`, `settingsIntent()`, `appDetailsIntent(ctx)`.
  - `maker()`: 제조사별 안내 분기.
  - `shouldShowHint(ctx, settings.batteryHintDismissedAt)`: 30일마다 다시 표시.
- 부팅 후 자동 시작: `Settings.startOnBoot`(새 설치 기본값 **true**, CR-6. 기존 설치는 저장된 값 유지). 켜져 있고 재부팅 전에 서버가 켜져 있었으면 `BootReceiver`가 서버를 켠다. 끄기: `settings.update { it.copy(startOnBoot = false) }`.

### F-23 접속 기록
- `accessLog.entries: StateFlow<List<AccessLogEntry>>`: 최대 2000개, 5초마다 저장.
  - 필드: `at`, `clientIp`, `route{WIFI,HOTSPOT,TAILSCALE,NGROK,LOCALHOST,OTHER}`, `repoName`, `action{READ,PUSH,WEB}`, `authResult{OK,FAILED,ANONYMOUS,BLOCKED}`, `status`.
- 필터: `AccessLogStore.filter(list, ngrokOnly, authFailuresOnly)`. 삭제: `clear()`.
- 인증 챌린지용 401(자격 증명 없는 첫 요청)은 기록하지 않는다.

### F-24 LFS·서브모듈 경고
- `RepoMeta.hasLfs`: 기본 브랜치의 `.gitattributes`에 `filter=lfs`가 있는지.
- `RepoMeta.submoduleUrls`: `.gitmodules`에 적힌 URL들.
- 배지 `LFS_MISSING`. 웹 blob 응답은 LFS 포인터면 `lfs: {oid, size}`를 담는다.
- 동기화나 push 후 자동으로 갱신된다(push는 응답 직후 갱신).
- LFS push(QA-05): 서버는 LFS 객체를 저장하지 않는다(PRD 9장 "제외, 경고만"). git-lfs가 설치된 PC에서 push하면 `info/lfs/objects/batch`가 422와 함께 git-lfs가 그대로 출력하는 안내를 돌려준다: "mobilegit은 Git LFS 파일을 저장하지 않습니다 … GIT_LFS_SKIP_PUSH=1 로 push 하세요". 사용자가 `GIT_LFS_SKIP_PUSH=1`로 다시 push하면 커밋과 포인터 파일만 올라가고 `LFS_MISSING` 배지가 붙는다. clone/pull 시 LFS 다운로드 요청에는 `GIT_LFS_SKIP_SMUDGE=1` 안내를 준다. 잠금 API(`locks/verify`)는 404라 git-lfs가 "잠금 미지원"으로 넘어간다.
  - 결정 근거: 객체를 조용히 받는 척(성공 응답)하면 PC는 업로드된 줄 알지만 폰에는 파일이 없어 "조용히 빠지지 않게 경고"(PRD 9장, 인터뷰 01/02/05)에 어긋난다. 사용자가 명시적으로 포인터만 올리게 하는 쪽을 택했다. A-07 LFS 안내에도 이 명령을 적는 것을 권한다.

### F-25 첫 실행 온보딩
- `settings.initialPassword(): String?`: 온보딩 화면에서 한 번 보여 줄 자동 생성 비밀번호.
- `settings.completeOnboarding()`: 완료하면 평문 사본을 지운다.
- 상태는 `Settings.onboardingCompleted`.

## 2. 오류 타입

| 타입 | 코드 | 의미 |
|---|---|---|
| `RepoException.code` | `INVALID_NAME`, `NAME_TAKEN`, `NOT_FOUND`, `ALREADY_MIRRORED`, `STORAGE_LOW`, `NOT_A_MIRROR`, `BUSY` | 레포 생성·변경·삭제 |
| `SettingsException` | (메시지) | 포트 범위, 사용자명, 비밀번호 길이, 주기 범위 |
| `CatalogException.status` | HTTP 상태 | 일괄 목록 조회 |
| `ErrorCategory` | 위 F-12 | 동기화 실패 분류 |
| `TunnelState.TokenRejected` | - | ngrok 토큰 거부. 저장된 토큰을 복호화하지 못해도 이 상태(AND-13) |
| `StoreIssue` | - | 저장 파일 손상(7절) |
| `BrowseException(status, code)` | 3절 | 웹 API |

## 3. 웹 JSON API (읽기 전용)

- 기본 경로: `http://<주소>:<포트>/api/v1/`. 인증은 git과 같은 HTTP Basic이다(브라우저가 기본 프롬프트를 띄움).
- 모든 응답은 `application/json; charset=utf-8`이다. 시각은 epoch ms이고 null 필드도 포함된다.
- 오류 본문: `{"error":{"code":"REF_NOT_FOUND","message":"브랜치/태그 'x'을(를) 찾을 수 없습니다"}}`
- 오류 코드:

| 상태 | 코드 |
|---|---|
| 404 | `REPO_NOT_FOUND`, `REF_NOT_FOUND`, `EMPTY_REPO`, `PATH_NOT_FOUND`, `README_NOT_FOUND`, `NOT_FOUND` |
| 400 | `BAD_PATH`, `NOT_A_DIRECTORY`, `NOT_A_FILE`, `NOT_MARKDOWN`, `TOO_LARGE`, `PAGE_TOO_DEEP`(commits page > 1000) |
| 403 | 전역 IPv6 주소로 들어온 요청(본문 텍스트, SEC-04) |
| 401 | 인증 필요(본문 없음, `WWW-Authenticate`) |
| 429 | 인증 실패 과다 |
| 503 | `BUSY`: tree/blob/readme/commits는 동시에 2개까지 처리하고 10초 안에 자리가 안 나면 이 응답(GIT-09). 잠시 뒤 다시 시도 |
| 500 | `INTERNAL`. message는 `서버 오류가 발생했습니다 (오류 ID xxxxxxxx)`뿐이고 상세는 앱 로그에만(SEC-10) |

- 익명 읽기(`anonymousRead`)는 설정값이다. 실제로는 사설망·링크로컬·ULA·Tailscale에서 Host가 IP·`localhost`·점 없는 이름·`.local`·`.ts.net`인 요청, 그리고 ngrok 경로(2차 R2-06)만 익명으로 통과한다. 루프백과 그 밖의 Host는 익명 설정이어도 401(SEC-03)
- 쿼리: `ref`는 브랜치, 태그, 커밋 SHA. 생략하면 기본 브랜치. `path`는 `/`로 구분하며 `..`은 거부한다.

| 메서드/경로 | 응답 |
|---|---|
| `GET /api/v1/info` | `{"app":"mobilegit","version":"0.1.0","baseUrl":"http://192.168.31.120:8080","authRequired":true,"anonymousRead":false}` |
| `GET /api/v1/repos` | `RepoSummaryDto[]` |
| `GET /api/v1/repos/{name}` | `RepoDetailDto` |
| `GET /api/v1/repos/{name}/tree?ref=&path=` | `TreeDto` |
| `GET /api/v1/repos/{name}/blob?ref=&path=` | `BlobDto` (텍스트는 1MB까지) |
| `GET /api/v1/repos/{name}/raw?ref=&path=[&download=1]` | 파일 바이트. 정확한 `Content-Length`, chunked 아님, 압축 안 함. 이미지(png, jpg/jpeg, gif, webp, avif, bmp, ico, svg)는 해당 `image/*` 타입 + `Content-Disposition: inline`이라 `<img src>`에 바로 쓸 수 있다(QA-14). 그 밖의 파일(HTML, 텍스트 포함)은 `application/octet-stream` + `attachment`. `download=1`이면 이미지도 attachment. 모든 raw 응답에 `X-Content-Type-Options: nosniff`와 `Content-Security-Policy: default-src 'none'; img-src data:; style-src 'unsafe-inline'; sandbox`가 붙어, SVG를 직접 열어도 안의 스크립트가 실행되지 않는다. 다운로드 버튼은 `download=1`을 붙이거나 `<a download>`를 쓴다 |
| `GET /api/v1/repos/{name}/readme?ref=&path=` | `ReadmeDto`. path가 디렉터리면 그 안의 README(없으면 404 `README_NOT_FOUND`). path가 `.md`/`.markdown` 파일이면 그 파일을 같은 렌더러로(WR-5). 그 밖의 파일(이진, LFS 포인터 포함)은 400 `NOT_MARKDOWN`, 1MB 초과는 400 `TOO_LARGE` |
| `GET /api/v1/repos/{name}/archives` | `ArchivesDto` (R2-03, 8절). 동시 처리 제한(503 `BUSY`) 대상 |
| `GET /api/v1/repos/{name}/commits?ref=&path=&page=1&perPage=50` | `CommitPageDto`. perPage는 1~100으로 맞춰짐. page는 1000까지(넘으면 400 `PAGE_TOO_DEEP`). 한 요청은 커밋을 20만 개까지만 훑는다. 그 안에서 페이지를 다 못 채우고 멈추면 `truncated: true`, `hasMore: false`(GIT-09, SEC-11) |

응답 예시(실기기에서 받은 형태):

```json
// GET /api/v1/repos
[{"name":"pc-verify","type":"LOCAL","description":"","defaultBranch":"main","lastActivityAt":1792886934344,
  "lastUpdateAt":null,"lastPushAt":1792886934344,"originUrl":null,"originStatus":null,"hasLfs":false,"sizeBytes":2298}]

// GET /api/v1/repos/Hello-World
{"repo":{...RepoSummaryDto, "type":"MIRROR","originUrl":"https://github.com/octocat/Hello-World.git","originStatus":"OK"},
 "empty":false,"head":"master","branches":[{"name":"master","sha":"7fd1a60b01f91b314f59955a4e4d4e80d8edf11d"}],
 "tags":[],"archivedRefCount":0,"submoduleUrls":[],"readOnly":true,"forkedFromUrl":null,
 "cloneUrl":"http://192.168.31.120:8080/Hello-World.git"}

// GET /api/v1/repos/demo/tree?path=src
{"ref":"main","commit":{CommitDto},"path":"src",
 "entries":[{"name":"lib","path":"src/lib","type":"dir","size":null,"mode":"40000"},
            {"name":"a.kt","path":"src/a.kt","type":"file","size":120,"mode":"100644"}]}
// type: "dir" | "file" | "submodule" | "symlink". 순서는 git 트리 순서(이름순). 디렉터리 우선 정렬은 웹에서 한다.

// GET /api/v1/repos/demo/blob?path=README.md
{"ref":"main","path":"README.md","size":6,"binary":false,"tooLarge":false,"lfs":null,"text":"hello\n"}
// 바이너리이거나 1MB를 넘으면 text=null이고 binary/tooLarge=true. 그때는 raw로 내려받는다.

// GET /api/v1/repos/pc-verify/readme
{"ref":"main","path":"README.md","format":"markdown","raw":"hello\n","html":"<p>hello</p>\n"}
// GET /api/v1/repos/demo/readme?path=docs/guide.md → {"ref":"main","path":"docs/guide.md","format":"markdown","raw":"…","html":"…"}
// format: "markdown" | "text". 상대 경로 링크와 이미지(src/href)는 원문 그대로 두므로 웹이 응답의 path 기준으로 바꾼다(README와 같음). html은 서버에서 정화되어 있어 innerHTML로 넣어도 된다
// 원시 HTML은 GitHub와 비슷한 안전한 부분집합만 남긴다(QA-04, jsoup Safelist): h1~h6·p·div의 align, img(src/alt/width/height),
// picture, details/summary, table, kbd 등은 유지. script·이벤트 핸들러(on*)·style 속성·iframe·form·input, http(s)/mailto가 아닌 URL은 제거.
// class는 code의 language-* 만 허용. 상대 경로 링크는 그대로 둔다.

// GET /api/v1/repos/demo/commits?page=1&perPage=2
{"ref":"main","path":"","page":1,"perPage":2,"hasMore":true,"truncated":false,"commits":[
  {"sha":"3f94903d…","shortSha":"3f94903","summary":"init","message":"init\n","authorName":"T","authorEmail":"t@e",
   "authoredAt":1792886900000,"committedAt":1792886900000,"parents":[]}]}
```

## 4. 정적 웹 파일 위치

- 웹 UI 빌드 결과물은 **`app/src/main/assets/web/`**에 둔다. `index.html`이 루트다. `index.html`이 없으면 안내 문구 페이지가 나온다.
- 경로 처리:
  - `/` 아래 경로 중 `/api/v1/`와 git 경로(`/<repo>/info/refs`, `git-upload-pack`, `git-receive-pack`)가 아니면 `assets/web/<path>`를 제공한다.
  - **`/r`과 `/r/...`는 항상 `index.html`**(WR-1). 파일을 찾지 않으므로 `/r/foo.js/blob/README.md`처럼 `.`이 있어도 된다. history 라우팅은 이 접두사 아래에 둔다.
  - 그 밖의 경로는 파일이 있으면 그 파일, 없으면 마지막 조각에 `.`이 있을 때 404, 없을 때 `index.html`.
  - git 경로는 먼저 매칭된다. `/r/<x>/info/refs`(GET)와 `/r/<x>/git-upload-pack`·`git-receive-pack`(POST)는 git 요청으로 처리되므로 웹 라우트에서 `info/refs`로 끝나는 주소는 쓰지 않는다.
- 캐시(WR-3): `assets/...`(Vite 해시 파일)는 `Cache-Control: public, max-age=31536000, immutable`. `index.html`(SPA 대체 포함)은 `no-cache`. 그 밖의 루트 파일(`favicon.svg`, `theme.js` 등)은 캐시 헤더가 없다. 바뀔 수 있는 파일은 `assets/` 밖에 두거나 이름에 해시를 넣는다.
- 압축(WR-4): `Accept-Encoding: gzip`이면 정적 파일과 `/api/v1/` JSON을 1KB 이상일 때 gzip으로 보낸다. git smart HTTP와 `raw`는 압축하지 않는다(정확한 바이트와 `Content-Length`).
- 보안 헤더(변경 불가):
  - `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'`
  - **인라인 `<script>`, 인라인 `style=""`, `<style>` 태그, CDN 사용 불가.** 번들된 .js/.css 파일만 쓴다.
  - `nosniff`, `Referrer-Policy: no-referrer`.
- 콘텐츠 타입은 확장자로 정한다: html, js, mjs, css, json, svg, png, ico, woff, woff2, txt, map, webp, jpg.
- 웹 화면도 인증 대상이다. 같은 출처라 fetch에 자격 증명이 자동으로 실린다.

## 5. 미구현 / 실기기 미검증 항목

구현하지 않은 기능은 없다(F-01..F-25 코어 로직 모두 있음). 화면은 임시 화면 하나뿐이다(서버 스위치, 주소, 미러 추가, 목록). 실제 UI와 웹 UI는 각 담당이 구현한다.

실기기나 실서비스에서 확인하지 못한 것:

| 항목 | 상태 |
|---|---|
| ngrok 실제 터널(유효 토큰) | 토큰이 없어 미검증. 실기기에서 라이브러리 로드와 실서비스의 가짜 토큰 거부(ERR_NGROK_105)는 확인. 터널 상태 전이는 페이크 백엔드 단위 테스트로 확인. |
| 비공개 레포 토큰(GitHub/GitLab 실계정) | 미검증. 로컬 서버를 Basic 인증 원본으로 써서 토큰 경로, 인증 실패 분류, 비밀값 제거를 JVM 테스트와 실기기 테스트로 확인. |
| 일괄 미러의 실제 GitHub/GitLab API | 페이크 Ktor 서버로 페이지 이동과 rate limit을 테스트. 실 API 호출은 미검증. |
| Android 8~15(API 26~35) | API 36(LineageOS, M2012K11AG)에서만 실행. |
| 30대 동시 clone, 2GB 레포, 8시간 배터리 | 부하·장시간 테스트 미실시. |
| 재부팅 후 자동 시작, WorkManager 주기 작업, SAF 내보내기 | 실기기 미검증. 코드 경로와 JVM 테스트(내보내기 스트림, 동기화 규칙)만 확인. |
| x86_64 에뮬레이터에서 ngrok | AAR에는 포함. 실행은 미검증. |

## 6. PRD 12절 답변

1. **최소 Android 버전과 포그라운드 서비스 유형**
   - minSdk 26(Android 8.0), targetSdk 36, compileSdk 37.
   - JGit 7의 Java 17 API는 core library desugaring으로 맞춘다. 실기기 검증은 API 36뿐이므로, 출시 전 API 26/29/33 에뮬레이터 확인이 필요하다.
   - 서버: `specialUse`(subtype 설명 포함). git 서버는 dataSync, connectedDevice 등 정해진 유형에 맞지 않고, dataSync는 Android 15부터 하루 6시간 제한이 있어 상시 서버에 쓸 수 없다. Play 배포 시 specialUse 사유 심사가 필요하다.
   - 동기화와 내보내기 작업: WorkManager 포그라운드 `dataSync`.
2. **30대 동시 clone 처리 한도와 대기열 상한**
   - pack 생성·수신(upload-pack/receive-pack POST)을 동시에 **4개**만 실행한다.
   - 나머지는 대기열에서 최대 **300초** 기다린 뒤 `503` + `Retry-After: 30`을 돌려준다.
   - info/refs, 웹 API, 정적 파일은 제한하지 않는다(가벼움).
   - JGit `WindowCache` 32MB를 공유하므로 4개 동시 실행 시 메모리 상한이 예측 가능하다. 30대 동시 요청은 4개씩 순차 처리된다. 작은 레포 기준 1회 clone은 1초 안팎(실측 1.1초 push)이다.
   - 실제 30대 부하 테스트는 미실시다. 값은 `GitServer(maxConcurrentPacks, queueWaitMillis)` 인자로 조정할 수 있다.
3. **2GB pack 레포**
   - JGit은 pack을 스트리밍으로 보내고 받으며, 큰 객체는 `streamFileThreshold`(16MB) 이상이면 메모리에 올리지 않는다. 그러나 fetch/receive 시 인덱싱이 객체 수에 비례해 힙을 쓰고, 중저가 기기 힙(largeHeap 512MB 안팎)에서 2GB는 검증하지 않았다.
   - **공지 권장 상한: 레포당 1GB(pack 기준), 2GB는 "가능할 수 있으나 보장 안 함".**
   - 수신 pack 크기는 `여유 공간 − 32MB`로 제한해 저장 공간 고갈을 막는다. 저장 공간 하한(기본 1GB) 아래에서는 자동 동기화를 멈춘다.
   - 다음 사이클에 1GB, 2GB 실측 후 수치를 확정한다.
4. **웹 API 형식과 README 렌더링 위치**
   - REST + JSON(`/api/v1/`, 3절)이다.
   - README는 **서버에서** commonmark-java로 HTML 렌더링하고 jsoup 허용 목록으로 정화한다(QA-04). `raw`도 같이 준다. 웹 쪽에 마크다운 라이브러리가 필요 없고 XSS 정화를 한 곳에서 관리할 수 있다.

## 7. 1차 코드 리뷰로 바뀐 동작 (UI/웹이 알아야 할 것)

근거와 항목별 결과: `progress.md` 10절. UI 요청: `docs/ui/core-requests.md` CR-8.

### 저장 파일 손상

- 앱 상태 파일(`files/state/*.json`, 레포의 `mobilegit/meta.json`)은 읽기 실패 시 **초기화하지 않는다**. 원본은 `<이름>.corrupt-<ms>`로 복사해 둔다.
- `core.storageIssues(): List<StoreIssue>`: 시작할 때 문제가 있었던 파일. `StoreIssue(file, preservedAs, message)`. 비어 있으면 정상.
  - 설정 파일이 깨지면 첫 실행처럼 시작한다(서버 꺼짐, 온보딩에 새 비밀번호). 자격 증명이 깨지면 토큰이 비어 보인다.
  - 개별 저장소: `settings.loadIssue`, `credentials.loadIssue`, `accessLog.loadIssue`, `bulkJobs.loadIssue`.
- `core.repos.damaged: StateFlow<List<DamagedRepo>>`: meta.json을 못 읽는 레포. `DamagedRepo(name, path, issue)`. 이 레포는 `repos`에 없고 git으로도 보이지 않는다. 같은 이름으로 만들면 `NAME_TAKEN`. 디렉터리는 그대로 둔다(복구는 사람이).
- 목록 파일에서 일부 항목만 깨지면 나머지는 쓰고, 원본은 `<이름>.partial-<ms>`로 남는다(issue 없음).
- 파일 형식: 모든 파일에 `"schemaVersion":1`. 목록 파일은 `{"schemaVersion":1,"items":[…]}`. 모르는 필드는 무시, 모르는 enum 값은 기본값.

### 설정 쓰기

- `SettingsStore`의 setter는 `state`를 바로 바꾸고 디스크 쓰기는 백그라운드에서 순서대로 한다(AND-08). 메인 스레드에서 불러도 된다. 디스크까지 기다려야 하면 `settings.flush()`(IO에서).

### 서버·네트워크

- `ServerController.start/stop`은 겹쳐 불러도 마지막 명령대로 끝난다(AND-02).
- 포그라운드 서비스를 못 얻으면(백그라운드 시작 제한) 서버를 띄우지 않는다. 이전처럼 "앱을 열면 다시 시작" 알림(AND-01).
- 루프백(127.0.0.1, adb forward)도 이제 인증이 필요하다. 익명 읽기 조건은 3절(SEC-03).
- 전역 IPv6 주소로 들어온 요청은 403(SEC-04). 주소 목록에는 원래 나오지 않는다.
- ngrok 경로는 2차부터 사용자의 비밀번호 요구 설정을 따른다(8절).
- ngrok 경로: 인증 실패가 분당 30회를 넘으면 ngrok 경로 전체가 1분간 429. 이미 인증된 브라우저·git은 계속 된다(SEC-01).

### git

- `refs/archive/*`는 push로 만들거나 바꾸거나 지울 수 없다. git 클라이언트에는 해당 ref만 `refs/archive/ is read-only (mobilegit keeps preserved history there)`로 거부된다(GIT-07). `git push --mirror`는 다른 ref는 들어가고 archive ref만 거부된다.
- push 받은 레포의 HEAD가 없는 브랜치를 가리키면 받은 브랜치로 맞춘다(GIT-10).
- 오래된 레포의 0으로 시작하는 파일 모드, 이상한 작성자 정보도 push된다(GIT-06).
- 하루 1회(충전 중 + 유휴) 필요한 레포만 repack(GIT-03). 도달 불가 객체·아카이브는 지우지 않는다.

### 미러

- `SyncEvent.newCommitCount`: 커밋 수는 10000에서 멈춘다. 10000이면 "10000+"로 보여 달라(GIT-02).
- `MirrorSource.waitingForForeground: Boolean`: 첫 받기가 백그라운드에서 포그라운드 작업을 못 얻어 미뤄진 상태. 앱을 열면 자동으로 시작된다(GIT-08).
- 받는 중 여유 공간이 하한(수동 업데이트는 64MB) 밑으로 가면 `STORAGE_FULL`로 멈춘다.
- 취소·WorkManager 중단은 실패로 기록되지 않는다(AND-04).
- 토큰은 https 또는 사설망/루프백 http URL에만 붙는다. 공개 호스트의 `http://` 미러는 토큰 없이 받는다(SEC-05). 리다이렉트로 다른 호스트로 가면 토큰을 주지 않는다(SEC-09).
- 저장된 토큰을 복호화하지 못하면 `AUTH_REQUIRED`("토큰을 다시 입력해 주세요").

### 내보내기

- "파일로 저장"(SAF Uri)은 문서에 바로 쓴다. 실패하면 그 문서를 지운다. `K_OUT_FILE`은 null.
- "공유"(Uri 없음)는 캐시 파일을 만들고 24시간 뒤 지운다. 남는 공간이 하한보다 작아지면 `저장 공간이 부족해 공유용 파일을 만들 수 없습니다…`로 실패한다(ARC-07).
- 선택한 레포가 많아도 된다(목록은 파일로 넘김, AND-10).

## 8. 2차 변경 (PRD v2 13장)

진행 기록: `progress.md` 11절. 코드 커밋 `f2af10d`.

### R2-01 목록의 주의 상태
- `Badge.HISTORY_CHANGED`: 업데이트가 ref를 보존(F-11)하면 붙는다. 로컬로 분리된 레포에도 남는다. 저장 위치는 `RepoMeta.historyChangedAt: Long?`이라 앱을 다시 켜도 유지된다.
- 지우기: 보존 기록 화면(A-15)을 열 때 `core.repos.markHistorySeen(name)`.
- "주의 필요" = `Badges.problem` = `FAILED`, `ORIGIN_GONE`, `AUTH_REQUIRED`, `DELAYED`, `HISTORY_CHANGED`. 필터 값은 그대로 `RepoFilter.PROBLEM`이다(화면 이름만 "주의 필요"). `RepoListItem.needsAttention`.
- 행 둘째 줄: `RepoListItem.issue: RepoIssue?`. null이면 지금처럼 "업데이트 N분 전". `RepoIssue(badge, since, category)`. 우선순위는 원본 없음 → 토큰 확인 필요 → 실패 → 지연 → 히스토리 바뀜.
  - 실패류의 `since`는 `MirrorSource.failingSince`(이번 연속 실패의 시작, "3일째"). 2차 이전에 시작된 실패는 null이라 기간 없이 보여 준다.
  - `DELAYED`의 `since`는 마지막 확인 시각("6시간 주기인데 2일째 확인 못 함"). `HISTORY_CHANGED`는 보존 시각.
- 정렬 `RepoSort.RECENT`는 주의 필요 레포를 맨 위에 둔다.

### R2-02 메인 요약
- `core.overview: Flow<RepoOverview>`(1분마다 다시 계산). `RepoOverview(mirrors, healthy, attention, locals, lastCheckAt, checksStalled)`.
  - `attention`: 주의 필요 레포 수(필터와 같음, 로컬 포함). `healthy`: 주의가 없는 미러 수.
  - `lastCheckAt`: 모든 미러 중 원본에 마지막으로 성공적으로 닿은 시각(`MirrorSource.lastOkAt`). 미러가 없으면 null.
  - `checksStalled`: 그 시각(없으면 가장 오래된 미러의 추가 시각)이 확인 주기의 3배보다 오래됐다 → "백그라운드 확인이 멈춘 것 같아요".
- 새 필드 `MirrorSource.lastSuccessCheckAt`(확인·업데이트 성공 시각). `lastCheckAt`은 실패해도 움직인다.
- "서버를 꺼도 …" 안내를 닫은 상태: `Settings.serverOffHintDismissed`. 닫기: `settings.update { it.copy(serverOffHintDismissed = true) }`.

### R2-03 보존 기록
- 앱: `core.ops.archives(name): List<ArchiveEntryDto>`(디스크 읽기, IO에서). 화면을 열면 `repos.markHistorySeen(name)`도 부른다.
- 웹: `GET /api/v1/repos/{name}/archives` → `{"entries":[ArchiveEntryDto…]}`.
- `ArchiveEntryDto(at, group, kind, ref, name, archiveRef, sha, shortSha, summary, committedAt, currentSha)`. 최신 묶음이 처음, 묶음 안은 ref 이름순.
  - `group`: 한 번의 업데이트가 보존한 묶음 이름(`20261025T101010Z`, 같은 초면 `…Z-2`). `at`은 그 시각(epoch ms, 초 단위).
  - `kind: RefChangeKind?`: `FORCED`(히스토리 바뀜), `DELETED`(브랜치 삭제됨), `TAG_MOVED`, `TAG_DELETED`. **null이면 "강제 push 또는 삭제"**(2차 이전 보존이고 이력에서도 사라진 경우).
  - `ref`: 원래 ref 전체 이름. `name`: 짧은 이름(`main`, `v1`). `summary`/`committedAt`: 보존된 커밋의 첫 줄과 시각(태그는 커밋까지 따라감, 커밋이 아니면 null).
  - `currentSha`: 지금 이 레포에서 그 ref의 값(마지막 업데이트 기준 원본 값). null = "원본에 없음".
- 받는 명령은 그대로 `git fetch <URL> 'refs/archive/*:refs/archive/*'`.
- AC6(이력 → A-15 묶음): `RefChange.archivedAs`가 `refs/archive/<group>/…`이므로 그 `group`으로 찾는다.
- 종류 저장: 업데이트마다 `mobilegit/archives.json`에 보존 ref별 변경을 남긴다(이력 200개 한도와 무관). 2차 이전 보존은 남은 이력에서 찾는다.

### R2-04 변화 종류
- `RefChange.kind: RefChangeKind`(파생 속성이라 예전 이력에도 나옴): `FAST_FORWARD`(새 커밋), `FORCED`(히스토리 바뀜), `CREATED`(새 브랜치·태그), `DELETED`(브랜치 삭제됨), `TAG_MOVED`, `TAG_DELETED`.
- PR ref(`refs/pull/…`)는 보존하지 않으므로 움직이면 항상 `FAST_FORWARD`로 나온다.
- 실패 제목은 기존 `SyncEvent.errorCategory`.

### R2-05 AC4 / D-8 재부팅·업데이트 뒤 ngrok
- `BootReceiver`(부팅 완료, 앱 업데이트)는 ngrok이 켜져 있었으면 `ngrokEnabled = false`로 바꾸고 `core.server.ngrokOffAfterRestart`(`MutableStateFlow<Boolean>`)를 true로 둔다. 서버 알림에 "외부 공개는 꺼진 채로 다시 시작했어요"가 나온다. ngrok을 다시 켜거나 `core.server.ackNgrokOff()`를 부르면 사라진다. 프로세스가 끝나도 사라진다.
- 같은 부팅 안의 프로세스 재시작(START_STICKY)은 ngrok을 그대로 둔다.
- 부팅 후 서버 자동 시작은 새 설치 기본 켜짐(`startOnBoot = true`, CR-6). 기존 설치는 저장값을 유지한다.

### R2-06 비밀번호 요구 + ngrok
- 코어는 조합을 거부하지 않는다. ngrok 경로에도 "비밀번호 요구" 설정을 같은 규칙으로 적용한다.
  - 읽기·쓰기(`authEnabled = true, anonymousReadOnLan = false`): 모든 요청에 비밀번호.
  - push만(`authEnabled = true, anonymousReadOnLan = true`): ngrok 주소로 익명 clone 가능, 익명 push는 401.
  - 끄기(`authEnabled = false`): ngrok으로도 모두 익명(push 포함).
- `Settings.effectiveAuthEnabled`/`effectiveAnonymousRead`는 이제 저장값 그대로다(ngrok과 무관).
- `Settings.exposedWithoutPassword`: ngrok 켜짐 + "push만" 또는 "끄기". 위험 배너·경고 표시용.
- 접속 기록 경로는 지금처럼 `NGROK`. 실패 한도(IP별, ngrok 전체 분당 30회)는 그대로.
- `/api/v1/info`의 `authRequired`, `anonymousRead`는 저장값이다.

### R2-07 AC6
- 할 일 없음. 접근 확인 결과 `ProbeResult.Reachable.pullRefCount`, `Settings.mirrorPullRefs`/`MirrorSource.includePullRefs`, 웹 API `RepoSummaryDto.hasLfs`가 이미 있다.

### R2-15 테더링 주소
- `AddressKind.USB_TETHER`(인터페이스 `rndis*`, `usb*`, `ncm*`). 와이파이 핫스팟과 블루투스 테더링(`bt-pan`)은 `HOTSPOT`.
- 대표 주소 순서: 와이파이 → 핫스팟 → USB 테더링 → Tailscale → ngrok(enum 순서).
- 접속 기록 `AccessRoute.USB_TETHER`.
- 실기기에서 USB 테더링을 켜서 확인하지는 않았다(인터페이스 이름은 기기마다 다를 수 있음).

### UI 코드에 넣은 것 (빌드 유지용)
- `ui/Format.kt`: 새 enum 값 때문에 `when`이 깨지지 않도록 `Badge.HISTORY_CHANGED`(경고 톤, History 아이콘, "히스토리 바뀜"), `AddressKind.USB_TETHER`·`AccessRoute.USB_TETHER`("USB 테더링") 분기와 `badgeOrder` 위치만 넣었다. 모양은 UI가 바꿔도 된다.
- 문자열: `badge_history_changed`, `route_usb_tether`, `server_notif_ngrok_off_after_restart`.

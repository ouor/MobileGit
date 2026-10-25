# 코어 진행 상황 (progress)

기준일 2026-10-25 · 사이클 1 (PRD v1 F-01..F-25 코어 로직 전체)

## 1. 커밋

| 커밋 | 내용 |
|---|---|
| `4537b29` | smart HTTP git 서버(JGit + Ktor CIO), 인증, 웹 JSON API |
| `41174cb` | 아카이브 보존 미러 동기화, 자격 증명, 일괄 목록, fork/삭제, 내보내기, 터널 관리자 |
| `47a4bce` | `ngrok-mobile/`: ngrok-go v2 gomobile 바인딩(AAR) |
| `ec1b752` | 안드로이드 계층: 포그라운드 서비스, WorkManager, Keystore, 알림, 임시 화면, 실기기 테스트 |
| (이 문서) | `docs/core/` 명세, 조사, 진행 |

## 2. 기능 상태

F-01..F-25 코어 로직은 모두 구현됐다. API 목록은 `spec.md` 1절에 있다. 화면은 임시 화면(`app/MainActivity.kt`) 하나뿐이고 정식 UI와 웹 UI는 각 담당 몫이다.

## 3. 검증

### JVM 테스트 (`./gradlew :app:testDebugUnitTest`, 34개 통과)

실제 git CLI(격리된 설정)와 실제 서버 소켓을 쓴다.

| 스위트 | 개수 | 다루는 것 |
|---|---|---|
| GitServerTest | 8 | push로 레포 생성, 잘못된 이름 거부, 미러 push 거부 문구, 인증 규칙(익명 읽기, ngrok 강제 인증), 무차별 대입 차단(429), 웹 API와 정적 파일, 빈 레포 API, 포트 충돌 |
| MirrorSyncTest | 12 | 첫 미러(모든 ref + HEAD), force-push·브랜치 삭제·태그 이동 보존, fast-forward는 보존 안 함, 원본 삭제 후 데이터 유지와 복구, 비공개 원본 토큰 경로와 토큰 비노출, 저장 공간 하한, 지연 배지, 여러 줄 일괄 추가, bundle·zip·전체 백업 내보내기, fork COPY/CONVERT와 삭제, 디스크 재적재, 원본 HEAD 추적 |
| UrlAndNameTest | 4 | URL 정규화(GitHub/GitLab/Bitbucket/브라우저 주소), 이름 규칙, 공유 텍스트 추출 |
| AuthUnitTest | 3 | PBKDF2, 인증 결정, rate limiter |
| RemoteCatalogTest | 3 | 페이크 Ktor 서버로 GitHub/GitLab 페이지 이동, rate limit 대기 |
| NotificationRulesTest | 2 | 상태 전이 시에만 알림 |
| NetTest | 1 | Wi-Fi/핫스팟/Tailscale 판별, 접속 경로 분류 |
| TunnelManagerTest | 1 | 페이크 백엔드로 연결, 재시도 백오프, 토큰 거부 시 재시도 중단 |

Go 테스트(`ngrok-mobile`, `go test`)는 실제 ngrok 서비스가 가짜 토큰을 ERR_NGROK_105로 거부하는 것을 확인했다(`AUTH:` 분류).

### 실기기 instrumented 테스트 (사이클 1 초기: `connectedDebugAndroidTest`, 3개 통과. 이 명령은 앱을 제거하므로 지금은 7장의 절차를 쓴다)

기기: Xiaomi M2012K11AG, LineageOS, API 36.

- `keystoreRoundTrip`: Keystore AES-GCM 암복호화.
- `smartHttpPushCloneAndArchiveOnArt`: ART에서 서버 시작, JGit 클라이언트로 push/clone, 미러 등록, 원본 force-push 후 아카이브 ref가 원래 커밋을 가리키는지, bundle 내보내기.
- `ngrokNativeLoadsAndRejectsBogusToken`: gomobile 라이브러리 로드와 실서비스의 가짜 토큰 거부를 `TunnelAuthException`으로 받는지.

### 실기기 수동 확인 (PC 192.168.31.101 → 휴대폰 192.168.31.120:8080)

- 임시 화면에서 서버 켜기 → `Running(port=8080)`, Wi-Fi 주소 표시.
- PC에서 `git push http://192.168.31.120:8080/pc-verify.git main dev`(새 레포 생성) 약 1.2초.
- `git clone .../pc-verify`(.git 생략) → 브랜치 main/dev, HEAD=main, 내용 일치. protocol v0 `ls-remote`도 정상.
- 웹 API: 인증 없으면 401, 인증하면 `/api/v1/repos`와 `/readme` 정상.
- (이전 설치에서) GitHub `octocat/Hello-World` 미러 추가(안드로이드 JGit HTTPS fetch), PC에서 clone, 미러로 push하면 읽기 전용 오류 문구가 표시됨.

## 4. UI 요청 처리 (사이클 1 후속, `docs/ui/core-requests.md`)

| 요청 | 커밋 | 결과 | 검증 |
|---|---|---|---|
| CR-1 포트 변경 반영 | `a52a702` | `ServerController.restart()`. 서비스가 포트 변경을 감지해 자동으로 다시 연다 | 실기기 instrumented `portChangeRebindsRunningServer` |
| CR-2 PR ref 옵션 | `ca55156` | `Settings.mirrorPullRefs`(기본 false), `MirrorSource.includePullRefs`, `setIncludePullRefs`, `Reachable.pullRefCount` | JVM `pullRefsFollowSettingAndOverride` |
| CR-3 이력 Flow | `ca55156` | `RepoStore.eventsFlow(name)`(최신이 처음). spec의 순서 설명 수정 | JVM `eventsFlowEmitsNewestFirst` |
| CR-4 지금 확인 | `ca55156` | `MirrorController.checkNow(name)`, `checkAll()` | JVM `checkNowIsCheckOnly` |

테스트: JVM 37개, 실기기 4개 모두 통과. Spoon-Knife 실제 미러에서 PR ref 제외 후 시간은 다시 재지 않았다.

## 5. 웹 요청 처리 (`docs/web/core-requests.md`)

| 요청 | 결과 (커밋 `b35e68f`) |
|---|---|
| WR-2 raw의 Content-Length + chunked | 수정. 원인: 수동 `Content-Length` 헤더 + 스트리밍 응답. `respondOutputStream(contentLength = size)`로 바꿈. 모든 엔드포인트(API, raw, info/refs, 정적, SPA)를 소켓 수준에서 gzip 유무 각각 검사해 중복이 없음을 확인 |
| WR-1 `/r/` SPA 대체 | `/r`, `/r/...`는 항상 index.html |
| WR-3 해시 자산 캐시 | `assets/...`에 1년 immutable |
| WR-4 gzip | Ktor Compression(ktor-server-compression 3.6.0). 정적·JSON만, git과 raw 제외 |

| WR-5 임의 Markdown 렌더링 | `24bc04c`. 기존 `readme?path=`가 `.md` 파일 경로도 받음(새 엔드포인트 없음). `NOT_MARKDOWN`, `TOO_LARGE` |

테스트: JVM `webFramingSpaCacheAndGzip`, `compressionScope`, `readmeRendersAnyMarkdownFile` 추가, 전체 40개 통과. 실기기에서 헤더를 직접 확인하지는 않았다(같은 Ktor CIO 엔진의 JVM 테스트로 확인).

## 6. QA 1차 처리 (`docs/qa/round1/report.md` 4장)

| ID | 결과 | 커밋 | 검증 |
|---|---|---|---|
| QA-01 포트 사용 중 크래시 | CIO accept 코루틴의 BindException을 서버 부모 컨텍스트의 CoroutineExceptionHandler로 가둠 → `Failed(PORT_IN_USE)` | `44b01da` | JVM `portInUseLeaksNoUncaughtException`(수정 전 실패 재현), 실기기 `busyPortFailsWithoutCrash`(실제 서비스, 앱 프로세스 생존) |
| QA-02 업데이트 뒤 서버 미복구 | `Failed(kind = BACKGROUND_START_BLOCKED)` + 한국어 문구(원문은 `detail`), 알림, 앱 화면이 열리면 자동 재시작 | `fbab46e` | 실기기: 서버 켬 → `adb install -r` → 시스템 로그 "Background started FGS: Disallowed", 크래시 없음, 알림 표시 → 앱 열기 → 서버 응답(401) 복구, 알림 사라짐 |
| QA-03 이미 미러된 URL | 코어는 `existing`을 채우고 있었음. `AddPreview.errorKind`(`ALREADY_MIRRORED`/`UNSUPPORTED_URL`) 추가. 화면 문구는 UI 몫 | `dbb641e` | JVM `alreadyMirroredKindAndCatalogDescription` |
| QA-04 README raw HTML | jsoup 1.23.2(MIT) Safelist로 안전한 부분집합 허용 | `414c004` | JVM `MarkdownSanitizeTest` 2개, 기존 XSS 테스트 유지 |
| QA-05 LFS push 실패 | LFS batch API가 git-lfs에 표시되는 안내(GIT_LFS_SKIP_PUSH=1)를 돌려줌. 객체는 저장하지 않음 | `44b01da` | JVM `lfsPushGetsGuidanceAndPointerPushWorks`(실제 git-lfs 3.7.1로 거부 문구 확인 후 포인터 push 성공) |
| QA-08 잘못된 이름 사유 | 규칙별 사유를 400 본문으로 → git이 `remote:`로 표시 | `44b01da` | JVM `invalidNamePushShowsReason`, `NameReasonTest` |
| QA-09 미러 설명 | 일괄 미러에서 카탈로그 설명 저장, `add(description=)` | `dbb641e` | JVM 위 테스트 |

테스트: JVM 47개, 실기기 5개 통과. QA-04는 웹 화면에서 직접 보지 않았다(API 출력만 검증).

## 7. QA 재검증 처리 (`docs/qa/round1/report.md` 8장)

| ID | 결과 | 커밋 | 검증 |
|---|---|---|---|
| QA-14 README SVG 깨짐 | raw가 이미지를 `image/*` + inline으로 보냄. 모든 raw에 CSP sandbox + nosniff. HTML·텍스트 등은 여전히 octet-stream attachment. `download=1` 추가 | `a4fa598` | JVM `rawContentTypes`(SVG/PNG inline·타입, HTML/TXT attachment, download=1). 실기기 브라우저 확인은 QA 재검증 몫(인증 필요) |
| QA-13 포트 바꾼 뒤 실패 배너 남음 | 포트 설정이 바뀌면 `Failed(PORT_IN_USE)`를 `Stopped`로 정리 | `24f81f7` | 실기기 `busyPortFailsWithoutCrash`에 확인 추가. 테스트 뒤 메인 화면 "서버 꺼짐"만 표시 |

참고: 개발 PC의 AdGuard가 로컬 HTTP 응답의 CSP 헤더를 바꿔 끼운다. 그래서 JVM 테스트는 CSP 전체 문자열이 아니라 `sandbox` 존재와 상수 값을 확인한다.

### 실기기 테스트를 데이터 손실 없이 돌리는 방법

`connectedDebugAndroidTest`는 끝나면 앱을 제거해 폰 데이터(레포)가 지워진다. 이 사이클부터는 아래 절차를 쓴다(QA 레포 15개 유지 확인함).

1. 백업: `adb -s d418f2d1 exec-out run-as com.ouor.mobilegit tar cf - files > backup.tar`. Keystore 키는 앱을 제거하지 않는 한 유지된다.
2. 설치(제거 없음): `./gradlew :app:installDebug :app:installDebugAndroidTest`
3. 실행: `adb -s d418f2d1 shell am instrument -w -e class com.ouor.mobilegit.OnDeviceTest#<테스트> com.ouor.mobilegit.test/androidx.test.runner.AndroidJUnitRunner`
4. 확인: `adb shell run-as com.ouor.mobilegit ls files/repos`. 테스트가 서버를 끄므로 원래 켜져 있었다면 앱에서 다시 켠다.
- 주의: `OnDeviceTest`의 포트 관련 테스트는 실제 앱 설정(포트, 서버 켜짐)을 바꿨다가 되돌린다. 레포 데이터는 건드리지 않는다.

## 8. 1차 사용자 리뷰 요청 (`docs/ui/core-requests.md` CR-6, CR-7)

| 요청 | 결과 | 커밋 | 검증 |
|---|---|---|---|
| CR-6 부팅 시 자동 시작 기본값 | `Settings.startOnBoot = true`(새 설치). 설정 파일은 기본값까지 모두 저장하므로 기존 설치의 `false`는 그대로 | `56493f8` | JVM `startOnBootDefault`(빈 JSON → true, 저장된 false 유지) |
| CR-7 로컬 push 이력 | `PushEvent` + `RepoStore.pushEventsFlow`, 레포당 200개 | `3f4ca23` | JVM `pushEventsAreRecorded`(생성·갱신·force·삭제, 순서, 한도) |

- **CR-6은 PRD와 다른 결정이다.** PRD 6장 데이터 모델은 `startOnBoot | false`다. 바꾼 근거: 1차 사용자 리뷰에서 2명(02, 04)이 "폰을 재부팅하면 서버가 꺼져 있다"를 불편으로 꼽았다. 이 앱은 폰을 상시 서버로 쓰는 것이 기본 사용 방식이다. 자동 시작은 사용자가 서버를 켜 둔 상태(`serverEnabled`)에서만 일어나므로, 서버를 쓰지 않는 사용자에게는 영향이 없다. Android 15의 부팅 시 FGS 제한은 dataSync 등 특정 유형에만 적용되고 서버의 specialUse 유형은 대상이 아니다. 실기기 재부팅 확인은 하지 않았다(리뷰용 데이터 보호, 기존 미검증 항목과 같음).
- 기존 설치(리뷰용 폰 포함)는 저장된 `startOnBoot=false`가 남는다. 요청대로 마이그레이션하지 않았다.
- 기기 테스트는 돌리지 않았다(서버 코드 경로는 JVM 테스트로 충분하고 폰 데이터를 건드리지 않기 위해).

## 9. 남은 일 / 위험

| 항목 | 메모 |
|---|---|
| ngrok 유효 토큰으로 실제 터널 | 토큰이 없어 미검증. 다음 사이클에 토큰 받아서 확인 |
| 비공개 레포 실계정 토큰, 일괄 미러 실제 API | 페이크와 로컬 서버로만 검증 |
| API 26~35 | 에뮬레이터 확인 필요(desugaring 경로) |
| 30대 동시 clone, 1GB/2GB 레포, 8시간 배터리 | 부하·장시간 테스트 필요. 2GB는 보장하지 않음(spec 6절) |
| 부팅 후 자동 시작, 주기 작업, SAF 내보내기 | 실기기 확인 필요 |
| ngrok AAR 크기 | APK가 약 8.7MB(2 ABI) 늘어남. 필요하면 ABI split |
| PR ref 미러 | CR-2로 해결. 기본 꺼짐, 설정·레포별로 켬 |
| 임시 화면 | 정식 Compose UI로 교체 대상 |
| AAR 다시 빌드 | `ngrok-mobile/build.sh`. Go 1.26.10 이상, Android NDK 필요 |

## 10. 1차 코드 리뷰 반영 (`docs/code-review/round1/`)

담당: AND-01~08, AND-10~16, GIT-01~16, SEC-01~12, ARC-01~05, ARC-07, ARC-15, ARC-18 (51개). 결과: 수정 42, 일부 수정 8, 해당 없음 1.

커밋: 저장 `aee0cfe`, 서비스 `3c87e3d`, 동기화·내보내기 `3d8fb78`, GC `fc4fb07`, 서버·보안 `d889841`, 시작·Keystore·터널 `518ecd8`, 기기 테스트 `0fea9b4`.
JVM 테스트 78개 통과(신규 `StorageTest` 12개 포함). 실기기 `OnDeviceTest` 6개 통과(7절 보존 절차, 레포 15개 유지).

### 저장 계층 (ARC-01/02/03/15/18, AND-03, GIT-15)

| ID | 결과 | 내용 |
|---|---|---|
| ARC-01, AND-03 | 수정 | `JsonFile.read`: 파일 없음 = null, 읽기/파싱 실패 = `CorruptFileException`이며 원본을 `<이름>.corrupt-<ms>`로 복사해 둔다(같은 내용이면 다시 복사하지 않음). 각 저장소는 `loadIssue: StoreIssue?`로 알린다. 설정이 깨지면 첫 실행처럼 시작(서버 꺼짐, 온보딩에 새 비밀번호)하고 원본은 남는다 |
| GIT-15 | 수정 | meta.json을 못 읽는 레포는 로컬로 바꾸지 않고 `RepoStore.damaged`에 넣는다. meta는 건드리지 않고, 같은 이름 생성은 `NAME_TAKEN`. meta가 없고 HEAD만 있으면 예전처럼 로컬로 채택 |
| ARC-02 | 수정 | `schemaVersion: 1`을 모든 파일에 쓴다. 목록 파일은 `{"schemaVersion":1,"items":[…]}`, 예전 맨 배열도 읽는다. `coerceInputValues`로 모르는 enum은 기본값. 목록은 항목별로 디코드하고 버린 항목이 있으면 `<이름>.partial-<ms>` 복사. 더 새 버전 파일은 `<이름>.v<N>-<ms>`로 복사한 뒤 읽을 수 있는 만큼 읽는다 |
| ARC-03 | 수정 | 자격 증명·일괄 작업·접속 기록의 read-modify-write를 `@Synchronized mutate`/쓰기 락 안으로. 설정 쓰기는 단일 스레드에서 순서대로 |
| ARC-15 | 수정 | 임시 파일 이름을 `.<이름>.<uuid>.tmp`로(동시 쓰기 충돌 없음), rename 뒤 디렉터리 fsync |
| ARC-18 | 수정 | `StorageTest` 12개: 손상 보존, 모르는 필드/enum, meta 손상, 옛 형식, 새 스키마, 시작 정리, 삭제 레포 이력, 동시 변경, 접속 기록 clear/flush 경합, 비동기 로드 |

### 서비스·안드로이드 (AND-01~08, 10~16)

| ID | 결과 | 내용 |
|---|---|---|
| AND-01 | 수정 | `startForeground` 실패면 서버를 띄우지 않고 서비스를 끝낸다. 알림(id 2)은 기존 "백그라운드에서 시작 못함" 흐름 |
| AND-02 | 수정 | `ServerController`에 lifecycle Mutex와 `desiredUp`. bringUp/bringDown 직렬화, 서비스의 락 획득·관찰자는 멱등. 실기기 `startStopCommandsAreSerialized` 통과 |
| AND-04 | 수정 | `CancellationException`은 실패로 기록하지 않고 다시 던진다. 진행 상태도 정리 |
| AND-05 | 수정 | 슬롯과 레포 락을 얻은 뒤에 `setForeground`(`onStart` 콜백) |
| AND-06 | 일부 수정 | 화면이 꺼져 있으면 인터페이스 열거를 60초에 한 번. PARTIAL wake lock은 유지(서버가 화면 꺼짐에도 응답해야 함, PRD F-22) |
| AND-07 | 수정 | `onDestroy`는 `core.scope`(IO)에서 서버를 내린다 |
| AND-08 | 일부 수정 | 설정 쓰기 비동기(`flush()` 제공), 접속 기록 로드 비동기, Keystore 키 캐시. UI 호출 위치(자격 증명 추가가 메인 스레드)는 UI 몫 → `docs/ui/core-requests.md` CR-8 |
| AND-10 | 수정 | 선택이 4000자를 넘으면 레포 목록을 `files/state/export-<uuid>.txt`로 넘기고 작업 뒤 삭제 |
| AND-11 | 수정 | `update()`가 `SyncFailure` 아닌 예외도 분류해 기록, 진행 상태가 남지 않음 |
| AND-12 | 수정 | 공유용 내보내기 파일은 24시간 뒤, `.part`는 다음 시작 때 삭제 |
| AND-13 | 수정 | 키 1회 로드 + `@Synchronized` 생성. ngrok 토큰 복호화 실패는 `TunnelState.TokenRejected`로 보인다 |
| AND-14 | 수정 | SDK 34+에서는 Wi-Fi lock을 잡지 않는다(실기기 SDK 36에서 없음 확인) |
| AND-15 | 수정 | 터널 Job의 finally는 자기가 만든 세션만 닫는다 |
| AND-16 | 해당 없음(판단 다름) | `usesCleartextTraffic`는 유지: LAN의 http 미러(사내 Gitea 등)를 지원한다. 대신 토큰은 https 또는 사설망/루프백 http에만 붙인다(SEC-05). `largeHeap`도 유지(JGit pack 처리) |

### git 서버·미러 (GIT-01~16)

| ID | 결과 | 내용 |
|---|---|---|
| GIT-01 | 수정 | 시작 시 레포마다 락 안에서 남은 `*.lock`, pack 임시 파일 정리(`sweepAll`, IO). push/업데이트 직전에도 그 레포의 락 정리(레포 락을 잡고 있으므로 남은 것은 모두 고아). Gitaly도 housekeeping에서 오래된 ref 락을 지운다 |
| GIT-02 | 수정 | 커밋 수 세기는 본문 없이, 10000개에서 멈춤(`MAX_COUNTED_COMMITS`, UI는 "10000+") |
| GIT-03 | 수정 | `RepoOps.gcIfNeeded`: pack 20개 초과, loose 2000개 초과, loose ref 500개 초과면 JGit GC. 충전 + 유휴일 때 하루 1회(`MaintenanceWorker`). 200만 객체 넘는 레포는 건너뜀. 도달 불가 객체는 지우지 않음(`expire = EPOCH`, 아카이브 보존). 옛 pack은 JGit 기본값대로 1시간 뒤 삭제 |
| GIT-04 | 수정 | `RepositoryCache` 사용(10분 미사용 시 닫음). 삭제 전 캐시에서 빼고 닫는다 |
| GIT-05 | 수정 | 삭제는 먼저 `.trash-<uuid>`로 rename. 시작 정리가 남은 `.trash-*`, `.tmp-*`를 지운다 |
| GIT-06 | 수정 | `fsck.allowLeadingZeroFileMode`, `allowInvalidPersonIdent`(git 기본 허용과 같은 범위). 기존 레포는 시작 시 마이그레이션 |
| GIT-07 | 일부 수정 | `refs/archive/*`는 push로 바꿀 수 없다(pre-receive hook에서 거부). reflog는 켜지 않음: 보호로 충분하고 레포가 계속 커짐 |
| GIT-08 | 수정 | 첫 받기 중 여유 공간이 하한(수동은 64MB) 밑으로 가면 `STORAGE_FULL`로 멈춤. 백그라운드에서 FGS를 못 얻으면 첫 받기를 `waitingForForeground`로 미루고 앱을 열 때 재개 |
| GIT-09 | 수정 | 커밋 API: page 1000 초과는 400 `PAGE_TOO_DEEP`, 한 요청당 20만 커밋까지 보고 `truncated`. tree/blob/readme/commits는 동시 2개, 10초 기다려도 안 되면 503 `BUSY` |
| GIT-10 | 수정 | HEAD가 없는 브랜치를 가리키면 push 뒤 받은 브랜치로 맞춘다(생성 때만이 아님) |
| GIT-11 | 수정 | push는 레포 락 안에서 미러 여부를 다시 확인(403). fork COPY는 대상 이름도 락으로 잡고 다시 확인 |
| GIT-12 | 수정 | 같은 초의 아카이브 이름에 `-2`, `-3`. 아카이브 적용 구간은 `NonCancellable` |
| GIT-13 | 수정 | 전체 백업은 ref 스냅샷 하나로 bundle을 쓰고, 이미 압축된 bundle은 zip에서 다시 압축하지 않음. 파일 쓰기는 fsync + ATOMIC_MOVE |
| GIT-14 | 수정 | raw는 blob을 한 번만 해석(길이와 본문 일치) |
| GIT-16 | 수정 | 분리 중 대기 업데이트는 NPE 없이 끝남. 확인이 실행 중이어도 수동 업데이트는 예약됨(`isUpdating`) |

### 보안 (SEC-01~12)

| ID | 결과 | 내용 |
|---|---|---|
| SEC-01 | 수정 | ngrok 문서: X-Forwarded-For에는 ngrok이 본 주소를 **뒤에 덧붙이므로 마지막 값**을 쓴다. 그에 맞춰 마지막 값 사용. 추가로 ngrok 경로 전체 실패 한도(분당 30회 → 1분 차단, 이미 인증된 헤더는 계속 통과) |
| SEC-02 | 수정 | PBKDF2는 IO 스레드에서, 동시 2개. 3초 안에 못 들어가면 429 |
| SEC-03 | 수정 | 익명 읽기는 사설망/링크로컬/ULA/Tailscale에서, Host가 IP·`localhost`·점 없는 이름·`.local`·`.ts.net`일 때만(DNS 리바인딩 차단). 루프백(adb forward 포함)도 이제 인증 필요 |
| SEC-04 | 수정 | 실측: `ss -ltn`에 `*:8080`, `/proc/net/tcp6`에 `::` 수신(듀얼 스택). 측정 시 기기에 전역 IPv6는 없었다. 전역 IPv6 로컬 주소로 들어온 요청은 403 |
| SEC-05 | 일부 수정 | 토큰은 https 또는 사설망/루프백 http에만. 외부 인텐트로 받은 URL을 자동 probe하기 전 확인은 UI 몫 → CR-8 |
| SEC-06 | 일부 수정 | upload/receive-pack 120초 무응답 시 끊음, upload-pack 요청 본문 64MB 상한. 인증 전 연결 수 제한은 나중(Ktor/CIO에 연결 단위 한도 API가 없음, 인증 실패 한도로 대부분 막힘) |
| SEC-07 | 수정 | IPv6는 /64 단위로 세고, 추적 4096개를 넘으면 오래된 것부터 정리 |
| SEC-08 | 일부 수정 | `refs/archive/*` 보호(GIT-07). 로컬 레포 강제 push 자동 아카이브는 제품 결정이 필요해 나중 |
| SEC-09 | 수정 | 토큰은 자기 호스트에만 주는 `HostBoundProvider`. 일괄 목록 next 링크는 같은 origin일 때만 따라감 |
| SEC-10 | 수정 | 500은 "서버 오류 (오류 ID xxxxxxxx)"만, 상세는 로그에 |
| SEC-11 | 수정 | GIT-09와 같음 |
| SEC-12 | 일부 수정(판단 다름) | fetch fsck는 켜지 않음: 미러는 원본을 그대로 보존해야 하고 오래된 레포에 흔한 경고로 받기가 실패한다(GIT-06과 같은 이유). 대신 소스 zip은 위험한 경로(절대, `..`, 역슬래시)를 건너뛰고 항목 100만 개, 8GB 상한 |

### 구조 (ARC-04/05/07)

| ID | 결과 | 내용 |
|---|---|---|
| ARC-04 | 수정 | 시작 정리(`.tmp-*`, `.trash-*`, HEAD·meta 없는 디렉터리, 상태 폴더 tmp). 지워진 레포의 이력 쓰기는 디렉터리를 되살리지 않음. 남은 점: 저장 공간 화면 합계에 손상 레포 크기는 들어가지 않음(작음, 나중) |
| ARC-05 | 일부 수정 | 정리·설정 마이그레이션·접속 기록 로드·내보내기 캐시 정리는 IO로. 설정·자격 증명·레포 meta 읽기는 작은 파일이고 UI가 첫 화면에 바로 써야 해서 시작 시 동기 유지 |
| ARC-07 | 수정 | "파일로 저장"은 SAF 문서에 바로 스트리밍(실패하면 문서 삭제). "공유"만 캐시 파일을 만들며, 예상 크기 뒤 남는 공간이 하한보다 작으면 거부 |

### 리뷰어 판단과 다른 결정

- AND-16: cleartext 유지(http LAN 미러 지원). 토큰 전송 범위로 대신 막음.
- SEC-12: fetch fsck 안 함(보존 우선). zip 경로·크기 상한으로 대신.
- ARC-02의 "새 스키마면 읽기 전용": 읽기 전용 모드는 만들지 않음. 원본 복사본을 남기고 관대하게 읽어 계속 쓴다(다운그레이드 후에도 앱이 동작).
- SEC-03: 리뷰는 "같은 네트워크만"이었고, 루프백도 익명에서 뺐다(adb forward·다른 앱이 폰 안에서 접근하는 경로).
- AND-02 재현: 옛 빌드에서 kill -9 1회로는 wake lock 중복이 재현되지 않았다(경합 창은 코드상 존재). 새 빌드: kill -9 뒤 약 1초 만에 START_STICKY 재시작, 8080 수신, wake lock 1개, Wi-Fi lock 없음 확인.

### 기기 확인 중 발견

- 설정 쓰기가 비동기가 되면서, `am instrument`가 테스트 직후 프로세스를 죽이면 마지막 `setPort(복원)`가 디스크에 안 남았다(폰 설정이 8089/꺼짐으로 남음, 직접 되돌림). 테스트에 `flush()`를 넣었다. 실사용에서 같은 일은 설정 변경 후 수 ms 안에 프로세스가 죽을 때만 생긴다.

### 미검증

- SEC-01은 ngrok 문서로만 확인(유효 토큰 없음). SEC-04는 전역 IPv6가 있는 망(셀룰러 IPv6)에서 403 실측 필요.
- GC를 실제 큰 레포에 돌린 시간·배터리, 첫 받기 `waitingForForeground` 재개는 JVM 테스트로만.

## 11. 2차 코어 작업 (PRD v2 13장)

코드 커밋 `f2af10d`. 명세는 spec.md 8절.

| 항목 | 결과 | 검증 |
|---|---|---|
| R2-01 히스토리 바뀜 저장, 행 사유, 주의 필요 정렬 | `RepoMeta.historyChangedAt`, `Badge.HISTORY_CHANGED`, `markHistorySeen`, `RepoListItem.issue/needsAttention`, `MirrorSource.failingSince`, RECENT 정렬 | JVM `queryFiltersAndSorts`, `forcePushDeleteAndTagMoveArePreserved`(재시작 뒤 유지, 열면 지워짐) |
| R2-02 요약 수치 | `core.overview`, `RepoQuery.overview`, `MirrorSource.lastSuccessCheckAt`, `Settings.serverOffHintDismissed` | JVM `queryFiltersAndSorts`(개수, 마지막 확인, 멈춤 판정) |
| R2-03 보존 기록 목록·API·종류 | `RepoOps.archives`, `GET /api/v1/repos/{name}/archives`, `mobilegit/archives.json` | JVM 위 테스트(강제 push·삭제·태그 이동, 현재 값, 커밋 요약, 이력이 지워져도 종류 유지, 옛 데이터는 null), API 응답 |
| R2-04 변화 종류 | `RefChange.kind`(파생, 예전 이력에도) | JVM 위 테스트 |
| R2-05 AC4, D-8 | `BootReceiver`가 ngrok을 끄고 서버 알림에 한 번 표시. 부팅 자동 시작 기본 켜짐은 CR-6으로 완료 | 컴파일만. 실기기 재부팅·업데이트 브로드캐스트는 확인하지 않음(재부팅 필요) |
| R2-06 인증 강제 제거 | `effectiveAuthEnabled/AnonymousRead`가 저장값 그대로, ngrok 경로도 익명 규칙 적용, `exposedWithoutPassword` | JVM `authRules`(ngrok 커넥터로 push만: 익명 clone 200·익명 push 401 / 읽기·쓰기: 401 / 끄기: push 200 / 루프백은 여전히 401 / 접속 기록 NGROK) |
| R2-07 AC6 | 이미 있음(`pullRefCount`, `hasLfs`) | - |
| R2-15 테더링 | `AddressKind.USB_TETHER`, `AccessRoute.USB_TETHER`, 블루투스는 핫스팟 | JVM `classifiesAddresses`. 실기기 USB 테더링은 확인하지 않음 |

- 실기기(d418f2d1): 7절 절차로 백업한 뒤 `installDebug`(제거 없음). 레포 15개 유지, 서버 8080 수신, 크래시 없음. 이 폰의 레포에는 보존 ref가 없어 A-15 데이터는 JVM 테스트로만 확인했다.
- UI 코드는 `ui/Format.kt`의 enum 분기 3개만 넣었다(새 enum 값으로 빌드가 깨지지 않게). spec 8절 끝에 적었다.
- 14장 항목은 구현하지 않았다.
- JVM 테스트 78개 통과.

## 12. 2차 QA 코어 결함 (`docs/qa/round2/report.md`)

| ID | 원인 | 수정 | 검증 |
|---|---|---|---|
| QA2-01 끊긴 연결이 "원본 없음" | `classify`가 오류 원문 전체의 `"not found"`로 판정했다. 안드로이드 HTTP 클라이언트는 끊긴 연결을 "unexpected end of stream … not found: size=0"으로 보고한다. 또 `"\b404\b"` 정규식은 일반 문자열이라 `\b`가 백스페이스가 되어 맞은 적이 없다. `fail()`은 네트워크 오류에서 이전 GONE을 유지했다 | 예외 타입 우선: `SocketTimeoutException` → TIMEOUT, `SocketException`·`EOFException`·`UnknownHostException`·`SSLException` → NETWORK, `NoRemoteRepositoryException` 또는 JGit의 HTTP 상태 문구(`404 …`) → ORIGIN_GONE, JGit 문구(not authorized, 401/403, `not permitted on`) → AUTH_REQUIRED. 상태 정규식은 raw string이고 `TransportException`의 본문(URI 뒤)에만 적용. `fail()`: 저장 공간 외의 실패는 이전 GONE/AUTH를 UNKNOWN으로 내린다(OK는 유지) | JVM `droppedConnectionIsNetworkNotOriginGone`(닫히는 소켓 → NETWORK·원본 없음 알림 없음, 404 → GONE, 이어서 연결 거부 → NETWORK·배지 FAILED), `ClassifyTest.failureCategories` |
| QA2-04 재시작 안내가 상태 알림에 없음 | 업데이트 직후에는 백그라운드 서버 시작이 막혀 보이는 상태 알림이 "서버가 꺼져 있어요"였고, 서버 알림의 안내는 앱 안 배너를 닫으면(`ackNgrokOff`) 같이 사라졌다 | 알림용 플래그를 배너와 분리(`ngrokOffNotice`). "서버가 꺼져 있어요" 알림 본문에 한 줄 추가, 서버가 켜지면 서버 알림에 그 실행 동안 표시(ngrok을 켜면 사라짐) | 빌드. 실기기 재현(ngrok 켜고 `install -r`)은 설정에 가짜 토큰이 남아 하지 않음, QA 재확인 필요 |

- 실기기: 7절 절차로 백업 후 `installDebug`. 레포 15개 유지, 서버 8080 수신, 크래시 없음. JVM 테스트 81개 통과.

## 13. 2차 코드 리뷰 반영 (`docs/code-review/round2/`)

| ID | 결과 |
|---|---|
| R2SEC-01 / R2GIT-09 | 고침. ngrok 포트 요청을 "ngrok 경로"로 보는 조건을 `ngrokEnabled && 터널 Connected`로 좁힘(`GitServer(tunnelConnected = …)`). 그 밖에는 보통 루프백(익명 없음, 클라이언트 IP = 실제 주소). 회귀 테스트: ngrok 꺼짐·켜짐+미연결에서 ngrok 포트 익명 401, 연결되면 200 |
| R2ARC-07 | 고침. `Authenticator.decide`의 `anonymousOk`는 필수 인자. `effective*` 속성 정리는 나중(호출부가 많고 동작 차이 없음) |
| R2ARC-01 / R2AND-03 | 고침. `RepoMeta.lastArchivedAt`(지우지 않음, 2차의 `historyChangedAt`을 `@JsonNames`로 읽음) + `historySeenAt`, 배지 = `historyUnseen`. 상세의 마지막 보존 시각이 유지됨 |
| R2GIT-08 | 고침. `markHistorySeen(name, seenAt = 지금)`은 그 시각까지의 보존만 본 것으로 처리 |
| R2ARC-15 | 고침. `StorageTest.roundOneAndTwoFilesReload`: 1차 meta(새 필드 없음) + `archivedAs` 이력 → 배지 없음, kind 파생, `lastOkAt` 추정, 이력에서 kind 이전. 2차 `historyChangedAt` → `lastArchivedAt`, seenAt 경계. 앱·웹 `lastActivityAt` 동일성 단언 추가 |
| R2GIT-01 | 고침. 고정 문구는 `startsWith`, 원격 본문·URL 제외, `invalid advertisement`/`Redirection blocked` → NETWORK. 리뷰어의 JGit 7.8.0 메시지를 그대로 고정값으로(`ClassifyTest.remoteTextDoesNotDecide`) |
| R2GIT-02 | 고침. 확인 성공은 원본 문제가 아닌 업데이트 실패 기록을 지우지 않음(변경이 남은 동안). `MirrorSyncTest.checkSuccessKeepsUpdateFailure`(저장 공간 실패 → 확인 성공 → 같은 `failingSince`, 알림 반복 없음 → 성공 시 RECOVERED) |
| R2GIT-03 | 고침. NETWORK/TIMEOUT은 확인된 GONE/AUTH와 그 `lastError` 유지, REMOTE_ERROR만 UNKNOWN. QA2-01 테스트의 마지막 단계를 "연결 거부 뒤에도 원본 없음, 새 알림 없음"으로 바꿈(QA2-01의 오판 자체는 R2GIT-01 분류로 막음) |
| R2GIT-04 | 고침. `Settings.lastPeriodicCheckAt`(PeriodicCheckWorker가 기록·flush) 기준. 기록 전 데이터는 기존 규칙. Wi-Fi 조건 대기와의 구분은 나중(UI 문구와 함께 정할 일) |
| R2GIT-05 | 고침. `RefChangeKind.REVIEW`. PR 정리를 "N개" 한 줄로 묶는 표시는 UI 몫(나중). `refs/notes/*`의 "브랜치" 표기는 나중(드묾) |
| R2GIT-06 | 고침. meta(배지 포함) 먼저 한 번에, 이력·archives.json은 `runCatching`, `countNewCommits` 실패는 0 |
| R2GIT-07 | 고침. 트리 LFS 판정은 100~200바이트만, 디렉터리당 1,000개. `hasLfs`로 건너뛰기는 하지 않음(`.gitattributes` 없이 올린 포인터도 표시하려고). blob(1,024바이트)과의 차이는 spec에 적음 |
| R2GIT-10 | 고침. 이력 스캔은 레포마다 한 번 `archives.json`으로 이전(`archives.migrated`), 쓰기는 레포별 모니터 |
| R2GIT-11 | 고침(위 테스트들). 실기기 OkHttp 문구 재검증은 QA 몫 |
| R2SEC-02 / R2AND-05 | 고침. `Core.start()`에서 부팅 횟수·versionCode가 바뀌었으면 복원 전에 ngrok 끄고 flush. BootReceiver는 서버 시작만. 배너 플래그의 영속화는 나중(배너만 사라질 뿐 ngrok은 꺼진 상태라 안전) |
| R2SEC-03 | 고침. 위험 조합이면 알림 제목 "비밀번호 없이 외부 공개 중", 잠금 화면 공개 버전에서 주소 제거. 한계: 잠금 화면 설정이 "모든 내용 표시"면 안드로이드가 전체 알림을 보여 줌 |
| R2SEC-04 | 정보만 제공(spec 8절). 문구 변경은 UI |
| R2SEC-05 | 고침. archives 최신 500개 + `truncated`(500개만 파싱), LFS 판정 상한(R2GIT-07) |
| R2ARC-02 | 고침. `Settings.authLevel`, `withAuthLevel`, `becomesExposed`. UI가 자기 매핑을 지우고 이것을 쓰면 됨 |
| R2ARC-03 | 고침. 웹 `lastActivityAt` = 앱 `RepoListItem.lastActivityAt`(성공 업데이트 기준) |
| R2ARC-04 | 고침(spec): F-03 `effectiveAuthEnabled`, R2-02 `checksStalled`·`RepoOverview` 시그니처, R2-05 두 플래그, 3절 `/repos`의 `severity`·2차 필드 안내. 트리 순서는 코드와 spec 모두 "디렉터리 먼저"로 이미 같음 |
| R2ARC-05 / R2AND-04 | 코어 쪽 고침. 워커가 저장 위치(이름, 폴더)를 IO에서 한 번 구해 출력 데이터(`K_SAVED_NAME`, `K_SAVED_FOLDER`)로 넘김. UI `savedText` 제거는 UI 몫 |
| R2AND-01 | 고침. 진행/저장 알림 ID를 마스크로 분리(`progressId`, `savedId`) |
| R2AND-13 (앞부분) | 고침. `repoItems`/`overview`가 한 번 계산한 결과를 `shareIn`으로 공유 |

- UI 코드에 최소로 넣은 것: `ui/Format.kt`의 `REVIEW` 분기, `RepoDetailScreen`의 `lastArchivedAt` 한 줄.
- JVM 테스트 전체 통과.


## 14. F-26 웹에서 미러 추가

### WM-00 앱이 닫혀 있을 때 첫 받기 (실기기 확인, 2026-10-25)
- 기기: 테스트폰 d418f2d1 (Android 16, M2012K11AG), 배터리 최적화 허용 목록에 없음.
- 방법: 서버를 켠 뒤 앱 화면을 닫고(뒤로 → 홈) 화면을 껐다. 디버그 빌드 전용 진입점 `DebugAddMirrorReceiver`(`app/src/debug`)로 adb broadcast를 보냈다. 이 진입점은 30초를 기다린 뒤 `MirrorController.add`를 부른다. 브로드캐스트 수신으로 생기는 잠깐의 백그라운드 허용이 끝난 뒤에 부르기 위해서다. 웹 요청과 같은 조건이다.
- 결과 1 (화면 꺼짐, Dozing): `add` 뒤 0.15초 안에 첫 받기 시작, 1.4초 만에 끝남. `waitingForForeground = false`. 시스템 로그: `Background started FGS: Allowed … uidState: FGS … code:PROC_STATE_FGS`. 서버 포그라운드 서비스가 돌고 있어서 WorkManager의 포그라운드 전환이 허용된다.
- 결과 2 (`dumpsys deviceidle force-idle`로 깊은 Doze): 같음. 0.1초 안에 시작, 1.2초에 끝남.
- 결론: **미뤄지지 않는다.** 서버가 켜져 있는 한 웹 등록의 첫 받기는 바로 시작된다. WM-05 AC3의 "앱을 열면 시작" 안내는 예외 경로(서버 없이 다른 경로로 등록된 경우, 제조사 정책 등)로만 남는다. 코어는 `waitingForForeground`를 레포 요약에 그대로 내준다.
- 확인용으로 만든 레포 2개(`wm00-test`, `wm00-doze`)는 확인 뒤 지웠다. 레포 15개 그대로.

### WM-01~03, WM-05 코어 (구현)
- `GitServer`에 `MirrorController`와 `coordinator.running`을 주입했다. `POST /api/v1/mirrors/preview`, `POST /api/v1/mirrors`(spec 3.1절).
- 거부 순서: ngrok 포트 → CSRF 3중 → push 수준 인증 → 본문 검사 → 앱과 같은 등록 흐름. 경로별 오류 코드는 spec 3.1절 표에 있다.
- 토큰 규칙(D-14): 비밀번호 인증이면 저장된 토큰을 쓰고, 익명이면 쓰지 않는다. https만 받는다(D-16). 테스트용 `httpsOnly = false` 주입만 http를 허용한다.
- 원본 확인: 20초, 동시 2개, 넘치면 503 `BUSY`.
- 접속 기록: `MIRROR_ADD`와 `AccessLogEntry.code`.
- 레포 요약: `syncing`, `waiting`. 생성 경로: `CreatedBy.WEB`.
- 테스트 `WebMirrorTest`(6개):
  - 앱 흐름 공유(미리보기, 등록, 첫 받기 예약, 중복)
  - 거부 코드(HTTPS_ONLY, MULTIPLE_URLS, TOKEN_NOT_ACCEPTED, UNSUPPORTED_URL, BAD_REQUEST, INVALID_NAME, NAME_TAKEN, STORAGE_LOW, ORIGIN_NOT_FOUND)
  - CSRF 5종(form, text/plain, 다른 Origin, Sec-Fetch-Site cross-site, 다른 Host)
  - 인증 세 단계와 ngrok 포트 거부, 토큰 규칙(인증 → REACHABLE, 익명 → TOKEN_REQUIRED)
  - 20초 제한과 BUSY(테스트에서는 1.5초)
  - 다른 웹 쓰기 경로 없음
- 미검증:
  - 실제 https 원본이 http로 리디렉션하는 경우. JGit이 `Redirection blocked`로 막는다고 보고 그 문구를 `HTTPS_ONLY`로 바꾼다. 그 처리는 테스트하지 않았다. QA-W8에서 확인한다.
  - 익명("끄기")으로 공개 레포를 등록한 뒤, 이후 업데이트는 앱 규칙대로 그 호스트의 토큰을 쓸 수 있다. 등록 시점에 토큰 없이 공개임을 확인하므로, 비공개 레포를 익명으로 끌어오는 일은 막힌다.


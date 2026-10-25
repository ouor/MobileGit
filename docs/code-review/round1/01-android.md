# 코드 리뷰 1차 — 안드로이드 플랫폼 / Kotlin

- 리뷰어 관점: 포그라운드 서비스, WorkManager, 생명주기, 코루틴, Compose, Keystore
- 범위: `app/src/main/java/com/ouor/mobilegit/{app,core,ui}`, `AndroidManifest.xml`, `res/`, `app/build.gradle.kts`, `libs.versions.toml`, 테스트
- 코드는 수정하지 않았습니다. 줄 번호는 리뷰 시점 기준입니다.

## 요약

| 심각도 | 개수 | ID |
|---|---|---|
| 치명 | 0 | - |
| 높음 | 4 | AND-01 ~ AND-04 |
| 중간 | 8 | AND-05 ~ AND-12 |
| 낮음 | 8 | AND-13 ~ AND-20 |

가장 중요한 문제 세 가지:
1. **서버 서비스의 시작/중지 상태 머신이 직렬화되어 있지 않습니다** (AND-01, AND-02). 프로세스 재시작 같은 흔한 상황에서 wake lock과 Wi-Fi lock이 새고, FGS가 아닌 상태로 서버가 돌며, UI는 "실행 중"이라고 표시합니다.
2. **JSON을 읽다 실패하면 조용히 초기화하고 덮어씁니다** (AND-03). 스키마가 바뀌거나 파일이 손상되면 미러가 로컬 레포로 바뀌어 원본 URL을 잃고, 비밀번호와 토큰도 사라집니다.
3. **WorkManager가 작업을 중단하거나 취소해도 동기화 실패로 기록됩니다** (AND-04). 백그라운드에서는 10분 제한 때문에 큰 레포의 첫 받기가 영원히 끝나지 않고, 실패 알림과 백오프가 잘못 쌓입니다.

---

## 높음

### AND-01 [높음] `startForeground` 실패 뒤에도 서버를 띄움 → FGS 없이 서버가 돌고 UI는 "실행 중"
- 위치: `app/Server.kt:185-192`, `app/Server.kt:196-204`, `app/Server.kt:132`
- 문제: `startInForeground()`가 예외(Android 12+의 `ForegroundServiceStartNotAllowedException` 등)를 잡으면 `blockedInBackground()`와 `stopSelf()`를 부르지만 **return하지 않습니다**. `onStartCommand`는 그대로 186행으로 내려가 IO 코루틴에서 `bringUp()`, `acquireLocks()`, `observe()`를 실행합니다.
- 실패 상황: START_STICKY 재시작이나 앱 업데이트 직후처럼 백그라운드에서 서비스가 다시 뜨면 `startForeground`가 거부됩니다. 그 뒤 순서는 다음과 같습니다.
  1. `blockedInBackground`가 상태를 `Failed(BACKGROUND_START_BLOCKED)`로 바꾸고 "서버가 꺼져 있어요" 알림을 띄웁니다.
  2. `stopSelf()` 때문에 곧 `onDestroy`가 불립니다. 그 시점에는 `bringUp`이 아직 끝나지 않아 `isRunning`이 false이므로 `bringDown`을 건너뛰고, `scope.cancel()`로는 이미 블로킹 중인 `gitServer.start`를 멈추지 못합니다.
  3. `bringUp`이 끝나면 상태가 `Running`이 되고 132행에서 "꺼져 있어요" 알림을 **지웁니다**. 서비스는 이미 죽었지만 wake lock과 Wi-Fi lock은 잡힌 채로 남습니다.
  4. 결과적으로 사용자에게는 서버가 켜져 있는 것으로 보이지만, 프로세스는 cached 상태(Android 14+에서는 동결 대상)여서 접속되지 않습니다. 상태가 `Running`이라 `resumeIfBlocked()`(QA-02 복구 경로)도 다시 동작하지 않습니다.
- 권장: `startInForeground`가 Boolean을 반환하게 하고, 실패하면 `onStartCommand`에서 바로 `return START_NOT_STICKY` 하세요. 서버 기동은 FGS 진입에 성공한 경우에만 하도록 보장하세요.

### AND-02 [높음] 서비스 시작/중지 명령이 직렬화되지 않음 → 중복 기동, wake lock 누수, 고아 서버
- 위치: `app/Server.kt:179-192` (onStartCommand), `app/Server.kt:244-248` (acquireLocks), `app/Core.kt:117` (restoreIfEnabled), `app/Core.kt:151` (BootReceiver), `app/Server.kt:76-87`
- 문제: 중복 기동을 막는 장치는 `if (core.gitServer.isRunning) return`뿐인데, `bringUp`은 비동기(IO)로 돌기 때문에 기동이 진행 중일 때 들어온 두 번째 start 명령은 이 검사를 통과합니다. `GitServer.start`는 `@Synchronized`이면서 멱등이라 두 번째 호출도 `Started`를 반환하고, 그 결과 **`acquireLocks()`와 `observe()`가 두 번 실행됩니다**. `wake`와 `wifi` 필드가 덮어써지면서 처음 잡은 lock은 해제할 참조를 잃습니다.
- 실패 상황 (흔한 경로):
  - **LMK로 프로세스가 죽은 뒤 재시작**: `Application.onCreate` → `core.start()` → `restoreIfEnabled()` → `startForegroundService(intent)`가 실행되고, 동시에 시스템의 START_STICKY 재시작(`intent == null`)도 들어옵니다. 같은 부팅 안이므로 둘 다 통과해 거의 매번 중복 기동됩니다.
  - `MY_PACKAGE_REPLACED`: `restoreIfEnabled`는 부팅 횟수가 같아서 통과하고, `BootReceiver.start()`도 실행됩니다.
  - 결과: 사용자가 서버를 꺼도 첫 번째 PARTIAL_WAKE_LOCK은 GC finalizer가 돌 때까지 잡혀 있어 CPU가 깨어 있습니다. Wi-Fi lock도 마찬가지로 남습니다. 설정 collector가 두 개라서 ngrok 터널도 두 번 재시작합니다.
  - **켜는 도중 끄기**: `Starting` 중에 `stop()`을 누르면 ACTION_STOP의 `bringDown`과 진행 중이던 `bringUp`이 경쟁합니다. `bringUp`이 나중에 끝나면 서비스는 `stopSelf`로 사라졌는데 서버는 `Running`으로 남습니다. `stopSelf()`를 startId 없이 부르기 때문에, 직후에 들어온 start 명령까지 함께 죽일 수도 있습니다.
- 권장: 서비스 안에 단일 상태 머신을 두세요. 예를 들어 `Mutex`나 단일 스레드 `Channel` 액터로 start/stop/restart를 직렬화하고, "이미 Starting/Running이면 무시"를 `isRunning`이 아니라 컨트롤러 상태로 판단합니다. `acquireLocks`는 이미 잡혀 있으면 아무것도 하지 않게 하고, `observe` Job은 하나만 유지하세요(재진입 시 기존 Job 취소). 중지는 `stopSelf(startId)`로 하세요. 이 경로를 검증하는 기기 테스트(연속 start 2회, start 직후 stop)를 추가하세요.

### AND-03 [높음] JSON 파싱 실패를 "파일 없음"으로 취급해 초기화하고 덮어씀 → 설정, 토큰, 미러 메타데이터 유실
- 위치: `core/Util.kt:15`, `core/Settings.kt:55-60`, `core/Credentials.kt:15`, `core/RepoStore.kt:52-59`
- 문제: `JsonFile.read`는 디코딩 예외를 `runCatching`으로 삼키고 `null`을 반환합니다. 호출하는 쪽은 이를 "처음 실행"으로 간주합니다.
  - `SettingsStore.load()`: **새 랜덤 비밀번호로 설정 전체를 새로 만들어 즉시 저장**합니다. 포트, 인증 설정, ngrok 토큰, `onboardingCompleted`가 모두 사라지고, 모든 클라이언트의 git 인증이 깨집니다.
  - `CredentialStore`: 빈 목록으로 시작하고, 사용자가 토큰을 하나 추가하는 순간 원래 파일을 덮어써 기존 토큰을 영구히 잃습니다.
  - `RepoStore.load()`: `meta.json`을 읽지 못했는데 `HEAD`가 있으면 **새 UUID를 붙인 LOCAL 레포로 "입양"하고 meta를 덮어씁니다**. 미러의 원본 URL, 자격 증명 연결, 히스토리 상태가 meta에만 있으므로 미러가 조용히 로컬 레포로 바뀌고 동기화가 멈춥니다.
- 실패 상황: 쓰기는 원자적이라 손상 가능성은 낮습니다. 하지만 **앱 업데이트에서 스키마가 바뀌는 경우**가 현실적인 위험입니다. enum 값 이름 변경이나 삭제, 필드 타입 변경은 `ignoreUnknownKeys`로 막을 수 없습니다. 예를 들어 `ErrorCategory`나 `WaitingReason`에서 값 하나를 바꾸면 업데이트 직후 모든 미러가 LOCAL이 되고 설정이 리셋됩니다. 다운그레이드 설치에서도 같은 일이 생깁니다.
- 권장: "파일 없음"(null)과 "파싱 실패"(예외)를 구분하세요. 파싱에 실패하면 원본을 `*.corrupt-<ts>`로 보존하고, 덮어쓰지 않은 채 오류 상태(읽기 전용, UI 경고)로 진입하세요. 직렬화 대상 enum에는 `@SerialName`으로 이름을 고정하고, 알 수 없는 값을 기본값으로 받는 커스텀 serializer나 `coerceInputValues = true`를 쓰세요. 손상된 meta를 adopt하는 경로는 meta 파일이 **없을 때만** 타게 하세요. "구버전 JSON 디코딩" 회귀 테스트를 추가하세요.

### AND-04 [높음] 워커 중단이나 취소가 "동기화 실패"로 기록됨 → 큰 레포의 첫 받기가 백그라운드에서 끝나지 않음
- 위치: `core/Sync.kt:175-201`, `core/MirrorEngine.kt:177`, `core/MirrorEngine.kt:184`, `app/Work.kt:111-112`
- 문제: 코루틴이 취소되면 `isCancelled = { !ctx.isActive }` → JGit Monitor가 취소 → JGit이 `TransportException`을 던짐 → `classify()`가 `REMOTE_ERROR`로 분류 → `SyncFailure` → `fail()` 순서로 진행됩니다. 결과적으로 실패 이벤트, `consecutiveFailures++`, `nextRetryAt` 백오프, "동기화 실패" 알림, 일괄 작업 항목 FAILED가 **모두 취소 때문에 생깁니다**.
- 실패 상황:
  - `SyncWorker`는 `setForeground` 실패를 `runCatching`으로 무시합니다. Android 12+에서 백그라운드로 시작된 워커(자동 업데이트, 일괄 등록 뒤 앱을 닫은 경우)는 FGS가 되지 못하고 **JobScheduler의 10분 실행 제한**을 받습니다. 리눅스 커널처럼 큰 레포의 첫 fetch는 10분마다 끊기고, 받던 팩은 버려지며, 실패 알림과 백오프만 쌓이고 영원히 완료되지 않습니다.
  - `updateWifiOnly` 조건에서 Wi-Fi가 끊겨 제약이 풀리거나, 수동 "지금 업데이트"의 `REPLACE`(`Work.kt:51`)가 실행 중인 작업을 대체하거나, 레포를 삭제할 때(`Ops.delete` → `scheduler.cancel`)도 모두 실패로 기록됩니다. 삭제 중인 레포에 "동기화 실패" 알림이 뜨기도 합니다.
- 권장: `update()`/`check()`에서 `!ctx.isActive`이거나 원인이 취소이면 `fail()`을 부르지 말고 `CancellationException`을 다시 던지세요(이벤트를 남기더라도 `CANCELLED`로 구분). 첫 받기와 큰 fetch는 사용자가 앱을 보고 있을 때 시작하거나, 실패 시 `setExpedited`나 UI 안내로 보완하세요. `setForeground` 실패 여부를 기록해 "백그라운드 제한으로 지연" 상태를 보여 주는 것도 방법입니다.

---

## 중간

### AND-05 [중간] 동시성 제한(세마포어 2)을 워커 안에서 기다림 → FGS 시간과 잡 슬롯 낭비, 알림 폭주
- 위치: `app/Work.kt:111`, `core/Sync.kt:165-168`
- 문제: `SyncWorker`는 먼저 `setForeground()`(진행 알림 + dataSync FGS)를 잡은 뒤 `coordinator.update()` 안의 `slots.withPermit`에서 대기합니다. 미러 100개를 일괄 등록하면 WorkManager가 동시에 실행하는 수십 개 워커가 각자 FGS 알림을 띄운 채 대기합니다.
- 왜 문제인가: Android 15(targetSdk 35+)에서는 **dataSync FGS가 24시간에 6시간**으로 제한되는데, 대기 시간도 이 할당량에 포함됩니다. FGS가 거부된 경우에는 대기 시간이 10분 잡 제한에 포함되어 AND-04로 이어집니다. 알림 영역에 "받는 중" 알림이 수십 개 쌓이기도 합니다.
- 권장: 동시성은 WorkManager 수준에서 제한하세요(레포별 작업을 2개 큐 체인으로 나누거나, `Configuration`의 executor로 제한). 아니면 permit을 얻은 **뒤에** `setForeground`를 부르세요.

### AND-06 [중간] 서버가 켜져 있는 동안 3초마다 네트워크 인터페이스를 폴링하고 PARTIAL wake lock까지 잡음 → 배터리 소모
- 위치: `app/Platform.kt:101-107`, `app/Server.kt:218`
- 문제: `NetworkMonitor.addresses`는 `WhileSubscribed`이지만 `ServerService.observe()`가 알림 갱신을 위해 서버가 켜져 있는 내내 구독합니다. 그래서 `NetworkInterface.getNetworkInterfaces()`와 `ConnectivityManager.allNetworks` 조회가 3초마다 무기한 실행되고, wake lock 때문에 화면이 꺼져도 멈추지 않습니다.
- 권장: `ConnectivityManager.registerNetworkCallback`(Wi-Fi, 핫스팟, VPN 변화)을 주 트리거로 쓰고, 폴링은 UI가 보일 때만 하거나 주기를 길게(예: 30~60초) 하세요. 알림용 구독과 UI용 구독의 주기를 분리하세요. 서버 상주를 위한 wake lock 자체도 필요한지 다시 검토할 만합니다. FGS 프로세스는 Doze 중에도 네트워크가 허용되므로, 요청 처리 중에만 짧게 잡는 방식을 고려할 수 있습니다.

### AND-07 [중간] `onDestroy`에서 메인 스레드로 서버를 정지 (최대 11초 블로킹)
- 위치: `app/Server.kt:256-258` → `core/GitServer.kt:150-153` (`grace 1s + timeout 10s`)
- 문제: ACTION_STOP 경로는 IO에서 처리하지만, 시스템이 서비스를 정리하거나 AND-01/02 경로를 거쳐 `onDestroy`에 도달하면 `bringDown()`이 메인 스레드에서 Ktor `stop(1000, 10000)`과 접근 로그 fsync를 실행합니다. 전송 중인 clone이 있으면 수 초 동안 UI가 멈추고 ANR 위험이 있습니다.
- 권장: `onDestroy`에서는 정지를 `Core.scope`(서비스 scope가 아닌 애플리케이션 scope)의 IO에 맡기거나, 정지가 끝난 뒤에만 `stopSelf`가 불리도록 흐름을 한 방향으로 정리하세요.

### AND-08 [중간] 메인 스레드 디스크 I/O와 Keystore 호출
- 위치: `app/Core.kt:56-77,121-128` (Application.onCreate에서 Core 생성), `core/Settings.kt:64-69`, `ui/screens/SettingsScreen.kt:110-230`, `ui/screens/ManageScreens.kt:162-163`, `ui/screens/MainScreen.kt:187`
- 문제:
  - 모든 프로세스 시작(UI, BootReceiver, 워커) 때 `Application.onCreate`가 메인 스레드에서 다음을 동기로 수행합니다: `RepoStore.load()`(레포 수만큼 meta.json 읽기), 접근 로그 2,000건 JSON 파싱, 설정과 자격 증명 로드, 첫 실행 시 Keystore 키 생성. 레포 500개(PRD 목표 규모)에서는 콜드 스타트가 눈에 띄게 늘고, `BootReceiver`의 10초 제한도 잠식합니다.
  - Compose 클릭 핸들러가 `settings.update {}`를 그대로 부릅니다(`fsync` 포함). `credentials.add`는 Keystore 암호화와 fsync를 메인에서 실행합니다. 백그라운드 clone이 디스크를 점유하고 있으면 fsync가 수백 ms 걸려 프레임 드롭이 생기고, 최악에는 ANR로 이어집니다.
- 권장: Core 초기화는 지연하거나 IO로 옮기세요. 적어도 RepoStore와 AccessLog 로드는 백그라운드에서 하고, 그동안 상태를 `null`이나 `Loading`으로 노출하면 됩니다. `SettingsStore.update`는 메모리 상태를 먼저 갱신하고 저장은 단일 IO 액터로 직렬화하는 구조가 단순합니다. 디버그 빌드에 StrictMode(disk read/write)를 켜서 회귀를 잡으세요.

### AND-09 [중간] `ExportRunner` 상태를 `remember`로만 보관 → 회전이나 프로세스 종료 시 내보내기 결과와 SAF 선택 유실
- 위치: `ui/Export.kt:32`, `ui/Export.kt:54`, `ui/Export.kt:62`
- 문제: `workId`와 `pending`(SAF 선택 대기 중인 kind, repos, ref)이 `remember` 안에만 있습니다.
  - 내보내기 중에 화면을 회전하거나 다크 모드를 전환하면 `workId`를 잃어 성공 시 공유 시트가 뜨지 않고, 결과 snackbar도 나오지 않습니다. 공유 모드에서는 결과물이 아무에게도 전달되지 않습니다.
  - 시스템 파일 선택기가 떠 있는 동안 메모리 부족으로 프로세스가 종료되면 `pending`이 null이 되어, 사용자가 위치를 골라도 아무 일도 일어나지 않습니다.
- 권장: `workId`와 `pending`을 `rememberSaveable`(UUID는 문자열로)이나 `SavedStateHandle`에 보관하세요. 또는 `getWorkInfosByTagFlow(TAG_EXPORT)`로 진행 중인 export를 다시 찾게 하세요.

### AND-10 [중간] 전체 백업에서 레포 목록을 `Data`에 담음 → 10KB 제한 초과 시 메인 스레드 크래시
- 위치: `app/Work.kt:83-86`, `ui/screens/StorageScreens.kt:179-181`
- 문제: WorkManager `Data`는 직렬화 크기가 10,240바이트로 제한됩니다(`Data.MAX_DATA_BYTES`). "전체 백업"에서 레포를 많이 선택하면(예: 이름 평균 25자 × 400개) `build()`가 `IllegalStateException`을 던지고, 클릭 핸들러에서 앱이 크래시합니다.
- 권장: 선택 목록은 파일(`cacheDir/export-<id>.json`)이나 BulkJobStore 같은 저장소에 쓰고 `Data`에는 ID만 넘기세요. "전부"이면 빈 배열을 넘기세요(워커는 이미 빈 배열을 전체로 해석합니다).

### AND-11 [중간] `update()`에서 `SyncFailure`가 아닌 예외가 새면 상태가 "진행 중"에 머묾
- 위치: `core/Sync.kt:165-204`, `core/Credentials.kt:53-55`
- 문제: `fail()`은 `SyncFailure`만 처리합니다. 다음 예외는 `engine.wrap` 밖에서 나므로 `SyncFailure`가 아닌 채로 워커까지 올라갑니다.
  - `credentials.provider(cred)`의 Keystore 복호화 실패 (`AEADBadTagException`, `KeyStoreException`)
  - `store.open()`의 `RepositoryNotFoundException`
  - `updateMirror`의 `RepoException`
- 결과: 일괄 항목은 166행에서 `RUNNING`으로 바뀐 뒤 그대로 남고(A-05 진행 화면이 끝나지 않음), 히스토리와 알림도 없습니다. 워커는 FAILED로 끝나 사용자에게 원인이 보이지 않습니다.
- 권장: `withRepoLock` 블록 안에서 `CancellationException`을 제외한 모든 예외를 `MirrorEngine.classify`로 감싸 `fail()`로 보내세요. Keystore 복호화 실패는 별도 범주(예: "토큰을 다시 입력해 주세요", AUTH_REQUIRED)로 매핑하세요.

### AND-12 [중간] 내보내기 결과 파일이 캐시에 무기한 남음
- 위치: `app/Work.kt:151`, `app/Work.kt:176`, `ui/Export.kt:86-91`
- 문제: 공유 모드에서는 `cacheDir/exports/`에 만든 번들, zip, 백업을 공유 뒤에도 지우지 않습니다. 전체 백업은 저장된 레포 전체 크기만큼 커질 수 있는데 날짜 스탬프가 붙은 파일명이라 계속 쌓입니다. 시스템이 캐시를 정리하기 전까지 저장 공간 하한(F-17) 계산을 압박하고, 그 결과 자동 업데이트가 "저장 공간 부족"으로 대기하게 됩니다.
- 권장: 새 export를 시작할 때나 앱이 시작될 때 일정 시간(예: 24시간)이 지난 `exports/*`를 지우세요. 공유는 수신 앱이 늦게 읽을 수 있으므로 바로 지우지 말고 TTL 방식으로 정리하는 편이 안전합니다.

---

## 낮음

### AND-13 [낮음] Keystore 처리: 실패가 조용하고 호출마다 KeyStore를 다시 로드함
- 위치: `app/Platform.kt:48-67`, `core/Settings.kt:93`, `app/Server.kt:213`
- `ngrokAuthtoken()`은 복호화에 실패하면 null을 반환하고, `observe()`는 이 경우 그냥 `tunnel.stop()`을 부릅니다. 그래서 ngrok 스위치는 켜져 있는데 터널은 Off이고 이유는 표시되지 않습니다. `TokenRejected` 같은 상태로 알려야 합니다.
- `key()`는 매 encrypt/decrypt마다 `KeyStore.getInstance().load()`를 실행합니다. 실패 시 비밀 마스킹(`credentials.secrets()`)이 토큰 수만큼 이를 반복합니다. `SecretKey`를 캐시하세요. 키 생성은 `@Synchronized`로 감싸, 동시에 처음 호출될 때 같은 alias를 두 번 생성해 덮어쓰는 일을 막으세요.

### AND-14 [낮음] `WIFI_MODE_FULL_HIGH_PERF`는 API 34+에서 동작하지 않음
- 위치: `app/Server.kt:244-248`
- 이 모드는 deprecated이고 Android 14+에서는 효과가 없습니다. `WIFI_MODE_FULL_LOW_LATENCY`는 앱이 포그라운드이고 화면이 켜져 있을 때만 동작합니다. 효과가 없는 lock을 잡을 바에는, 버전별로 무엇을 기대하는지 주석으로 적거나 해당 버전에서는 제거하세요.

### AND-15 [낮음] `TunnelManager`의 공유 `session` 필드를 이전 Job의 `finally`가 닫을 수 있음
- 위치: `core/Tunnel.kt:68-78`
- `stop()` → `start()`가 빠르게 이어지면, 이전 Job의 `finally { session?.close(); session = null }`이 새 Job이 설정한 세션을 닫을 수 있습니다. 실제로는 연결 시간 때문에 드물게만 일어납니다. 이전 Job이 닫는 대상은 필드가 아니라 자신의 지역 변수 `s`로 바꾸세요.

### AND-16 [낮음] 앱 전체에 `usesCleartextTraffic="true"`, `largeHeap="true"`
- 위치: `AndroidManifest.xml:24-25`
- 들어오는 HTTP 서버는 cleartext 정책의 영향을 받지 않습니다. 원격 URL은 https만 받으므로(`RemoteUrls.normalize`) 나가는 cleartext 허용은 필요하지 않고, 토큰을 실은 요청이 http로 나갈 여지만 남깁니다. `network_security_config`에서 기본값을 false로 두고 필요한 경우(로컬 테스트)만 허용하세요. `largeHeap`은 JGit 캐시를 이미 줄였다면 다시 검토할 만합니다.

### AND-17 [낮음] 생명주기를 따르지 않는 Compose 루프와 메인 스레드 계산
- 위치: `ui/screens/MainScreen.kt:136-138`, `ui/screens/QrScreen.kt:76`
- `produceState`의 `while(true)` StatFs 폴링은 액티비티가 백그라운드(STOPPED)에 있어도 composition이 살아 있는 동안 계속 돕니다. `LifecycleResumeEffect`/`repeatOnLifecycle` 안으로 옮기세요.
- QR 768px 인코딩과 `IntArray` 589k 생성은 `remember` 안에서, 즉 메인 스레드에서 실행됩니다. 주소 탭을 바꿀 때 프레임 드롭이 생길 수 있으니 `produceState` + `Dispatchers.Default`로 옮기세요.

### AND-18 [낮음] 문자열과 알림 리소스 관리
- 위치: `ui/Strings.kt:7`, `app/Notifications.kt:38-42,63-64,84-89`, `app/Server.kt` (알림 문구)
- UI 문자열은 `strings.xml`로 옮겼지만 알림 채널 이름, 알림 본문, 서버 상태 메시지는 한국어로 하드코딩되어 있습니다. 채널 이름은 사용자가 시스템 설정에서 보는 값이므로 리소스로 옮겨야 locale을 바꿀 때 `createNotificationChannels`로 갱신됩니다.
- `Strings.context`는 전역 `lateinit`이라, UI가 아닌 경로에서 `str()`을 호출하면 크래시합니다. 지금은 UI에서만 쓰지만 이 규칙을 강제할 장치가 없습니다. 앱별 언어 설정(`LocaleManager`)도 반영하지 못합니다.
- 알림 ID를 `hashCode()`로 만들기 때문에 `ID_SERVER(1)`/`ID_SERVER_BLOCKED(2)`나 진행 알림(`name.hashCode()`)과 충돌할 수 있고, `FLAG_UPDATE_CURRENT` PendingIntent의 requestCode도 같은 값을 공유합니다. 확률은 낮지만 충돌하면 서버 FGS 알림이 다른 알림으로 바뀝니다. 진행 알림과 이벤트 알림에 tag를 붙여 ID 공간을 나누세요(`notify(tag, id, n)`).

### AND-19 [낮음] 공유 인텐트 텍스트를 네비게이션 route와 saved state에 그대로 넣음
- 위치: `app/MainActivity.kt:35-37`, `ui/App.kt:63`, `ui/screens/AddScreens.kt:144`
- 다른 앱이 아주 긴 텍스트(수백 KB)를 공유하면 route 인자와 `rememberSaveable`에 그대로 들어갑니다. 앱이 백그라운드로 갈 때 `TransactionTooLargeException`이 날 수 있으니, 받은 텍스트는 `extractCandidates` 후 길이를 제한(예: 64KB)하세요.

### AND-20 [낮음] 테스트 공백
- 위치: `app/src/test`, `app/src/androidTest/.../OnDeviceTest.kt:114-150`
- 위 높음 항목들(서비스 시작/중지 경쟁, 워커 취소 시맨틱, JSON 디코딩 실패 복구)에 대한 테스트가 없습니다. `androidx.work:work-testing`(`TestListenableWorkerBuilder`, `WorkManagerTestInitHelper`)이 의존성에 없어 워커를 테스트할 수 없습니다.
- `OnDeviceTest`가 **설치된 실제 앱의** `Core`(포트, `serverEnabled`)를 바꿉니다. 테스트가 중간에 실패하면 사용자 설정이 테스트 값으로 남습니다. 테스트 전용 `Core`나 임시 디렉터리를 쓰세요.

---

## 잘 된 점

- **FGS 선언이 정확합니다**: `specialUse`와 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, WorkManager `SystemForegroundService`에 `dataSync` merge, `ServiceCompat.startForeground`에 타입 지정까지 Android 14+ 요구 사항을 빠짐없이 지켰습니다.
- **재부팅과 프로세스 재시작을 `BOOT_COUNT`로 구분**(F-01 AC7 / F-22 AC2)한 설계가 깔끔하고, 백그라운드 FGS 시작이 거부될 때의 복구 경로(QA-02)를 따로 둔 것도 좋습니다. AND-01만 고치면 의도대로 동작합니다.
- **원자적 JSON 쓰기**(tmp + fsync + ATOMIC_MOVE)와 **원자적 BatchRefUpdate로 미러를 갱신**해, 강제 종료되어도 refs가 반쯤 갱신된 상태로 남지 않습니다.
- **Keystore 사용이 적절합니다**: AES-256-GCM, 키는 하드웨어 밖으로 나가지 않고, 사용자 인증을 요구하지 않습니다(잠금 상태에서도 서버가 동작해야 하므로 올바른 선택). 백업과 기기 이전에서도 전부 제외했습니다.
- **Compose**: 모든 Flow를 `collectAsStateWithLifecycle`로 수집하고, `LazyColumn`에 안정적인 `key`를 주며, 무거운 작업은 `withContext(IO)`로 넘기고, 히스토리는 버전 카운터 기반 `eventsFlow`로 필요할 때만 다시 읽습니다.
- PendingIntent는 모두 `FLAG_IMMUTABLE`과 명시적 인텐트를 쓰고, FileProvider는 `exported=false`에 경로 범위를 최소로 잡았습니다.
- CIO가 바인드에 실패할 때 생기는 미처리 코루틴 예외를 `CoroutineExceptionHandler`로 막은 것(QA-01)은 놓치기 쉬운 부분인데 잘 처리했고, 기기 테스트로 검증까지 했습니다.

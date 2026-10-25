# 코드 리뷰 1차 — 05 아키텍처 (데이터 저장, 디렉터리, 파일 구조)

- 리뷰어 관점: 모바일 앱 + 임베디드 서버 아키텍트
- 기준: `main` @ `0188ffc`
- 범위: 데이터 저장 설계, 폰 저장소 레이아웃, 패키지/모듈 경계, 파일 구조. 코드는 수정하지 않았다.

## 요약

| 심각도 | 개수 | ID |
|---|---|---|
| 치명 | 0 | |
| 높음 | 2 | ARC-01, ARC-02 |
| 중간 | 8 | ARC-03 ~ ARC-10 |
| 낮음 | 8 | ARC-11 ~ ARC-18 |

가장 먼저 고칠 것: **읽기 실패를 "파일 없음"으로 취급해 덮어쓰는 문제(ARC-01)** 와 **스키마 버전/열거형 내성 부재(ARC-02)**. 두 건이 합쳐지면, 다음에 enum 하나를 바꾸는 순간 모든 미러의 원본 URL·자격 증명 연결이 조용히 지워진다. 다음 스키마 변경 전에 반드시 막아야 한다.

## 현재 구조

### 코드 디렉터리

```
app/src/main/java/com/ouor/mobilegit/
├─ core/   (Android import 없음. JGit, Ktor, kotlinx.serialization만 사용)
│  ├─ Models.kt        PRD 6장 데이터 모델 (@Serializable)
│  ├─ Util.kt          JsonFile(원자적 쓰기), SecretCipher, Clock, Redact
│  ├─ RepoStore.kt     레포 레지스트리 + 메타/이력 저장 + 레포 락
│  ├─ Settings.kt / Credentials.kt / AccessLog.kt   설정, 토큰, 접속 기록 저장소
│  ├─ Bulk.kt          RemoteCatalog(HTTP) + BulkJobStore(저장)
│  ├─ Sync.kt          SyncCoordinator + MirrorController + RepoQuery + Badges + NotificationRules
│  ├─ MirrorEngine.kt  JGit fetch/보존 ref
│  ├─ GitServer.kt     Ktor: smart HTTP + JSON API + 정적 파일
│  ├─ Ops.kt           RepoOps(생성/분리/삭제) + Exporter + 백업 manifest DTO
│  └─ RepoBrowser.kt, RepoNames.kt, Auth.kt, Tunnel.kt, JGitEnv.kt
├─ app/    Core(객체 그래프), Platform(Keystore, StatFs, 네트워크), Server(서비스), Work(WorkManager), Notifications
└─ ui/     App.kt, Strings.kt, Format.kt, Export.kt, components/, screens/(8개, 최대 570줄), theme/
web/ (React + Vite) → 빌드 결과가 app/src/main/assets/web/ 로 직접 출력되어 커밋됨
```

의존 방향: `ui → app → core`, `ui → core`. `core`는 `app`/`ui`/`android.*`를 import하지 않는다 (확인함). 순환 없음.

### 폰 저장소 레이아웃

```
filesDir/
├─ repos/
│  ├─ <name>.git/                 bare 레포 (JGit)
│  │  └─ mobilegit/
│  │     ├─ meta.json             RepoMeta (+ MirrorSource)
│  │     ├─ events.json           SyncEvent 최근 200건 (매번 전체 재작성)
│  │     └─ pushes.json           PushEvent 최근 200건 (매번 전체 재작성)
│  └─ .tmp-<uuid>/                fork COPY 작업 중 임시 디렉터리
└─ state/
   ├─ settings.json               Settings (passwordHash, *Enc 는 Keystore AES-GCM)
   ├─ credentials.json            RemoteCredential[] (토큰 암호화)
   ├─ accesslog.json              AccessLogEntry 최근 2,000건 (5초마다 전체 재작성)
   └─ bulk.json                   BulkJob 최근 20개
cacheDir/exports/                 내보내기 결과(.part → 최종), 공유용 파일은 남음
```

모든 JSON은 `JsonFile.write`(tmp + fsync + ATOMIC_MOVE)로 쓰고, 메모리 `StateFlow`가 정본이다. 클라우드 백업·기기 이전은 `allowBackup=false` + `data_extraction_rules`로 차단되어 있다.

## 잘 된 점

- **원자적 쓰기**: `JsonFile.write`가 tmp + fsync + atomic rename. 크래시 시 옛 파일 또는 새 파일만 남는다.
- **레포와 메타데이터의 공존**: `<repo>.git/mobilegit/` 에 두어 삭제 시 함께 사라지고, `init` 직후 크래시로 meta가 없는 레포는 `load()`가 LOCAL로 입양한다.
- **ref 갱신의 원자성**: 미러 업데이트가 보존 ref + 미러 ref를 하나의 atomic `BatchRefUpdate`로 적용. 반쯤 갱신된 레포가 생기지 않는다.
- **레포 단위 직렬화**: push/update/fork/delete가 `withRepoLock`으로 묶이고, 키가 소문자라 대소문자 다른 이름도 같은 락을 쓴다.
- **core의 순수성**: `core`에 Android 의존이 없어 JVM 단위 테스트(GitServerTest, MirrorSyncTest)가 돈다. 모듈 분리가 쉬운 상태.
- **비밀 처리**: 비밀번호는 해시, 토큰·ngrok 토큰·초기 비밀번호는 Keystore AES-GCM, 백업에서도 제외.
- **크기 상한**: 이력 200건, 접속 기록 2,000건, 일괄 작업 20개로 모든 JSON이 상한을 가진다.

---

## 발견 사항

### ARC-01 [높음] 읽기 실패를 "파일 없음"으로 취급해 정상 데이터를 덮어쓴다 — 지금

- 위치: `core/Util.kt:14-15` (`JsonFile.read`), `core/RepoStore.kt:50-62`, `core/Settings.kt:54-60`, `core/Credentials.kt:15`, `core/Bulk.kt:164`, `core/AccessLog.kt:14`
- 문제: `JsonFile.read`는 파일이 없을 때와 디코드에 실패했을 때 모두 `null`을 돌려준다 (`runCatching { ... }.getOrNull()`). 호출부는 이를 전부 "처음 실행"으로 다룬다.
  - `RepoStore.load()`: meta.json 디코드 실패 + `HEAD` 존재 → `RepoType.LOCAL, CreatedBy.PUSH` 메타를 새로 만들어 **meta.json을 덮어쓴다**. 미러의 `remoteUrl`, `credentialId`, 백오프 상태, 설명, 생성 시각이 영구히 사라지고 미러가 로컬 레포로 바뀐다.
  - `SettingsStore.load()`: 디코드 실패 → 새 무작위 비밀번호를 만들어 settings.json을 덮어쓴다. 사용자 이름·비밀번호·포트·ngrok 토큰·온보딩 완료가 초기화되고, 모든 클라이언트의 push/clone이 갑자기 401이 된다.
  - `CredentialStore`/`BulkJobStore`/`AccessLogStore`: 빈 목록으로 시작하고, 다음 `add`/`save`가 빈 목록 기준으로 파일을 덮어써 원본을 지운다.
  - 실패 원인은 로그도, 백업도 남지 않는다.
- 영향: 원자적 쓰기 덕분에 크래시로 깨질 가능성은 낮지만, 실제 트리거는 **스키마 변경(ARC-02), 앱 다운그레이드, 저장 매체 오류**다. 한 번 일어나면 레포 수백 개의 미러 설정이 한꺼번에, 조용히, 복구 불가능하게 바뀐다. "아카이브 앱"의 핵심 약속(데이터를 잃지 않는다)에 직접 걸린다.
- 권장:
  1. `JsonFile.read`가 "없음"과 "읽기 실패"를 구분하게 한다 (예: 실패 시 예외 또는 sealed 결과).
  2. 읽기 실패 시 원본을 `<name>.corrupt-<epoch>`로 옮겨 보존하고, 절대 같은 경로에 덮어쓰지 않는다.
  3. `RepoStore.load()`: meta 디코드 실패 레포는 입양하지 말고 "메타 손상" 상태로 목록에 올리거나 최소한 건드리지 않는다. 입양은 meta.json이 **없을 때만** 한다.
  4. `SettingsStore.load()`: 디코드 실패 시 비밀번호를 재생성하지 말고 서버를 끈 상태로 시작해 사용자에게 알린다.
  5. 손상 파일·누락 필드·알 수 없는 enum에 대한 재로딩 테스트를 추가한다 (ARC-18).

### ARC-02 [높음] 스키마 버전이 없고, enum 하나만 바뀌어도 파일 전체 디코드가 실패한다 — 지금

- 위치: `core/Util.kt:10` (`CoreJson`), `core/Models.kt` 전체, 모든 저장 파일
- 문제: `CoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }`. 필드 추가·삭제는 견디지만,
  - 저장된 enum 값이 코드에 없으면 (`AccessRoute`, `ErrorCategory`, `CreatedBy` 등의 값 이름 변경·삭제, 또는 새 버전에서 값 추가 후 다운그레이드) 해당 **파일 전체**가 디코드 실패 → ARC-01 경로로 덮어쓰기.
  - 필드 타입 변경이나 의미 변경을 처리할 마이그레이션 지점이 없다. 어떤 파일에도 `schemaVersion`이 없다.
  - CR-6처럼 기본값을 바꿀 때 "저장된 값은 유지"가 `encodeDefaults`의 부수 효과로만 보장되고 있다 (`core/Settings.kt:19-20`의 주석). 의도한 마이그레이션 규칙이 코드에 없다.
- 영향: 기획이 계속 바뀌는 단계(CR-1~7)라 enum 변경 가능성이 높다. ARC-01과 결합하면 업데이트 한 번에 데이터가 사라진다.
- 권장:
  1. `CoreJson`에 `coerceInputValues = true`를 켜서 알 수 없는 enum은 기본값으로 떨어지게 한다 (nullable/기본값 있는 필드에 한함). 기본값 없는 enum 필드(`RepoMeta.type`, `createdBy`, `AccessLogEntry.route` 등)는 기본값을 정하거나 커스텀 serializer로 `UNKNOWN`을 둔다.
  2. 각 파일 최상위에 `schemaVersion: Int`를 둔다 (목록 파일은 `{ "schemaVersion": 1, "items": [...] }` 래퍼). 로드 시 `migrate(from, json)` 한 곳을 거치게 한다. 지금은 v1 → v1 무변환이면 충분하다.
  3. 상위 버전 파일(다운그레이드)을 만나면 읽기 전용으로 열고 쓰지 않는다.

### ARC-03 [중간] 저장소들의 read-modify-write가 락 밖에서 일어나 갱신이 유실된다 — 지금

- 위치: `core/Credentials.kt:25,29-39`, `core/Bulk.kt:171-175,189-192`, `core/AccessLog.kt:29-36`
- 문제:
  - `CredentialStore.add/update/delete`는 `_items.value`를 락 밖에서 읽고 `save()`만 `@Synchronized`다. 동시 호출 시 한쪽 변경이 사라진다.
  - `BulkJobStore.create`와 `setRateLimited`도 같은 패턴이다. `onRepoStatus`(워커 스레드, `@Synchronized`)와 `setRateLimited`(카탈로그 코루틴, 락 없음)가 실제로 동시에 돈다 → 일괄 작업 항목 상태가 되돌아간다.
  - `AccessLogStore.flush()`는 동기화되지 않는데, 플러셔 스레드와 `clear()`가 동시에 같은 `accesslog.json.tmp`를 연다. 두 번째 `ATOMIC_MOVE`가 `NoSuchFileException`으로 실패하고 `runCatching`이 삼키며, `dirty`는 쓰기 **전에** false가 되어 재시도도 없다 → "기록 지우기"가 재시작 후 되살아날 수 있다.
- 영향: 일괄 미러 추가 진행률 표시 오류, 드물게 토큰 추가/수정 유실, 접속 기록 지우기 미반영.
- 권장: 각 저장소에 `RepoStore.update`와 같은 `update(transform)` 하나를 두고 읽기~쓰기를 같은 락 안에서 한다. `AccessLogStore.flush`는 `synchronized(this)` 안에서 스냅샷을 잡고, 쓰기 실패 시 `dirty = true`로 되돌린다.

### ARC-04 [중간] 고아 디렉터리·임시 파일을 정리하는 곳이 없고, 삭제된 레포 디렉터리가 되살아난다 — 지금

- 위치: `core/RepoStore.kt:71,126-132,148,165-179`, `core/Sync.kt:109-135`(check는 레포 락 없음), `core/Ops.kt:54-60`, `core/Ops.kt:169-176`, `app/Work.kt:151-174`, `app/Core.kt:102-107`
- 문제:
  - `check()`는 레포 락 없이 돈다. 그 사이 레포가 삭제되면 `appendEvent` → `dir(name)`이 레지스트리에 없는 이름으로 경로를 만들고 `JsonFile.write`의 `mkdirs()`가 `repos/<name>.git/mobilegit/events.json`을 **다시 만든다**. HEAD가 없어 `load()`는 무시하지만 디렉터리는 계속 남는다. 이후 같은 이름으로 fork COPY를 하면 `renameTo` 실패("rename failed"), `create()`는 그 디렉터리를 묻지 않고 `deleteRecursively()`한다(`RepoStore.kt:87`).
  - fork 중 프로세스가 죽으면 `repos/.tmp-<uuid>/`(레포 전체 크기)가 남는다. `finally`는 프로세스 kill에서 돌지 않는다.
  - `*.json.tmp`, `cacheDir/exports/*.part`, 공유 후 남은 `cacheDir/exports/<file>`도 정리되지 않는다.
  - `storageSummary()`는 레포 캐시 크기 + `state/`만 더해서 위 고아 데이터가 "앱 사용량"에 잡히지 않는다.
- 영향: 수 GB 단위의 보이지 않는 공간 누수(백업·fork는 레포 전체 크기), 저장 공간 하한 판정 왜곡, 이름 재사용 실패.
- 권장:
  1. `RepoStore.load()`에서 `repos/.tmp-*`, `*.tmp`, 그리고 `HEAD`도 meta도 없는 `*.git` 디렉터리를 정리한다.
  2. 이벤트/메타 쓰기는 `metas`에 등록된 레포에만 하고(`dir(name)`이 미등록 이름을 받으면 쓰지 않음), `appendEvent`/`appendPushEvents`가 `mkdirs()`로 디렉터리를 만들지 않게 한다.
  3. 앱 시작 시 `cacheDir/exports/`를 비운다 (공유 직후 파일은 FileProvider 권한이 끝나면 필요 없다).
  4. `create()`의 무조건 `deleteRecursively()`는 "HEAD 없는 고아"일 때만 지우고, 아니면 예외로 바꾼다.

### ARC-05 [중간] 앱 시작 시 모든 저장 파일을 메인 스레드에서 읽는다 — 지금

- 위치: `app/Core.kt:121-128` (`MobileGitApp.onCreate` → `core.start()`), `app/Core.kt:62-66`, `core/RepoStore.kt:43,50-64`, `core/AccessLog.kt:14`
- 문제: `Core`는 `Application.onCreate`에서 동기 생성된다. 생성자에서 `settings.json`, 레포 수만큼의 `meta.json`(PRD 기준 500개), 2,000건짜리 `accesslog.json`, `credentials.json`, `bulk.json`을 메인 스레드에서 읽고 파싱한다. `BootReceiver`도 같은 경로를 탄다. 첫 실행이면 Keystore 키 생성과 암호화도 메인 스레드다.
- 영향: 레포가 많을수록 콜드 스타트 지연, 저사양 폰에서 ANR 위험. 리시버 10초 제한도 있다.
- 권장: 설정만 동기로 읽고(서버 자동 시작 판단에 필요), 레포 레지스트리와 접속 기록은 `Dispatchers.IO`에서 로드하며 `repos`에 "로딩 중" 상태를 둔다. 최소한 `AccessLogStore`는 지연 로드한다.

### ARC-06 [중간] 접속 기록을 5초마다 통째로 다시 쓴다 — 나중 (ngrok 공개 시 지금)

- 위치: `core/AccessLog.kt:17-36`, `core/RepoStore.kt:165-179`
- 문제: 요청이 하나라도 있으면 5초마다 2,000건 전체(수백 KB)를 직렬화 + fsync한다. ngrok 공개 주소는 봇 스캔 트래픽이 꾸준해서 하루 수 GB 수준의 쓰기가 될 수 있다. 같은 패턴이 `events.json`/`pushes.json`(200건 전체 재작성)에도 있다.
- 영향: 플래시 마모, 배터리, CPU. 상시 켜 두는 서버 앱이라 누적된다. 또 프로세스가 죽으면 최대 5초분 기록이 사라진다(허용 가능).
- 권장: 접속 기록은 JSON Lines 추가 쓰기(`accesslog.jsonl`) + 상한 초과 시 회전(파일 2개 교대)으로 바꾼다. 이력류가 더 늘어나면 SQLite(Room 없이 `SQLiteDatabase`로도 충분) 하나로 접속 기록·동기화 이력·push 이력을 모으는 것을 검토한다. 레포 메타는 지금처럼 레포 디렉터리 안 JSON이 낫다.

### ARC-07 [중간] 전체 백업이 앱 캐시에 완성본을 만든 뒤 복사해 2배 공간이 들고, 공간 확인이 없다 — 지금

- 위치: `app/Work.kt:151-174`, `core/Ops.kt:138-158,169-176`
- 문제: SAF 대상이 있어도 먼저 `cacheDir/exports/`에 전체 zip을 완성한 뒤 복사한다. 전체 백업 크기 ≈ 모든 레포 크기라 내부 저장소에 레포 크기만큼 추가 공간이 필요하다. `storageFloorBytes`나 남은 공간을 확인하지 않는다. `cacheDir`은 시스템이 공간 부족 시 지울 수 있는 위치라 대용량 작업 중간 파일로 부적절하다.
- 영향: 레포가 큰 사용자는 백업이 "공간 부족"으로 실패하거나, 백업 중 내부 저장소가 꽉 차서 미러 업데이트/push가 같이 실패한다. 남은 공간이 바닥나면 JSON 상태 파일 쓰기도 실패한다.
- 권장: SAF Uri가 있으면 `contentResolver.openOutputStream(uri)`에 바로 스트리밍하고, 실패 시 문서를 지운다(지금도 실패 시 삭제는 함). 공유 경로만 캐시 파일을 쓰되, 시작 전에 예상 크기(`sizeBytes` 합) + 하한을 확인한다.

### ARC-08 [중간] 레포 메타 쓰기가 전역 단일 락 + fsync, push마다 레포 전체를 순회한다 — 나중

- 위치: `core/RepoStore.kt:83,113-120,167,174`, `core/RepoStore.kt:185-210`, `core/GitServer.kt:394-396`
- 문제: 모든 레포의 `create/update/appendEvent/appendPushEvents`가 `synchronized(this)` 하나를 공유하고, 그 안에서 fsync까지 한다. 또 push·업데이트·fork 후 `refreshDerived`가 `dirSize()`로 레포 디렉터리 전체를 순회하는데, push 경로에서는 레포 락을 쥔 채 실행된다.
- 영향: 동시 동기화 2개 + push + UI 갱신이 겹치면 fsync 대기열이 생긴다. 큰 레포(수만 개 loose object)에서는 push 응답 후에도 레포 락이 오래 잡혀 다음 push/fetch가 기다린다.
- 권장: 락을 레포별(`locks`와 같은 키)로 쪼개고 전역 락은 이름 등록(create/delete)에만 쓴다. `refreshDerived`의 크기 측정은 락 밖에서, 또는 팩 파일 크기 합으로 근사한다.

### ARC-09 [중간] 백업/복원 경로가 미러 설정과 앱 상태를 잃는다 — 나중 (D-7 재검토 시)

- 위치: `core/Ops.kt:95-99` (`ManifestRepo`, `BackupManifest`), PRD F-19 AC7 / D-7
- 문제: 복원은 "bundle clone → push"뿐이라 미러는 LOCAL로 돌아오고, 로컬 레포를 미러로 되돌리는 기능이 없다. 다시 미러로 추가하면 보존 ref(`refs/archive/...`)가 없는 새 미러가 된다. manifest에는 `remoteUrl`은 있지만 `autoUpdateExcluded`, `includePullRefs`, `description`, 자격 증명 연결(호스트·라벨 수준) 등이 없고, manifest 자체에 형식 버전도 없다(`appVersion`만 있음). 설정(state/)은 어떤 방식으로도 내보낼 수 없다.
- 영향: 폰 교체·초기화 시 "아카이브 + 미러 관계"를 함께 복원할 수 없다. 백업은 git 데이터 보존으로는 충분하나 앱 상태 복원으로는 부족하다.
- 권장: 지금은 manifest에 `formatVersion`과 레포 메타(비밀 제외) 전체를 넣어 두기만 한다(나중에 가져오기를 만들 때 쓸 수 있게). 가져오기 기능 자체는 2차에 판단한다.

### ARC-10 [중간] core 경계가 관례로만 지켜진다: `:core` Gradle 모듈 분리 — 나중

- 위치: `settings.gradle.kts` (`include(":app")`만 있음), `app/src/main/java/com/ouor/mobilegit/core/`
- 문제: `core`는 현재 Android import가 전혀 없어 순수 Kotlin/JVM이지만, 같은 모듈이라 누군가 `android.*`나 `app.*`를 import해도 빌드가 막지 않는다. 테스트도 Android 단위 테스트 설정(`isReturnDefaultValues`)을 거쳐 돈다.
- 영향: 경계가 깨지면 JVM 테스트(GitServerTest, MirrorSyncTest)가 돌지 않게 되고, 지금의 가장 큰 구조적 장점을 잃는다.
- 권장: `:core`를 `kotlin("jvm")` 모듈로 옮기고 `:app`이 의존하게 한다. 지금은 파일 이동만으로 끝나는 상태라 비용이 가장 낮을 때다. 당장 하지 않는다면 CI에 `grep "import android" core/` 같은 검사라도 둔다.

### ARC-11 [낮음] 한 파일에 책임이 섞이고, 파일 이름이 내용을 말하지 않는다 — 나중 (해당 파일을 고칠 때)

- 위치:
  - `core/Sync.kt` (396줄): `SyncCoordinator`, `MirrorController`(등록 흐름), `RepoQuery`(목록 정렬/필터), `Badges`, `NotificationRules`, `AddPreview`
  - `core/GitServer.kt` (539줄): smart HTTP, LFS 응답, JSON API 라우팅, 정적 파일 서빙, API DTO
  - `core/Bulk.kt`: `RemoteCatalog`(GitHub/GitLab HTTP 클라이언트) + `BulkJobStore`(저장)
  - `core/Ops.kt`: `RepoOps` + `Exporter` + 백업 manifest DTO
  - `core/AccessLog.kt`: `AccessLogStore` + `NetClassifier`(네트워크 분류)
- 문제: "저장소"를 찾으려면 Settings/Credentials/AccessLog/Bulk/RepoStore 다섯 파일을 뒤져야 하고, `Sync.kt`에서 등록 UI 로직을 찾게 된다.
- 권장: 파일 이름 = 주 클래스 이름으로 나눈다 (`MirrorController.kt`, `RepoQuery.kt`, `RemoteCatalog.kt`, `BulkJobStore.kt`, `Exporter.kt`, `NetClassifier.kt`, `WebApi.kt`). 저장소 클래스는 `core/store/` 하위 패키지로 모으면 ARC-01~03 수정도 한곳에서 끝난다.

### ARC-12 [낮음] UI가 객체 그래프를 직접 잡고, 상태 보유 계층이 없다 — 나중

- 위치: `ui/App.kt:80` (`fun core()`), `ui/Strings.kt:7` (`lateinit var context`), `app/MainActivity.kt:24`, `ui/screens/RepoDetailScreen.kt`(570줄), `AddScreens.kt`(508줄)
- 문제: Composable이 `MobileGitApp.core(ctx)`로 `Core` 전체를 받아 저장소·스케줄러를 직접 호출한다. `Core` 주석은 "ViewModels call these directly"라고 하지만 ViewModel은 없다. 문자열은 전역 `lateinit` Context로 읽는다(코드에 shortcut 주석 있음). 화면 파일이 상태·부수 효과·레이아웃을 모두 가진다.
- 영향: 화면 단위 테스트와 프리뷰가 어렵고, 설정 변경(회전 등) 시 진행 중 작업 상태 보존을 각 화면이 알아서 해야 한다.
- 권장: 큰 화면(레포 상세, 추가)부터 화면별 상태 홀더(ViewModel 또는 일반 클래스)를 두고 `Core`의 필요한 부분만 주입한다. 지금 기능 개발을 막지는 않으므로 나중.

### ARC-13 [낮음] 문자열 리소스 키가 번호라 의미가 없다 — 나중 (일괄 변환)

- 위치: `app/src/main/res/values/strings.xml` (556개 중 대부분 `detail_001`, `add_042`, `common_017`, `fmt_003` 형식), 사용처 예: `ui/Export.kt:76` `R.string.export_002`
- 문제: 코드에서 `str(R.string.main_031)`만 보고는 무엇을 표시하는지 알 수 없다. 리뷰·검색·중복 문자열 정리가 어렵다. 최근 추가된 키(`tunnel_title_connected`, `storage_floor_body`)는 의미 있는 이름이라 규칙이 섞여 있다.
- 권장: 화면_요소_의미 형식으로 일괄 이름 변경(IDE rename으로 기계적으로 가능). 새 키는 지금부터 의미 있는 이름만 쓴다.

### ARC-14 [낮음] core가 한국어 사용자 메시지를 직접 만든다 — 나중

- 위치: `core/RepoStore.kt:69,85`, `core/Settings.kt:72-97`, `core/Sync.kt:111,273,291`, `core/Ops.kt:41,50,52` 등
- 문제: `RepoException`/`SettingsException` 메시지가 한국어 문장이고 UI는 이를 그대로 표시한다. UI는 리소스(strings.xml)를 쓰므로 표시 문자열의 출처가 둘이다. 웹 API 오류도 같은 문장을 쓴다.
- 권장: 예외는 이미 있는 `RepoException.Code`(+인자)로 구분하고 문장은 UI/웹이 만든다. 다국어 계획이 없으면 그대로 두어도 되지만, 새 예외부터는 코드로 구분한다.

### ARC-15 [낮음] 원자적 쓰기의 세부 구멍 — 지금 (작은 수정)

- 위치: `core/Util.kt:17-25`, `core/Ops.kt:169-176`
- 문제:
  - rename 후 부모 디렉터리를 fsync하지 않아 전원 차단 시 rename이 되돌아갈 수 있다(옛 파일이 남으므로 손상은 아니고 최신 변경 유실).
  - tmp 이름이 `<file>.tmp`로 고정이라 같은 파일을 서로 다른 락에서 쓰면 충돌한다(ARC-03의 접속 기록 사례).
  - `toFileAtomically`는 `target.delete()` 후 `renameTo`라 원자적이지 않다. 주석("failure never leaves a partial file")과 실제가 다르다.
- 권장: `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`를 `toFileAtomically`에도 쓰고, `JsonFile.write`에 `FileChannel.open(dir).force(true)`를 추가한다(Android에서 디렉터리 fsync는 실패할 수 있으니 runCatching).

### ARC-16 [낮음] Keystore 키를 잃으면 토큰 복호화 실패가 일반 예외로 터진다 — 나중

- 위치: `app/Platform.kt:46-67` (`KeystoreCipher`), `core/Credentials.kt:53-55`, `core/Sync.kt:119-120,174-178`
- 문제: 키 손실(일부 기기의 잠금 방식 변경·OS 업데이트 후 Keystore 초기화)이면 `token()`이 `AEADBadTagException` 등을 던진다. `provider()`는 `engine.wrap` 밖에서 호출되므로 `SyncFailure`로 분류되지 않고 워커 실패로만 끝나며, 레포에는 실패 이력도 남지 않는다. 설정의 `ngrokAuthtoken()`/`initialPassword()`는 `runCatching`으로 조용히 null이 된다.
- 권장: 복호화 실패를 `ErrorCategory.AUTH_REQUIRED`(또는 별도 "토큰 다시 입력 필요")로 분류하고, 자격 증명 화면에 "복호화 불가" 상태를 표시한다.

### ARC-17 [낮음] 웹 빌드 결과물을 소스 트리에 커밋하고, API 타입을 손으로 맞춘다 — 나중

- 위치: `web/vite.config.ts:11` (`outDir: '../app/src/main/assets/web'`), `app/src/main/assets/web/assets/*-<hash>.js`, `web/src/lib/api.ts` ↔ `core/RepoBrowser.kt`/`core/GitServer.kt` DTO
- 문제: 해시가 붙은 빌드 산출물이 git에 들어가 웹 소스와 어긋나도 알 수 없고(Gradle에 웹 빌드 태스크 없음), 커밋마다 diff 잡음이 생긴다. TS 타입과 Kotlin DTO가 별도로 관리된다.
- 권장: 빌드 산출물을 `.gitignore`하고 Gradle `preBuild`에 `npm run build` 태스크를 연결하거나, 커밋을 유지한다면 CI에서 "웹 빌드 후 diff 없음"을 확인한다. DTO 동기화는 `GitServerTest`에 API 응답 스냅샷을 두는 정도로 충분하다.

### ARC-18 [낮음] 저장 계층 테스트가 없다 — 지금 (ARC-01/02 수정과 함께)

- 위치: `app/src/test/java/com/ouor/mobilegit/core/UnitTests.kt` (설정 첫 실행 외 저장 테스트 없음)
- 문제: 재시작 후 재로딩, 손상 파일, meta 없는 레포 입양, 알 수 없는 enum, 동시 갱신, 삭제 후 이벤트 쓰기에 대한 테스트가 없다.
- 권장: `TestSupport.kt`의 임시 디렉터리로 `RepoStore`/`SettingsStore`/`CredentialStore`를 만들고 파일을 직접 조작한 뒤 재생성하는 테스트 5~6개면 ARC-01~04 회귀를 막을 수 있다.

---

## 지금 / 나중 정리

| 시점 | ID |
|---|---|
| 지금 (다음 스키마 변경 전) | ARC-01, ARC-02, ARC-18 |
| 지금 (작은 수정) | ARC-03, ARC-04, ARC-05, ARC-07, ARC-15 |
| 나중 | ARC-06(ngrok 상시 사용이면 지금), ARC-08, ARC-09, ARC-10, ARC-11, ARC-12, ARC-13, ARC-14, ARC-16, ARC-17 |

저장 형식 자체(레포별 JSON + state/ JSON)는 이 규모(레포 수백 개, 상한 있는 이력)에 맞는 선택이다. DB로 갈아탈 필요는 없고, **읽기 실패 처리·버전·락 범위**만 바로잡으면 된다. 이력/접속 기록이 커지거나 검색이 필요해질 때 그 부분만 SQLite로 옮긴다.

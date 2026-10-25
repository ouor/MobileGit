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

### 실기기 instrumented 테스트 (`ANDROID_SERIAL=d418f2d1 ./gradlew :app:connectedDebugAndroidTest`, 3개 통과)

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

## 6. 남은 일 / 위험

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

# 코어 기술 조사 (research)

작성: 코어 개발 · 기준일 2026-10-25 · 대상: PRD v1 F-01..F-25

원칙: 이미 검증된 오픈소스를 우선 쓴다. 라이선스가 맞지 않거나 불명확한 코드는 복사하지 않는다(이번 사이클에 외부 코드를 소스째로 복사한 곳은 없음, 모두 의존성으로 사용).

## 1. 후보 비교와 선택

| 영역 | 후보 | 선택 | 라이선스 | 이유 / 알려진 이슈 |
|---|---|---|---|---|
| git 구현 | **JGit 7.8.0**, JGit 6.10, JGit 5.13(Java 8), libgit2(JNI), git 바이너리 동봉 | JGit 7.8.0.202609011348-r | EDL-1.0 (BSD 계열) | Java 순수 구현이라 ART에서 그대로 동작. 서버(UploadPack/ReceivePack, protocol v2)와 클라이언트(fetch, bundle, archive)를 한 라이브러리로 해결. 7.x는 Java 17 API(`java.nio.file`, `ProcessHandle` 등)를 쓰므로 minSdk 26 + core library desugaring(`desugar_jdk_libs_nio`)으로 맞춤. MGit(안드로이드 git 클라이언트, GPL-3)은 JGit 3.7.1을 쓰는데 오래되어 protocol v2와 보안 수정이 없음. 코드는 참고만, 복사 없음. libgit2는 서버 측 smart HTTP가 없어 제외. git 바이너리 동봉은 GPL-2 + 아키텍처별 빌드 + W^X 정책 문제로 제외. |
| JGit의 안드로이드 설정 | - | 직접 구현(`JGitEnv`) | - | JGit은 기본으로 `~/.gitconfig`, `/etc/gitconfig`, JMX, mmap을 사용. 안드로이드에서는 SystemReader를 빈 설정으로 바꾸고 WindowCache를 mmap 없이 작은 한도(packedGitLimit 32MB, deltaBaseCache 16MB)로 설정. |
| HTTP 서버 | **Ktor 3.6 CIO**, Jetty 12 + JGit GitServlet, NanoHTTPD, AndroidAsync | Ktor server CIO | Apache-2.0 | JGit의 `GitServlet`은 javax/jakarta servlet 컨테이너가 필요. Jetty 12는 안드로이드에서 `java.lang.management`, MethodHandle 관련 이슈와 APK 크기 부담이 있음. NanoHTTPD는 유지보수가 끊김(2019 이후 릴리스 없음). Ktor CIO는 순수 코틀린 코루틴 서버라 ART에서 문제없이 동작하고 스트리밍 요청/응답을 지원. smart HTTP 처리는 JGit `http.server`의 `InfoRefsServlet`/`UploadPackServlet`/`ReceivePackServlet` 동작(콘텐츠 타입, gzip 요청, `Git-Protocol` 헤더, pkt-line 오류)을 따라 약 300줄로 다시 구현. |
| ngrok | **ngrok-go v2 (gomobile)**, ngrok-java 1.1.1, java-ngrok, ngrok 에이전트 바이너리 동봉 | ngrok-go v2.4.0 + gomobile bind | MIT | **ngrok-java 1.1.1은 ART에서 로드 실패**: android-aarch64 네이티브가 `JNI_OnLoad`에서 `JNI_VERSION_1_8`(65544)를 반환해 "Bad JNI version" 발생(ngrok/ngrok-java#54, #50, 수정 릴리스 없음). 실기기 instrumented 테스트로 재현함. 또 .so가 4KB 정렬이라 Android 15+ 16KB 페이지 기기와 맞지 않음. java-ngrok(MIT)은 ngrok 에이전트 바이너리를 내려받아 실행하는 방식이라 안드로이드에서 실행 파일 다운로드/실행 금지 원칙과 맞지 않고, 에이전트 바이너리는 재배포 라이선스가 없음. ngrok-go는 ngrok 공식 Agent SDK, MIT, 활발히 유지(v2.4.0, 2026-10-23). gomobile로 arm64/x86_64 AAR(약 8.7MB)을 만들고 ELF LOAD 정렬 0x4000(16KB) 확인. Go 1.26.10으로 빌드(이전 1.26.x는 유출된 eMudhra CA를 신뢰, CVE-2026-111943). |
| 마크다운 | **commonmark-java 0.31**, flexmark, Markwon | commonmark-java + GFM tables/strikethrough/autolink | BSD-2 | 서버에서 HTML로 렌더링하고 `escapeHtml(true)`, `sanitizeUrls(true)`로 원시 HTML과 `javascript:` 링크를 막음. flexmark는 크고 무거움. Markwon은 안드로이드 뷰 전용. |
| 백그라운드 작업 | **WorkManager 2.11**, AlarmManager + 직접 서비스 | WorkManager | Apache-2.0 | 제약 조건(비데이터 망, 충전 중), 재부팅 후 유지, 고유 작업(레포별 `sync-<name>`), `setForeground(dataSync)`를 기본 제공. |
| 비밀값 저장 | **Android Keystore AES-GCM**, EncryptedSharedPreferences/Jetpack Security | Keystore 직접 사용 | 플랫폼 | `androidx.security:security-crypto`는 지원 중단(deprecated). Keystore AES-256-GCM 키 하나로 토큰·ngrok authtoken·초기 비밀번호를 암호화(iv‖ct, base64). |
| 설정/메타 저장 | Room, DataStore, **JSON 파일** | kotlinx.serialization JSON + 원자적 쓰기 | Apache-2.0 | 레포 메타는 레포 디렉터리 안(`mobilegit/meta.json`)에 두어 백업·삭제 단위가 레포와 같음. 데이터가 작고(레포 수백 개 이하) 쿼리가 필요 없어 DB를 쓰지 않음. tmp + fsync + ATOMIC_MOVE. |
| 비밀번호 해시 | **PBKDF2-HMAC-SHA256**, bcrypt/argon2 라이브러리 | PBKDF2 120k회 (JCA 내장) | 플랫폼 | 추가 의존성 없음. 매 요청 재계산을 피하려고 (해시, 사용자) 키로 성공 결과를 메모리에 캐시. |
| QR | **ZXing core 3.5** | ZXing core | Apache-2.0 | 의존성이 core 하나뿐. |
| 일괄 미러 목록 | GitHub/GitLab REST API 직접 호출, 각 SDK | Ktor 없이 `HttpURLConnection` | - | 필요한 엔드포인트가 4개뿐이라 SDK를 넣지 않음. Link 헤더와 X-Next-Page로 페이지 이동, `Retry-After`/`X-RateLimit-*`로 대기. |

## 2. 미러 동작: Gitea/Forgejo와 비교

- Gitea/Forgejo의 pull mirror는 `git remote update --prune`을 실행. 원본에서 force-push나 삭제가 일어나면 이전 커밋을 가리키던 ref가 사라지고 gc 이후 객체도 사라짐. **PRD F-11(아카이브 보존)과 정반대 동작**.
- mobilegit은 JGit으로 직접 구현:
  1. `FetchConnection`으로 원격 ref 목록을 받음.
  2. 없는 객체만 내려받고 ref는 아직 쓰지 않음.
  3. 삭제되거나 fast-forward가 아닌 ref를 계산. 태그가 바뀌면 항상 재작성으로 봄.
  4. 아카이브 ref `refs/archive/<UTC yyyyMMdd'T'HHmmss'Z'>/<원래 ref에서 refs/ 뺀 이름>`과 미러 ref를 한 번의 원자적 `BatchRefUpdate`로 기록. 중간에 실패하면 아무것도 바뀌지 않음.
- Gitea와 맞춘 점: `refs/pull/*`, `refs/merge-requests/*`도 미러함(PRD "그 밖의 refs"). 단 이 ref들은 원본에서 자주 재작성되므로 **아카이브하지 않음**. 저장 공간 비용이 있음(예: octocat/Hello-World는 refs/pull이 수천 개라 약 21MB).
- 원본 HEAD 심볼릭 ref를 따라 기본 브랜치를 맞춤.

## 3. 알려진 이슈와 대응

| 이슈 | 영향 | 대응 |
|---|---|---|
| ngrok-java ART 로드 실패(#54) | F-04 불가 | ngrok-go + gomobile로 교체(`ngrok-mobile/`). 실기기에서 로드와 가짜 토큰 거부(ERR_NGROK_105) 확인. |
| ngrok-go는 첫 연결 실패를 무한 재시도 | 잘못된 토큰이면 `Start`가 영원히 블록 | 이벤트 핸들러로 연결 전 첫 끊김을 잡아 즉시 실패 + 30초 타임아웃. 재시도와 백오프는 앱의 `TunnelManager`가 맡음(2초부터 최대 5분, 토큰 거부는 재시도 안 함). |
| AGP는 jar 루트의 .so를 버림 | (ngrok-java 시절) 네이티브 누락 | AAR `jni/` 경로를 쓰는 gomobile로 해결. |
| 안드로이드 기본 cleartext 차단 | JGit 클라이언트가 `http://` LAN 원본에 접근 못 함, 로컬 테스트 실패 | `usesCleartextTraffic="true"`. 서버 자체가 LAN HTTP라 필수. |
| JGit 7은 Java 17 API 사용 | minSdk 26에서 일부 API 부재 가능 | core library desugaring(nio 변형) 사용. **API 36 기기에서만 실행 검증**. 그 아래 버전은 미검증. |
| JGit fetch 인덱싱 메모리 | 큰 pack은 객체 수에 비례해 메모리 사용 | `largeHeap`, 작은 WindowCache. 2GB pack은 미검증(spec 12절). |

## 4. 참고 링크

- JGit: https://github.com/eclipse-jgit/jgit (EDL-1.0)
- Ktor: https://github.com/ktorio/ktor (Apache-2.0)
- ngrok-go: https://github.com/ngrok/ngrok-go (MIT), ngrok-java 이슈: https://github.com/ngrok/ngrok-java/issues/54
- gomobile: https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile (BSD-3)
- commonmark-java: https://github.com/commonmark/commonmark-java (BSD-2)
- Gitea mirror: https://docs.gitea.com/usage/repo-mirror
- MGit: https://github.com/maks/MGit (GPL-3, 참고만)

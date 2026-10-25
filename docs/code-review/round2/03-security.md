# 코드 리뷰 2차 — 03. 보안 (2차 사이클 변경분)

- 범위: `git diff 0a481ef..HEAD -- app/ web/src`. 중심은 R2-05(재시작 뒤 ngrok 꺼짐), R2-06(비밀번호 없이 ngrok 공개 허용), 새 API(`/api/v1/repos/{name}/archives`, `/api/v1/overview`, 트리 `lfs` 필드)다. 판단에 필요한 만큼 변경되지 않은 주변 코드(`GitServer.kt` 커넥터, `ngrok-mobile/tunnel.go`, `NetClassifier`)도 읽었다.
- 전제: "비밀번호 없이 공개"를 경고 후 허용하는 것은 사용자가 정한 제품 결정이다. 결함으로 보지 않는다. 구현이 그 결정의 경계(경고한 범위, 고른 순간, 고른 경로)를 지키는지만 본다.
- 방법: 코드 정적 리뷰. 기기나 ngrok 엔드포인트에는 아무것도 보내지 않았다. "실측 필요"라고 적은 항목은 기기에서 한 번 확인하면 확정된다.
- 요약: 치명 0 · 높음 0 · 중간 1 · 낮음 4

| ID | 심각도 | 한 줄 요약 |
|---|---|---|
| R2SEC-01 | 중간 | ngrok용 루프백 포트가 ngrok이 꺼져 있어도 늘 열려 있고, 이제 그 포트에서는 익명 읽기가 된다. 폰 안의 다른 앱이 "push만" 설정에서 비밀번호 없이 모든 레포를 읽는다 |
| R2SEC-02 | 낮음 | 앱 업데이트 직후 `Application.onCreate`의 자동 복원이 `BootReceiver`보다 먼저 돌아, 꺼야 할 ngrok이 잠깐 켜질 수 있다 (실측 필요) |
| R2SEC-03 | 낮음 | 상태 알림이 잠금 화면에 ngrok 주소를 그대로 보여 준다. 비밀번호 없이 공개 중이면 주소가 곧 접근 권한인데, 알림에는 그 위험이 표시되지 않는다 |
| R2SEC-04 | 낮음 | 경고 문구가 실제 노출 범위보다 좁다 (보존 기록, 원본 주소, 레포 생성으로 저장 공간 채우기) |
| R2SEC-05 | 낮음 | `archives` API와 트리 `lfs` 판정에 상한이 없다 |

---

## R2SEC-01 (중간) ngrok 루프백 포트가 늘 열려 있고, 이제 그 포트에서는 익명 읽기가 된다

- 위치: `core/GitServer.kt:112`(루프백 커넥터를 항상 바인딩), `core/GitServer.kt:164`(`viaNgrok` = 로컬 포트가 ngrok 포트인가), `core/GitServer.kt:183-184`(`anonymousOk = viaNgrok || …`), `core/Auth.kt:101`, `res/values/strings.xml:434`(`settings_auth_push_sub`)
- 문제:
  ```kotlin
  val anonymousOk = viaNgrok || (NetClassifier.isLocalNetwork(call.request.local.remoteAddress) &&
      NetClassifier.isDirectHost(call.request.header(HttpHeaders.Host)))
  ```
  `viaNgrok`은 "요청이 ngrok 전용 루프백 커넥터(`127.0.0.1:<임의 포트>`)로 들어왔는가"일 뿐이다. 이 커넥터는 ngrok 설정과 상관없이 서버가 켜질 때마다 바인딩된다. 1차에서는 이 포트가 메인 포트보다 **엄격했기** 때문에(항상 인증) 누가 붙든 문제가 없었다. 1차 리뷰가 "포트로 구분하므로 위조할 수 없다"고 칭찬한 것도 그 전제에서였다. 2차에서 이 포트가 **더 느슨해졌다**(익명 허용 + Host 검사 생략). 그래서 이제는 ngrok을 거치지 않고도 이 포트에 직접 붙기만 하면 익명 경로를 탈 수 있다.
  - "push만" 설정의 화면 설명은 "push와 **폰 안에서 접속할 때는** 비밀번호를 물어요"다. 1차 SEC-03 수정도 루프백을 일부러 익명 대상에서 뺐다. 메인 포트는 지금도 그렇게 동작한다(테스트 `GitServerTest.kt:123`). 하지만 ngrok 포트는 같은 루프백인데도 익명이다. ngrok이 **꺼져 있어도**, 켜져 있지만 연결이 실패(TokenRejected/Retrying)한 상태여도 마찬가지다. 테스트는 `ngrokEnabled = true`인 경우만 확인하고 꺼진 경우는 보지 않는다.
  - R2-06이 허용한 것은 "사용자가 ngrok을 켜고 경고를 본 뒤 인터넷 공개를 고른 조합"이다. ngrok이 꺼진 상태의 루프백 익명 접근은 그 결정 범위 밖이다. 경고도 뜨지 않는다(`exposedWithoutPassword`가 false).
- 공격 시나리오:
  1. 사용자는 집 와이파이용으로 "push만"을 고른다. 2차 기본값대로 ngrok은 꺼져 있다. 화면에는 위험 표시가 없다.
  2. 같은 폰에 설치된 아무 앱(인터넷 권한만 있으면 됨)이 `127.0.0.1`의 포트를 훑는다. 포트 65k개를 루프백으로 훑는 데는 수 초면 된다. `GET /api/v1/info`의 응답(`"app":"mobilegit"`)으로 포트를 찾는다.
  3. 인증 없이 `/api/v1/repos`, `/{repo}.git/info/refs`, `git-upload-pack`으로 토큰을 써서 받아 둔 **비공개 미러를 포함한 모든 레포**를 clone한다.
  - 덤: 이 포트에서는 `X-Forwarded-For` 마지막 값을 클라이언트 IP로 쓴다(`GitServer.kt:169`, SEC-01 수정). 그래서 로컬 앱은 접속 기록의 IP를 마음대로 적고, 비밀번호 대입 시 IP별 차단도 피한다. 이 부분은 1차부터 있었지만, 같은 원인(ngrok이 꺼져도 포트가 열려 있다)으로 함께 고쳐진다.
  - 폰 브라우저에서 연 공격 페이지가 `evil.example`을 `127.0.0.1`로 리바인딩하는 경로도 생긴다. 이 포트에서는 `isDirectHost` 검사를 건너뛰기 때문이다. 다만 임의 포트를 찾아야 하고 최신 Chrome은 공개 → 루프백 요청을 막거나 묻는 쪽이라 부차적이다.
- 권장 수정 (작은 것부터):
  1. `anonymousOk`의 ngrok 쪽 조건을 "실제로 공개 중"으로 좁힌다: `viaNgrok && settings().ngrokEnabled && tunnelConnected()`. `GitServer`에 `() -> Boolean`을 하나 넘기면 된다. 그 밖의 경우 ngrok 포트도 메인 포트의 루프백과 똑같이 인증을 요구한다. 이 경로에서는 `clientIp`도 `remoteAddress`를 쓴다.
  2. 더 깔끔하게는 루프백 커넥터를 터널이 켜질 때만 열고 꺼질 때 닫는다. 지금 구조(설정 변경 시 `restart()`)에서는 `start()`에 `withNgrokPort` 인자 하나로 된다.
  3. 테스트: `ngrokEnabled = false` + "push만"에서 `viaPort = ng`로 익명 `info/refs`가 401인지 보는 assert 한 줄을 `GitServerTest`에 더한다.
  - 남는 한계(설명에 적을 것): ngrok이 실제로 켜져 있는 동안에는 폰 안의 앱도 "인터넷의 누구나"에 포함된다. 이는 R2-06 경고 범위 안이다.

## R2SEC-02 (낮음) 앱 업데이트 직후 ngrok이 잠깐 다시 켜질 수 있다 (실측 필요)

- 위치: `app/Core.kt:132`(`server.restoreIfEnabled()`, `Application.onCreate` 안), `app/Server.kt:111-114`(같은 부팅이면 복원), `app/Core.kt:171-180`(`BootReceiver`가 `ngrokEnabled = false`)
- 문제: R2-05 AC4는 "재부팅이나 앱 업데이트 뒤에는 ngrok을 켜지 않는다"이다. 재부팅은 `serverBootCount != BOOT_COUNT`라서 `restoreIfEnabled`가 아무것도 하지 않으므로 안전하다. 그런데 **앱 업데이트는 같은 부팅 안**이다. 업데이트된 프로세스가 `MY_PACKAGE_REPLACED`를 받으려고 뜨면 `Application.onCreate` → `restoreIfEnabled()` → `start()`가 먼저 실행된다. 이때 설정은 아직 `ngrokEnabled = true`다. `BootReceiver.onReceive`가 그 뒤에 꺼 준다. 실제로는 서비스의 수집기가 설정을 읽기 전에 receiver가 먼저 도는 경우가 대부분일 것이다. 또 Android 12+에서는 백그라운드 시작이 막혀 대개 `BACKGROUND_START_BLOCKED`로 끝난다. 하지만 이 보장은 메인 루퍼의 메시지 순서에 기대고 있다. 프로세스가 브로드캐스트가 아닌 다른 이유(사용자가 업데이트 직후 "열기"를 누름, 동기화 작업)로 먼저 떴다면 터널이 연결된 뒤에야 꺼질 수 있다.
- 영향: 사용자가 "업데이트하면 꺼진다"고 믿는 상태에서 수 초~수십 초 동안 공개될 수 있다. R2-06 조합이었다면 비밀번호 없이 공개된다.
- 권장 수정: 순서에 기대지 말고 상태로 판단한다. `Settings`에 마지막으로 실행한 `versionCode`를 두고, `Core.start()`에서 `restoreIfEnabled()`를 부르기 **전에** "부팅 횟수나 버전이 바뀌었으면 `ngrokEnabled = false` + `markNgrokOffAfterRestart()`"를 한다. 그러면 `BootReceiver`의 같은 처리는 지워도 된다.
- 확인: ngrok을 켠 상태에서 `adb install -r`로 업데이트한 뒤 곧바로 앱을 열고, ngrok 대시보드의 세션 목록이나 알림에 `ngrok:` 줄이 잠깐이라도 생기는지 본다.

## R2SEC-03 (낮음) 잠금 화면 알림에 ngrok 주소가 그대로 나오고, "비밀번호 없이" 상태는 표시되지 않는다

- 위치: `app/Server.kt:286`(`"ngrok: ${t.publicUrl}"`), `app/Server.kt:298-305`(`buildNotification`, `setVisibility`/`setPublicVersion` 없음)
- 문제: 상태 알림은 상시 알림이고 본문에 공개 URL을 넣는다. 가시성 설정이 없으므로 기본값(PRIVATE)을 따른다. 많은 기기의 기본 설정인 "잠금 화면에 모든 알림 내용 표시"에서는 주소가 잠금 화면에 보인다. 1차에서는 주소를 알아도 비밀번호가 필요했다. R2-06 조합에서는 **주소를 아는 것만으로 clone(끄기면 push)**이 된다. 책상에 둔 폰의 잠금 화면을 본 사람이 곧바로 전체 레포를 받을 수 있다. 알림 제목도 "외부 공개 중"뿐이라, 앱 안 배너(R2-06 AC3)와 달리 위험 상태가 알림에서는 드러나지 않는다.
- 권장 수정: `exposedWithoutPassword && Connected`이면 제목을 "비밀번호 없이 외부 공개 중"으로 바꾼다. 알림에 `setPublicVersion(…)`으로 URL을 뺀 버전을 달아 잠금 화면에서는 주소를 숨긴다. 주소는 앱 안에서 보면 된다.

## R2SEC-04 (낮음) 경고 문구가 실제 노출 범위보다 좁다

- 위치: `res/values/strings.xml:729-747`(`risk_body_*`, `main_exposed_*`), `core/GitServer.kt:466-491`(익명으로 열리는 API), `core/GitServer.kt:379-387`(push로 레포 생성)
- 문제: 경고는 "주소를 아는 인터넷의 누구나 clone하고 웹 화면을 볼 수 있어요"("안 함"이면 "clone하고 push할 수 있어요")다. 실제로 익명 인터넷 사용자가 얻는 것은 그보다 넓다. 사용자가 이 결정을 내리는 데 중요한 차이는 다음과 같다.
  - **보존 기록**: 2차의 `/api/v1/repos/{name}/archives`와 W-05가 `refs/archive/*`를 목록으로 보여 준다. 이 ref는 upload-pack에도 그대로 광고된다(`'refs/archive/*:refs/archive/*'` fetch 명령도 화면에 있다). 원본에서 **실수로 올린 비밀을 강제 push로 지운 커밋**이 바로 여기에 남는다. 1차에도 SHA를 알면 받을 수 있었지만, 2차는 이를 찾기 쉽게 목록으로 만들었다.
  - **미러 메타데이터**: `/api/v1/repos`의 `originUrl`(비공개 GitHub 조직·레포 이름), 2차에서 더한 `issue`/`severity`(예: `AUTH_REQUIRED` = 토큰 만료), `/api/v1/overview`의 집계. 비밀값은 없지만 정찰 정보다.
  - **"안 함"의 push**: 덮어쓰기만이 아니다. 없는 이름으로 push하면 **새 레포가 생긴다**(`store.create(…, CreatedBy.PUSH)`). 인터넷의 누구나 저장 공간을 채울 수 있다. LAN용 문구(`settings_auth_off_body`)에는 "push로 레포를 만들거나 덮어쓸 수 있어요"가 있는데, ngrok 경고에는 빠졌다.
- 권장 수정: 경고 본문에 한 줄을 더한다. 예: "보존된 기록(강제 push로 지워진 커밋 포함)과 원본 주소도 보여요." "안 함"에는 "새 레포를 만들어 저장 공간을 채울 수도 있어요."를 붙인다. 코드 변경은 필요 없다.

## R2SEC-05 (낮음) `archives` API와 트리 `lfs` 판정에 상한이 없다

- 위치: `core/GitServer.kt:491`, `core/RepoBrowser.kt`의 `archives()`(모든 archive ref에 `parseAny` + `parseBody` + `exactRef`), `core/RepoStore.kt`의 `archiveNotes()`(요청마다 `archives.json` 최대 10,000건 + 이력 200건 읽기), `core/RepoBrowser.kt:169-170`(200바이트 이하 파일마다 blob 열기)
- 문제: archive ref 수에는 상한이 없다. 원본이 확인 주기마다 강제 push하면 계속 늘어난다. 요청 하나가 그 전부를 파싱하고 전부를 응답한다(페이지 없음). 트리 API는 이제 작은 파일마다 blob을 하나씩 연다. 작은 파일 수만 개짜리 디렉터리(예: `node_modules` 스타일 커밋, 공격자가 만든 공개 레포 미러)에서는 요청 하나가 그만큼 객체를 읽는다. 둘 다 `heavy`(동시 2개) 안에서 돌기 때문에 서버 전체가 멈추지는 않는다. 하지만 읽기 권한만 있으면(R2-06 조합에서는 인터넷의 누구나) 웹 화면 무거운 요청 슬롯을 계속 점유할 수 있다.
- 권장 수정: `archives`는 최신순 N개(예: 500)만 주고 `truncated` 표시를 단다. `lfs` 판정은 디렉터리당 처음 M개(예: 1,000) 파일까지만 하고 나머지는 `false`로 둔다. 웹은 이미 `lfs`가 없을 때를 처리하므로 계약이 바뀌지 않는다.

---

## 확인했고 문제없는 것

- **경고 흐름(R2-06 AC1·AC2)**: ngrok을 켜려 할 때(`riskFromNgrok`)와 ngrok이 켜진 상태에서 비밀번호 요구를 낮출 때(`riskFromAuth`) 모두 경고를 띄운다. 기본 버튼은 "취소"이고, 위험 조합을 저장하는 것은 "비밀번호 없이 공개"뿐이다. 대안 버튼은 한 번의 `settings.update`로 안전한 조합을 원자적으로 저장한다. 앱 안에서 `ngrokEnabled = true`나 `authEnabled = false`를 쓰는 다른 경로는 없다(grep 확인). 큰 글꼴용 라디오 목록도 같은 `pick`을 거친다.
- **배너와 실제 동작 일치**: `exposedWithoutPassword = ngrokEnabled && (!authEnabled || anonymousReadOnLan)`이고, 서버 판정은 `effectiveAuthEnabled = authEnabled`, `effectiveAnonymousRead = anonymousReadOnLan && authEnabled`다. 두 식이 같은 조건을 가리킨다. "push만"이어도 ngrok 경로의 push는 401이다(테스트 있음). "공개 중"은 터널이 실제로 연결됐을 때만 표시하고, 그 전에는 "연결되면 열려요"라고 쓴다.
- **재부팅(R2-05 AC4)**: 재부팅 뒤에는 `BOOT_COUNT`가 달라 자동 복원이 돌지 않고, `BootReceiver`가 먼저 ngrok을 끈 다음 서버를 켠다. `startOnBoot`가 꺼져 있어도 ngrok 설정은 꺼진다. (업데이트 경우는 R2SEC-02.)
- **새 API 입력 검증**: `archives`/`overview`는 쿼리 인자를 받지 않는다. 레포 이름은 기존처럼 `store.get`(이름 정규식)을 통과해야 한다. 같은 `authorize`를 거친다. `issue.category`는 enum이라 원격 오류 원문(경로, URL)이 나가지 않는다. `RefChange` 노트 파일은 레포 디렉터리 안 고정 경로다.
- **웹 XSS**: 새 화면(`Archives.tsx`, `issue.ts`, `RepoList.tsx` 요약 줄)은 원본이 정한 ref 이름과 커밋 메시지를 React 텍스트로만 출력하고 `dangerouslySetInnerHTML`을 쓰지 않는다. 링크는 `repoHref.*`(경로 인코딩)로만 만든다. 복사용 fetch 명령에 들어가는 값은 레포 이름(`[A-Za-z0-9._-]`)과 origin뿐이라 셸 주입이 없다. CSP와 정적 파일 처리는 바뀌지 않았다.
- **알림·로그**: 내보내기 완료 알림은 파일 이름과 폴더 이름만 보여 준다. 업데이트 뒤 알림 문구에는 민감 정보가 없다. 미러 오류 분류(QA2-01) 변경 뒤에도 메시지는 `Redact.text`를 거친다.
- **USB 테더링(R2-15)**: 사설 대역 주소만 분류하고, 익명 읽기 판정은 바뀌지 않은 `isLocalNetwork`를 그대로 쓴다.

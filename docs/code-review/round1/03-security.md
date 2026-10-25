# 코드 리뷰 1차 — 03. 보안 (인증·인가, 노출 범위, 입력 처리)

- 범위: `app/src/main/java/com/ouor/mobilegit/core/`, `app/…/app/`, `ngrok-mobile/tunnel.go`, `web/src/`, `AndroidManifest.xml`, `docs/core/spec.md`, `docs/planning/prd.md`
- 방법: 코드 정적 리뷰. 실제 폰이나 ngrok 엔드포인트에는 아무것도 보내지 않았다. "실측 필요"라고 적은 항목은 기기에서 한 번만 확인하면 확정된다.
- 요약: 치명 0 · 높음 1 · 중간 4 · 낮음 7

| ID | 심각도 | 한 줄 요약 |
|---|---|---|
| SEC-01 | 높음 | ngrok 경로의 클라이언트 IP를 X-Forwarded-For 첫 값에서 가져와 브루트포스 차단을 우회할 수 있다 |
| SEC-02 | 중간 | 인증 없이 PBKDF2(120k)를 마음대로 돌리게 할 수 있다 (CPU·배터리 DoS) |
| SEC-03 | 중간 | "LAN 익명 읽기"가 실제로는 "ngrok이 아닌 모든 경로"에 적용되고, Host 검증이 없어 DNS 리바인딩에 열려 있다 |
| SEC-04 | 중간 | 0.0.0.0 바인딩이 셀룰러/공용 망의 IPv6 전역 주소까지 열 가능성이 있다 (실측 필요) |
| SEC-05 | 중간 | `http://` 미러 URL에도 호스트가 같은 토큰을 붙여 평문으로 보낸다. 외부 앱의 공유 인텐트만으로 자동 probe가 시작된다 |
| SEC-06 | 낮음 | 팩 슬롯 4개를 느린 요청으로 오래 붙잡을 수 있다 (UploadPack/ReceivePack 타임아웃 없음) |
| SEC-07 | 낮음 | 레이트리미터 맵에 크기 제한이 없다 (SEC-01과 겹치면 메모리 고갈) |
| SEC-08 | 낮음 | 평문 LAN에서 탈취한 Basic 계정으로 로컬 레포 히스토리를 보존 없이 지울 수 있다 |
| SEC-09 | 낮음 | 원격이 준 링크·리다이렉트를 따라갈 때 토큰이 다른 호스트나 http로 따라갈 수 있다 |
| SEC-10 | 낮음 | 500 응답에 내부 예외 메시지(경로 등)가 그대로 나간다 |
| SEC-11 | 낮음 | `commits?page=` 상한이 없어 요청 하나로 히스토리 전체를 걷게 할 수 있다 |
| SEC-12 | 낮음 | 미러 fetch는 fsck를 하지 않는다. 소스 zip 내보내기는 "git bomb" 트리에 대비가 없다 |

---

## SEC-01 (높음) ngrok 경로에서 X-Forwarded-For 첫 값을 신뢰해 브루트포스 차단을 우회할 수 있다

- 위치: `core/GitServer.kt:164`, `core/Auth.kt:33-56`, `core/Auth.kt:69,75`
- 문제:
  ```kotlin
  val clientIp = if (viaNgrok) call.request.header("X-Forwarded-For")?.substringBefore(',')?.trim() ?: remoteAddress else remoteAddress
  ```
  레이트리미터(F-03 AC6, 1분 10회 → 5분 차단)의 키가 이 `clientIp`다. 일반적인 리버스 프록시는 클라이언트가 보낸 X-Forwarded-For 뒤에 자기가 본 IP를 **덧붙인다**(append). 그러면 **첫 값은 공격자가 고른 값**이고, 신뢰할 수 있는 값은 마지막 값이다. ngrok의 정확한 동작은 문서로 확인하지 못했다. 직접 요청을 보내 1회 실측하면 확정된다(아래 참고).
- 공격 시나리오: 공개 인터넷의 공격자가 ngrok URL(`*.ngrok-free.app`)을 상대로 요청마다 `X-Forwarded-For: <무작위 IP>`를 붙여 `Authorization: Basic …`을 바꿔 가며 시도한다. 매번 다른 IP로 집계되므로 차단이 걸리지 않는다. 사용자가 직접 정한 8자 비밀번호(`Settings.kt:83`의 최소 길이)라면 사전 공격이 현실적이다. 성공하면 모든 레포 읽기와 push(force-push, 브랜치 삭제, 새 레포 생성)가 가능하다. 덤으로 접속 기록(F-23)과 `lastPushIp`, `PushEvent.clientIp`의 IP도 마음대로 위조할 수 있다.
  - 반대로 ngrok이 헤더를 보내지 않는 경우에는 모든 ngrok 클라이언트가 `127.0.0.1` 하나로 묶인다. 그러면 공격자 한 명이 실패 11번으로 정상 사용자 전원을 5분씩 반복해서 막을 수 있다.
- 권장 수정:
  1. ngrok이 붙인 **마지막** 값(`substringAfterLast(',')`)을 쓴다. 이 값은 ngrok만 넣을 수 있다. 또는 ngrok-go 쪽에서 원격 주소를 전용 헤더로 넘기고, 클라이언트가 보낸 같은 이름의 헤더는 지운다.
  2. IP별 제한에 더해 **ngrok 경로 전체에 대한 전역 실패 상한**을 둔다(예: 분당 30회를 넘으면 모든 ngrok 인증 시도에 지연). 계정이 하나뿐인 앱이므로 전역 제한이 IP 제한보다 효과적이다.
  3. 실측 방법: 터널을 켜고 `curl -H 'X-Forwarded-For: 1.2.3.4' https://<ngrok>/api/v1/info -u x:y`를 보낸 뒤 접속 기록에 찍힌 IP를 본다.

## SEC-02 (중간) 인증 없이 PBKDF2를 계속 돌리게 해 CPU와 배터리를 소모시킬 수 있다

- 위치: `core/Auth.kt:74,78-90`, `core/Auth.kt:12`(120,000회), `core/GitServer.kt:170-171`
- 문제: 틀린 `Authorization` 헤더 하나마다 PBKDF2-HMAC-SHA256 120k회가 요청 처리 코루틴 안에서 동기로 실행된다(`withContext(Dispatchers.IO)`도 없다). 성공한 헤더만 캐시되므로 실패는 매번 전부 계산된다. 동시에 몇 개를 돌릴지 막는 장치는 IP별 레이트리미터뿐이고, 이것은 SEC-01로 우회된다.
- 공격 시나리오: ngrok URL에 무작위 Basic 헤더를 단 요청을 수십 개씩 동시에 보낸다. 폰의 모든 코어가 해시 계산에 묶인다. 정상 clone, push, 웹 요청이 멈추고 발열과 배터리 소모가 이어진다. 앱은 wake lock을 잡고 있어 화면이 꺼져 있어도 계속 돈다. 인증 정보는 필요 없다.
- 권장 수정: 해시 검증에 전역 `Semaphore(1~2)`를 두고, 기다리는 요청이 일정 수를 넘으면 곧바로 429를 돌려준다. SEC-01의 전역 실패 상한도 함께 둔다. 검증은 IO 디스패처에서 돌린다.

## SEC-03 (중간) "같은 네트워크 익명 읽기"가 실제로는 ngrok을 뺀 모든 경로에 적용되고, DNS 리바인딩에도 열려 있다

- 위치: `core/Settings.kt:43`, `core/Auth.kt:72`, `core/GitServer.kt:188-192`(Host 헤더를 검증 없이 사용)
- 문제:
  - 익명 허용 조건은 `!isPush && !viaNgrok && effectiveAnonymousRead`뿐이다. **원격 주소가 사설 대역인지 보지 않는다.** 이름은 "OnLan"이지만 셀룰러 IPv6(SEC-04), 공용 와이파이, Tailscale에서 공유받은 노드, 사용자가 따로 띄운 Tailscale Funnel이나 리버스 프록시(127.0.0.1로 들어오는 경로)까지 모두 익명 읽기가 된다.
  - Host 헤더를 검증하지 않는다. 그래서 **DNS 리바인딩**이 그대로 통한다.
- 공격 시나리오(DNS 리바인딩): 사용자가 익명 읽기를 켠 상태에서, 같은 와이파이에 있는 누군가(본인 포함)가 공격자 웹 페이지를 연다. 페이지가 `evil.example`을 `192.168.x.y`로 다시 바인딩하고 `:8080/api/v1/repos`, `/tree`, `/raw`를 same-origin으로 읽어 공격자 서버로 보낸다. 비공개 레포를 토큰으로 미러한 내용도 함께 빠져나간다. 기본 포트(8080)와 /24 대역 스캔은 공개된 도구(Singularity 등)로 자동화되어 있다. 익명 읽기를 끈 경우에는 브라우저가 Basic 자격 증명을 다른 origin에 보내지 않으므로 해당하지 않는다.
- 권장 수정:
  1. 익명 허용 조건에 `remoteAddress`가 사설 대역(RFC1918), 링크 로컬, Tailscale CGNAT(100.64/10)일 것을 추가한다. 루프백과 그 밖의 주소는 인증을 요구한다.
  2. `Host` 헤더 허용 목록을 둔다. 현재 인터페이스 IP:port, `localhost`, `127.0.0.1`, ngrok 호스트만 허용하고, 나머지는 421이나 403으로 막는다. 리바인딩은 이것 하나로 막힌다.

## SEC-04 (중간) 0.0.0.0 바인딩이 셀룰러나 공용 망의 IPv6 전역 주소까지 열 가능성이 있다 (실측 필요)

- 위치: `core/GitServer.kt:100,110`, `app/Platform.kt:218-222`(IPv4만 표시), PRD F-01 AC6, NFR "IPv6는 표시 대상에서 뺀다"
- 문제: JDK와 Android NIO는 IPv6 소켓에 `0.0.0.0`을 바인딩할 때 이를 `::`(듀얼 스택)로 바꾸는 경우가 많다. 그러면 모바일 데이터(`rmnet*`)의 **전역 IPv6 주소**에서도 평문 HTTP로 서버가 열린다. 국내 이통 3사는 단말에 IPv6를 준다. 들어오는 연결을 통신사가 막는지는 망마다 다르다. 이 경로는 화면에 표시되지 않고, 접속 기록에는 `OTHER`로만 남으며, SEC-03 때문에 익명 읽기까지 적용된다. 사용자는 "와이파이에서만 열려 있다"고 믿는다.
- 공격 시나리오: IPv6 스캐너나 히트리스트가 폰의 주소를 찾아내 `:8080`에 접속한다. 익명 읽기가 켜져 있으면 전체 레포가 유출된다. 꺼져 있으면 평문 Basic 인증 브루트포스가 가능하다(IP별 제한은 IPv6 /64 안에서 주소를 바꾸면 무력화된다).
- 확인: 서버를 켠 상태에서 `adb shell ss -ltn | grep 8080`의 출력이 `[::]:8080`인지 `0.0.0.0:8080`인지 본다.
- 권장 수정: 앞의 경우라면 (a) 바인딩 주소를 IPv4로 고정하거나 Wi-Fi·핫스팟·Tailscale 인터페이스 주소별로 따로 바인딩한다. 또는 (b) 원격 주소가 사설·Tailscale·루프백이 아니면 무조건 인증을 요구하고, 접속 기록에 경고 배지를 단다. 어느 쪽이든 IPv6 노출 여부를 설정 도움말에 적는다.

## SEC-05 (중간) `http://` 미러 URL에도 호스트가 같은 토큰을 붙여 평문으로 보낸다. 외부 앱이 자동 probe를 일으킬 수 있다

- 위치: `core/RepoNames.kt:150-151`(http 허용), `core/Credentials.kt:41-44`(`effective`: 스킴 무시, 호스트만 비교), `core/Sync.kt:280-283`, `ui/screens/AddScreens.kt:172-178`(URL이 정해지면 자동 probe), `app/MainActivity.kt:37`(exported, `ACTION_SEND`), `AndroidManifest.xml`의 `usesCleartextTraffic="true"`
- 문제: `http://github.com/owner/repo`처럼 스킴만 http인 URL도 정규화를 통과한다. 그 뒤 `forHost("github.com")`로 저장된 토큰이 자동으로 붙는다. JGit은 401을 받으면 Basic(`x-access-token:<PAT>`)을 보낸다. http 미러는 경고 없이 등록되고, 주기 업데이트 때마다 평문으로 나간다. GitLab 셀프호스트의 `http_url_to_repo`(Bulk)도 같다.
- 공격 시나리오:
  1. 카페 와이파이의 중간자가 `http://github.com/...` 요청에 401을 돌려주고 GitHub PAT를 받아 간다.
  2. 같은 폰에 있는 악성 앱은 exported된 `MainActivity`에 `ACTION_SEND` + `EXTRA_TEXT="http://github.com/a/b"`를 **명시적 인텐트로** 보낼 수 있다(공유 시트 선택이 필요 없다). 화면이 열리면 사용자가 아무것도 누르지 않아도 probe가 저장된 토큰을 달고 평문 요청을 보낸다. 같은 망의 공격자와 조합하면 토큰이 탈취된다.
- 권장 수정: 저장된 자격 증명은 `https` URL에만 자동으로 붙인다(`effective`와 `probe`에서 스킴을 확인한다). http URL은 등록 화면에 "평문" 경고를 띄우고 자격 증명 선택을 막는다. 인텐트로 들어온 텍스트는 사용자가 확인을 누르기 전까지 probe하지 않는다. 가능하면 `network_security_config`로 cleartext를 `127.0.0.1`과 사설 대역에만 허용한다.

## SEC-06 (낮음) 팩 슬롯 4개를 느린 요청으로 붙잡을 수 있다

- 위치: `core/GitServer.kt:88-89,314-321`, `:215-219`(UploadPack에 `setTimeout` 없음), `:371`(ReceivePack도 마찬가지), `:112`(idle 120초)
- 문제와 시나리오: 읽기 권한이 있는 쪽(익명 읽기 켜짐 또는 계정 보유)이 `git-upload-pack` POST 4개를 열고 몇 초마다 1바이트씩 보낸다. idle 타이머가 계속 초기화되어 슬롯 4개가 오래 점유되고, 정상 clone은 5분 대기 후 503을 받는다. gzip 요청 본문(`:209-213`)은 해제 후 크기에 상한이 없다. 그래서 작은 요청 하나로 큰 `have` 협상 부하를 줄 수 있다. 전체 동시 연결 수에도 상한이 없다.
- 권장 수정: `UploadPack.setTimeout(60)`, `ReceivePack.setTimeout(...)`을 설정한다. 요청 본문은 해제 후 크기 상한(upload-pack 기준 수 MB)이 있는 스트림으로 감싼다. 인증되지 않은 동시 연결 수를 제한한다.

## SEC-07 (낮음) 레이트리미터 맵이 무제한으로 커진다

- 위치: `core/Auth.kt:34-35,46,55`
- 문제: `failures`와 `blockedUntil`은 IP별 항목을 성공할 때만 지우고, 오래된 항목을 정리하지 않는다. SEC-01(헤더 위조)이나 IPv6 주소 순회와 겹치면 요청마다 `ArrayDeque`가 하나씩 늘어 장기적으로 힙이 고갈된다(`largeHeap` 앱이라도 결국 OOM).
- 권장 수정: 크기 상한이 있는 LRU를 쓰거나, 주기적으로 window가 지난 항목을 정리한다. IPv6는 /64 단위로 키를 잡는다.

## SEC-08 (낮음) 평문 LAN에서 탈취한 계정으로 로컬 레포 히스토리를 보존 없이 지울 수 있다

- 위치: `core/GitServer.kt:363-398`(ReceivePack 기본값: non-fast-forward 허용, 삭제 허용), PRD NFR "서버는 평문 HTTP"(문서화된 수용 위험)
- 문제: 평문 노출 자체는 PRD에 적힌 결정이다. 다만 그 결과가 생각보다 크다. 같은 와이파이에서 Basic 헤더를 한 번 엿보면 계정이 하나뿐이므로 push 권한이 통째로 넘어간다. 미러는 push가 막혀 있지만, **로컬 레포와 fork 사본**은 force-push나 브랜치·태그 삭제로 히스토리를 지울 수 있다. 미러와 달리 `refs/archive` 보존도 없고, fork 사본에 복사된 `refs/archive/*`도 push로 지울 수 있다. 인증을 끈 상태(F-03 AC4)라면 같은 망의 누구나 할 수 있다.
- 권장 수정: 로컬 레포에도 non-fast-forward 갱신이나 삭제 전에 `refs/archive/`로 보존하는 pre-receive 훅을 붙인다(F-11과 같은 코드). `refs/archive/*`에 대한 push는 거부한다. 설정 도움말에 "공용 와이파이에서는 Tailscale 사용"을 명시한다.

## SEC-09 (낮음) 원격이 준 링크나 리다이렉트를 따라가면 토큰이 다른 호스트나 http로 따라갈 수 있다

- 위치: `core/Bulk.kt:107-112,119-122,137-140`, `core/MirrorEngine.kt:88-90,160-163`
- 문제: Bulk 목록을 가져올 때 응답의 `Link: <…>; rel="next"`를 호스트나 스킴 검사 없이 그대로 다음 요청 URL로 쓰고, 같은 `Authorization`/`PRIVATE-TOKEN` 헤더를 붙인다. JGit 전송도 `UsernamePasswordCredentialsProvider`가 URI를 확인하지 않으므로, 리다이렉트된 호스트가 401을 주면 같은 토큰을 내준다(JGit 기본값 `http.followRedirects=initial`).
- 시나리오: 손상된 셀프호스트 GitLab이나 중간 프록시가 `Link`를 `http://attacker/…`로 바꾸면 토큰이 외부로 나간다. github.com이나 gitlab.com 자체가 그러는 상황은 현실적이지 않아서 낮음으로 분류했다.
- 권장 수정: `next` URL은 첫 요청과 스킴·호스트·포트가 같을 때만 따라간다. JGit에는 URI를 확인하는 `CredentialsProvider`(`supports`/`get`에서 호스트와 https 확인)를 쓴다.

## SEC-10 (낮음) 500 응답에 내부 예외 메시지가 그대로 나간다

- 위치: `core/GitServer.kt:473-474`
- 문제: `Redact.text(e.message)`는 자격 증명만 지운다. `/data/user/0/com.ouor.mobilegit/files/repos/...` 같은 내부 경로, JGit 내부 상태, 패키지 구조는 익명이나 ngrok 사용자에게도 그대로 보인다. 단독으로 악용하기는 어렵다.
- 권장 수정: 클라이언트에는 고정 메시지와 짧은 오류 ID만 보내고, 상세는 앱 내부 로그에 남긴다.

## SEC-11 (낮음) `commits?page=` 상한이 없다

- 위치: `core/RepoBrowser.kt:245-258`, `core/GitServer.kt:447`
- 문제: `page=2000000000`이면 `(pg-1)*per`에서 Int 오버플로가 나 결과는 첫 페이지로 돌아오지만, 반대로 큰 정상 범위 값(예: `page=1000000`)이면 커밋 그래프 전체를 걷는다. path 필터까지 붙이면 diff 계산이 겹쳐 리눅스 커널 규모의 미러에서는 요청 하나가 수십 초씩 CPU를 쓴다. 읽기 권한만 있으면 된다.
- 권장 수정: `page`의 상한을 정하고(예: 1000), 계산은 Long으로 한다. 요청별 처리 시간이나 커밋 수에 상한을 둔다.

## SEC-12 (낮음) 미러 fetch는 fsck를 하지 않고, 소스 zip은 git bomb에 대비가 없다

- 위치: `core/MirrorEngine.kt:88-99`(fetch: `fsckobjects` 설정 없음. push 쪽만 `RepoStore.kt:373`에서 켬), `core/Ops.kt:118-135`
- 문제: 공격자가 만든 공개 레포를 미러하면 손상되거나 악의적인 객체가 검증 없이 저장된다. 웹 화면은 checkout을 하지 않으므로 경로 조작(`..`, `.git/`) 위험은 없다. 다만 지수적으로 커지는 트리(git bomb)를 "소스 zip 내보내기"하면 재귀 TreeWalk가 끝나지 않고 저장소를 다 채운다(사용자가 직접 시작하는 작업이라 낮음).
- 권장 수정: fetch에 `transfer.fsckobjects`를 켠다(`Transport.setCheckFetchedObjects(true)`). zip 내보내기에는 항목 수와 총 바이트 상한을 둔다.

---

## 잘 된 점

- **ngrok 전용 루프백 커넥터**(`GitServer.kt:111,163`)로 ngrok 트래픽을 확실히 구분하고, ngrok이 켜지면 인증을 강제하고 익명 읽기를 끈다(`Settings.kt:42-43`, `Auth.kt:72`). 헤더가 아니라 포트로 구분하므로 위조할 수 없다.
- **비밀번호 저장**: 솔트를 넣은 PBKDF2-SHA256 120k, `MessageDigest.isEqual`로 상수 시간 비교, 첫 실행 때 16자 무작위 비밀번호(약 93비트), 성공 캐시는 해시 변경 시 무효화.
- **비밀 저장**: Keystore AES-256-GCM, 백업과 기기 이전에서 전부 제외, FileProvider는 export하지 않고 `cache/exports/`만 공유, `Log.*`로 비밀을 출력하는 곳 없음, `Redact`로 URL·헤더·알려진 토큰 제거, 접속 기록에 Authorization 미저장.
- **경로 조작 방어**: 레포 이름 정규식(`^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$`, `/` 없음), API 경로 `normPath`의 `.`/`..` 거부, 정적 파일 `..` 거부. 웹은 checkout 없이 git 객체만 읽으므로 심볼릭 링크로 파일시스템에 접근할 수 없다.
- **웹 XSS 방어 여러 겹**: SPA에 `script-src 'self'` 등 엄격한 CSP와 `frame-ancestors 'none'`. README는 commonmark `sanitizeUrls` 다음에 jsoup allowlist(스크립트, 이벤트 핸들러, style 없음, `code[class]`는 `language-*`만). highlight.js 출력은 이스케이프됨. raw는 이미지 확장자만 inline이고 나머지는 attachment이며, `nosniff`와 `sandbox` CSP가 붙어 SVG를 직접 열어도 스크립트가 돌지 않는다. JSON은 `nosniff` 덕분에 `script-src 'self'`로 우회 실행할 수 없다.
- **CSRF**: API는 GET 전용이고 상태를 바꾸지 않는다. git POST는 `Content-Type`을 정확히 검사하므로 다른 origin에서는 preflight가 필요하고, CORS 헤더가 없어서 막힌다.
- **push 안전장치**: `receive.fsckobjects=true`, 원자적 ReceivePack, 남은 공간 기준 팩 크기 제한, 미러 push는 advertisement 단계에서 거부.
- **미러 URL**: `normalize`가 http(s) 외의 스킴(`file://`, `ssh`, `git://`)을 거부하고, userinfo(URL에 섞인 자격 증명)를 버린 뒤 URL을 다시 조립한다. 미러 추가는 웹/API로 할 수 없고 폰 UI에서만 가능하므로 원격 SSRF 경로가 없다.
- **매니페스트**: `allowBackup=false`. `BootReceiver`는 보호 브로드캐스트 action만 처리. 알림 인텐트의 라우팅 인자는 `Uri.encode`로 감싸 경로 주입이 안 된다.

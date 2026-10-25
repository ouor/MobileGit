# F-26 웹에서 미러 추가 QA

- 기준: `docs/planning/prd.md` 15.4 QA-W1~QA-W14, API는 `docs/core/spec.md` 3.1
- 빌드: 코어 d582e0e~dcbc554, 웹 ce0875a, 앱 10a42e7 (v0.2.1 디버그). `ANDROID_SERIAL=d418f2d1 ./gradlew installDebug`로 d418f2d1에만 설치했다 ("Installed on 1 device").
- 기기: 테스트폰 d418f2d1만 썼다. adb는 모두 `-s d418f2d1`이다. 무선으로 붙어 있던 S25는 건드리지 않았다.
- 도구:
  - 웹: PC Chromium(Playwright)
  - API: Python `http.client`로 Host·Origin·Content-Type을 직접 지정
  - 앱: `docs/qa/round1/tools/ui.py`
- 스크린샷: `docs/qa/f26/screenshots/` (README에 목록)

## 요약

통과 12, 부분 3 (QA-W3, QA-W11, WM-05 대기·실패 표시), 실패 0. 새 결함은 없다 (F26-xx 없음).

## 결과

| # | 결과 | 근거 |
|---|---|---|
| QA-W1 정상 흐름 | 통과 | 목록의 "미러 추가" → `/add`에 `https://github.com/sindresorhus/is-plain-obj/tree/main`을 붙여 넣었다. 0.5초 뒤 자동 확인: `…/is-plain-obj.git`, "브랜치·태그 22개 받기 · PR 브랜치 제외 · Git LFS 파일과 서브모듈은 받지 않아요", 기본 브랜치 main, 이름 칸. "추가" → `/r/is-plain-obj`로 이동, "받는 중" 배지와 "원본에서 받는 중이에요 · 끝나면 자동으로 보여 줘요"(샷 03) → 새로고침 없이 파일 트리와 readme로 바뀜(샷 04). 앱: 목록에 is-plain-obj, 상세 만든 방법 "웹에서 추가 · 2026.10.25 23:23"(샷 05), meta.json `createdBy: "WEB"`. 접속 기록: `MIRROR_ADD is-plain-obj OK 201`, 앱 화면에 "웹에서 미러 추가 · is-plain-obj". 헤더 문구는 "웹에서는 보기와 미러 추가만 할 수 있어요". 폰 알림은 오지 않았다 (D-17) |
| QA-W2 앱 닫고 화면 끔 | 통과 | 최근 앱에서 mobilegit 카드를 지우고 홈 → 전원 버튼으로 화면 끔 (`mWakefulness=Dozing`). 서버 프로세스는 살아 있다. PC 브라우저에서 GitLab 레포를 등록했다. 1.7초에 레포 홈 "받는 중"(샷 15), 6.7초에 파일 트리. `waiting`(FOREGROUND) 안내는 나오지 않았다. WM-00 결과(서버가 켜져 있으면 바로 시작)와 같다. Doze 강제 진입은 하지 않았다 |
| QA-W3 GitLab · Tailscale | 부분 | GitLab: `https://gitlab.com/pages/plain-html/-/tree/main` → `…/plain-html.git`, "브랜치·태그 215개 받기 · PR 브랜치 제외"(MR 213개 제외), 등록 성공, 파일 트리 표시(샷 14). Tailscale: 폰의 Tailscale이 꺼져 있다 (VPN 인터페이스 없음). 폰 시스템 VPN을 켜는 것은 하지 않았다. 대신 와이파이로 `Host: 100.79.37.5:8080`, `Host: devphone01.tail1234.ts.net:8080`(+ 같은 Origin)을 보냈고 둘 다 200이다. 실제 Tailscale 경로는 확인 불가 |
| QA-W4 비밀번호 3단계 | 통과 | 항상: 비밀번호 없이 preview·등록 모두 401 + `WWW-Authenticate: Basic realm="mobilegit"`, 비밀번호 있으면 201. push할 때만(`anonymousReadOnLan` true): 비밀번호 없으면 preview·등록 401, 있으면 201(qa-w4-push). 익명 브라우저로 `/add`를 열면 확인 단계에서 "미러를 추가하려면 이 폰의 비밀번호가 필요해요. / 401"과 버튼 옆 "추가하려면 비밀번호가 필요해요", "추가" 비활성(샷 07). 안 함(`authEnabled` false): 익명 등록 201(qa-w4-off), 접속 기록 `ANONYMOUS 201`. 끝난 뒤 "항상"으로 되돌림 |
| QA-W5 ngrok 거부 | 통과 | ngrok 전용 루프백 포트(43057)는 폰에서 `nc -z`로 훑어 찾았다. ngrok 꺼짐, 그리고 가짜 토큰 `qa-fake-token`으로 켬(터널 실패 "ngrok이 토큰을 거부했어요") 두 상태를 봤다. 항상 / push할 때만 / 안 함 세 단계 모두 폰에서 그 포트로 보낸 POST가 403 `EXTERNAL_NOT_ALLOWED`이다. `WWW-Authenticate` 헤더가 없어 비밀번호 창이 뜨지 않는다. 틀린 비밀번호를 붙여도 403이다. 같은 요청을 8080으로 보내면 "안 함"에서 200이다. 레포는 생기지 않았다. `adb forward`로 그 포트를 PC에서 열면 웹 화면에 "외부 공개(ngrok) 주소에서는 미러를 추가할 수 없어요. 같은 와이파이나 Tailscale 주소로 접속하세요. / EXTERNAL_NOT_ALLOWED"가 나온다(샷 09). 끝난 뒤 토큰 지움, ngrok 꺼짐, "항상"을 settings.json으로 확인 |
| QA-W6 CSRF (브라우저) | 통과 | 다른 출처 시험 페이지 `http://127.0.0.1:8765`에서 보냈다. 브라우저는 먼저 폰 페이지를 비밀번호로 열어 자격 증명을 기억한 상태다. (a) form urlencoded와 (a') form `text/plain`(JSON 모양 본문)은 403 `CROSS_SITE` "본문은 application/json이어야 합니다". (b) `fetch` JSON(credentials include)은 사전 요청 OPTIONS가 405이고 CORS 헤더가 없어 "Failed to fetch". (c) `fetch` no-cors(text/plain)는 opaque 응답이고 서버에서 403 `CROSS_SITE`. 레포는 생기지 않았다. 접속 기록에 `MIRROR_ADD ANONYMOUS 403 CROSS_SITE` 3건이 남았다 (b는 본 요청이 가지 않음). curl로 따로 본 것: `Origin: http://evil.example`, `Origin: null`, 같은 호스트의 https Origin, 다른 포트 Origin, `Sec-Fetch-Site: cross-site`/`same-site`는 모두 403 `CROSS_SITE`. 같은 출처 Origin과 `application/json; charset=utf-8`은 통과 |
| QA-W7 DNS 리바인딩 | 통과 | `Host: evil.example`, `evil.example:8080`, `phone.example.com`, `127.0.0.1.nip.io:8080`은 403 `CROSS_SITE` "폰 주소로 직접 연 페이지에서만 추가할 수 있습니다". 직접 주소 규칙대로 점 없는 이름(`phone:8080`)과 `x.local:8080`은 허용된다 (spec 3.1, `isDirectHost`) |
| QA-W8 https만 | 통과 | `http://github.com/…`는 확인·등록 모두 400 `HTTPS_ONLY`. 리디렉션: `https://http.badssl.com/a/b.git`은 유효한 인증서의 https에서 같은 경로의 http로 301을 준다 (PC curl로 확인). 확인과 등록 모두 400 `HTTPS_ONLY` "https가 아닌 주소로 넘어가서 추가하지 않았습니다". 레포는 생기지 않았다. 코어가 코드로만 처리했던 부분을 실제 원격으로 확인했다 |
| QA-W9 여러 줄·토큰 필드 | 통과 | 줄바꿈 있는 `url`은 400 `MULTIPLE_URLS`. 본문의 `token`과 `Password`(대소문자 무시)는 400 `TOKEN_NOT_ACCEPTED`. 그 밖에: JSON이 아님과 배열 본문은 400 `BAD_REQUEST`, `url` 없음과 "hello world"는 400 `UNSUPPORTED_URL` |
| QA-W10 중복·이름·저장 공간 | 통과 | 이미 미러됨: 등록 409 `ALREADY_MIRRORED` + `repo`. 웹은 "이미 보존 중이에요 / Hello-World 레포로 가기", "추가" 비활성(샷 11). 이름 충돌: `chalk`, `CHALK` 모두 409 `NAME_TAKEN`. 규칙 위반: `-bad name`은 400 `INVALID_NAME` "이름은 영문이나 숫자로 시작해야 합니다". 저장 공간: 앱의 선택지(최대 5GB)로는 남은 공간 98GB 아래로 못 내린다. 그래서 앱을 멈추고 settings.json의 `storageFloorBytes`만 200GB로 바꿨다. 결과는 등록 507 `STORAGE_LOW`, 웹 "폰의 남은 공간이 최소 여유 공간보다 적어서 추가할 수 없어요. / STORAGE_LOW"(샷 12). 원래 파일로 되돌렸고 바이트 단위로 같음을 확인했다 |
| QA-W11 비공개 레포 | 부분 | 앱에 저장된 원본 토큰이 0개이고 실제 비공개 레포·토큰이 없다. 그래서 (a) 토큰 있음 + 비밀번호 접속 성공과 (b) 토큰 있음 + 익명은 확인 불가. 없는 GitHub 레포(비공개와 같은 401)로 (c)를 봤다. 비밀번호로 접속하면 확인 `access: TOKEN_REQUIRED`, 등록 403 `TOKEN_REQUIRED`, 웹 문구 "비공개 레포예요. … 토큰을 등록한 뒤 다시 시도하세요."(샷 13). "안 함" 익명이면 같은 코드에 "비밀번호 없이 접속하면 저장된 토큰을 쓸 수 없어요. 앱에서 비밀번호 요구를 켜고 비밀번호로 접속하세요."가 더해진다(샷 08) |
| QA-W12 느린 원격·동시 3개 | 통과 | 응답 없는 주소 `https://10.255.255.1~3/a/b.git`으로 확인 3개를 0.3초 간격으로 보냈다. 3번째는 1.0초에 503 `BUSY`. 1·2번째는 20.4초, 20.7초에 200 `access: FAILED, failure: TIMEOUT`. 그 뒤 5초 간격으로 보낸 확인은 모두 바로 200이다 (자리가 남지 않음) |
| QA-W13 회귀 | 통과 | 앱: 공유 시트로 `https://github.com/sindresorhus/is-png` → "다른 앱에서 받은 주소예요" → 연결 확인 "브랜치·태그 9개 받기 · PR 브랜치 4개 제외" → 추가, `createdBy: MIRROR_ADD`, 첫 받기 성공. 지금 업데이트 → `MANUAL NO_CHANGE`. PC: is-png clone 성공. 미러로 push는 거부(읽기 전용 안내). 새 이름으로 push하면 로컬 레포 qa-f26-push 생성. 웹: GET info, overview, repos, repos/{name}, tree, commits, readme, blob, raw, archives 모두 200. 페이지 `/`, `/r/chalk`, `/r/chalk/commits`, `/add` 200. CSP·`nosniff`·`Referrer-Policy` 헤더는 spec 4장과 같다. 앱 crash 버퍼 0줄 |
| QA-W14 다른 쓰기 없음 | 통과 | `/api/v1/repos`, `/repos/chalk`, `/repos/chalk/update`, `/mirrors/chalk`, `/settings`에 POST·PUT·DELETE·PATCH → 모두 404. `/mirrors`와 `/mirrors/preview`는 POST만 받고 나머지는 405. OPTIONS도 405이고 `Access-Control-*` 헤더가 없다. 웹 코드에서 POST는 `lib/api.ts`의 `postJson` 하나이고, 부르는 곳은 `pages/AddMirror.tsx`의 확인과 등록 두 군데뿐이다 |
| WM-05 대기·실패 표시 | 부분 | "받는 중"과 자동 전환은 확인했다 (QA-W1, QA-W2). 확인 불가 항목은 셋이다. 첫째, 서버가 켜진 상태에서는 첫 받기가 바로 시작돼 `waiting: FOREGROUND` 안내("폰에서 mobilegit 앱을 열면…")를 만들 수 없었다. 둘째, 저장 공간 하한 아래에서는 등록 자체가 507이라 `waiting: STORAGE`가 생기지 않는다. 셋째, 확인은 통과하고 첫 받기만 실패하는 원격을 만들지 못해 첫 받기 실패 표시(AC4)를 보지 못했다 |

## 관찰 (결함 아님)

1. **401 도전 응답은 접속 기록에 남지 않는다.** 비밀번호 없이 보낸 등록(401 도전)은 남지 않고, 틀린 비밀번호는 `MIRROR_ADD FAILED 401`로 남는다. 기존 GET·git과 같은 동작이라 결함으로 보지 않았다. PRD WM-02 AC6 "결과에는 성공 또는 거부 코드"를 401에도 적용할지는 PM 판단이다.
2. **터널이 실패한 상태의 ngrok 포트 요청은 경로가 "이 폰"(LOCALHOST)으로 남는다.** 시험 요청이 실제로 폰 안(adb)에서 왔기 때문이다. 실제 터널로 들어온 요청이 "ngrok" 경로로 남는지는 유효 토큰이 없어 확인 불가.
3. **확인 단계는 저장 공간 하한을 보지 않는다.** 하한 아래에서도 "추가할 수 있어요"가 나오고, 등록할 때 507이다. spec 3.1의 "등록만"과 같다.
4. **첫 받기 중인 레포 홈에서 404 요청이 한 번 나간다.** 브라우저 콘솔에 "Failed to load resource: 404"가 한 줄 남는다 (접속 기록 `WEB is-plain-obj 404`). 화면은 "받는 중"으로 정상이다. 담당: 웹, 낮음.
5. **기존 설정 동작 (F-26 밖):** ngrok이 켜진 상태에서 "push할 때만" → "안 함"으로 바꿀 때는 일반 "안 함" 확인만 나오고 ngrok 경고 대화상자는 나오지 않는다. "push할 때만"으로 바꿀 때 이미 노출 경고를 거쳤기 때문이다 (`becomesExposed`). 이 상태의 "안 함" 설명은 "(외부 공개를 켜면 인터넷의 누구나)"인데, 외부 공개는 이미 켜져 있다. 2차 설계 그대로라 기록만 한다.
6. **PC 8080 포트의 python:** `python -m http.server 8080 --bind 127.0.0.1` (PID 30288)이다. 22:26에 Claude 데스크톱 앱(claude.exe)이 직접 띄웠다. QA 원본 서버(`origin_server.py`, 9418)가 아니고 다른 세션의 미리보기 서버로 보여 종료하지 않았다. 폰 서버(192.168.31.120:8080)와는 겹치지 않는다. 필요하면 띄운 세션에서 정리해야 한다.
7. **이번 QA 전에 있던 시험 흔적:** 접속 기록에 23:06~23:20의 `MIRROR_ADD` 기록이 있다 (LOCALHOST에서 is-plain-obj 등록·NAME_TAKEN, 와이파이 CROSS_SITE 1건). 코어·웹 개발 중 시험으로 보이며, 이 QA와 무관하다.

## 정리

- 만든 테스트 레포 6개(is-plain-obj, qa-w4-push, qa-w4-off, qa-storage, qa-w2-gitlab, is-png)와 push로 만든 qa-f26-push를 모두 지웠다. 디버그 진입점 `DEBUG_ADD_MIRROR --es delete`를 썼다.
- 레포 15개가 남았다 (overview 미러 12 · 정상 12 · 주의 0 · 로컬 3).
- settings.json: 비밀번호 요구 항상(익명 읽기 꺼짐), ngrok 꺼짐·토큰 없음, 포트 8080, 서버 켜짐, 최소 여유 공간 1GB.
- adb forward와 reverse는 없다. PC의 시험 페이지 서버(8765)는 끝났다. PC 작업 폴더는 지웠다.

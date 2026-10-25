# QA 1차 보고서

- 일시: 2026-10-25 10:40~11:35 · 담당: QA
- 대상: `main` `8af31d1` (assembleDebug로 다시 빌드해 설치, versionName 0.1.0)
- 기기: 테스트폰 `d418f2d1` (Xiaomi M2012K11AG, LineageOS, API 36), Wi-Fi 192.168.31.120. PC: Windows 11, git 2.54.0, Python Playwright(Chromium), 같은 Wi-Fi(192.168.31.101)
- 기준: `docs/planning/prd.md` F-01~F-25 수용 기준(AC)

## 1. 요약

| 결과 | 개수 | 기능 |
|---|---|---|
| 통과 | 15 | F-03, F-05, F-07, F-08, F-09, F-11, F-14, F-16, F-17, F-19, F-21, F-22, F-23, F-24, F-25 |
| 부분 | 6 | F-02, F-12, F-13, F-15, F-18, F-20 |
| 실패 | 2 | F-01 (포트 충돌 시 앱 크래시, 앱 업데이트 후 서버 복구 실패), F-06 (이미 미러된 URL 처리) |
| 확인 불가 | 2 | F-04 (ngrok 토큰 없음), F-10 (주기 작업을 강제로 돌릴 수 없음) |

발견한 문제 12건: 치명 1, 높음 1, 중간 3, 낮음 7. 담당별: 코어 6, 안드로이드 UI 5, 웹 UI 1 (QA-02는 코어 원인 + UI 표시 문제가 같이 있다).

가장 먼저 고칠 것: **QA-01**(포트 충돌로 앱 크래시), **QA-02**(앱 업데이트 뒤 서버가 꺼진 채 남고 영어 예외가 화면에 노출).

## 2. 테스트 방법

- **PC git**: `git -c credential.helper=` 로 캐시 없이 실행. 비밀번호는 설정 화면의 "비밀번호 재설정"으로 새로 정한 테스트 값(저장소에 넣지 않음).
- **테스트 원격 (원본 force-push 재현)**: `tools/origin_server.py` — PC에서 `git http-backend`를 CGI로 돌리는 smart HTTP 서버(127.0.0.1:9418). 폰은 `adb reverse tcp:9418 tcp:9418` 로 `http://127.0.0.1:9418/<repo>.git` 에 접속. `/private/` 아래는 Basic 인증(토큰 경로 검증용). 원본 조작은 PC에서 bare 레포에 직접 `git push -f`, 브랜치 삭제, 태그 이동, 디렉터리 이름 바꾸기(원본 삭제 재현).
- **앱 조작**: `tools/ui.py` (uiautomator 덤프로 텍스트를 찾아 탭), `tools/delete_repo.sh` (인텐트 extra로 상세를 열어 삭제). 단계마다 덤프로 결과를 바로 확인하는 편이 빨라 quick-qa 대신 직접 조작했다.
- **웹**: `tools/web_check.py`(XSS, 1MB 초과/바이너리/LFS, 경로 조작, CSP·raw 헤더, 외부 요청, 모바일 가로 스크롤), `tools/web_shots.py`(스크린샷).
- 테스트 후 정리: QA 레포와 웹 개발자의 `webdemo`를 지웠다. 지우기 전 앱의 전체 백업 zip을 `fixtures/phone-before-cleanup-backup.zip`에 남겼다(`webdemo.bundle` 포함, `git clone webdemo.bundle` 후 push로 복원 가능). 테스트 자격 증명과 ngrok 토큰도 지웠다. `부팅 후 서버 자동 시작`은 켠 상태로 두었다. 비밀번호는 QA가 바꾼 값이다(필요하면 설정에서 재설정).

## 3. 기능별 결과

| 기능 | 결과 | 근거 |
|---|---|---|
| F-01 서버 on/off | **실패** | AC1 통과: 켜고 2초 안에 응답, 상태 알림에 주소 + "서버 끄기". AC2 통과: 끄면 연결 거부, 알림 사라짐. **AC3 실패(QA-01)**: 포트 사용 중이면 앱이 죽는다. AC4 통과: Windows git 2.54로 clone(protocol v0, v2), fetch, pull, push. AC5 통과: `.git` 생략 URL도 같은 레포. AC6 통과: 폰 셸에서 `127.0.0.1:8080` 응답, 접속 기록 경로 LOCALHOST. AC7: 프로세스 kill, force-stop 후 앱 실행, 재부팅 후 복구는 통과. **앱 업데이트(`install -r`) 후에는 복구 실패(QA-02)** |
| F-02 접속 주소 | 부분 | 와이파이 대표 주소 크게 표시, 서버 꺼짐 안내, Tailscale 안내, 웹 주소 QR 통과. 핫스팟/Tailscale 주소, 네트워크 변경 갱신, 주소 없음 안내는 시스템 네트워크 설정을 바꿔야 해서 미확인 |
| F-03 Basic 인증 | 통과 | AC1: 인증 없이 clone/웹 401. AC3: 7자 비밀번호 거부, `settings.json`에는 PBKDF2 해시만. AC4: 인증 끄기 확인 대화상자 → 메인 "인증 꺼짐" 칩, 익명 push로 새 레포 생성. AC5: 익명 읽기 켜면 익명 clone·웹 허용, 익명 push 거부. AC6: 잘못된 비밀번호 11번째부터 429, 약 5분 차단 후 해제, 접속 기록 "차단됨". AC7: ngrok 켜면 두 옵션 비활성. (AC2 첫 실행은 미확인, 5절) |
| F-04 ngrok | 확인 불가 | 토큰 없음으로 확인 불가. 토큰 없이 확인한 것: 기본 꺼짐, 토큰 입력이 먼저, 첫 켜기 경고 문구가 PRD와 같음, 가짜 토큰 → "토큰 확인 필요"(재시도 안 함), 메인 배너 "외부 공개 끄기" 동작. 표시 문제는 QA-07 |
| F-05 레포 목록 | 통과 | 배지(`원본 없음`, `인증 필요`, `LFS 누락`, `업데이트 있음`), 이름순/최근순, 필터(개수 정확), 검색. `지연` 배지와 500개 성능은 미확인 |
| F-06 미러 추가 | **실패** | AC1: `/tree/main/styles` 주소 정규화, http 원격 등록 통과. AC2: 이름 제안. AC3: 비공개 원격 → "토큰 필요" → 토큰 추가 → "접근 가능". AC4: 등록 즉시 전체 받기. AC6: 공유 시트에서 URL 채움. AC7: URL 5개 → 5/5 완료. **AC8 실패(QA-03)**. AC5, AC9는 미확인 |
| F-07 원격 자격 증명 | 통과 | 호스트별 저장, 앞 4자만 표시, 토큰이 앱 파일·이력·logcat에 없음. 틀린 토큰 → `인증 필요` + 알림, 알림 탭 → 자격 증명 화면. 삭제 시 "이 토큰을 쓰는 미러가 2개 있어요" 확인 |
| F-08 미러 읽기 전용 | 통과 | push → `remote error: This repository is a read-only mirror of … Fork it in the mobilegit app to push.` 접속 기록에 PUSH 403 |
| F-09 수동 업데이트 | 통과 | 상세, 목록 메뉴, "모든 미러 업데이트" 모두 동작, 결과가 이력에 기록. AC5(업데이트 중 clone)는 미확인 |
| F-10 자동 확인/업데이트 | 확인 불가 | 설정 항목과 기본값은 PRD와 같다. `cmd jobscheduler run -f`로도 WorkManager가 "before schedule"로 미뤄 실제 주기 실행을 보지 못했다. 수동 "변경 확인만 하기"는 동작 |
| F-11 아카이브 보존 | 통과 | 테스트 원격에서 main force-push, dev 삭제, v1 태그 이동 → `refs/archive/20261025T014519Z/{heads/main,heads/dev,tags/v1}`가 이전 SHA로 남음. 이력 "3개 ref 보존", 알림 "force-push/삭제 감지". 원본 제거 → `원본 없음`, 데이터 유지, 복구 시 알림. `git clone --mirror`와 `refs/archive/*` fetch로 받아지고 일반 clone에는 없음. 상세에 받는 명령 표시 |
| F-12 이력·원본 상태·알림 | 부분 | 이력, 원본 카드, 상태가 바뀔 때만 알림 통과. AC5 `지연` 배지는 18시간 이상 기다려야 해서 미확인 |
| F-13 push로 생성 | 부분 | 새 이름 push로 생성(0.4초), HEAD 규칙(master/feature), 기본 브랜치 변경(ls-remote 확인), 대소문자 무시. **AC2 사유 없음(QA-08)**, LFS 레포 push 실패(QA-05) |
| F-14 빈 레포 | 통과 | 이름 오류("이름은 .git으로 끝날 수 없습니다", "이미 같은 이름의 레포가 있어요"), 명령 복사, 웹 빈 레포 안내 |
| F-15 일괄 미러 | 부분 | GitHub `octocat`(8개, fork 제외 6개), 조직 `chalk`(16개), `등록됨` 표시, 3개 등록 → 3/3 완료, 없는 계정·가짜 토큰 오류. GitLab, 레이트리밋 대기, 재시도는 미확인 |
| F-16 clone 주소·QR | 통과 | URL·명령 복사, QR 전체 화면(KEEP_SCREEN_ON, 밝기 1.0), URL에 계정 정보 없음 |
| F-17 저장 공간 | 통과 | 화면 항목 모두 표시. AC3(공간 부족)은 97GB 남아 미확인 |
| F-18 웹 화면 | 부분 | W-01~W-04 동작, 인증 없으면 401, 보존 ref는 ref 선택에 없음, 1.1MB·바이너리 "표시할 수 없어요" + 다운로드, LFS 포인터 표시, 커밋 50개씩, 한글·공백 경로, 404·빈 레포 화면, 외부 요청 0건, CSP, raw 응답 헤더, 스크립트 미실행, 경로 조작 400/404, 390px 가로 스크롤 없음. **README raw HTML이 글자로 보임(QA-04)** |
| F-19 내보내기 | 통과 | bundle을 SAF로 저장 → `git clone x.bundle` 성공(보존 ref 포함), push로 복원. 전체 백업 zip의 manifest 항목 확인, 비밀 없음. 소스 zip 정상 |
| F-20 분리(fork) | 부분 | 확인 문구, 기본 복제, 보존 ref 유지, push 성공, 분리 원본 표시. 전환(CONVERT)은 미확인. **복제 직후 빈 레포 카드(QA-06)** |
| F-21 삭제 | 통과 | 이름 입력 확인, 원본 없는 미러는 경고 + 내보내기, 데이터 제거 |
| F-22 백그라운드 | 통과 | 재부팅 → 약 6초 뒤 서버 응답. 화면 꺼짐(Dozing) 1분·5분 뒤 clone 성공. 배터리 안내 "샤오미" 먼저, 메인 칩. 8시간 테스트는 하지 않음 |
| F-23 접속 기록 | 통과 | 모든 항목, 필터, 비밀 없음, 로컬 상세에 마지막 push IP |
| F-24 LFS·서브모듈 | 통과 | `LFS 누락` 배지, 서브모듈 카드 + "미러 추가", 웹 LFS 포인터 |
| F-25 온보딩 | 통과 | "소개 다시 보기"로 4장 확인. 첫 실행 경로는 미확인(5절) |

비기능 일부: 같은 PC에서 `awesome` 30개 동시 clone 3.1초, 30/30 성공. 앱 자동 백업 꺼짐(ALLOW_BACKUP 없음).

## 4. 발견한 문제

| ID | 심각도 | 담당 | 제목 |
|---|---|---|---|
| QA-01 | 치명 | 코어 | 포트가 사용 중이면 앱 프로세스가 죽는다 |
| QA-02 | 높음 | 코어 (+UI) | 앱 업데이트 뒤 서버가 복구되지 않고 영어 예외가 메인에 노출 |
| QA-03 | 중간 | 안드로이드 UI (+코어) | 이미 미러된 URL이 "지원하지 않는 주소예요"로 나오고 기존 레포로 이동할 수 없다 |
| QA-04 | 중간 | 코어 | README의 raw HTML이 제거되지 않고 태그 글자 그대로 보인다 |
| QA-05 | 중간 | 코어 | git-lfs가 설치된 PC에서 LFS 레포 push 실패 |
| QA-06 | 낮음 | 안드로이드 UI | 분리(복제)한 레포가 "이 레포는 비어 있어요"로 보인다 |
| QA-07 | 낮음 | 안드로이드 UI | ngrok 토큰 거부 시 영어 원문 오류를 그대로 보여 준다 |
| QA-08 | 낮음 | 코어 | 잘못된 레포 이름 push에 사유가 없다 |
| QA-09 | 낮음 | 코어 | 미러 설명이 비어 있다 (W-01 설명 칸이 빈칸) |
| QA-10 | 낮음 | 안드로이드 UI | 미러 추가 화면 "남은 공간"이 추가 버튼 뒤로 가려진다 |
| QA-11 | 낮음 | 웹 UI | Clone 카드 URL이 줄임표 없이 글자 중간에서 잘린다 |
| QA-12 | 낮음 | 안드로이드 UI | 온보딩 복사 버튼 접근성 설명 오류 |

### QA-01 포트가 사용 중이면 앱 프로세스가 죽는다 (치명, 코어)
- 재현: 1) `adb shell "nc -L -p 9090 sh -c 'cat >/dev/null' &"` 2) 서버 실행 중 설정 → 포트 `9090` 저장, 또는 9090에서 메인 스위치로 서버 켜기
- 기대: 스위치가 꺼진 채 "포트 사용 중" 오류 (F-01 AC3)
- 실제: "mobilegit이(가) 계속 중단됨". 두 경로 모두 크래시
- 로그: `FATAL EXCEPTION: DefaultDispatcher-worker-5 java.net.BindException: Address already in use … at io.ktor.server.cio.backend.HttpServerKt$httpServer$acceptJob$1.invokeSuspend(HttpServer.kt:46)`
- 원인: `GitServer.start()`의 try/catch가 `s.start(wait = false)`만 감싼다. CIO는 accept 코루틴 안에서 bind하므로 예외가 처리되지 않은 코루틴 예외로 올라가 프로세스가 죽는다

### QA-02 앱 업데이트 뒤 서버가 복구되지 않음 (높음, 코어 + 안드로이드 UI)
- 재현: 서버가 켜진 상태에서 `adb install -r app-debug.apk` → 앱 열기
- 기대: 서버 유지(F-01 AC7). 안 되면 읽을 수 있는 안내
- 실제: 서버 꺼짐 + 메인에 `백그라운드에서는 서버를 시작할 수 없습니다: startForegroundService() not allowed due to mAllowStartForeground false…`. 시스템 로그 `Background started FGS: Disallowed`. `serverEnabled`는 true로 남음
- 원인 추정: `BootReceiver`가 `MY_PACKAGE_REPLACED`에서 포그라운드 서비스를 시작하는데 이 브로드캐스트는 백그라운드 FGS 시작 예외가 아니다. 재부팅과 프로세스 kill은 정상
- UI: 앱이 포그라운드로 올 때 `serverEnabled=true`면 다시 시도하거나 한국어 안내로 바꿀 것

### QA-03 이미 미러된 URL이 "지원하지 않는 주소예요" (중간, 안드로이드 UI + 코어)
- 재현: `Spoon-Knife`가 있을 때 `https://github.com/octocat/Spoon-Knife/tree/main/styles` 붙여넣기 또는 공유
- 기대: "이미 미러됨" + 기존 레포로 이동 버튼 (F-06 AC8)
- 실제: 빨간 카드 "지원하지 않는 주소예요" / "이미 미러됨: Spoon-Knife", 이동 버튼 없음
- 원인: preview가 `existing`과 함께 `error`도 채우고, `ui/screens/AddScreens.kt` 225~226행이 `p.error`를 먼저 검사한다

### QA-04 README raw HTML이 글자로 보인다 (중간, 코어)
- 재현: 웹 `/r/chalk`
- 기대: raw HTML 제거(F-18 AC5)
- 실제: `<h1 align="center"> <br> <img …>` 태그가 글자로 화면에 나온다. XSS는 막혀 있다
- 제안: 이스케이프 대신 제거 또는 안전한 태그 화이트리스트 정화. `docs/core/spec.md` 3절("이스케이프")도 PRD와 다르다

### QA-05 LFS 레포 push 실패 (중간, 코어)
- 재현: git-lfs가 있는 PC에서 `filter=lfs` 레포를 `git push http://<폰>/x.git main`
- 기대: git 객체 push는 되거나 해결 방법 안내 (F-24는 "경고만")
- 실제: `batch response: Repository or object not found: …/info/lfs/objects/batch` → push 실패. `GIT_LFS_SKIP_PUSH=1`로만 된다
- 제안: LFS batch 엔드포인트에서 git-lfs가 그대로 표시하는 JSON 메시지로 우회 방법 안내, 앱 LFS 안내에도 기재

### QA-06 분리(복제)한 레포가 빈 레포로 보인다 (낮음, 안드로이드 UI)
- 재현: 미러 상세 → 로컬 레포로 분리 → 복제
- 실제: "이 레포는 비어 있어요" 카드가 첫 push 전까지 보인다
- 원인: `ui/screens/RepoDetailScreen.kt` 207행 조건 `m.mirror == null && m.lastPushAt == null`

### QA-07 ngrok 토큰 거부 오류 원문 노출 (낮음, 안드로이드 UI)
- 재현: 설정 → 잘못된 authtoken 저장 → 외부 접속 켜기
- 실제: 영어 원문 전체가 `\r\n` 빈 줄까지 그대로 나오고, 연결되지 않았는데 배너 제목은 "외부 공개 중"
- 기대: 한국어 한 줄 + 원문은 "자세히"로, 실패 상태 제목

### QA-08 잘못된 레포 이름 push에 사유가 없다 (낮음, 코어)
- 재현: `-bad.git`, `a..b.git`, `x.git.git`, 101자 이름, `foo%20bar`로 push
- 실제: 모두 `remote: Invalid repository name`만 나옴 (F-13 AC2는 사유 요구)

### QA-09 미러 설명이 비어 있다 (낮음, 코어)
- 재현: 일괄 미러로 등록(카탈로그에는 설명이 있음) → 웹 W-01, `/api/v1/repos`
- 실제: `description`이 빈 문자열

### QA-10 "남은 공간"이 가려짐 (낮음, 안드로이드 UI)
- 미러 추가 화면 맨 아래 "남은 공간"이 하단 고정 "추가" 버튼 뒤로 가려진다(`screenshots/02-add-mirror.png`)

### QA-11 Clone 카드 URL 잘림 (낮음, 웹 UI)
- `/r/field-notes` 데스크톱 1440px에서 URL이 줄임표 없이 글자 중간에서 잘린다(`screenshots/10-web-repo-home.png`). 복사는 전체 URL

### QA-12 접근성 설명 오류 (낮음, 안드로이드 UI)
- 온보딩 사용자 이름 복사 버튼 contentDescription이 "사용자 이름 주소 복사". "사용자 이름 복사"가 맞다
- 참고: uiautomator 덤프에 메인 FAB가 나오지 않음. 도구 한계일 수 있으니 TalkBack으로 확인 필요

### 관찰 (문제로 올리지 않음)
- 메인 서버 카드가 높아 레포가 2~3개만 보인다
- 원본 복구 뒤에도 이전 "원본 없음" 알림이 남는다
- 삭제 스낵바 "을(를)" 조사 표기
- 빈 레포 크기 "0 B · 측정 전"

## 5. 확인하지 못한 항목

| 항목 | 이유 |
|---|---|
| ngrok 실제 터널 | 토큰 없음으로 확인 불가 |
| 실제 GitHub/GitLab 비공개 토큰 | 토큰 없음으로 확인 불가 (PC 테스트 원격 Basic 인증으로 토큰 경로는 확인) |
| GitLab 일괄 미러, 레이트리밋 대기, 재시도 | 상황을 만들 수단 없음 |
| 주기 확인·자동 업데이트, 조건 대기, `지연` 배지, jitter, 백오프 | WorkManager 주기 작업 강제 실행 불가 |
| 핫스팟/Tailscale 주소, 네트워크 변경, 주소 없음 안내 | 시스템 설정 변경 필요 |
| 첫 실행 비밀번호·기본값 | 데이터 삭제 필요, run-as tar 백업이 깨져 진행 안 함 |
| 저장 공간 부족 동작 | 남은 공간 97GB |
| F-20 전환(CONVERT), F-06 AC5 첫 받기 실패 | 시간 부족 |
| 업데이트 중 clone, push 중 끊김 | 작은 레포로 경합 재현 불가 |
| 8시간 화면 꺼짐, 실기기 30대, 1~2GB 레포, 500개 레포 | 장시간·대용량 |
| Firefox/Safari/Edge | Chromium만 확인 |
| 내보내기 진행률 알림, 공유 경로 | 작업이 짧아 확인 못 함 |

## 6. 대표 스크린샷

`screenshots/` 14장(라이트 12, 다크 2). 목록과 한 줄 설명은 `screenshots/README.md`.

## 7. 산출물

- `docs/qa/round1/report.md`
- `docs/qa/round1/screenshots/` + `README.md`
- `docs/qa/round1/tools/` — `ui.py`, `delete_repo.sh`, `origin_server.py`, `web_check.py`, `web_shots.py`
- `docs/qa/round1/fixtures/phone-before-cleanup-backup.zip`

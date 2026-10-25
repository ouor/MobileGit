# 대표 스크린샷 (QA 1차)

촬영: 2026-10-25, 테스트폰 `d418f2d1`(LineageOS, 1080x2400) + PC Chromium(Playwright, 1440x900 @1.5x). 앱 빌드는 `8af31d1`.
데이터: 실제 공개 GitHub 레포 미러 12개(octocat, chalk 조직, sindresorhus, expressjs 등)와 PC에서 push한 로컬 레포 3개(`field-notes`, `dotfiles`, `client-a`). QA용 테스트 레포는 촬영 전에 모두 지웠다.

| 파일 | 화면 |
|---|---|
| `01-main.png` | A-02 메인: 서버 켜짐, 와이파이 대표 주소, 배터리 칩, 레포 15개 목록(이름순) |
| `02-add-mirror.png` | A-03 미러 추가: 공유 시트로 받은 GitHub URL, 정규화 결과, 이름 제안, 접근 가능(PR ref 제외) |
| `03-bulk-mirror.png` | A-04 계정·조직 일괄 미러: GitHub 조직 `chalk` 목록, 3개 선택, `등록됨` 표시, 선택 크기/남은 공간 |
| `04-repo-detail-mirror.png` | A-07 레포 상세(미러 `chalk`): 원본 상태 카드, 지금 업데이트/변경 확인, 자동 업데이트 제외, PR ref 옵션 |
| `05-repo-detail-local.png` | A-07 레포 상세(로컬 `field-notes`): 기본 브랜치, 마지막 push 시각·IP, clone 주소와 명령, 관리 |
| `06-qr.png` | A-08 QR 전체 화면: `field-notes` clone URL QR과 큰 URL 텍스트 |
| `07-settings.png` | A-10 설정: 서버(포트, 계정, 비밀번호 재설정, 인증 끄기, 익명 읽기, 부팅 자동 시작), 연결 경로별 보안 |
| `08-storage.png` | A-09 저장 공간: 앱 사용량, 기기 남은 공간, 하한, 전체 백업, 레포별 용량(큰 순) |
| `09-web-repo-list.png` | W-01 웹 레포 목록: 미러/로컬, 원본 상태 배지, 원본 주소, 업데이트 시각, 용량 |
| `10-web-repo-home.png` | W-02 웹 레포 홈(`field-notes`): 파일 트리, 최신 커밋, README(한국어 표 포함), Clone 카드, 정보 카드 |
| `11-web-file-view.png` | W-03 웹 파일 보기(`chalk/source/index.js`): 줄 번호, 구문 강조, 원본 다운로드 |
| `12-web-commit-log.png` | W-04 웹 커밋 로그(`chalk`): 날짜별 묶음, 작성자, 상대 시각, 짧은 SHA |
| `13-main-dark.png` | A-02 메인 (다크 모드) |
| `14-web-repo-home-dark.png` | W-02 웹 레포 홈 (다크 모드) |

참고: 상태 표시줄에 USB 디버깅 등 시스템 아이콘이 일부 보인다(데모 모드는 시스템 설정 변경이라 쓰지 않았다).

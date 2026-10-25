# 대표 스크린샷 (1차 사이클 최종, 2차 인터뷰용)

- 촬영: 2026-10-25 15:00~15:20. 사용자 리뷰, 코드 리뷰, 앱 아이콘이 반영된 현재 `main` 빌드(installDebug).
- 기기:
  - 앱: 테스트폰 `d418f2d1`(LineageOS, 1080x2400).
  - 웹: PC Chromium(Playwright, 1440x900 @1.5x).
- 18장: 라이트 16, 다크 2.
- 데이터: 실제 공개 GitHub 레포 미러 12개(octocat, chalk 조직, sindresorhus, expressjs 등)와 PC에서 push한 로컬 레포 3개(`field-notes`, `dotfiles`, `client-a`).

| 파일 | 화면 |
|---|---|
| `01-main.png` | 메인: 한 줄로 접힌 서버 카드, 배터리 최적화 안내 배너, 필터 칩, 레포 15개 목록(최근 활동순), "레포 추가" 버튼 |
| `02-add-sheet.png` | 레포 추가 시트: 미러 추가 / 계정·조직 일괄 미러 / PC 프로젝트 올리기 / 빈 레포 만들기, 갈래마다 한 줄 설명 |
| `03-add-mirror.png` | 미러 추가: 다른 앱에서 공유받은 GitHub 주소로 "연결 확인" 뒤 접근 가능, 이 폰에서 쓸 이름과 주소, 고급 접힘, 남은 공간 |
| `04-pc-upload-guide.png` | PC 프로젝트 올리기 안내: 레포 이름, `git remote add`·`git push` 명령 복사, 계정 안내, VS Code 팁 |
| `05-bulk-mirror.png` | 계정·조직 일괄 미러: GitHub 조직 `chalk`, 이미 미러한 레포는 "등록됨", 새로 2개 선택, 선택 크기와 남은 공간 |
| `06-repo-detail-mirror.png` | 미러 상세(`chalk`): PC에서 clone 카드, 원본 카드와 지금 업데이트, 읽기 전용 안내 + 분리하기 |
| `07-repo-detail-local.png` | 로컬 레포 상세(`field-notes`): clone 주소와 명령, 마지막 push 시각·IP, 고급, 관리 |
| `08-push-history.png` | 로컬 레포 push 이력: 브랜치별 이전 → 새 커밋, 새 브랜치 "새로 만듦", 시각과 보낸 PC 주소 |
| `09-qr.png` | clone 주소 QR 전체 화면: 누가 찍는지 안내, 복사·공유 버튼 |
| `10-settings.png` | 설정: 포트, 계정, 비밀번호 재설정, "비밀번호 요구" 세그먼트, 부팅 후 자동 시작, 연결 경로별 보안 |
| `11-storage.png` | 저장 공간: 앱 사용량, 기기 남은 공간, 최소 여유 공간, 전체 백업, 레포별 용량 |
| `12-launcher-icon.png` | 런처 아이콘: 앱 서랍의 mobilegit(파란 배경, 흰 원 안 브랜치), 옆은 Play 스토어·Tailscale |
| `13-main-dark.png` | 메인 (다크 모드) |
| `14-web-repo-list.png` | 웹 레포 목록: 미러/로컬, 설명, 원본 주소, 시간·용량 열, 필터와 정렬 메뉴 |
| `15-web-repo-home.png` | 웹 레포 홈(`chalk`): 파일 트리, 최신 커밋, SVG 로고가 보이는 README, PC에서 clone 카드, 정보 |
| `16-web-file-view.png` | 웹 파일 보기(`chalk/source/index.js`): 줄 번호, 구문 강조, 파일 다운로드 |
| `17-web-commit-log.png` | 웹 커밋 로그(`field-notes`): 날짜별 묶음, 작성자, 시각, 짧은 SHA |
| `18-web-repo-home-dark.png` | 웹 레포 홈(`field-notes`, 다크 모드) |

참고:
- 상태 표시줄에 시스템 아이콘이 일부 보인다(데모 모드는 시스템 설정 변경이라 쓰지 않았다).
- 12번은 앱 서랍에서 개인 앱이 보이지 않게 아이콘 세 개만 잘라 냈다.

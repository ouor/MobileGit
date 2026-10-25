# F-26 웹에서 미러 추가 QA 스크린샷

웹 화면은 PC Chromium(Playwright, 1100px, 배율 1.5)으로 찍었고, 앱 화면은 테스트폰 d418f2d1에서 찍었다. 빌드는 코어 dcbc554, 웹 ce0875a, 앱 10a42e7이다.

| 파일 | 내용 | 기준 |
|---|---|---|
| 01-web-list-add-button.png | 레포 목록 상단의 "미러 추가" 버튼. 헤더 문구는 "웹에서는 보기와 미러 추가만 할 수 있어요" | WM-04 AC1, AC8 |
| 02-web-add-preview.png | `…/is-plain-obj/tree/main`을 붙여 넣은 결과: 정규화 주소, 받는 범위 한 줄, 기본 브랜치, 이름 | QA-W1 |
| 03-web-repo-syncing.png | 등록 직후 레포 홈의 "받는 중" 배지와 "원본에서 받는 중이에요" 안내 | QA-W1, WM-05 AC2 |
| 04-web-repo-tree-after-sync.png | 새로고침 없이 자동으로 바뀐 파일 트리와 README | QA-W1 |
| 05-app-detail-created-by-web.png | 앱 레포 상세의 만든 방법: "웹에서 추가 · 2026.10.25 23:23" | WM-06 AC2 |
| 06-app-accesslog-mirror-add.png | 앱 접속 기록의 "웹에서 미러 추가" 행과 "거부: https 주소만 추가할 수 있어요" | WM-06 AC1 |
| 07-web-push-only-anonymous.png | "push할 때만"에서 비밀번호 없이 연 화면: 401 문구와 "추가하려면 비밀번호가 필요해요" | QA-W4, WM-04 AC5 |
| 08-web-off-anonymous-private.png | "안 함"에서 익명으로 비공개 레포를 넣었을 때 TOKEN_REQUIRED 문구와 익명 안내 | QA-W11 (c) |
| 09-web-ngrok-refused.png | ngrok 전용 포트로 연 화면에서 확인할 때 EXTERNAL_NOT_ALLOWED 문구 | QA-W5 |
| 10-csrf-test-page.png | 다른 출처(127.0.0.1:8765) 시험 페이지. form 두 개는 CROSS_SITE JSON, fetch JSON은 막힘, no-cors는 opaque | QA-W6 |
| 11-web-already-mirrored.png | 이미 미러한 Hello-World: "이미 보존 중이에요"와 레포 링크, "추가" 비활성 | QA-W10 |
| 12-web-storage-low.png | 최소 여유 공간을 200GB로 올린 상태에서 "추가" → STORAGE_LOW 문구 | QA-W10 |
| 13-web-token-required.png | 비밀번호로 접속한 상태에서 없는 GitHub 레포 → TOKEN_REQUIRED 문구 | QA-W11 |
| 14-web-gitlab-preview.png | GitLab 주소 `…/-/tree/main`의 확인 결과 | QA-W3 |
| 15-web-syncing-screen-off.png | 앱을 최근 앱에서 내리고 화면을 끈 상태에서 등록한 직후 "받는 중" | QA-W2 |

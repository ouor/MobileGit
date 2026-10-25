# 2차 리뷰 반영 뒤 대표 스크린샷

기기 d418f2d1 (393dp 폭, 라이트 모드), 빌드 7fe5bde, 01·05·06·08·19는 30f70e8, 12~16·20·21·25·26은 d823460. 오류·주의·지연·멈춤 상태는 PC 테스트 원본(`origin_server.py`)으로 만든 테스트 미러 `design-tokens`, `qa-gone`과 meta 시각 조정으로 만들었다. 테스트 레포는 모두 지웠고 meta는 되돌렸다. ngrok 화면은 가짜 토큰 `qa-fake-token`으로 만든 실패 상태다. 와이파이 이름이 보이는 빠른 설정 화면은 넣지 않았다 (18번은 알림 부분만 잘라 냄).

| 파일 | 보여 주는 것 | 기준 |
|---|---|---|
| 01-main-summary-error-warning.png | 요약 "미러 14개 · 정상 12 · 오류 1 · 주의 1", 오류 빨강·주의 주황, 한 기준선 (30f70e8), 접힌 "+" 버튼 | CR-11, RV2-06, QA2-07, QA2-12 |
| 02-main-failed-row.png | 실패 행 "업데이트 실패 · 네트워크 오류 · 18:45부터" 잘림 없음, 요약 "오류 2" | RV2-05, CR-11 |
| 03-main-stalled-not-inflated.png | 백그라운드 확인 멈춤에서 "주의 필요 2" (지연이 부풀지 않음) | RV2-06, CR-11 |
| 04-main-list-end-fab.png | 목록 끝 여백: 마지막 행이 "레포 추가" 버튼에 가리지 않음 | RV2-05 |
| 05-add-sheet.png | 레포 추가 시트, 보조 설명 마침표 없음 | RV2-01, QA2-09 |
| 06-detail-clone-origin.png | 상세 원본 카드(chalk): 마지막 확인 "18:26", 마지막 업데이트 "14:58", "다음 확인 2026.10.26 00:26쯤" 절대 시각만 | RV2-01, QA2-10 |
| 07-detail-scope-local-repo.png | 받는 범위 "LFS 파일은 받지 않음 (포인터만)", "로컬 레포 만들기" | RV2-01, RV2-09 |
| 08-update-history.png | "업데이트 이력" 탭, 시각 "18:59", "0.2초 걸림" | RV2-01, QA2-10 |
| 09-a15-archives.png | "design-tokens 보존된 기록", 절대 시각만 | RV2-01 |
| 10-settings-auth-options.png | 비밀번호 요구 "항상 / push할 때만 / 안 함", 경로별 보안 색 점 옆 글자(주의·안전·위험) | RV2-02, RV2-10 |
| 11-settings-public-section.png | "외부 공개 (ngrok)" 섹션이 연결 경로별 보안 바로 아래 | RV2-04 |
| 12-dialog-turn-on-public.png | push할 때만에서 외부 공개 켜기: "비밀번호 없이 외부 공개할까요?", 본문에 "보존된 기록(강제 push로 지워진 커밋 포함), 원본 주소, 레포 상태도 보여요" (R2SEC-04) | RV2-03, R2SEC-04 |
| 13-dialog-lower-auth-while-public.png | 외부 공개 중 비밀번호 요구 낮추기: "외부 공개 중에 비밀번호 요구를 줄일까요?", 같은 노출 범위 문장 | RV2-03, R2SEC-04 |
| 14-settings-pending-warning.png | 터널 실패 때 비밀번호 요구 설명 "clone과 웹 화면(보존된 기록 포함)은…"과 "ngrok이 연결되면 비밀번호 없이 인터넷에 열려요" | RV2-03, R2SEC-04 |
| 15-settings-public-failed.png | "외부 공개 (ngrok) · 실패"와 같은 경고 | RV2-03 |
| 16-main-public-failed-banner.png | 메인 배너 "외부 공개 실패 (ngrok)" · "…clone, 웹 화면, 보존된 기록이 비밀번호 없이 인터넷에 열려요." | RV2-03, R2SEC-04 |
| 17-bulk-mirror.png | 일괄 미러: "아카이브된 레포 숨기기", 마지막 행이 하단 바에 가리지 않음 | RV2-01, RV2-05 |
| 18-notification-icon-robot.png | 알림 창에 아직 로봇 아이콘 (원인은 보고서) | RV2-11 |
| 19-delete-origin-gone-warning.png | 삭제 대화상자 "이 레포는 원본이 없어 지우면 다시 받지 못할 수 있어요" | QA2-11 |
| 20-web-archives.png | 웹 보존된 기록 표(1100px): 보존된 커밋 SHA는 글자+복사, 지금 원본만 링크, fetch 명령 한 줄 | RV2-08, R2WEB-01, R2WEB-09 |
| 21-web-archives-800.png | 800px에서는 표 대신 쌓은 목록 | R2WEB-01 |
| 22-web-home-200pct.png | 200% 확대(뷰포트 720px)에서 레포 홈, 정보 패널 "보존된 기록 3개 보기" | RV2-10, 개수 일치 |
| 23-web-lfs-pointer.png | LFS 포인터 레포: 파일 목록 "LFS 포인터" 표시와 안내 | RV2-09, WR-7 |
| 24-web-repo-list-summary.png | 웹 목록 요약 "미러 14개 · 정상 12 · 오류 1 · 주의 1 …" | CR-11 |
| 25-history-pr-branch-change.png | 업데이트 이력 자세히: 히스토리 바뀜·브랜치 삭제됨·PR 브랜치 바뀜·태그 이동됨, PR 브랜치는 보존하지 않아 "3개를 보존" | R2GIT-05 |
| 26-web-archives-dark.png | 다크 모드 보존된 기록 표 | R2WEB-01 |

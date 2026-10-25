# 2차 QA 대표 스크린샷

기기 d418f2d1 (1080x2400, 393dp 폭), 라이트 모드. 실패·원본 없음·지연 화면은 PC 테스트 원본 서버(`docs/qa/round1/tools/origin_server.py`)로 만든 미러 `design-tokens`에서 실제로 만든 상태다. 지연과 "백그라운드 확인 멈춤"은 meta의 마지막 확인 시각을 과거로 바꿔 만들었고, 끝난 뒤 되돌렸다.

| 파일 | 보여 주는 것 | 기준 |
|---|---|---|
| 01-main-summary-history-badge.png | 메인 상단 요약 "미러 13개 · 정상 12 · 주의 1 …", 행 "히스토리 바뀜 · 방금 보존"과 배지, 주의 필요 필터 | R2-01, R2-02 |
| 02-main-update-failed.png | 행 "업데이트 실패 · 네트워크 오류 · 방금부터"와 실패 배지 | R2-01 |
| 03-main-origin-gone.png | 행 "원본 없음 · 방금부터"와 배지 | R2-01 |
| 04-main-delayed.png | 행 "6시간 주기인데 3일째 확인 못 함"과 지연 배지 | R2-01 |
| 05-main-background-stalled.png | 요약 줄 경고 "백그라운드 확인이 멈춘 것 같아요"와 배터리 안내 | R2-02 AC3 |
| 06-sync-history-change-kinds.png | 동기화 이력의 변화 종류(브랜치 삭제됨, 새 브랜치·태그, 히스토리 바뀜, 새 커밋, 태그 이동됨)와 "보존된 기록 보기" | R2-04, R2-03 AC6 |
| 07-a15-archives.png | A-15 보존 기록 화면: 시각별 묶음, 종류, SHA, 커밋, 원본 현재값, fetch 명령과 웹에서 보기 | R2-03 |
| 08-web-w05-archives.png | 웹 W-05 보존 기록 표 | W-05, R2-03 AC5 |
| 09-web-w05-archives-mobile.png | W-05 390px 폭 (가로 스크롤 없음) | W-05 |
| 10-web-w02-repo-home.png | 웹 레포 홈: 보존된 기록 탭, 정보 패널 | R2-03 AC5, R2-07 |
| 11-web-lfs-labels.png | LFS 레포의 웹 경고와 정보 패널 "LFS 파일 없음(포인터만)" | R2-07 AC5 |
| 12-detail-scope-interval.png | 미러 상세: 확인 주기와 다음 확인 시각, 받는 범위 행, QR이 복사 뒤에 있음 | R2-08 AC2, R2-07 AC3, R2-13 AC2 |
| 13-detail-check-only-mode.png | 자동 업데이트를 끈 미러의 "자동 업데이트 꺼짐 · 확인만 해요" | R2-08 AC3 |
| 14-server-card-qr.png | 펼친 서버 카드의 주소 옆 QR 버튼 (접힌 줄에는 없음) | R2-13 AC1 |
| 15-new-local-guide-lfs.png | 새 로컬 레포 안내: 핫스팟·USB 테더링 줄, LFS 접힘 영역, 1GB 권장 | R2-11, R2-07 AC2 |
| 16-fork-sheet.png | 분리 시트의 남는 것 / 바뀌는 것, 아래 안내 | R2-14 |
| 17-export-done-notification.png | 백그라운드에서 끝난 내보내기 알림 "qa 폴더에 … 파일로 저장했어요. 암호화되지 않은 파일이에요." | R2-10 AC2, CR-9 |
| 18-r206-no-password-ngrok-dialog.png | 비밀번호 끄기 상태에서 ngrok 켜기 경고 (취소 / 비밀번호 요구 켜고 공개 / 그래도 진행) | R2-06 AC1 |
| 19-r206-settings-warning.png | 위험 조합 저장 뒤 설정의 "비밀번호 없이 공개 중"과 그 아래의 틀린 push만 설명 | R2-06 AC3, QA2-02 |
| 20-r206-main-banner.png | 위험 조합에서 터널 실패인데 제목이 "비밀번호 없이 공개 중"인 배너 | QA2-03 |
| 21-ngrok-off-after-update.png | 앱 업데이트 뒤 "외부 공개는 꺼진 채로 다시 시작했어요" (앱 안 배너) | R2-05 AC4, QA2-04 |
| 22-settings-sync-interval.png | 설정 요약 줄을 눌러 연 변경 확인 주기 대화상자 | R2-08 AC1 |
| 23-storage-recalc.png | 저장 공간 "레포 용량은 방금 기준이에요"와 용량 다시 계산 | R2-17 AC2 |
| 24-bulk-select-all-chip-clipped.png | 일괄 미러: 전체 선택은 한 줄이지만 "보관된 레포 숨기기" 칩이 잘림 | R2-17 AC1, QA2-05 |

17번은 상태 표시줄 위 빠른 설정에 와이파이 이름이 보인다.

# mobilegit 안드로이드 디자인 시스템

기준: PRD v1, 사이클 1. 코드: `app/src/main/java/com/ouor/mobilegit/ui/`.
모든 화면은 아래 토큰과 공통 컴포넌트로만 만든다. 새 색·간격·모양이 필요하면 먼저 이 문서와 `theme/Theme.kt`에 추가한다.

## 1. 원칙

1. **상태가 먼저 보인다.** 서버 on/off, 접속 주소, 문제 있는 레포는 스크롤 없이 첫 화면에 있다.
2. **한 화면에 강조는 하나.** 브랜드 색(파랑)은 주요 동작과 선택 상태에만 쓴다. 나머지는 회색 계열.
3. **카드 위에 정보, 배경은 회색.** (라이트) 회색 배경 `#F2F4F6` 위에 흰 카드. (다크) `#111215` 위에 `#1B1D21` 카드. 테두리와 그림자는 쓰지 않는다.
4. **말투는 해요체, 짧게.** "~했어요", "~하세요". 오류는 원인과 다음 행동을 함께 쓴다.
5. **색만으로 구분하지 않는다** (NFR 접근성). 배지·배너는 항상 아이콘 + 글자를 함께 쓴다. 아이콘 버튼에는 모두 `contentDescription`을 단다.

## 2. 색 (Material 3 ColorScheme + 상태 팔레트)

| 역할 | 라이트 | 다크 | 용도 |
|---|---|---|---|
| primary | `#3366FF` | `#7C9CFF` | 주요 버튼, 선택, 링크 |
| primaryContainer | `#E5ECFF` | `#1E2F66` | 미러 아이콘 바탕, 선택된 선택지 |
| background / surface | `#F2F4F6` | `#111215` | 화면 바탕 |
| surfaceContainer | `#FFFFFF` | `#1B1D21` | 카드, 행 그룹, 바텀시트 |
| surfaceContainerHighest | `#E8EBEF` | `#2C2F36` | 검색창 |
| onSurfaceVariant | `#6B7684` | `#9EA6B0` | 보조 글자 |
| error | `#E5303B` | `#FF6B70` | 파괴적 동작 글자 |

**상태 톤(`Tone`)**: `Neutral / Info / Success / Warning / Danger`. 각 톤은 `container`(바탕)와 `content`(글자·아이콘) 한 쌍이다. `tone(Tone.X)`로 꺼낸다.

| 톤 | 쓰는 곳 |
|---|---|
| Info | 업데이트 있음, 업데이트 중, 진행 안내 |
| Success | 서버 켜짐 점, 원본 정상, 접근 가능, 완료 |
| Warning | 인증 필요, 지연, 조건 대기, 배터리, 토큰 필요 |
| Danger | 실패, 원본 없음, 외부 공개 중(ngrok), 저장 공간 부족, 인증 꺼짐 |
| Neutral | LFS 누락, 메타 정보, 트리거 |

시스템 다이내믹 컬러(Material You)는 쓰지 않는다. 상태 색의 의미가 기기마다 바뀌면 안 되기 때문이다.

## 3. 타이포그래피

시스템 기본 산세리프(한글은 기기 기본 고딕)에 굵기와 크기만 조정했다. 폰트 파일은 넣지 않는다(APK 크기).

| 스타일 | 크기/굵기 | 용도 |
|---|---|---|
| headlineMedium | 26 Bold | 온보딩 제목, 큰 숫자 |
| headlineSmall | 22 Bold | 메인 앱 이름, 진행 수치 |
| titleLarge | 20 Bold | 화면 제목, "서버 켜짐" |
| titleMedium | 17 SemiBold | 카드 제목 |
| titleSmall | 15 SemiBold | 목록 항목 이름 |
| bodyLarge/Medium/Small | 16/14/13 | 본문, 설명, 메타 |
| labelMedium | 12 SemiBold | 배지, 작은 라벨 |
| `CodeText.large/body/small` | 고정폭 19/13/12 | 주소, URL, 명령, SHA |

## 4. 간격·모양

- 간격 `Space`: 2, 4, 8, 12, 16, 24, 32dp. 화면 좌우 여백은 `Space.screen = 20dp`. 카드 사이 8~12dp, 카드 안쪽 16dp.
- 모양: extraSmall 6, small 10(보조 버튼, 코드 블록), medium 14(입력창, 배너, 주소 바), large 20(카드, 행 그룹, FAB), extraLarge 28(바텀시트).
- 터치 영역은 최소 48dp. 주요 버튼 높이 54dp, 보조 버튼 44dp.

## 5. 아이콘 규칙

- Material Icons **Outlined** 한 세트만 쓴다(`material-icons-extended`). Filled/Rounded를 섞지 않는다.
- 크기: 목록 앞 아이콘 20~22dp, 배지 안 13dp, 칩 16dp, 툴바 24dp.
- 의미 고정: 미러 `SyncAlt`, 로컬 `Folder`, QR `QrCode2`, 복사 `ContentCopy`, 원본 없음 `CloudOff`, 인증 `Key`, 지연 `HourglassBottom`, 외부 공개 `Public`, 저장 공간 `SdCardAlert`, 배터리 `BatteryAlert`, 내보내기 `Upload`, 분리 `CallSplit`, 삭제 `DeleteOutline`.

## 6. 공통 컴포넌트 (`ui/components/Components.kt`)

| 컴포넌트 | 설명 |
|---|---|
| `AppScaffold` | 뒤로 버튼 + 제목 + 액션의 상단 바, 회색 배경. 하위 화면 전부 |
| `BottomAction` / `PrimaryButton` / `SecondaryButton` | 화면 하단 고정 주요 버튼(내비게이션 바 여백 포함), 보조 버튼(회색) |
| `SectionCard` | 흰 카드 + 선택적 제목·우측 요소 |
| `RowGroup` + `NavRow` / `SwitchRow` / `Divider` | 설정형 행 묶음 |
| `InfoRow` | 라벨-값 한 줄 (값 톤 지정 가능) |
| `Pill` / `BadgeRow` | 상태 배지. `Badge` → 라벨·톤·아이콘 매핑은 `Format.kt`의 `Badge.style()`. 심각한 것부터 표시 |
| `Banner` | 지속 상태 알림(아이콘, 제목, 본문, 동작, 닫기). 외부 공개 중, 저장 공간 부족, 원본 없음, 오류 |
| `WarningChip` | 메인 화면 경고 칩(탭 = 해결 화면, 선택적 닫기) |
| `AddressBar` | **주소 표시줄**. 고정폭 URL, 탭하면 복사, 복사·QR 버튼. `large`는 메인 대표 주소(host:port 크게, 복사는 전체 URL) |
| `CommandBlock` | 복사 가능한 명령 블록 (`git clone ...`) |
| `RepoRow` | **레포 카드**. 종류 아이콘, 이름, "종류 · 시각 · 용량", 배지, 진행률, ⋮ 메뉴/롱프레스 = 지금 업데이트 |
| `EmptyState` | **빈 상태**. 원형 아이콘, 제목, 설명, 다음 행동 |
| `ErrorState` / `Loading` | **오류 표시**(Danger 배너 + 다시 시도), 로딩 |
| `ConfirmDialog` | 확인 대화상자(파괴적이면 빨간 확인) |
| `OverflowMenu` | ⋮ 메뉴 |
| `LocalSnack` / `rememberCopy()` | 앱 전역 스낵바, "복사했어요" 확인 포함 복사 |
| `CredentialPicker` (screens) | 원격 자격 증명 드롭다운 (A-03, A-04, A-07 공용) |
| `rememberExportRunner()` (`Export.kt`) | 내보내기 공용 흐름: SAF 저장 위치 선택 또는 공유 → WorkManager 진행 관찰 → 결과 |

## 7. 화면 패턴

- 하위 화면: `AppScaffold` + `ScreenColumn`(스크롤) 또는 `LazyColumn`(목록). 주요 동작은 `BottomAction`.
- 목록 화면: 위에 설명 한 줄 → 필터 칩 → 항목 카드. 비면 `EmptyState`.
- 위험 동작(삭제, 인증 끄기, 분리, ngrok 켜기)은 반드시 `ConfirmDialog`. 레포 삭제는 이름 입력까지.
- 비밀 값은 비밀번호 입력 필드(`PasswordVisualTransformation`)로 받고, 표시는 토큰 앞 4자리만.
- QR 화면만 예외로 다크 모드에서도 항상 흰 바탕·검은 QR(프로젝터, 스캔 대비), 밝기 최대, 화면 꺼짐 방지.

## 8. 라이브러리와 참고한 오픈소스

| 선택 | 이유 | 라이선스 |
|---|---|---|
| Jetpack Compose BOM 2026.09.00 + Material 3 | 표준 UI 툴킷. 다크 모드, 접근성, 컴포넌트 | Apache-2.0 |
| Navigation Compose 2.9 (문자열 라우트) | 화면 14개 + 딥 링크성 진입(공유, 알림)에 충분. 타입 세이프 라우트는 직렬화 플러그인 설정이 필요해 보류 | Apache-2.0 |
| lifecycle-runtime-compose | `collectAsStateWithLifecycle`, `LifecycleResumeEffect`(설정 화면에서 돌아왔을 때 권한·배터리 상태 다시 읽기) | Apache-2.0 |
| Foundation `HorizontalPager` | 온보딩. 예전 Accompanist Pager는 지원 종료되어 쓰지 않음 | Apache-2.0 |
| ZXing core (코어가 이미 포함) | QR. `QrCodes.bitmap()` 재사용, 새 의존성 없음 | Apache-2.0 |
| material-icons-extended 1.7.8 | Outlined 아이콘 세트. BOM에서 빠져 버전 고정. 릴리스 빌드에서 R8이 꺼져 있으면 APK가 커지는 알려진 문제가 있음(남은 문제 참고) | Apache-2.0 |

설계 참고(코드 복사 없음, 구조와 패턴만):
- **Now in Android** (google/nowinandroid, Apache-2.0): 디자인 시스템을 `theme` + `components`로 분리하고 화면은 그 위에서만 그리는 구조, `collectAsStateWithLifecycle` 사용 방식.
- **Tailscale Android** (tailscale/tailscale-android, BSD-3-Clause): 연결 스위치와 내 주소를 첫 카드에 두고 탭해서 복사하는 메인 화면 구성.
- **Termux / ConnectBot** (GPL-3.0 / Apache-2.0): 고정폭 주소·명령 표시 관례. GPL 코드는 보지 않고 화면 관례만 참고.
- 토스·당근 앱의 시각 언어: 회색 바탕 + 흰 둥근 카드, 큰 굵은 제목, 하단 고정 큰 버튼, 해요체.

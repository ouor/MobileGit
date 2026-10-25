# 코드 리뷰 2차 — 안드로이드 플랫폼 / Kotlin

- 리뷰어 관점: 생명주기, 코루틴과 스레드(메인 스레드 IO), Compose 상태와 재구성, 알림, 포그라운드 서비스, 접근성, 리소스·문자열, 회귀 위험
- 범위: `git diff 0a481ef..HEAD -- app/` 가운데 `app/src/main/java/com/ouor/mobilegit/{app,ui}`, `res/values/strings.xml`. 이해에 필요한 만큼 `core/`(Sync, RepoStore, Settings, Models)도 읽었습니다.
- 코드는 수정하지 않았습니다. 줄 번호는 리뷰 시점(HEAD `adadd4b`) 기준입니다. 경로는 `app/src/main/java/com/ouor/mobilegit/` 아래 기준입니다.

## 요약

| 심각도 | 개수 | ID |
|---|---|---|
| 치명 | 0 | - |
| 높음 | 0 | - |
| 중간 | 4 | R2AND-01 ~ R2AND-04 |
| 낮음 | 9 | R2AND-05 ~ R2AND-13 |

가장 중요한 문제 세 가지:
1. **내보내기 "저장했어요" 알림 ID가 약 4분의 1 확률로 진행 알림 ID와 같습니다** (R2AND-01). 이 경우 워커가 끝날 때 WorkManager가 포그라운드 알림을 지우면서 저장 알림도 함께 사라집니다. CR-9가 막으려던 "파일이 어디 갔는지 모름"이 다시 생깁니다.
2. **포크 시트가 진행 중일 때 스와이프나 뒤로 가기로 닫으면 화면이 막힐 수 있습니다** (R2AND-02, 확신 중간). `onDismissRequest`만 무시하고 시트 상태의 숨김은 막지 않기 때문입니다.
3. **보존 기록 화면을 한 번 열면 상세의 "마지막 보존 시각"이 사라집니다** (R2AND-03). `historyChangedAt` 하나가 "안 본 배지"와 "마지막 보존 시각" 두 가지 뜻을 함께 맡고 있어서, R2-03 AC1을 어깁니다.

1차에서 고친 서비스 상태 머신(AND-01/02)은 2차 변경으로 깨지지 않았습니다. `ngrokOffNotice`는 `shutdown()`에서만 지워지고, `blockedInBackground` 경로는 `shutdown`을 거치지 않으므로 안내 문구가 기대한 대로 남습니다.

---

## 중간

### R2AND-01 [중간] 저장 알림 ID가 진행 알림 ID와 겹침 → 워커가 끝날 때 함께 지워짐
- 위치: `app/Work.kt:256` (`id.hashCode() or 0x20000`), `app/Work.kt:203-204`, `app/Work.kt:106-107`, `app/Notifications.kt:84` (`progressId = seed or 0x10000`)
- 문제: 진행(포그라운드) 알림 ID는 `h or 0x10000`, 저장 알림 ID는 `h or 0x20000`입니다(`h = id.hashCode()`). `h`에 16번과 17번 비트가 이미 모두 켜져 있으면 두 값은 똑같이 `h`가 됩니다. 확률은 1/4입니다. OR로는 서로 다른 ID 공간이 보장되지 않습니다.
- 실패 상황: 앱을 닫은 채 SAF로 저장하면, `notifySaved`가 워커 실행 중(`Result.success` 반환 전)에 같은 ID로 포그라운드 알림을 덮어씁니다. 곧이어 WorkManager가 포그라운드를 끝내면서 그 ID의 알림을 지우므로, 사용자에게는 아무 알림도 남지 않습니다. 같은 이유로 진행 중인 알림의 ongoing 속성이 잠깐 풀리는 부작용도 있습니다. (비트 계산은 확신 높음. WorkManager가 그 ID를 지우는 시점은 버전에 따라 다를 수 있어 확신 중간)
- 제안: 저장 알림에는 진행 알림과 겹칠 수 없는 ID를 쓰세요. 예를 들면 `(h and 0xFFFF) or 0x40000`처럼 하위 비트를 마스크하거나, `Notifier`에 고정 범위(`ID_EXPORT_SAVED_BASE + n`)를 두는 방법이 있습니다. 또는 `Result.success` 이후에 알림을 올리세요(워커 밖, 예를 들어 `WorkInfo` 관찰자). `progressId`도 OR 대신 마스크를 쓰도록 맞추면 동기화 알림끼리 생기는 충돌도 함께 줄어듭니다.

### R2AND-02 [중간] 포크 진행 중 시트를 닫으면 보이지 않는 모달이 남음 (확신 중간)
- 위치: `ui/screens/RepoDetailScreen.kt:577` (`onDismissRequest = { if (!busy) onDismiss() }`), 같은 파일의 `BackHandler(enabled = step == 1 && !busy)`
- 문제: Material3 `ModalBottomSheet`는 스와이프로 내리거나 시스템 뒤로 가기를 누르면 **먼저 `sheetState`를 Hidden으로 애니메이션한 다음** `onDismissRequest`를 부릅니다. 호출하는 쪽이 이를 무시해도 시트는 이미 숨겨진 상태입니다. 그런데 `sheet == "fork"`가 그대로라서 시트의 창(스크림)은 남고, 화면 입력을 막습니다. `busy`가 끝나면 성공 시 `onDismiss()`가 불려 풀리지만, **실패하면(`catch`) 아무도 `onDismiss`를 부르지 않습니다**. 오류 문구도 숨겨진 시트 안에 있어 보이지 않습니다.
- 실패 상황: CONVERT/COPY 포크가 몇 초 걸리는 큰 레포에서 사용자가 기다리다 시트를 아래로 내립니다. 포크가 실패(이름 충돌, 공간 부족)하면 상세 화면이 반응하지 않는 것처럼 보이고, 뒤로 가기를 한 번 더 눌러야(이때는 `busy=false`) 풀립니다.
- 제안: `rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !busy })`로 숨김 자체를 막으세요. `busy` 동안 `BackHandler(enabled = busy) {}`로 뒤로 가기도 소비하세요. `ExportSheet`과 메인의 추가 시트는 `busy`가 없어 해당되지 않습니다.

### R2AND-03 [중간] 보존 기록 화면을 열면 상세의 "마지막 보존 시각"이 사라짐 (R2-03 AC1 위반)
- 위치: `ui/screens/ArchiveScreen.kt:69` (`markHistorySeen`), `core/RepoStore.kt` `markHistorySeen` (`historyChangedAt = null`), `ui/screens/RepoDetailScreen.kt:279`
- 문제: `historyChangedAt`은 "히스토리 바뀜 배지를 아직 안 봤음"(R2-01 AC2) 플래그이면서, 상세의 "보존된 기록 N개 · 마지막 보존 HH:mm" 표시(R2-03 AC1)의 값으로도 쓰입니다. A-15를 여는 순간 null이 되므로 그 뒤로는 개수만 남고 마지막 보존 시각은 다음 보존 전까지 표시되지 않습니다. PRD 13장 R2-03 AC1은 "1개 이상이면 개수와 마지막 보존 시각"을 보여 주라고 합니다.
- 실패 상황: 배지를 보고 보존 기록을 확인한 뒤 상세로 돌아오면 "마지막 보존" 정보가 없어집니다. 확신 높음.
- 제안: 두 값을 나누세요. 예를 들면 `lastArchivedAt`(항상 유지)과 `historySeenAt`(또는 `historyUnseen: Boolean`)으로 나누고, 배지 조건은 `lastArchivedAt > historySeenAt`으로 둡니다. 데이터를 옮기기 어렵다면, 상세에서는 `core.ops.archives()`의 `max(at)`나 `archiveNotes`에서 시각을 구하는 방법도 있습니다(IO에서). 회귀 테스트로 "markHistorySeen 뒤에도 마지막 보존 시각 유지"를 하나 추가하세요.

### R2AND-04 [중간] 저장 완료 스낵바가 메인 스레드에서 ContentResolver를 질의함, 폴더 표시 규칙이 두 군데에서 다름 (확신 중간)
- 위치: `ui/Export.kt:89`, `ui/Export.kt:102-110` (`savedText`), 비교 대상 `app/Work.kt:179-187` (`folderOf`)
- 문제:
  - `LaunchedEffect` 안의 collect는 메인 디스패처에서 돌기 때문에, `savedText`의 `contentResolver.query(uri, DISPLAY_NAME)`는 **메인 스레드에서 DocumentsProvider로 가는 binder IPC**입니다. 외부 저장소는 빠르지만, Drive나 클라우드 제공자는 메타데이터를 원격에서 가져오며 수백 ms 이상 막을 수 있습니다.
  - 폴더 계산이 워커의 `folderOf`와 다릅니다. `savedText`는 `substringAfter(':')` 뒤의 경로 전체를 폴더로 씁니다. 그래서 `primary:Documents/Backups/x.zip`은 "Documents/Backups", `raw:/storage/emulated/0/Download/x.zip`은 "/storage/emulated/0/Download"로 표시됩니다. 반면 알림은 마지막 폴더 이름("Backups", "Download")만 씁니다. authority를 확인하지 않으므로, 문서 ID에 `:`와 `/`가 들어 있는 다른 제공자에서는 의미 없는 문자열이 "폴더"로 나올 수 있습니다.
- 실패 상황: Drive를 저장 위치로 고른 뒤 완료되는 순간 화면이 잠깐 멈춥니다(ANR까지 갈 가능성은 낮음). 같은 저장에 대해 스낵바와 알림이 서로 다른 폴더 이름을 말합니다.
- 제안: `savedText`를 `withContext(Dispatchers.IO)`로 감싸고, 폴더는 `ExportWorker.folderOf(uri.authority, docId)`를 재사용하세요. 더 단순하게는 워커가 이미 계산한 이름과 폴더를 `outputData`(`K_OUT_NAME`, `K_OUT_FOLDER`)로 돌려주고, UI는 그것을 표시만 하게 하면 질의가 한 번으로 줄고 문구도 하나로 통일됩니다.

---

## 낮음

### R2AND-05 [낮음] 재부팅·업데이트 뒤 ngrok 끄기(D-8)가 비동기 저장과 메시지 순서에 기대고 있음 (확신 낮음)
- 위치: `app/Core.kt:170-180` (`BootReceiver`), `app/Core.kt:132` (`restoreIfEnabled`), `core/Settings.kt:82-88` (`update`는 단일 스레드 executor에 비동기로 씀)
- 문제:
  - `onReceive`는 `settings.update { ngrokEnabled = false }` 뒤에 바로 반환합니다. 실제 파일 쓰기는 데몬 스레드에서 일어나고 `goAsync()`나 `flush()`가 없습니다. 서버를 띄우지 않는 경우(`startOnBoot=false`)에는 receiver가 끝난 프로세스가 곧 cached가 되므로, 쓰기 전에 죽으면 디스크에는 `ngrokEnabled=true`가 남습니다.
  - `MY_PACKAGE_REPLACED`에서는 `Application.onCreate → restoreIfEnabled()`가 같은 부팅이라 서버를 먼저 띄웁니다. 터널이 꺼진 상태로 시작되는지는 "receiver 메시지가 서비스의 observe보다 먼저 처리된다"는 순서에 달려 있습니다. 현재 AMS 동작상 대개 receiver가 먼저지만, 이 순서를 코드가 보장하지는 않습니다.
  - `ngrokOffAfterRestart`는 메모리에만 있어서, 프로세스가 한 번 죽으면 배너가 사라집니다. 사용자는 ngrok이 왜 꺼졌는지 모르게 됩니다.
- 제안: receiver에서 `goAsync()`로 감싸고 `settings.flush()`를 부르세요. D-8 규칙을 `restoreIfEnabled`(업데이트 직후)에도 적용하거나, 앱 버전·부팅 횟수가 바뀌었는지를 `Settings`에 저장해 `Core.start()`에서 한 번 판정하면 순서 문제와 메모리 플래그 문제가 함께 풀립니다.

### R2AND-06 [낮음] 서버가 꺼진 채 ngrok을 다시 켜면 "외부 공개는 꺼진 채로 다시 시작했어요" 배너가 남음
- 위치: `ui/screens/MainScreen.kt:221-224`, `app/Server.kt:266`
- 문제: `ackNgrokOff()`는 서비스의 설정 collector에서만 불립니다(서버가 실행 중일 때만). 서버가 꺼진 상태에서 설정으로 ngrok을 켜면 `ngrokOffAfterRestart`가 true로 남습니다. 그래서 메인에 "ngrok 켜짐(위험)" 배너와 "외부 공개는 꺼진 채로 다시 시작했어요" 배너가 함께 보입니다.
- 제안: 배너 조건을 `ngrokOff && !settings.ngrokEnabled`로 바꾸거나, `ackNgrokOff()`를 `SettingsScreen.turnNgrokOn`의 저장 지점(또는 `ServerController` 바깥의 설정 관찰)으로 옮기세요.

### R2AND-07 [낮음] 저장 결과 안내가 화면 상태에 따라 빠지거나 두 번 나옴
- 위치: `app/Work.kt:248-250` (`visibleActivities > 0`이면 알림 생략), `ui/Export.kt:77-97` (`rememberExportRunner`의 `LaunchedEffect`), `app/Core.kt:147,155`
- 문제: 알림은 "액티비티가 보이는가"로 판단하고, 스낵바는 "`rememberExportRunner`가 조합 안에 있는가"로 판단합니다. 둘은 같은 조건이 아닙니다.
  - 앱은 보이지만 사용자가 상세나 백업 화면에서 메인으로 나간 경우: runner가 dispose되어 스낵바가 없고, 앱이 보이므로 알림도 없습니다. 아무 안내도 나오지 않습니다.
  - 앱을 홈으로 보낸 경우(액티비티 stopped, 조합 유지): `LaunchedEffect`는 생명주기를 따르지 않으므로 백그라운드에서 스낵바를 띄우고, 알림도 올라갑니다. 공유 경로에서는 `share()`가 백그라운드에서 `startActivity`를 시도합니다(1차부터 있던 동작).
- 제안: 결과 안내를 한 곳으로 모으세요. 예를 들면 워커가 항상 알림을 올리고, UI가 결과를 받으면 그 알림을 취소하는 방식입니다. 또는 `collect`를 `repeatOnLifecycle(STARTED)`로 감싸고, 놓친 결과는 다음에 화면에 들어올 때 `WorkInfo`로 한 번 보여 주세요.

### R2AND-08 [낮음] 확인 주기 대화상자와 요약 줄의 접근성
- 위치: `ui/screens/SettingsScreen.kt:267-276`, `ui/screens/MainScreen.kt:454-474` (`Summary`), `ui/screens/ManageScreens.kt` (자격 증명의 "사용 중 N개" Pill과 레포 이름 링크)
- 문제:
  - 확인 주기 행은 `clickable(role = RadioButton)`이라 선택 상태 semantics가 없습니다. TalkBack이 "선택됨/선택 안 됨"을 읽지 않고, `selectableGroup()`도 없습니다. 같은 파일의 인증 라디오 목록(`selectable`)과 방식이 다릅니다. 행에 `heightIn(min = 48.dp)`도 없습니다.
  - 요약의 "오류 1 · " 같은 버튼은 레이블에 구분자 " · "가 함께 들어가고 `onClickLabel`이 없습니다. 탭하면 무슨 일이 일어나는지("주의 필요만 보기")가 전달되지 않습니다.
  - 자격 증명 화면의 레포 이름 `Text.clickable`에는 role이나 레이블이 없어서 링크라는 사실이 읽히지 않습니다.
- 제안: 주기 행은 `selectable(selected, role = RadioButton)`과 `selectableGroup()`으로 바꾸세요. 요약 버튼에는 `semantics { contentDescription = text; onClick(label = "주의 필요만 보기") }`처럼 구분자를 뺀 이름과 동작 레이블을 주세요. 레포 링크에는 `role = Role.Button`과 `onClickLabel`을 주세요.

### R2AND-09 [낮음] `breakable()`에 보이지 않는 문자가 소스에 그대로 들어 있음
- 위치: `ui/Format.kt:125-126` (`append('​')`, 실제로는 U+200B 리터럴)
- 문제: 같은 함수의 다른 줄은 `'​'`와 `'⁠'`으로 이스케이프했는데, RV2-10에서 추가한 두 줄만 리터럴 ZWSP를 씁니다. 리뷰에서 보이지 않고, 편집기나 포매터, 복사·붙여넣기에서 문자가 빠지면 `append('')`로 바뀌어 컴파일 오류가 나거나, 다른 문자로 바뀌면 줄바꿈 동작이 조용히 깨집니다.
- 제안: `'​'`로 바꾸세요. `BreakableTest`에 IP와 포트 사례(`192.168.0.120:8080`)가 있는지 확인하고, 없으면 추가하세요.

### R2AND-10 [낮음] "다음 확인 HH:mm쯤"이 실제 스케줄이 아니라 추정값이고, 화면에 머무는 동안 갱신되지 않음
- 위치: `ui/screens/RepoDetailScreen.kt:321-323`
- 문제: `lastCheckAt + 주기`는 WorkManager 주기 작업의 flex, 제약(Wi-Fi, 충전), Doze, 앱 대기 버킷을 반영하지 않습니다. `System.currentTimeMillis()`를 조합 중에 한 번만 읽기 때문에, 화면을 열어 둔 채 시간이 지나도 "곧"으로 바뀌지 않습니다. `checksStalled`(백그라운드 확인이 멈춤) 상태에서도 그럴듯한 시각을 보여 줍니다.
- 제안: 문구를 "마지막 확인 뒤 약 N시간"처럼 추정임이 드러나게 하거나, `checksStalled`이면 "백그라운드 확인이 멈춘 것 같아요"로 바꾸세요. 1분 tick이 필요하면 `Core.repoItems`의 tick을 재사용하세요.

### R2AND-11 [낮음] 전역 `LineBreak(HighQuality, Strict, Phrase)`가 입력란에도 적용됨 (확신 낮음)
- 위치: `ui/theme/Theme.kt:110-126`
- 문제: `bodyLarge`는 Material3 TextField의 기본 텍스트 스타일입니다. 그래서 검색창, 이름 입력, 비밀번호 입력에도 HighQuality와 어절(Phrase) 줄바꿈이 적용됩니다. 플랫폼 문서는 편집 가능한 텍스트에 phrase 기반 줄바꿈을 권하지 않습니다(입력할 때마다 다시 계산하는 비용, 커서 이동 시 줄이 다시 흐름). 한 줄 입력란이 대부분이라 체감 영향은 작을 수 있습니다.
- 제안: TextField에는 `textStyle = LocalTextStyle.current.copy(lineBreak = LineBreak.Simple)`을 쓰거나, 읽기용 스타일과 입력용 스타일을 나누세요. API 33 미만에서는 효과가 없다는 점은 주석대로입니다.

### R2AND-12 [낮음] 보존 기록 화면의 작은 문제들
- 위치: `ui/screens/ArchiveScreen.kt:69`, `:73-76`, `:83`, `:89-92`
- 문제:
  - `markHistorySeen`을 목록을 읽기 **전에** 부릅니다. `ops.archives`가 실패해도(손상, 잠금) 배지는 지워집니다.
  - 오류 화면에 `exception.message`를 그대로 보여 줍니다(영어 JGit 메시지일 수 있음, 비어 있으면 "내보내기" 오류 문구를 재사용).
  - `LaunchedEffect(groups, group)`는 회전이나 프로세스 복원으로 다시 조합될 때마다 `group` 위치로 강제 스크롤합니다. 사용자가 이동해 둔 위치가 사라집니다.
  - 한 그룹 안의 항목은 `forEachIndexed`로 한 번에 그립니다. 원본이 브랜치나 태그를 대량으로 지운 업데이트(수백~수천 개)는 Lazy가 아닌 거대한 카드 하나가 됩니다.
- 제안: 목록을 읽는 데 성공한 뒤에 `markHistorySeen`을 부르세요. 오류 문구는 문자열 리소스로 바꾸세요. 스크롤은 `rememberSaveable { mutableStateOf(false) }` 플래그로 한 번만 하세요. 그룹 항목은 그룹 헤더와 항목을 각각 `item`으로 펼치거나, 그룹당 상위 N개와 "더 보기"로 나누세요.

### R2AND-13 [낮음] `Core.overview`가 `repoItems` 파이프라인을 따로 한 번 더 돌림, 배너 닫기 버튼이 카드 밖으로 밀려남
- 위치: `app/Core.kt:98-100`, `ui/screens/MainScreen.kt:141-142`, `ui/components/Components.kt:265`
- 문제:
  - `repoItems`와 `overview`는 공유되지 않는 cold flow입니다. 메인이 둘 다 수집하므로 1분 ticker 두 개와 `RepoQuery.items` 계산 두 벌이 메인 디스패처에서 돕니다. 레포 500개 기준으로 비용은 작지만, 두 결과가 같은 순간의 데이터라는 보장이 없어 필터 칩 숫자와 목록이 잠깐 어긋날 수 있습니다.
  - 배너 닫기 `IconButton`에 `offset(x = 8.dp, y = -8.dp)`를 줬습니다. offset은 그리기와 입력 위치만 옮기므로, Surface의 둥근 모서리 클립 밖으로 나간 부분은 탭이 닿지 않아 실제 터치 영역이 48dp보다 작아질 수 있습니다(확신 낮음).
- 제안: `repoItems`를 `core.scope`에서 `stateIn(SharingStarted.WhileSubscribed(5_000))`으로 공유하고, `overview`를 그 결과에서 `map`으로 만드세요. 닫기 버튼은 offset 대신 Row의 padding을 줄여 자리를 만드세요.

---

## 확인했지만 문제로 보지 않은 것
- `ServerService`의 CR-10 제목 규칙(`public = t is Connected`)과 `ngrokOffNotice`의 수명(한 번의 서버 실행): 상태 머신과 잘 맞습니다.
- `MobileGitApp.visibleActivities`: 메인 스레드에서만 증감하고 워커는 읽기만 하므로 `@Volatile`로 충분합니다. 회전할 때 잠깐 0이 되는 구간은 영향이 없습니다.
- 새 문자열의 여러 인자 포맷은 모두 위치 지정(`%1$s`)을 씁니다. 비위치 `%s/%d`가 섞인 새 문자열은 없습니다.
- `ArchiveScreen`, `LocalInfoCard`, `refreshSizes`의 저장소/JGit 접근은 모두 `Dispatchers.IO`에서 돕니다.
- `rememberSaveable`로 바꾼 시트 단계와 입력값(`addStep`, `guideName`, `ExportSheet.step`, `ForkSheet.newName`)은 회전 뒤에도 유지됩니다.

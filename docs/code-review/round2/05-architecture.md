# 코드 리뷰 2차 — 05 아키텍처 (계층 경계, 데이터 모델, 문서 정합성)

- 리뷰어 관점: 모바일 앱 + 임베디드 서버 아키텍트
- 기준: `git diff 0a481ef..HEAD` (`HEAD` = `adadd4b`), 빌드 결과물(`app/src/main/assets/web/`)과 스크린샷 제외
- 범위: 2차 사이클 변경분만. 맥락은 PRD 13장, spec 8절, 1차 `round1/05-architecture.md`. 코드는 수정하지 않았다.

## 요약

| 심각도 | 개수 | ID |
|---|---|---|
| 치명 | 0 | |
| 높음 | 0 | |
| 중간 | 4 | R2ARC-01 ~ R2ARC-04 |
| 낮음 | 11 | R2ARC-05 ~ R2ARC-15 |

2차의 큰 방향은 맞다. **"주의 필요" 판정이 코어 한 곳(`Badges` → `RepoListItem` → `RepoQuery.overview`)에 있고, 앱과 웹이 같은 값을 쓴다.** UI가 직접 세던 `Counts`는 CR-11에서 지웠고, 웹도 `originStatus` 추측(WR-6)과 LFS 크기 추측(WR-7)을 지우고 코어 값을 쓴다. 1차의 "지금" 항목(ARC-01/02/04)도 새 저장 코드에서 지켜졌다.

가장 먼저 고칠 것:
- **`historyChangedAt`의 두 가지 의미(R2ARC-01)**: "안 본 보존"과 "마지막 보존 시각"을 한 필드가 맡는다. 그래서 보존 기록을 한 번 열면 상세의 "마지막 보존 시각"이 사라진다(PRD R2-03 AC1 위반).
- **R2-06 위험 조합 판정이 코어와 UI 두 곳에 있음(R2ARC-02)**.
- **앱과 웹의 "최근 활동" 시각이 다름(R2ARC-03)**: 웹 정렬이 "앱과 같게"라는 주석과 다르게 동작한다.
- **spec 8절과 코드가 어긋난 곳 네 군데(R2ARC-04)**.

## 2차에서 바뀐 구조

### 판정 흐름 (주의 상태)

```
RepoMeta ─┬─ Badges.of(m, s, now, running, checksStalled) ── Set<Badge>
          │        └ RepoQuery.checksStalled(all, s, now)  (CR-11: 전체 멈춤이면 DELAYED 안 붙임)
          ├─ RepoListItem(meta, badges)
          │     needsAttention / issue: RepoIssue / severity: Severity
          ├─ RepoQuery.apply(...)    앱 필터·정렬
          └─ RepoQuery.overview(...) RepoOverview  ── core.overview (앱)
                                                  └─ GET /api/v1/overview (웹)
RepoBrowser.summary(m, s, now, stalled) → RepoListItem를 만들어 같은 값을 RepoSummaryDto에 복사 (웹 목록·상세)
```

### 새 데이터

| 위치 | 필드/파일 | 예전 데이터일 때 |
|---|---|---|
| `RepoMeta` | `historyChangedAt: Long?` | null → 배지 없음 |
| `MirrorSource` | `lastSuccessCheckAt: Long?`, `failingSince: Long?` | `lastOkAt`이 `lastCheckAt`/`lastSuccessUpdateAt`로 추정, `failingSince` null이면 기간 없이 표시 |
| `RefChange` | `kind`(파생 속성, 저장 안 함) | 예전 이력에도 나옴 |
| `Settings` | `serverOffHintDismissed` | false |
| `repos/<n>.git/mobilegit/archives.json` | 보존 ref별 `RefChange`, 최대 10,000건 | 없으면 이력에서 찾고, 그래도 없으면 `kind = null`("강제 push 또는 삭제") |
| enum | `Badge.HISTORY_CHANGED`(+ `@Serializable`), `AddressKind/AccessRoute.USB_TETHER`, `RefChangeKind`, `Severity` | 아래 참고 |
| 웹 API | `RepoSummaryDto.needsAttention/issue/severity/includePullRefs`, `TreeEntryDto.lfs`, `/overview`, `/repos/{n}/archives` | 모두 기본값 있음 |

스키마 호환은 확인했다. 새 필드는 전부 기본값 또는 null이 있다. `schemaVersion`을 올릴 변경은 없다. 앱을 다운그레이드하면 `AccessRoute.USB_TETHER`가 든 접속 기록 항목은 `JsonFile.readList`가 항목 단위로 버린다. 파일 전체가 실패하지는 않는다(ARC-02 대책이 작동). `RefChange.kind`를 저장하지 않고 파생 속성으로 둔 것은 좋은 선택이다.

## 잘 된 점

- **판정은 코어 한 곳**: `RepoBrowser.summary`가 `RepoListItem`을 그대로 만들어 웹 DTO에 복사한다. 그래서 앱과 웹의 `needsAttention`·`issue`·`severity`가 정의상 같다. `UnitTests`에 `RepoBrowser.summary(...).needsAttention/severity`를 단언하는 테스트가 있다.
- **UI → 코어로 옮긴 판정**: CR-11에서 UI의 `Counts`를 지우고 `overview`/`severity`로 바꿨다. 웹은 `hasProblem(originStatus)`, 크기로 LFS를 추측하던 코드, 디렉터리 우선 정렬을 지웠다. 요청 문서(`docs/ui/core-requests.md`, `docs/web/core-requests.md`)에 "현재 UI 처리 → 코어 처리 결과 → UI에서 지울 것"이 남아 있어 경계를 옮긴 흔적을 따라갈 수 있다.
- **예전 데이터 처리**: `lastOkAt` 추정, `RefChange.kind` 파생, `ArchiveEntryDto.kind = null`, `failingSince = null`을 모두 화면이 처리한다. archives.json을 지운 경로도 테스트한다(`MirrorSyncTest`).
- **ARC-04 유지**: `appendArchives`도 `exists(name)` 확인 뒤에만 쓴다. 삭제된 레포 디렉터리를 되살리지 않는다.
- **오류 분류(QA2-01)**: 자유 문장 대신 예외 타입과 JGit 상태 문구로 분류한다. 네트워크 실패가 예전의 "원본 없음"을 `UNKNOWN`으로 되돌리는 규칙도 코어에 있다. 테스트 `failureCategories`, `droppedConnectionIsNetworkNotOriginGone`이 붙어 있다.
- **core 순수성 유지**: `core/`에 `import android`가 여전히 없다.
- **ARC-13 개선**: 2차에 추가된 문자열 키는 모두 의미 있는 이름이다(번호 키 0개).

---

## 발견 사항

### R2ARC-01 [중간] `historyChangedAt` 하나가 "안 봄"과 "마지막 보존 시각"을 겸한다 — 지금

- 위치: `core/Models.kt`(`RepoMeta.historyChangedAt`), `core/Sync.kt:261`, `core/RepoStore.kt` `markHistorySeen`, `ui/screens/RepoDetailScreen.kt:279`
- 문제: 코어는 이 필드를 배지 플래그로 쓴다. 보존 기록을 열면 null이 된다. 상세 화면은 같은 필드를 "마지막 보존 시각"(`detail_archive_last`)으로 보여 준다. 그래서 보존 기록을 한 번 열면 상세 행에서 마지막 보존 시각이 사라진다. PRD R2-03 AC1은 "1개 이상이면 개수와 마지막 보존 시각"을 항상 보여 달라고 한다. `RepoIssue.since`(HISTORY_CHANGED)도 같은 필드를 쓴다.
- 영향: 보존 기록을 본 사용자는 마지막 보존이 언제였는지 상세에서 알 수 없다. 한 필드를 두 뜻으로 쓰면 다음 변경에서도 같은 일이 생긴다.
- 권장: 필드를 둘로 나눈다. `lastArchivedAt: Long?`(보존마다 갱신, 지우지 않음)과 `historySeenAt: Long?`을 두고, 배지는 `lastArchivedAt > (historySeenAt ?: 0)`로 정한다. 예전 데이터는 `historyChangedAt`을 `lastArchivedAt`으로 읽는다(필드 이름만 바꾸면 `ignoreUnknownKeys` 때문에 값이 사라지므로 `@JsonNames` 또는 기존 이름 유지). 더 작은 대안도 있다. 상세의 마지막 보존 시각을 `ops.archives(name).maxOf { it.at }`(그룹 시각)으로 구하면 필드는 그대로 둬도 된다. 다만 상세를 열 때마다 디스크를 읽는다.

### R2ARC-02 [중간] R2-06 "위험 조합" 판정이 코어와 UI에 따로 있다 — 지금 (작은 수정)

- 위치: `core/Settings.kt:51`(`exposedWithoutPassword`), `ui/screens/SettingsScreen.kt:103-110,133,299`(`authLevel`, `turnNgrokOn`, `pick`)
- 문제: 위험 조합의 정의("ngrok 켜짐 + push만/끄기")는 코어 `exposedWithoutPassword`에 있다. 그런데 경고를 띄울지는 UI가 따로 정한다. 기준은 `authLevel(s) != 0`과 `s.ngrokEnabled`이다. 또 두 boolean(`authEnabled`, `anonymousReadOnLan`)을 3단계로 바꾸는 매핑(`authLevel`, `setAuthLevel`)도 UI 파일에만 있다. 코어의 정의가 바뀌면 UI 경고 조건이 조용히 어긋난다(예: 나중에 Tailscale 공개를 위험에 넣는 경우).
- 권장: UI가 "바꾼 뒤의 설정"으로 코어 판정을 부른다. `val next = s.copy(...); if (next.exposedWithoutPassword && !s.exposedWithoutPassword) dialog = ...` 한 줄이면 두 순서(AC1·AC2)를 같은 규칙으로 처리한다. 3단계 매핑은 `Settings`에 `authLevel` 파생 속성과 `withAuthLevel(l)`로 옮긴다. 웹 `/info`가 같은 개념을 말해야 할 때도 이것을 쓴다.

### R2ARC-03 [중간] 앱과 웹의 `lastActivityAt` 정의가 달라 "최근 활동순"이 다르다 — 지금 (한 줄)

- 위치: `core/RepoBrowser.kt:102`(`m.mirror?.lastUpdateAt`), `core/Sync.kt:490`(`RepoListItem.lastActivityAt`, `lastSuccessUpdateAt`), `web/src/pages/RepoList.tsx:47`
- 문제: 웹 DTO는 업데이트 **시도** 시각을 쓴다(실패해도 움직임). 앱 목록은 **성공** 시각을 쓴다. 2차에서 웹 정렬에 "앱처럼 주의 필요 먼저"를 넣고 주석도 "like the app"이라고 달았다. 하지만 같은 순서 안에서 실패가 반복되는 미러는 웹에서만 위로 올라간다. 1차부터 있던 차이인데, 2차에서 "같은 판정" 약속이 생겨 드러났다.
- 권장: `RepoBrowser.summary(m, s, now, stalled)` 안에서 이미 만드는 `RepoListItem`의 `lastActivityAt`을 DTO에 넣는다. private `summary(m)`의 식은 지운다.

### R2ARC-04 [중간] spec.md와 코드가 어긋난 곳 — 지금 (문서)

- 위치와 내용:
  1. `docs/core/spec.md:58` (F-03): "파생값 `effectiveAuthEnabled = authEnabled || ngrokEnabled`". 2차 코드는 `= authEnabled`다(같은 문서 8절 R2-06과 모순).
  2. spec 8절 R2-02: "`checksStalled`: 그 시각(마지막 성공 `lastOkAt`…)이 주기의 3배보다 오래됐다". CR-11 이후 코드는 마지막 **시도**(`lastCheckAt`) 기준이다. 같은 절 CR-11 항목과 모순된다. `RepoOverview(...)` 시그니처에도 `errors`, `warnings`가 빠져 있다.
  3. spec 8절 R2-05: "`ackNgrokOff()`를 부르면 (알림 문구가) 사라진다". QA2-04 이후 코드는 플래그가 둘이다(`ngrokOffAfterRestart` = 배너, `ngrokOffNotice` = 알림). `ackNgrokOff`는 배너만 지우고, 알림 문구는 서버 한 번 실행 동안 남는다(`app/Server.kt:81-87,266,328`).
  4. spec 3절 표의 `/repos` 설명에 `severity`가 없다(8절에는 있음). 응답 예시에도 2차 필드가 없다.
- 영향: spec은 UI·웹 담당이 코어를 읽는 유일한 계약서다(1차 리뷰의 운영 방식). 같은 문서 안에서 서로 다른 말을 하면 다음 사이클에 잘못된 쪽을 따라 구현하게 된다.
- 권장: 위 네 곳을 고친다. 바뀐 규칙은 한 곳(8절)에만 쓰고, 1~3절의 옛 문장은 지우거나 "8절 참고"로 바꾼다.

### R2ARC-05 [낮음] SAF 저장 폴더 이름 해석이 두 벌이고 결과도 다르다 — 지금 (작은 수정)

- 위치: `ui/Export.kt:102`(`savedText`), `app/Work.kt:179`(`ExportWorker.folderOf`)
- 문제: 같은 일(R2-10 AC2 "저장 위치 표시")을 스낵바와 알림이 따로 구현한다. UI는 공급자를 확인하지 않고 `substringAfter(':')` 뒤의 경로 전체를 보여 준다(`Documents/Backups`, `raw:`이면 `/storage/emulated/0/Download`). 워커는 공급자를 확인하고 마지막 폴더 이름만 쓴다(`Backups`). 같은 저장인데 화면과 알림의 문구가 다르고, 테스트(`folderOfSafDocuments`)는 워커 쪽에만 있다.
- 권장: UI도 `ExportWorker.folderOf(uri.authority, docId)`를 부른다(ui → app 의존은 이미 허용). `savedText`의 경로 해석은 지운다.

### R2ARC-06 [낮음] 작은 규칙이 여러 계층에 복사돼 있다 — 나중 (해당 파일을 고칠 때)

- 위치와 내용:
  - "PR 브랜치를 받는가" `includePullRefs ?: mirrorPullRefs`: `core/Sync.kt:166,252`, `core/RepoBrowser.kt:97`, `ui/screens/RepoDetailScreen.kt:324,366`, `ui/screens/AddScreens.kt:157`(6곳)
  - "외부 공개 중" `running && tunnel is Connected`: `ui/screens/MainScreen.kt:209`, `ui/screens/SettingsScreen.kt:126,178`, `app/Server.kt:291`(4곳, CR-10은 이 규칙이 한 곳에서 어긋나서 생겼다)
  - "확인 기준 시각" `lastCheckAt ?: createdAt`: `core/Sync.kt:85,101,507`, `ui/screens/RepoDetailScreen.kt:321`(다음 확인 시각)
- 권장: `MirrorSource.pullRefs(s: Settings)`, `MirrorSource.checkBase(m)`(또는 `RepoMeta` 확장), `TunnelState.isPublic(serverRunning)` 같은 한 줄 함수를 코어에 두고 호출부를 바꾼다. CR-10 같은 재발을 막는 가장 싼 방법이다.

### R2ARC-07 [낮음] `Authenticator.decide`의 `anonymousOk` 기본값이 `true`로 뒤집혔다 — 지금 (작은 수정)

- 위치: `core/Auth.kt:101`, `core/Settings.kt:47-49`
- 문제: 1차에는 기본값이 `!viaNgrok`(안전한 쪽)이었다. 2차에서 `true`(익명 허용 쪽)로 바뀌었다. 지금 운영 호출부(`GitServer`)는 값을 명시하므로 동작은 맞다. 하지만 새 호출부가 인자를 빼먹으면 SEC-03(직접 Host·사설망만 익명) 검사 없이 익명 읽기가 열린다. 테스트 몇 개가 기본값에 기대고 있다. 같은 변경으로 `effectiveAuthEnabled`/`effectiveAnonymousRead`는 저장값과 거의 같아졌는데, 이름은 아직 "ngrok 때문에 달라질 수 있음"을 암시한다.
- 권장: `anonymousOk`의 기본값을 없애 필수 인자로 만든다. `effective*` 두 속성은 다음에 Settings를 고칠 때 지우거나 R2ARC-02의 `authLevel`로 합친다.

### R2ARC-08 [낮음] archives.json이 "버려도 되는 이력"으로 취급되지만 유일한 원본이다 — 나중

- 위치: `core/RepoStore.kt:209-213`(`readList`, "History files are disposable"), `:238-250`, `MAX_ARCHIVE_NOTES = 10_000`
- 문제:
  - events/pushes와 같은 `readList`를 쓴다. 읽기 실패면 빈 목록이고, 다음 보존 때 빈 목록 기준으로 새로 쓴다. 손상 파일은 `JsonFile`이 따로 보존하지만 다시 읽히지 않으므로 보존 종류(kind)는 사실상 사라진다. events와 달리 이 파일은 200건 한도를 넘는 종류 정보의 **유일한** 출처다.
  - 보존이 일어날 때마다 최대 1만 건을 통째로 다시 쓴다(ARC-06과 같은 패턴). 강제 push는 드물어 지금 비용은 작다.
  - `archiveNotes`는 events.json(200건)까지 매번 함께 읽는다.
- 권장: 지금은 주석으로 "disposable이 아님, 손상 시 kind가 null로 보임"을 남긴다. 이력류를 SQLite나 JSONL로 옮길 때(ARC-06) 함께 옮긴다.

### R2ARC-09 [낮음] "재시작 뒤 ngrok 꺼짐" 상태가 앱 계층의 메모리 플래그 둘이다 — 나중

- 위치: `app/Server.kt:81-87,187,266,281,328`, `app/Core.kt` `BootReceiver`
- 문제: 같은 사건을 플래그 둘로 나타내고(배너용, 알림용), 4곳에서 지운다(서비스 수집기, `shutdown`, `ackNgrokOff`, ngrok 켜기). 규칙("한 번만", "서버 한 번 실행 동안")이 흩어져 있어 spec에 잘못 옮겨졌다(R2ARC-04-3). 메모리 상태라 BootReceiver 직후 프로세스가 죽으면 안내도 없이 사라진다(spec에 "프로세스가 끝나도 사라진다"로 명시돼 있으니 의도된 동작이다).
- 권장: 지금은 문서만 맞춘다. 더 바뀌면 `Settings`에 `ngrokOffAfterRestartAt: Long?` 하나를 저장하고, 배너는 "닫은 시각 < 그 시각", 알림은 "이번 서버 시작 시각 < 그 시각"으로 파생한다.

### R2ARC-10 [낮음] 보존 ref 형식과 "본 것으로 표시"의 책임이 화면에 있다 — 나중

- 위치: `ui/Format.kt:176`(`archiveGroupOf`), `ui/screens/ArchiveScreen.kt:326`, `core/Ops.kt` `archives`(주석 "Opening the screen should also call markHistorySeen")
- 문제: `refs/archive/<group>/…` 형식은 코어(`MirrorEngine`, `RepoBrowser.archives`)가 정한다. 그런데 UI가 문자열을 잘라 group을 다시 구한다. 또 "보존 기록을 열면 배지를 지운다"는 규칙을 호출하는 쪽이 기억해야 한다(앱만 지우고 웹 W-05는 지우지 않는다. 웹은 읽기 전용이므로 의도대로이지만 spec에 적혀 있지 않다).
- 권장: `RefChange.archiveGroup` 파생 속성을 코어에 두고 UI는 그것을 쓴다. `ops.archives(name, markSeen = true)`처럼 한 호출로 묶는다. 웹은 배지를 지우지 않는다는 점을 spec 8절 R2-01에 한 줄 적는다.

### R2ARC-11 [낮음] 웹 API에 확인 주기가 없어 웹 사유 문구가 앱과 다르다 — 나중

- 위치: `web/src/lib/issue.ts`(`DELAYED`: "주기를 모르니 생략"), `core/GitServer.kt` `/info`, `/overview`
- 문제: 앱은 "6시간 주기인데 2일째 확인 못 함", 웹은 "2일째 확인 못 함"이다. 판정은 같은데 데이터가 부족해서 문구가 다르다. 웹은 UI 문구 규칙(`running`, `issueText`)도 따로 갖고 있다(언어가 달라 불가피하다. 테스트는 있음).
- 권장: `RepoOverview`(또는 `/info`)에 `checkIntervalHours`를 하나 더한다. 필드 추가라 호환 문제는 없다.

### R2ARC-12 [낮음] TS 타입과 Kotlin DTO를 손으로 맞추는 면적이 커졌다(ARC-17 악화) — 나중 (테스트는 지금 싸다)

- 위치: `web/src/lib/api.ts`(`RepoIssue.badge`, `ArchiveKind`, `Overview`) ↔ `core/Sync.kt`(`RepoIssue`, `RepoOverview`), `core/RepoBrowser.kt`(`ArchiveEntryDto`)
- 문제: 2차에서 DTO가 4개 늘었다. `RepoIssue.badge`의 Kotlin 타입은 `Badge`(8개 값)이고, "5개 중 하나"라는 계약은 `Badges.issue`의 구현에만 있다. 웹 `issueText`의 `switch`는 모르는 값이면 `undefined`를 돌려준다. `GitServerTest`는 archives 응답에 문자열 두 개가 들어 있는지만 본다.
- 권장: `GitServerTest`에 `/repos`, `/overview`, `/archives` 응답의 **키 집합** 스냅샷 하나를 둔다. 웹 `issueText`에는 `default` 분기(배지 이름 표시)를 둔다. 빌드 산출물 커밋 문제(ARC-17 본문)는 그대로 나중이다.

### R2ARC-13 [낮음] 큰 파일이 더 커졌다(ARC-11, ARC-12 악화) — 나중

- 위치: `core/Sync.kt` 457 → 533줄(동기화 조정 + 배지 + `RepoIssue`/`Severity`/`RepoOverview` + 목록 질의), `core/GitServer.kt` 606줄, `ui/screens/RepoDetailScreen.kt` 572 → 652줄, `MainScreen.kt` 508줄
- 문제: 2차의 핵심 판정 모델(주의 상태)이 `Sync.kt` 안에 들어가, "주의 필요가 어떻게 정해지나"를 찾으려면 동기화 코드를 지나가야 한다. 상세 화면은 시트 두 개(내보내기, 분리)를 단계형으로 바꾸며 커졌다.
- 권장: 다음에 손댈 때 `Badges`·`RepoIssue`·`Severity`·`RepoListItem`·`RepoQuery`·`RepoOverview`를 `core/RepoStatus.kt`로 옮긴다(파일 이동만). `RepoDetailScreen`의 `ExportSheet`/`ForkSheet`는 `DetailSheets.kt`로 뺀다. 동작 변경은 없다.

### R2ARC-14 [낮음] 요약 숫자의 기준이 섞여 있다(미러 기준 + 전체 기준) — 나중

- 위치: `core/Sync.kt` `RepoQuery.overview`, spec 8절 CR-11
- 문제: `healthy`는 미러만 세고, `errors`/`warnings`/`attention`은 로컬(분리 후 `HISTORY_CHANGED`)까지 센다. "미러 12개 · 정상 11 · 주의 2"처럼 더해서 맞지 않는 줄이 나올 수 있다. 의도(필터 결과 수와 일치)는 spec에 적혀 있지만, 앱과 웹 화면이 각각 이 숫자로 문장을 만든다.
- 권장: 로컬의 `HISTORY_CHANGED`를 둘지 먼저 정한다. 전환(CONVERT)된 레포는 미러가 아니므로, 분리할 때 `historyChangedAt`을 지우는 것이 가장 단순하다(`core/Ops.kt:45`). 그러면 모든 수가 미러 기준이 된다.

### R2ARC-15 [낮음] 테스트 전략: 코어 판정은 잘 덮였고, 남은 빈칸은 경계와 예전 데이터 — 지금 (R2ARC-01과 함께)

- 현황: 판정(`Badges`, `RepoQuery.overview`, `checksStalled`, `RepoBrowser.summary`의 severity), 오류 분류, 보존 목록, R2-06 인증 경로(ngrok 포트로 익명 clone/push), USB 테더링 분류에 JVM 테스트가 있다. 웹은 `issue.test.ts`가 있다. 판정이 코어로 모였기 때문에 이 정도로 회귀를 막을 수 있다.
- 빈칸:
  1. **1차 형식 파일 재로딩**: 1차 meta.json(새 필드 없음) + `archivedAs`가 있는 events.json으로 `RepoStore`를 만들어 `HISTORY_CHANGED` 없음, `kind` 파생, `lastOkAt` 추정을 한 번에 확인하는 테스트가 없다(`lastOkAt` 추정은 `UnitTests`의 overview 단언에서 간접적으로만 확인됨). R2ARC-01에서 필드를 바꿀 때 꼭 필요하다.
  2. **앱·웹 동일성**: `RepoQuery.items(...)`와 `/api/v1/repos`가 같은 데이터에서 같은 `needsAttention`/`severity`/`lastActivityAt`을 내는지 비교하는 테스트 하나면 R2ARC-03 같은 차이를 잡는다.
  3. UI 쪽 판정(`authLevel`, `savedText`)은 테스트가 없다. 테스트를 추가하기보다 R2ARC-02/05처럼 코어로 옮기는 편이 낫다.

---

## 1차 아키텍처 지적의 상태 (2차 변경분 기준)

| ID | 2차 변경에서 | 비고 |
|---|---|---|
| ARC-01, ARC-02 | 유지 | 새 필드는 모두 기본값이 있다. 알 수 없는 enum은 항목 단위로 버린다. archives.json만 "disposable" 경로를 탄다(R2ARC-08) |
| ARC-03 | 유지 | `appendArchives`도 `synchronized(this)` 안에서 읽고 쓴다 |
| ARC-04 | 유지 | `appendArchives`가 `exists` 확인 |
| ARC-06 | 약간 악화 | archives.json 전체 재작성 추가(R2ARC-08) |
| ARC-08 | 약간 악화 | 보존이 있는 업데이트는 meta를 두 번 쓴다(`store.update` + `updateMirror`). 묶어도 된다 |
| ARC-10 | 그대로 | `:core` 모듈 분리 없음. core에 Android import 없음은 유지 |
| ARC-11, ARC-12 | 악화 | R2ARC-13 |
| ARC-13 | 개선 | 새 키 모두 의미 있는 이름 |
| ARC-14 | 그대로 | 새 한국어 예외 문장은 늘지 않음 |
| ARC-17 | 악화 | R2ARC-12 |

## 지금 / 나중 정리

| 시점 | ID |
|---|---|
| 지금 (데이터 모델, 다음 스키마 변경 전) | R2ARC-01, R2ARC-15 |
| 지금 (작은 수정, 각 몇 줄) | R2ARC-02, R2ARC-03, R2ARC-05, R2ARC-07 |
| 지금 (문서) | R2ARC-04 |
| 나중 | R2ARC-06, R2ARC-08, R2ARC-09, R2ARC-10, R2ARC-11, R2ARC-12(키 스냅샷 테스트는 지금 해도 싸다), R2ARC-13, R2ARC-14 |

2차의 계층 경계는 1차보다 좋아졌다. 판정이 코어로 모였고, 앱과 웹이 같은 함수를 쓴다. 남은 문제는 대부분 "작은 규칙의 복사본"(R2ARC-02/03/05/06)이고, 각각 코어 쪽 한 줄 함수 하나로 끝난다. 구조를 바꿀 일은 없다.

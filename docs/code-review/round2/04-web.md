# 코드 리뷰 2차 — 웹 (`web/`)

검토자 관점: React·TypeScript·Tailwind·shadcn/ui 프론트엔드. 대상은 2차 사이클 변경분 `git diff 0a481ef..HEAD -- web/`(빌드 결과물 제외, 14개 파일 +568/−110). 맥락은 `docs/web/progress.md` 12~13절, `docs/core/spec.md` 3·8절(R2-03 보존 기록, WR-6 `issue`, CR-11 `overview`·`severity`), 그리고 응답을 만드는 `core/RepoBrowser.kt`의 `archives()`.

확인한 것:
- `npm test` 11/11 통과, `tsc -b --noEmit` 오류 0. 저장소는 바꾸지 않았다.
- 실기기·브라우저 실행은 하지 않았다. 아래 내용은 코드와 레이아웃 수치로 판단했다(R2WEB-01은 Tailwind 폭 계산).

## 요약

| 심각도 | 개수 |
|---|---|
| 치명 | 0 |
| 높음 | 0 |
| 중간 | 2 |
| 낮음 | 9 |

---

## 중간

### R2WEB-01 768~950px 폭에서 보존된 기록 표의 "보존된 커밋" 열이 거의 0이 된다
- 위치: `web/src/pages/Archives.tsx:164-176` (`hidden md:block`, `table-fixed`, `w-36`·`w-44`·`w-36`·`w-60`), `web/src/App.tsx` `<main className="max-w-6xl px-4 sm:px-6">`
- 문제: 표는 `md`(768px)부터 나온다. 고정 열 네 개의 합이 144+176+144+240 = 704px인데, 768px 화면의 본문 폭은 768−48 = 720px이고 표 테두리를 빼면 남는 "보존된 커밋" 열은 약 14px다. `table-fixed`라서 고정 열은 줄지 않는다. 이 열은 SHA 칩, 복사 버튼, 커밋 첫 줄, 시각을 모두 담는다. 900px에서도 150px 정도라 첫 줄이 글자마다 끊긴다(아래 R2WEB-05의 `overflow-wrap: anywhere` 때문에 더 심하다).
- 실패 상황: 아이패드 세로(768·810·834), 폴더블 펼침, 또는 1600~1900px 창을 200%로 확대한 경우(CSS 폭 800~950). progress.md 13절은 1440과 390, 그리고 1440의 200%(720, 목록 보기)만 확인했다. 그래서 표 보기가 막 켜지는 이 구간은 확인하지 않았다.
- 권장: 표를 `lg`부터만 쓰거나(`hidden lg:block` / `lg:hidden`) 고정 폭을 줄인다. 예를 들어 이동 열을 `w-60`에서 `w-48`로 줄이고 종류 열은 `w-28`로 한다. "보존된 커밋" 열에는 `min-w`를 주기 어려우니 breakpoint를 옮기는 쪽이 가장 작은 수정이다.

### R2WEB-02 웹이 모르는 `kind` 값이 오면 화면 전체가 하얗게 된다
- 위치: `web/src/pages/Archives.tsx:93-99` (`kindStyle[kind ?? "UNKNOWN"]` 뒤 `k.t`), `web/src/lib/api.ts` `ArchiveKind`, 코어 `RepoBrowser.kt:74-75` (`kind: RefChangeKind?`)
- 문제: 코어 DTO의 타입은 `RefChangeKind?`이다. 이 enum은 `FAST_FORWARD`, `CREATED`를 포함한 6개 값이다(spec 8절 R2-04). 웹 타입과 표는 4개만 안다. 종류는 `archives.json`과 "남은 이력"에서 찾아 붙이므로(`notes[r.name]?.kind`) 이력에서 다른 종류가 붙을 수 있다. 그러면 `k`가 `undefined`가 되어 `TypeError`가 난다. 앱에는 Error Boundary가 없어서 React 트리가 통째로 내려가고 빈 화면만 남는다. 같은 구조가 `issueIcon`·`issueHint`·`issueText`(`common.tsx`, `issue.ts`)에도 있다. 그쪽은 터지지 않고 아이콘과 글자가 빈 배지가 된다.
- 권장: `kindStyle[kind as ArchiveKind] ?? kindStyle.UNKNOWN` 한 줄이면 된다. `issueText`의 `switch` 끝에도 `default: return issueLabel[i.badge] ?? "주의 필요"`를 둔다. 덧붙여 `App` 최상단에 작은 Error Boundary(ErrorState + 새로 고침)를 두면 다른 렌더 오류도 빈 화면이 되지 않는다.

## 낮음

### R2WEB-03 `DELAYED`의 `since`가 null이면 지금 시각을 "부터"로 보여 준다
- 위치: `web/src/lib/issue.ts:38` (`running(i.since ?? now, now)`)
- 문제: `since`가 null이면 `running(now)`가 `clockTime(now)+"부터"`를 돌려준다. 그래서 "16:35부터 확인 못 함"처럼 방금 시작된 것처럼 보이는데, 실제로는 기간을 모르는 상태다. spec 8절에서 `since`는 "epoch ms 또는 null"이다. 테스트에도 이 경우가 없다.
- 권장: 다른 배지처럼 `i.since ? \`${running(i.since, now)} 확인 못 함\` : "확인 지연"`으로 쓰고 테스트 한 줄을 더한다.

### R2WEB-04 묶음 시각을 해석하지 못하면 제목이 " · 3개"가 된다
- 위치: `web/src/pages/Archives.tsx:67-71`, 코어 `RepoBrowser.kt:329` (`at = archiveTime(group) ?: 0L`)
- 문제: 묶음 이름이 시각 형식이 아니면 코어가 `at = 0`을 준다. 예를 들어 사용자가 직접 만든 `refs/archive/foo/...`가 그렇다. 웹은 `absoluteTime(0)`이 `""`라서 제목이 " · 3개"가 되고, `aria-label`은 "에 보존"이 된다. 반면 `<time dateTime>`은 1970년이다.
- 권장: `g.at`이 0이면 시각 대신 `g.group`(묶음 이름)을 제목으로 쓴다.

### R2WEB-05 body 전체의 `overflow-wrap: anywhere`가 모든 요소의 최소 폭을 바꾼다
- 위치: `web/src/index.css:144-145`
- 문제: `anywhere`는 `break-word`와 달리 min-content 계산에 들어간다. 이 값이 상속되어 표 칸, flex 항목, 배지가 한 글자 폭까지 줄어들 수 있다. 그 결과 넘치는 대신 세로로 길게 쪼개진다. 이 규칙이 들어오면서 `dt`에 `whitespace-nowrap`을 덧대야 했다(`RepoTree.tsx` AboutCard). R2WEB-01의 좁은 열이 글자마다 끊기는 것도 이 때문이다. `code, pre`는 `word-break`만 되돌렸고 `overflow-wrap`은 그대로 상속된다.
- 권장: body는 `overflow-wrap: break-word`로 바꾼다. 줄바꿈 동작은 같고 최소 폭은 건드리지 않는다. 긴 URL·경로를 표시하는 곳에는 이미 `break-all`·`BreakableText`가 있다.

### R2WEB-06 `tap`의 보이지 않는 영역이 이웃 버튼을 덮을 수 있다
- 위치: `web/src/index.css:126-136` (`inset: -12px -4px`), `web/src/components/RepoLayout.tsx:30-34`
- 문제: `::after`가 위아래로 12px 넓어지고, DOM에서 뒤에 오므로 위에 그려진다. ClonePanel은 `gap-2`(8px)이다. 그래서 로컬 레포(`readOnly` 문장이 없음)에 보존 기록이 있으면 "보존된 기록 N개 →" 링크의 영역이 바로 위 "clone 명령" 복사 버튼(터치 44px)의 아래 4px를 덮는다. 또 "→"는 스크린 리더가 "오른쪽 화살표"로 읽는다.
- 권장: 이 링크에 `mt-1` 이상을 준다(`tap`을 쓰는 링크 위아래에는 4px 이상 여유를 두는 것을 규칙으로 삼는다). 화살표는 `<span aria-hidden>→</span>`.

### R2WEB-07 모든 코드 칸이 이름 없는 Tab 정지점이 된다
- 위치: `web/src/components/common.tsx:185` (`<code tabIndex={0}>`)
- 문제: 가로 스크롤을 키보드로 하려고 늘 `tabIndex=0`을 준다. 레포 홈 사이드바, clone 팝오버, PushGuide, 보존된 기록에서 칸마다 정지점이 하나씩 늘어나는데, 대부분은 넘치지 않는다. 역할과 이름이 없어서 스크린 리더는 "clone 주소 복사" 버튼 앞에서 이름 없는 요소를 만난다.
- 권장: `role="region" aria-label={label 에서 " 복사"를 뺀 값}`을 준다. 넘칠 때만 포커스를 받게 하려면 `scrollWidth > clientWidth` 검사가 필요한데, 그건 생략해도 된다.

### R2WEB-08 목록 요약 줄의 자잘한 문제
- 위치: `web/src/pages/RepoList.tsx:33,70-76,154-180`
- 문제:
  - "마지막 확인 10분 전"은 렌더 시점에 한 번만 계산되고, overview도 다시 요청하지 않는다. 탭을 열어 두면 시간이 계속 그대로다. 코어는 1분마다 다시 계산한다.
  - overview가 오기 전에는 설명 문장이 보이다가 요약으로 바뀐다. 줄 수가 달라서 아래 목록이 밀린다.
  - 버튼인지 아닌지를 `p.text !== "모두 정상"`이라는 문자열 비교로 정한다. 문구를 바꾸면 "모두 정상"이 버튼이 된다.
  - `checksStalled` 배너의 제목과 본문이 같은 말을 반복한다("멈춘 것 같아요" / "멈췄어요").
- 권장: parts에 `filter: true` 같은 플래그를 두고 그 값으로 버튼을 정한다. 요약 자리에는 overview를 기다리는 동안 한 줄 높이의 `Skeleton`을 둔다. 본문은 "폰의 배터리 최적화에서 mobilegit을 빼면 다시 확인해요."만 남긴다. 시간 갱신은 다른 화면처럼 다시 불러올 때 갱신되는 것으로 두어도 된다.

### R2WEB-09 보존된 기록이 표와 목록을 둘 다 그리고, 같은 링크가 두 번 있다
- 위치: `web/src/pages/Archives.tsx:74-75,117-119,147-152`
- 문제: `GroupTable`과 `GroupList`를 늘 둘 다 렌더하고 CSS로 하나를 숨긴다. 그래서 DOM, 복사 버튼, Tooltip이 두 배다. 기록 수가 적으니 성능 문제는 작다. 또 행마다 SHA 칩과 "커밋 로그" 버튼이 같은 주소(`repoHref.commits(repo, "", e.sha)`)라서, 스크린 리더와 Tab 순서에서 같은 목적지가 두 번 나온다.
- 권장: 지금 구조는 유지해도 된다. 중복 링크는 SHA 칩을 링크가 아닌 텍스트로 바꾸고 "커밋 로그"만 남긴다. 지금 원본 열의 SHA 링크는 유일한 이동 수단이니 그대로 둔다.

### R2WEB-10 새 로직의 테스트 빈틈
- 위치: `web/src/lib/issue.test.ts`, `web/src/pages/Archives.tsx:39-43`, `web/src/pages/RepoList.tsx:154-163`
- 문제: 묶음 나누기(연속한 `group` 기준), 종류 대응표, 요약 줄 규칙(정상·오류·주의·모두 정상의 조합)은 컴포넌트 안에 있어 테스트할 수 없다. `issue.test.ts`에는 `DELAYED`의 since null(R2WEB-03), `HISTORY_CHANGED`의 since null, 모르는 `category`("알 수 없는 오류"), `ago`의 7일 이상 날짜 분기가 없다.
- 권장: `groupArchives(entries)`와 `summaryParts(o)`를 `lib/`의 순수 함수로 빼고 표 형태 테스트를 몇 개 둔다. `issue.test.ts`에는 위 네 경우를 한 줄씩 더한다.

### R2WEB-11 지울 수 있는 코드
- 위치와 내용:
  - `web/src/lib/issue.ts:5` `issueLabel`: export만 있고 쓰는 곳이 없다. R2WEB-02의 fallback으로 쓰지 않을 거라면 지운다.
  - `web/src/components/common.tsx:167` `BreakableText`의 `(?![/*])`: 받는 명령 때문에 넣었는데, 그 명령은 이제 `CodeField`(`whitespace-pre`)로만 표시된다. 남은 사용처(원본 URL, 서브모듈 URL)에는 `/*`가 없다. 되돌려도 된다.
  - `web/src/pages/RepoTree.tsx:138,146` `EntryRow`의 `lfs` prop: 이미 넘기는 `entry.lfs`와 같은 값이다. `e.lfs`를 직접 쓴다.
  - `web/src/lib/format.ts:17` `ago`의 날짜 조립: 바로 위의 `ymd`·`pad`와 같은 코드를 다시 썼다. `return ymd(new Date(t))`로 줄인다.

---

## 잘 된 점
- WEB-06 교훈을 새 페이지에도 적용했다. `Archives`가 `usePrefetch`로 상세와 보존 기록을 동시에 요청한다. 오류는 공용 `ErrorState`(BUSY 재시도 포함)를 쓴다.
- 라우팅이 좁고 정확하다. `/r/<repo>/archives`는 path가 없을 때만 받고, `/archives/foo`는 404로 간다. `repoHref.archives`는 인코딩 테스트가 있고, 문서 제목에도 반영된다.
- null 처리가 spec과 맞는다. `kind: null`은 "강제 push 또는 삭제", `currentSha: null`은 "없음", `summary`·`committedAt`이 null이면 생략, `includePullRefs: null`(로컬)이면 행 생략, `Time`의 `empty`로 "첫 받기 전"/"push 전"을 보인다.
- 날짜를 `Intl` 대신 직접 조립했다. 브라우저 언어와 관계없이 앱과 같은 `2026.10.25 16:35`이고 테스트로 고정되어 있다. 상대 시간도 앱의 `ago`·`running`과 같은 규칙이다.
- 보존 ref를 RefPicker에 넣지 않았고(F-18 AC4), SHA 기준 로그와 트리로 바로 이동할 수 있다(`isSha` 처리 재사용).
- 상태 판단(`needsAttention`, `severity`, overview 숫자)을 웹이 다시 계산하지 않고 코어 값을 그대로 쓴다. 앱과 숫자가 어긋날 여지를 없앴다(이전 `hasProblem` 삭제).
- 터치 영역(`pointer-coarse:` 44px), 링크 색 대비 조정, 배지의 `sr-only` 설명처럼 접근성 리뷰 항목을 코드 수준에서 반영했다.

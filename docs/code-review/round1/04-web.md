# 코드 리뷰 1차 — 웹 (`web/`)

검토자 관점: React·TypeScript·Vite·Tailwind·shadcn/ui 프론트엔드. 대상은 `web/src` 전체, `vite.config.ts`, `tsconfig*.json`, `package.json`, `public/theme.js`, 그리고 연동되는 `docs/core/spec.md`(CSP, README, blob 한도)와 `GitServer.kt`의 정적 서빙 CSP.

확인한 것(스크래치 복사본에서, 저장소는 건드리지 않음):
- `npm ci`, `npm test` 3/3 통과, `npm run build` 성공(`tsc -b` 오류 0). TS 6의 기본값으로 `strict`가 켜져 있고 `--strict`를 명시해도 오류 0.
- 번들: `index.js` 451KB(gzip 140KB), highlight.js 지연 청크 154KB(gzip 53KB), CSS 98KB(gzip 16KB). 빌드된 `index.html`에 인라인 스크립트·스타일 없음. 번들에 `react-remove-scroll`이나 `<style>` 주입 코드 없음.
- `oxlint`: 경고 5건(fast-refresh 내보내기 규칙, `theme.js` 미사용 catch 변수). 실제 문제 아님.
- 실기기·브라우저 실행은 하지 않았다. 아래 WEB-02는 Radix 동작에 근거한 코드상 판단이다.

## 요약

| 심각도 | 개수 |
|---|---|
| 치명 | 0 |
| 높음 | 1 |
| 중간 | 5 |
| 낮음 | 9 |

---

## 높음

### WEB-01 폴더를 가리키는 blob 주소에서 뒤로 가기가 막힌다
- 위치: `web/src/pages/FileView.tsx:61-63`
- 문제: blob API가 `NOT_A_FILE`을 주면 `navigate(repoHref.tree(...))`로 트리로 보내는데, `replace` 없이 **push**한다. 오류는 `useApi` 캐시에 남지 않으므로 다시 마운트될 때마다 같은 요청과 같은 push가 반복된다.
- 실패 상황: README의 `[문서](docs)`(끝 슬래시 없음) 링크 클릭 → 히스토리 `[레포 홈, /r/x/blob/docs, /r/x/tree/docs]`. 뒤로 가기 → `/blob/docs`가 다시 마운트 → 같은 오류 → 또 `/tree/docs`로 push. 사용자는 레포 홈으로 돌아갈 수 없고, 뒤로 가기를 아주 빠르게 두 번 눌러야만 빠져나온다. README 안의 폴더 링크는 흔하다.
- 권장: `navigate(..., { replace: true })`. 근본적으로는 `rewriteReadme`가 이미 트리 링크를 만들 수 있게 tree 응답의 엔트리 종류를 알 때만 blob으로 보내거나, 지금처럼 리다이렉트하되 반드시 replace.

## 중간

### WEB-02 http(LAN)에서 팝오버 안의 복사 버튼을 누르면 팝오버가 닫히고 포커스가 사라진다
- 위치: `web/src/components/common.tsx:99-114` (사용처 `RepoLayout.tsx:96-110` clone 팝오버, `RepoList.tsx` `PushGuide`)
- 문제: 폰 서버는 보통 평문 http라 `navigator.clipboard`가 없어 항상 대체 경로를 탄다. 대체 경로는 `<textarea>`를 `document.body`에 붙여 `select()`로 포커스를 옮긴다. Radix 비모달 Popover는 바깥으로 포커스가 나가면(`onFocusOutside`) 닫힌다. 그 뒤 textarea가 지워져 포커스는 `body`로 간다. 또 `execCommand("copy")`의 반환값을 보지 않아 실패해도 "복사했어요"를 보여 준다.
- 실패 상황: "clone 주소" 팝오버에서 주소를 복사 → 팝오버가 바로 닫혀 체크 표시를 볼 수 없고, 이어서 "clone 명령"을 복사하려면 다시 열어야 한다. 키보드 사용자는 포커스를 잃고 페이지 처음부터 다시 Tab해야 한다. "PC 프로젝트 올리기"의 두 명령도 같다.
- 권장: textarea를 버튼의 부모(팝오버 내부, `e.currentTarget.parentElement`)에 붙이고, 복사 후 `button.focus()`로 되돌린다. `execCommand` 결과가 false면 "복사 실패, 직접 선택하세요"를 보인다. 복사 결과는 `aria-live="polite"` 영역으로도 알린다(지금은 툴팁 글자만 바뀜).

### WEB-03 다시 불러오기가 실패하면 이미 보이던 내용이 오류 화면으로 바뀌고, 재시도 수단이 없다
- 위치: `web/src/lib/api.ts:136-159`, `web/src/components/common.tsx:168-188`
- 문제: `useApi`는 캐시된 데이터를 먼저 보여 주고 매번 다시 요청한다. 그 요청이 실패하면 `setState({ url, error })`로 `data`를 버리므로 캐시가 있어도 화면 전체가 `ErrorState`가 된다. `ErrorState`에는 "다시 시도"가 없고 "레포 목록으로"만 있다. 오류는 캐시하지 않으면서 재시도 트리거도 없다.
- 실패 상황: 폰 화면이 꺼지거나 Wi-Fi가 잠깐 끊겼을 때 뒤로 가기 → 방금 보던 트리가 캐시로 그려졌다가 곧 "연결할 수 없어요"로 덮인다. 사용자는 새로 고침밖에 방법이 없다.
- 권장: 데이터가 있으면 오류를 상단 배너(`Alert`)로만 띄우고 데이터를 유지. `useApi`가 `reload()`를 돌려주고 `ErrorState`에 "다시 시도" 버튼을 둔다. 함께: `AbortController`로 이전 요청을 취소(폰 서버의 git 작업 부담 감소), 캐시 크기 상한(아래 WEB-10).

### WEB-04 큰 텍스트 파일 표시가 메인 스레드를 오래 막는다
- 위치: `web/src/pages/FileView.tsx:192-248` (행 렌더 229-243), `web/src/lib/highlight.ts:36-42`
- 문제: blob 텍스트는 1MB까지 오고(spec), `CodeView`는 모든 줄을 `<tr><td><a/></td><td/></tr>`로 그린다. 1MB(2~3만 줄)면 DOM 노드가 10만 개 이상이다. 하이라이트(256KB 이하)는 메인 스레드에서 `hljs.highlight`를 돌린 뒤 **모든 행을 다시** 그린다. 줄바꿈 토글도 전체 재렌더다. 256KB짜리 한 줄 minified JS는 하이라이트만으로 수 초 걸릴 수 있다.
- 실패 상황: `package-lock.json`(수백 KB~1MB)이나 생성 코드 열기 → 수 초간 탭이 멈추고, 하이라이트 완료 시 한 번 더 멈춘다. 줄 링크(`?L=`) 클릭마다 `line` 변경으로 전체 행이 다시 렌더된다(`n === line` 비교).
- 권장: 하이라이트 한도를 바이트 외에 줄 수·최대 줄 길이로도 제한(예: 5천 줄, 한 줄 1만 자 초과면 생략), 하이라이트는 Web Worker로(지연 청크 그대로 사용 가능). 행 수가 많으면 가상 스크롤(이미 progress.md "남은 문제"에 있음) 또는 최소한 행을 `memo`된 컴포넌트로 분리해 `line`/`wrap` 변경 시 바뀐 행만 갱신. 줄 강조는 `:target`이나 `data-` 속성 + CSS로 처리하면 재렌더가 필요 없다.

### WEB-05 페이지 이동 시 문서 제목·포커스가 바뀌지 않는다
- 위치: `web/src/App.tsx:28-39`, `web/src/lib/router.ts:55-60`
- 문제: 모든 화면의 `<title>`이 "mobilegit"이다. 클라이언트 라우팅 후 포커스 이동이나 알림이 없다.
- 실패 상황: 브라우저 히스토리·탭·북마크가 전부 "mobilegit"이라 구분이 안 된다(뒤로 가기 길게 누르기 목록도 동일). 스크린 리더 사용자는 링크를 눌러도 화면이 바뀐 것을 알 수 없고, 포커스는 사라진 링크 자리(`body`)에 남는다.
- 권장: 각 페이지에서 `useEffect(() => { document.title = \`${path || repo} · ${repo} · mobilegit\` })` (React 19는 컴포넌트 안 `<title>`도 head로 올려 준다). `navigate` 후 `<main>`의 `h1`(또는 `tabIndex=-1`인 `main`)에 포커스.

### WEB-06 레포 화면 첫 로드가 요청 3개를 직렬로 기다린다
- 위치: `web/src/components/RepoLayout.tsx:56-59,128`, `web/src/pages/RepoTree.tsx:40,75`
- 문제: `RepoLayout`이 레포 상세를 받기 전에는 `children(detail)`을 그리지 않으므로 트리 요청이 시작되지 않고, README 요청은 트리를 받은 뒤에야 시작된다. 트리·blob·커밋 요청은 `repo`, `ref`, `path`만 있으면 되고 detail에 의존하지 않는다(`refParam`이 null이면 서버가 기본 브랜치를 쓴다).
- 실패 상황: Tailscale/ngrok처럼 왕복이 200~500ms인 경로로 레포 홈을 처음 열면 detail → tree → readme가 순서대로 걸려 1초 이상 스켈레톤만 보인다. 폰 서버가 바쁠 때 더 길어진다.
- 권장: 본문 요청을 `RepoLayout` 밖(페이지 컴포넌트 최상단)에서 동시에 시작하거나, `children`을 detail 없이도 렌더하고 PathBar만 detail을 기다리게 한다. README는 트리 응답의 엔트리 대신 처음부터 요청하고 404(`README_NOT_FOUND`)를 "없음"으로 처리하면 직렬 의존이 사라진다.

## 낮음

### WEB-07 커밋 날짜 묶음의 React key가 중복될 수 있다
- 위치: `web/src/pages/Commits.tsx:30-35,62`
- 문제: 묶음은 "직전과 날짜가 다를 때" 새로 만들고 key는 날짜 문자열이다. git log 순서는 커밋 시각 순이 보장되지 않아(머지, 시계 차이, cherry-pick) 같은 날짜가 떨어져 두 번 나오면 key가 겹친다.
- 실패 상황: 콘솔에 duplicate key 경고, 페이지 이동 시 React가 같은 key 섹션을 잘못 재사용해 행이 남거나 빠질 수 있다.
- 권장: `key={\`${i}-${g.day}\`}` 또는 첫 커밋 sha를 key로.

### WEB-08 브랜치·태그가 많으면 RefPicker가 느리다
- 위치: `web/src/components/RefPicker.tsx:30-57`
- 문제: cmdk는 모든 항목을 DOM에 그리고 입력마다 전체를 점수 매긴다. 큰 미러(태그 수천 개)에서 팝오버 열기·타이핑이 버벅인다. 또 `value`에 `branch:`/`tag:` 접두사가 있어 "tag"를 치면 모든 태그가 걸린다.
- 권장: 항목이 수백 개를 넘으면 `shouldFilter={false}`로 직접 부분 문자열 필터 후 상위 N개(예: 100)만 그리고 "N개 더, 검색으로 좁히세요"를 표시. 필터 키는 이름만.

### WEB-09 코드·README 탭이 링크가 아니라 버튼이다
- 위치: `web/src/components/RepoLayout.tsx:114-125`
- 문제: Radix `Tabs`를 `TabsContent` 없이 내비게이션에 쓴다. 트리거는 `<button role="tab">`이라 가운데 클릭·새 탭 열기·주소 복사가 안 되고, `aria-controls`가 없는 요소를 가리킨다. 자동 활성화라 화살표 키로 포커스만 옮겨도 페이지가 이동한다.
- 권장: `<nav>` 안의 `<a href aria-current="page">`로 바꾸고 기존 탭 스타일 클래스만 재사용.

### WEB-10 API 캐시에 상한이 없다
- 위치: `web/src/lib/api.ts:133-134`
- 문제: 탭이 살아 있는 동안 모든 응답(blob은 최대 1MB 텍스트)이 `Map`에 쌓인다.
- 실패 상황: 한 탭으로 오래 파일을 훑으면 메모리가 수십~수백 MB까지 늘어난다.
- 권장: 삽입 순서를 이용한 간단한 LRU(예: 100개, blob은 별도 소수) — `Map`에서 오래된 키 `delete`.

### WEB-11 줄 번호 링크 수만 개가 Tab 순서에 들어가고 대비가 낮다
- 위치: `web/src/pages/FileView.tsx:233-234`
- 문제: 모든 줄 번호가 포커스 가능한 `<a>`라 큰 파일 뒤의 내용(꼬리말 등)에 키보드로 닿으려면 수천 번 Tab해야 한다. 글자색 `text-muted-foreground/70`은 라이트에서 약 #838b95로 흰 바탕 대비 약 3.5:1이다.
- 권장: 줄 번호 링크에 `tabIndex={-1}`(마우스 전용 기능이므로; 키보드용은 "줄로 이동" 입력 등 별도), 색은 불투명도 없는 `text-muted-foreground`.

### WEB-12 목록 행의 덮개 링크가 배지 설명을 가린다
- 위치: `web/src/pages/RepoList.tsx:149` (`after:absolute after:inset-0`), 배지 `common.tsx:58-81`
- 문제: 행 전체를 덮는 `::after`가 원본 상태·LFS 배지 위에 올라와 `title` 툴팁이 마우스로도 나오지 않는다. `title`은 원래 키보드·터치·스크린 리더에 전달되지 않는다.
- 권장: 배지에 `relative z-10`을 주고, 중요한 설명은 `title` 대신 Tooltip 또는 `sr-only` 텍스트로.

### WEB-13 README의 목차(#) 링크가 모두 죽는다
- 위치: `web/src/lib/readme.ts:32`
- 문제: 서버가 heading id를 만들지 않아 `#...` 링크의 `href`를 지운다. 긴 README의 목차가 클릭 불가 텍스트가 된다.
- 권장: `rewriteReadme`에서 `h1~h6`에 GitHub 방식 slug id(`user-content-` 접두사)를 붙이고 `#` 링크를 살린다. 인터셉터는 이미 같은 페이지 해시를 브라우저에 맡긴다(`router.ts:72`).

### WEB-14 1MB를 넘는 이미지를 미리 보지 않는다
- 위치: `web/src/pages/FileView.tsx:162`
- 문제: 이미지 표시 조건에 `!blob.tooLarge`가 있다. `tooLarge`는 텍스트 한도인데 바이너리에도 걸려(spec 예시) 1MB 넘는 PNG 스크린샷이 "너무 커서 표시할 수 없어요, 1MB를 넘는 파일은 내용을 보여 주지 않아요"로 나온다. raw는 이미지를 inline으로 주므로 `<img>`는 동작한다.
- 권장: 이미지는 크기와 무관하게(또는 예: 20MB 한도) `<img loading="lazy">`로 보여 준다. 의도된 제한이라면 문구를 "이미지가 커서 미리 보지 않아요"로.

### WEB-15 테스트 범위가 좁고 테스트 파일은 타입 검사되지 않는다
- 위치: `web/package.json:11`, `web/tsconfig.app.json:27`, `web/src/lib/highlight.test.ts`
- 문제: 테스트는 `splitHtmlLines`·`languageFor` 3건뿐이다. 회귀가 잦을 순수 함수 — `readme.ts`의 `resolve`/`rewriteReadme`(상대 링크, `..`, 외부 이미지), `router.ts`의 `href`(인코딩, ref 생략), `format.ts` — 가 비어 있다. `*.test.ts`는 `tsconfig.app.json`에서 제외되고 node 타입 스트리핑으로만 실행돼 타입 오류가 잡히지 않는다.
- 권장: `href`와 `resolve`에 표 형태 테스트 몇 개 추가(`rewriteReadme`는 DOM이 필요하니 `resolve`만 export). 테스트용 `tsconfig.test.json`(types: node)을 `tsc -b` 참조에 추가.

---

## 잘 된 점
- CSP를 일관되게 지켰다: 테마 초기화를 `public/theme.js` 외부 파일로, `assetsInlineLimit: 0`, 모달 메뉴의 `<style>` 주입을 피하려 `DropdownMenu modal={false}`. 빌드 결과에도 인라인이 없다.
- README는 서버(commonmark + jsoup 허용 목록)에서 정화하고, 웹은 비활성 `<template>`에서 링크·이미지만 고친다. `script-src 'self'`라 정화가 새도 `javascript:` 링크는 실행되지 않는 이중 방어다. 외부 이미지를 링크로 바꾸고 배지 링크 중첩까지 처리했다.
- highlight.js를 지연 청크로 분리하고 크기 한도를 뒀다. hljs 출력은 이스케이프돼 있어 `splitHtmlLines` 후 `innerHTML`이 안전하고, 줄 간 span 재개 처리가 테스트로 고정돼 있다.
- 라우터가 작고 올바르다: 실제 `<a href>`를 유지해 새 탭·주소 복사가 되고, 수정 키·`download`·`target`·`/api/`는 브라우저에 맡긴다. 옛 해시 주소 호환까지 있다.
- `useApi`가 URL 단위 상태와 `alive` 플래그로 경쟁 상태(늦게 온 이전 응답)를 막고, 캐시로 뒤로 가기 때 즉시 그린다.
- 상태 배지는 아이콘+글자로 색에만 의존하지 않고, 빈 상태·오류 상태·스켈레톤이 화면마다 갖춰져 있다. 줄 번호를 `::before`로 그려 복사에 섞이지 않게 한 것도 좋다.
- 의존성이 shadcn 표준 구성에서 벗어나지 않고, 마크다운 라이브러리를 웹에 넣지 않아 번들이 작다(첫 방문 gzip 약 200KB).

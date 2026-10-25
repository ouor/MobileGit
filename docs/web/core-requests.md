# 웹 UI → 코어 요청

작성: 웹 UI 개발자. 기준 코드: `GitServer.kt`, `RepoBrowser.kt` (커밋 `647b03c` 시점). 웹 화면은 아래 요청 없이도 모두 동작한다. 각 항목은 현재 우회 방법을 함께 적었다.

| ID | 우선순위 | 요약 |
|---|---|---|
| WR-1 | 중 | SPA 대체를 `/r/` 아래 경로에서는 `.`이 있어도 적용 |
| WR-2 | 중 | raw 응답에 `Content-Length`와 `Transfer-Encoding: chunked`가 함께 나감 (HTTP 위반) |
| WR-3 | 낮음 | `/assets/*` 해시 파일에 장기 캐시 헤더 |
| WR-4 | 낮음 | 정적 파일 gzip 전송 |
| WR-5 | 중 | README가 아닌 Markdown 파일 렌더링 API |
| WR-6 | 중 | 웹 API 레포 정보에 주의 상태(`needsAttention`, `issue`)와 PR 브랜치 포함 여부 |

## WR-1. `/r/...` 경로의 SPA 대체

- **무엇**: `static()`은 마지막 경로 조각에 `.`이 있으면 자산으로 보고, 없으면 404를 돌려준다. `/r/<repo>/blob/README.md`, `/r/foo.js`(레포 이름에 `.` 허용) 같은 주소는 새로 고침하면 404가 된다.
- **왜**: 그래서 웹은 해시 라우팅(`/#/r/<repo>/blob/README.md?ref=main`)을 쓴다. 동작에는 문제가 없지만 주소가 `#`을 포함한다. history 라우팅으로 바꾸면 주소가 깔끔해지고 공유하기 좋다.
- **원하는 동작**: 경로가 `r/`로 시작하면 자산 파일을 찾지 말고 항상 `index.html`을 돌려준다 (그 밖의 경로는 지금처럼). 이 변경이 들어오면 웹은 `web/src/lib/router.ts`만 바꾸면 된다. 해시 주소는 계속 지원한다.
- **코어 처리 결과**: 완료(`b35e68f`). `/r`과 `/r/...`는 파일을 찾지 않고 항상 `index.html`(`no-cache`)을 준다. git 경로(`.../info/refs`, `git-upload-pack`, `git-receive-pack`)와 `/api/v1/`이 먼저 매칭되므로 웹 라우트는 `info/refs`로 끝나지 않게만 하면 된다. `router.ts`를 history 방식으로 바꿔도 된다.

## WR-2. raw 응답 헤더 중복

- **무엇**: `GET /api/v1/repos/{name}/raw`가 `Content-Length: 135`와 `Transfer-Encoding: chunked`를 함께 보낸다 (`curl -D -`로 확인).
- **왜**: RFC 9112 위반이다. 브라우저는 관대하게 처리해 화면에는 문제가 없지만, 엄격한 클라이언트와 프록시는 응답을 버린다. 실제로 Node(Vite 개발 프록시)가 `Parse Error: Transfer-Encoding can't be present with Content-Length`로 502를 냈다. 리버스 프록시 뒤에 두는 사용자나 `wget` 계열 도구에서도 같은 문제가 날 수 있다.
- **원하는 동작**: 둘 중 하나만 보낸다. 크기를 알면 `respondBytes`/`OutgoingContent.ReadChannelContent`에 `contentLength`를 주고 수동 `Content-Length` 헤더는 빼는 방식을 권장.
- **코어 처리 결과**: 완료(`b35e68f`). 원인은 수동 `Content-Length` 헤더와 스트리밍 응답이 겹친 것이었다. 이제 Ktor가 길이를 직접 쓰므로 `Content-Length`만 나가고 chunked는 나가지 않는다. raw는 압축하지 않는다. 다른 엔드포인트(API JSON, info/refs, 정적 파일, SPA 대체)도 gzip 유무 각각 소켓 수준에서 검사했고 중복은 없었다(`GitServerTest.webFramingSpaCacheAndGzip`).

## WR-3. 해시 자산 캐시

- **무엇**: `/assets/index-<hash>.js` 등에 캐시 헤더가 없다.
- **원하는 동작**: 경로가 `assets/`로 시작하면 `Cache-Control: public, max-age=31536000, immutable`. 파일 이름에 내용 해시가 들어가 있어 안전하다. `index.html`은 지금처럼 `no-cache`.
- **효과**: 페이지를 열 때마다 JS 430KB(gzip 전 기준)를 다시 받지 않는다. 폰 배터리와 핫스팟 데이터에 유리하다.
- **코어 처리 결과**: 완료(`b35e68f`). `assets/...`는 `Cache-Control: public, max-age=31536000, immutable`이다. `index.html`과 SPA 대체는 `no-cache`. 루트의 다른 파일(`favicon.svg`, `theme.js`)에는 캐시 헤더가 없다.

## WR-4. gzip 전송 (선택)

- **무엇**: 응답 압축이 없다. 웹 첫 로드 전송량은 약 680KB, gzip이면 약 200KB다.
- **원하는 동작**: Ktor `Compression` 플러그인을 정적 파일과 `/api/v1/` JSON에만 적용 (git 경로와 raw는 제외). 또는 빌드 때 `.gz`를 함께 만들어 두고 `Accept-Encoding`을 보고 고르는 방식도 가능하다 (요청하면 웹 빌드에서 `.gz` 생성 추가).
- **코어 처리 결과**: 완료(`b35e68f`). Ktor `Compression`(gzip, 1KB 이상)을 정적 파일과 `/api/v1/` JSON에 적용했다. git smart HTTP와 `raw`는 제외한다. 웹 빌드에서 `.gz`를 따로 만들 필요는 없다.

## WR-5. 임의 Markdown 파일 렌더링

- **무엇**: `readme?path=`는 디렉터리를 받아 그 안의 README만 렌더링한다. `docs/guide.md`처럼 README가 아닌 `.md` 파일은 렌더링할 방법이 없어 파일 보기에서 원문으로 보인다.
- **왜**: 문서 폴더(`docs/*.md`, `CHANGELOG.md`, `CONTRIBUTING.md`)는 README 다음으로 자주 여는 파일이다. 정화를 서버 한 곳에서 한다는 결정(spec 6.4)을 지키려면 웹에 마크다운 라이브러리를 넣지 않고 같은 렌더러를 쓰는 편이 맞다.
- **원하는 응답**: 둘 중 편한 쪽.
  1. `GET /api/v1/repos/{name}/readme?ref=&path=<파일>`: path가 `.md`/`.markdown` 파일이면 그 파일을 `ReadmeDto`로 돌려준다 (디렉터리면 지금처럼).
  2. 또는 `GET /api/v1/repos/{name}/render?ref=&path=<파일>` → `ReadmeDto`와 같은 형식 `{"ref","path","format":"markdown","raw","html"}`. Markdown이 아니면 400 `NOT_MARKDOWN`, 1MB 초과면 400 `TOO_LARGE`.
- **웹 쪽 준비**: 반영되면 파일 보기에 "미리보기 / 원문" 전환을 넣는다 (`FileView.tsx`, README 카드와 같은 `rewriteReadme` 재사용). 그 전까지는 원문 + 구문 강조로 보인다.
- **코어 처리 결과**: 완료(`24bc04c`). 방식 1을 택했다(README 경로와 일관). `GET /api/v1/repos/{name}/readme?ref=&path=<파일>`: `.md`/`.markdown` 파일이면 README와 같은 렌더러(원시 HTML 이스케이프, 위험 URL 제거)로 `ReadmeDto {ref, path, format:"markdown", raw, html}`를 돌려준다. 디렉터리면 지금과 같다. 그 밖의 파일(이진, LFS 포인터 포함)은 400 `NOT_MARKDOWN`, 1MB 초과는 400 `TOO_LARGE`, 없는 파일은 404 `PATH_NOT_FOUND`. 상대 이미지와 링크는 README처럼 원문 그대로라 `rewriteReadme`에 응답 `path`를 넘기면 된다.

## 코어 알림: 1차 코드 리뷰 반영 (요청 아님)

웹 API 변경. 자세한 형식은 `docs/core/spec.md` 3절.

- `commits`: `CommitPageDto`에 `truncated: boolean` 추가. 한 요청은 커밋을 20만 개까지만 훑는다. `truncated: true`면 더 오래된 커밋이 있지만 이 경로로는 볼 수 없다는 뜻(`hasMore`는 false). page는 1000까지, 넘으면 400 `PAGE_TOO_DEEP`. perPage는 1~100.
- tree/blob/readme/commits가 동시에 몰리면 503 `BUSY`. 잠시 뒤 다시 시도하는 안내를 보여 주면 된다.
- 500 `INTERNAL`의 message는 "서버 오류가 발생했습니다 (오류 ID xxxxxxxx)"로 고정.
- 루프백(127.0.0.1)과 사설망이 아닌 경로는 익명 읽기 설정이어도 401. `/api/v1/info`의 `anonymousRead`는 설정값이라, 401이 오면 그대로 브라우저 인증 프롬프트를 따르면 된다.

## WR-6. 웹 API 레포 정보에 주의 상태와 PR 브랜치 포함 여부 (2차, R2-01·R2-07)

- **무엇**: `RepoSummaryDto`에 두 가지를 더해 주세요(필드 추가만이라 웹 1차 화면은 그대로 동작).
  1. `needsAttention: Boolean`과 `issue: {badge, since}?`. 앱 목록과 같은 값(`RepoListItem.needsAttention`/`issue`, spec 8절 R2-01)이면 됩니다. badge는 `FAILED`·`ORIGIN_GONE`·`AUTH_REQUIRED`·`DELAYED`·`HISTORY_CHANGED`.
  2. 미러의 `includePullRefs: Boolean?`(로컬은 null).
- **왜**: 웹 목록의 "주의 필요" 필터는 지금 `originStatus`(원본 없음, 인증 필요)만 보고 셉니다. 업데이트 실패, 지연, 히스토리 바뀜이 빠져 앱과 개수가 다릅니다. 같은 이름의 필터가 앱과 웹에서 다른 수를 보이면 사용자가 헷갈립니다. 정보 패널의 "받는 범위"도 앱은 "PR 브랜치 포함/제외"까지 보여 주는데 웹은 그 값을 모릅니다.
- **지금 웹**: 필터 이름만 "주의 필요"로 맞추고 원본 상태 두 가지로만 셉니다. 받는 범위는 "모든 브랜치·태그 · LFS 파일 저장 안 함"으로, PR 브랜치는 언급하지 않습니다.
- **반영되면**: 목록 행의 배지·둘째 줄 사유, 필터 개수를 앱과 같게, 받는 범위에 PR 브랜치 포함 여부를 붙입니다.
- **코어 처리 결과**: 완료. `RepoSummaryDto`에 `needsAttention: boolean`, `issue: {badge, since, category} | null`, `includePullRefs: boolean | null`을 더했다. 목록(`/api/v1/repos`)과 상세의 `repo` 둘 다. 앱 목록과 같은 코드(`Badges.of`, `RepoListItem.needsAttention`/`issue`)로 계산하므로 개수가 같다. `badge`는 `ORIGIN_GONE`·`AUTH_REQUIRED`·`FAILED`·`DELAYED`·`HISTORY_CHANGED`, 우선순위도 앱과 같다. `since`(epoch ms)는 실패면 연속 실패 시작(2차 이전 실패는 null), 지연이면 마지막 확인, 히스토리 바뀜이면 보존 시각. `category`는 실패 분류(`NETWORK` 등) 또는 null. `includePullRefs`는 레포별 설정이 없으면 전역 기본값, 로컬은 null. 형식은 spec 8절 WR-6.

## 코어 알림: CR-11 (요청 아님)

- 백그라운드 확인이 모든 미러에서 멈춘 동안(`checksStalled`)은 `DELAYED`를 레포별 문제로 세지 않는다. `RepoSummaryDto.needsAttention`·`issue`도 그 규칙을 따른다.
- `RepoSummaryDto.severity: "ERROR" | "WARNING" | null` 추가(오류 = 실패·원본 없음·토큰 확인 필요, 주의 = 지연·히스토리 바뀜). 목록의 위험 색/주의 색은 이 값으로.
- `GET /api/v1/overview` 추가: `{"mirrors","healthy","attention","locals","lastCheckAt","checksStalled","errors","warnings"}`. 앱 메인 요약과 같은 숫자다. `attention = errors + warnings`이고 "주의 필요" 필터 결과 수와 같다.

## WR-7. tree 항목에 LFS 포인터 여부 (2차 사용자 리뷰, RV2-09)

- **무엇**: `TreeEntryDto`에 `lfs: Boolean`(파일이 Git LFS 포인터면 true)을 더해 주세요. blob 응답의 `lfs`와 같은 판정(`parseLfsPointer`)이면 됩니다. 크기가 200바이트 이하인 파일만 읽어 보면 되니 비용은 작습니다.
- **왜**: 리뷰에서 파일 목록에 포인터 파일을 표시해 달라고 했습니다(예: 130 B짜리 logo.psd). 지금 tree 응답에는 그 정보가 없습니다.
- **지금 웹**: LFS를 쓰는 레포에서 크기가 126~140바이트(포인터 파일의 크기 범위)인 파일에 "LFS 포인터" 표시를 붙입니다. 그 크기의 평범한 파일도 잘못 표시될 수 있습니다.
- **반영되면**: `lfs` 값이 있으면 그것만 씁니다(웹 코드는 이미 `e.lfs`를 먼저 봅니다). 추측 코드는 지웁니다.

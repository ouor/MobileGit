# 웹 UI → 코어 요청

작성: 웹 UI 개발자. 기준 코드: `GitServer.kt`, `RepoBrowser.kt` (커밋 `647b03c` 시점). 웹 화면은 아래 요청 없이도 모두 동작한다. 각 항목은 현재 우회 방법을 함께 적었다.

| ID | 우선순위 | 요약 |
|---|---|---|
| WR-1 | 중 | SPA 대체를 `/r/` 아래 경로에서는 `.`이 있어도 적용 |
| WR-2 | 중 | raw 응답에 `Content-Length`와 `Transfer-Encoding: chunked`가 함께 나감 (HTTP 위반) |
| WR-3 | 낮음 | `/assets/*` 해시 파일에 장기 캐시 헤더 |
| WR-4 | 낮음 | 정적 파일 gzip 전송 |

## WR-1. `/r/...` 경로의 SPA 대체

- **무엇**: `static()`은 마지막 경로 조각에 `.`이 있으면 자산으로 보고, 없으면 404를 돌려준다. `/r/<repo>/blob/README.md`, `/r/foo.js`(레포 이름에 `.` 허용) 같은 주소는 새로 고침하면 404가 된다.
- **왜**: 그래서 웹은 해시 라우팅(`/#/r/<repo>/blob/README.md?ref=main`)을 쓴다. 동작에는 문제가 없지만 주소가 `#`을 포함한다. history 라우팅으로 바꾸면 주소가 깔끔해지고 공유하기 좋다.
- **원하는 동작**: 경로가 `r/`로 시작하면 자산 파일을 찾지 말고 항상 `index.html`을 돌려준다 (그 밖의 경로는 지금처럼). 이 변경이 들어오면 웹은 `web/src/lib/router.ts`만 바꾸면 된다. 해시 주소는 계속 지원한다.

## WR-2. raw 응답 헤더 중복

- **무엇**: `GET /api/v1/repos/{name}/raw`가 `Content-Length: 135`와 `Transfer-Encoding: chunked`를 함께 보낸다 (`curl -D -`로 확인).
- **왜**: RFC 9112 위반이다. 브라우저는 관대하게 처리해 화면에는 문제가 없지만, 엄격한 클라이언트와 프록시는 응답을 버린다. 실제로 Node(Vite 개발 프록시)가 `Parse Error: Transfer-Encoding can't be present with Content-Length`로 502를 냈다. 리버스 프록시 뒤에 두는 사용자나 `wget` 계열 도구에서도 같은 문제가 날 수 있다.
- **원하는 동작**: 둘 중 하나만 보낸다. 크기를 알면 `respondBytes`/`OutgoingContent.ReadChannelContent`에 `contentLength`를 주고 수동 `Content-Length` 헤더는 빼는 방식을 권장.

## WR-3. 해시 자산 캐시

- **무엇**: `/assets/index-<hash>.js` 등에 캐시 헤더가 없다.
- **원하는 동작**: 경로가 `assets/`로 시작하면 `Cache-Control: public, max-age=31536000, immutable`. 파일 이름에 내용 해시가 들어가 있어 안전하다. `index.html`은 지금처럼 `no-cache`.
- **효과**: 페이지를 열 때마다 JS 430KB(gzip 전 기준)를 다시 받지 않는다. 폰 배터리와 핫스팟 데이터에 유리하다.

## WR-4. gzip 전송 (선택)

- **무엇**: 응답 압축이 없다. 웹 첫 로드 전송량은 약 680KB, gzip이면 약 200KB다.
- **원하는 동작**: Ktor `Compression` 플러그인을 정적 파일과 `/api/v1/` JSON에만 적용 (git 경로와 raw는 제외). 또는 빌드 때 `.gz`를 함께 만들어 두고 `Accept-Encoding`을 보고 고르는 방식도 가능하다 (요청하면 웹 빌드에서 `.gz` 생성 추가).

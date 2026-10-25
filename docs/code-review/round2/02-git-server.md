# 코드 리뷰 2차 — git 서버 / 미러 / JGit (02)

- 리뷰어 관점: JGit 기반 git 호스팅·미러링 운영 경험. 동기화 실패 분류, ref 변경 종류, 보존 기록 API, 히스토리 배지, 주의/심각도 판정, LFS 포인터 판정, 동시성, 큰 레포 성능, 테스트의 빈틈.
- 대상: 2차 사이클 변경분 `git diff 0a481ef..HEAD -- app/src/main/java/com/ouor/mobilegit/core app/src/test`. 변경을 이해하려고 주변 코드(`Sync.kt` 전체, `RepoStore.kt`, `GitServer.kt`, `app/Work.kt`)도 읽었다. 기능 맥락은 PRD 13장, spec 8절.
- JGit 7.8.0 기준. 저장소 코드는 수정하지 않았다.
- 스크래치 실험(JGit 7.8.0 jar로 직접 실행)으로 확인한 항목은 **[검증됨]**, 확신이 낮은 항목은 **[확신 낮음]**으로 표시했다.

## 요약

| 심각도 | 개수 | ID |
|---|---|---|
| 치명 | 0 | — |
| 높음 | 0 | — |
| 중간 | 3 | R2GIT-01 ~ R2GIT-03 |
| 낮음 | 7 | R2GIT-04 ~ R2GIT-10 |
| 테스트 빈틈 | 1 | R2GIT-11 |

---

## 중간

### R2GIT-01 실패 분류가 여전히 원격이 보낸 본문과 URL의 자유 텍스트로 "토큰 확인 필요"·"시간 초과"를 정한다. 공용 Wi-Fi 로그인 페이지 하나로 공개 미러가 "원본 없음"이 될 수 있다 [메시지 전달은 검증됨]
- 위치: `MirrorEngine.kt:220-232` (`classify`), `Sync.kt:316-318` (`fail`의 AUTH_REQUIRED → GONE 승격)
- 문제: QA2-01 수정은 "네트워크 스택의 자유 텍스트로 정하지 않는다"는 원칙을 세웠다. 하지만 `transport` 목록은 체인의 모든 `TransportException` 메시지다. 여기에는 JGit이 **원격 응답 본문을 그대로 붙인** 메시지도 들어간다. 스크래치에서 HTTP 200 + HTML 본문을 돌려주는 서버(캡티브 포털과 같은 모양)에 ls-remote를 하면 `PackProtocolException: invalid advertisement of <html><body>You are not authorized to use this Wi-Fi…` 가 나온다. `PackProtocolException`은 `TransportException`의 하위 타입이다. 그래서 `"not authorized" in it`에 걸려 `AUTH_REQUIRED`가 된다. 같은 이유로 `"timed out" in lower || "timeout" in lower`는 URL까지 포함한 전체 체인 문자열을 본다. 그래서 `https://…/timeout-utils.git: 500 Internal Server Error`는 `TIMEOUT`이 된다.
- 실패 상황: 토큰 없이 미러한 공개 레포(`!hasCred && initialFetchDone`)가 본문에 "not authorized"나 "authentication is required"가 들어간 포털 페이지를 받는다. 그러면 `fail()`이 `OriginStatus.GONE`으로 바꾸고 "원본 없음" 알림을 보낸다. QA2-01이 막으려던 오판과 같은 종류다. 이름에 timeout이 들어간 레포는 서버 오류가 모두 "시간 초과"로 표시된다.
- 참고(스크래치 결과, JGit 7.8.0): 429는 `TransportException: <uri>: 429 null`, 503은 `503 Service Unavailable`, 410은 `410 Gone`이고 모두 `REMOTE_ERROR`다. 다른 호스트로 보내는 302는 `Redirection blocked: …`로 `REMOTE_ERROR`다. 의도한 결과라면 문제는 없다. 캡티브 포털의 리다이렉트는 "네트워크 오류"가 더 맞다.
- 권장: 인증 판정은 JGit이 만든 고정 문구에만 맞춘다. `PackProtocolException`이나 원격 본문을 담는 메시지(`invalid advertisement of`, `remote:` 줄)는 `transport` 목록에서 빼거나, 문구 비교를 `startsWith`로 바꾼다(JGitText `notAuthorized`, `noCredentialsProvider` 등은 메시지 전체가 그 문구다). 시간 초과 텍스트는 URL을 뺀 메시지(`substringAfter(": ")`)에만 적용한다. `invalid advertisement` / `Redirection blocked`는 NETWORK로 분류하는 것을 검토한다.

### R2GIT-02 "확인" 성공이 바로 전 "업데이트" 실패를 지운다. 받기만 계속 실패하는 레포는 `failingSince`("N일째")가 쌓이지 않고, 실패·복구 알림이 주기마다 반복된다
- 위치: `Sync.kt:170-174` (check 성공 시 `lastError = null, consecutiveFailures = 0, failingSince = null`), `Sync.kt:159` (backoff 동안 AUTO check 건너뜀), `app/Work.kt:140` (check 뒤 자동 update)
- 문제: ls-remote는 되는데 fetch가 실패하는 레포가 흔하다. 모바일망에서 시간 초과가 나는 큰 레포, 저장 공간 하한(SpaceGuard), 원격의 pack 생성 오류가 그런 경우다. 이런 레포에서 주기 확인이 성공하면 실패 상태가 모두 초기화된다. 이어지는 자동 update가 다시 실패하면 `failingSince = now`로 새로 시작한다. `consecutiveFailures`도 0으로 돌아가 backoff가 1시간부터 다시 시작한다.
- 실패 상황: 수 GB 레포가 며칠째 받지 못해도 행 사유는 항상 "실패 · 방금"이고 "3일째"가 나오지 않는다(R2-01 AC1의 목적). 확인 주기마다 `RECOVERED` → `FAILED` 알림이 한 쌍씩 온다. `NotificationRules`는 상태 전이마다 알리기 때문이다. 확인과 실패 사이의 짧은 시간에는 "주의 필요" 필터와 요약 숫자에서도 빠진다. 수동 업데이트가 실패한 뒤 자동 업데이트를 꺼 둔 레포는 다음 확인에서 실패 표시가 사라진다.
- 권장: check 성공은 원본 상태(`originStatus`, `lastSuccessCheckAt`)만 갱신한다. 마지막 업데이트가 실패했고 아직 받을 변경이 남았다면(`pendingChanges > 0`) `lastError`, `failingSince`, `consecutiveFailures`는 유지한다. 또는 실패를 check와 update로 나눠 저장한다. 1차부터 있던 동작이지만 2차의 `failingSince` 의미를 깨뜨리므로 함께 본다.

### R2GIT-03 네트워크 오류 한 번에 "원본 없음"·"토큰 확인 필요"가 `UNKNOWN`으로 바뀐다. 원본이 실제로 사라진 레포가 오프라인 때마다 "업데이트 실패"로 보이고, 알림이 다시 온다
- 위치: `Sync.kt:319-321` (`else -> if (OK) OK else UNKNOWN`), `Sync.kt:44-60` (`NotificationRules.health`)
- 문제: QA2-01의 의도는 "오판된 GONE을 다음 실패로 풀어 준다"이다. 하지만 이 규칙은 **확인된** GONE·AUTH_REQUIRED도 NETWORK·TIMEOUT·REMOTE_ERROR 한 번으로 지운다. `UNKNOWN + lastError`는 `Health.FAILED`이므로 전이 알림도 함께 나간다.
- 실패 상황: 원본이 삭제된 미러(제품이 가장 알려 주고 싶은 상태)가 있다. 폰이 지하철에서 망을 잃으면 상태는 GONE → UNKNOWN이 되고 "업데이트 실패 (네트워크)" 알림이 온다. 배지는 "원본 없음"에서 "실패"로 바뀐다. 망이 돌아와 404를 받으면 다시 GONE이 되고 "원본 없음" 알림이 또 온다. 오프라인 구간마다 알림이 두 번씩 오고, 그 사이에는 목록에서 원본 사고가 보이지 않는다. 만료된 토큰(AUTH_REQUIRED)도 똑같이 흔들린다.
- 권장: 원본에 닿지 못한 실패(NETWORK, TIMEOUT)는 이전 originStatus를 **유지**한다. 오판된 GONE은 분류(R2GIT-01)를 고쳐서 막는다. 원본이 응답했지만 다른 오류를 낸 경우(REMOTE_ERROR)에만 UNKNOWN으로 낮춘다. 그래도 낮추려면 GONE→UNKNOWN 전이는 알림 대상에서 빼고, `UNKNOWN`이 직전 GONE이었으면 다음 GONE에서 다시 알리지 않는다.

---

## 낮음

### R2GIT-04 "확인 멈춤" 판정이 미러 하나의 최신 시도에 좌우된다. 수동 확인 한 번이나 새 미러 추가로 멈춤 표시가 사라지고 나머지 미러가 모두 "지연"이 된다
- 위치: `Sync.kt:506-509` (`checksStalled`), `Sync.kt:85` (`Badges.of`)
- 상황: 백그라운드 확인이 Doze나 배터리 제한으로 며칠 멈췄다. 사용자가 미러 하나에서 "지금 확인"이나 "지금 업데이트"를 누르거나 새 미러를 추가한다(`lastCheckAt`/`createdAt`이 지금이 됨). 그러면 `checksStalled = false`가 되고 나머지 N-1개에 DELAYED가 붙어 "주의 필요"가 한꺼번에 늘어난다. 정작 원인인 "백그라운드 확인이 멈춘 것 같아요"는 사라진다. 또 `checkWifiOnly`가 켜진 채 모바일 데이터만 쓰는 기간에도 "멈춤"으로 표시되는데, 사용자가 고른 조건 대기와 구별되지 않는다.
- 권장: 미러별 시각의 최댓값 대신 **주기 작업이 마지막으로 돈 시각**(`PeriodicCheckWorker`가 기록하는 전역 값, 또는 AUTO 트리거 시도만)을 기준으로 삼는다. Wi-Fi 조건 대기는 따로 표시한다.

### R2GIT-05 PR ref의 생성·삭제가 "새 브랜치"·"브랜치 삭제됨"(위험 색)으로 표시된다
- 위치: `Models.kt:87-97` (`RefChange.kind`), `MirrorEngine.kt:92` (`includePullRefs=false`면 기존 `refs/pull/*`를 삭제), `ui/Format.kt:161-171`
- 상황: 문서는 "PR ref는 움직이면 항상 FAST_FORWARD"라고만 다룬다. 하지만 `refs/pull/N/merge`가 생기고 사라질 때는 `CREATED`/`DELETED`가 된다. GitHub은 PR이 닫히거나 충돌이 생기면 `merge` ref를 지운다. 그래서 PR ref를 받는 미러의 이력에 빨간 "브랜치 삭제됨"이 자주 나온다. 전역 기본값이나 레포 설정에서 PR ref 받기를 끄면, 다음 업데이트 한 번에 PR ref 수백~수천 개가 "브랜치 삭제됨"으로 기록된다. `events.json` 한 항목도 그만큼 커진다. `refs/notes/*` 같은 다른 네임스페이스도 "브랜치"라고 표시된다.
- 권장: `kind`에서 `MirrorEngine.isReviewRef(ref)`면 별도 종류(예: `REVIEW`)로 두거나, 이력과 알림에서 PR ref 변경은 개수로만 묶는다. PR ref 받기를 꺼서 생긴 삭제는 이력에 "PR 브랜치 N개 정리"로 한 줄만 남긴다.

### R2GIT-06 "히스토리 바뀜" 배지와 보존 종류 기록이 ref 적용 뒤의 별도 쓰기라서, 그 사이 예외 하나로 배지와 알림 없이 보존만 일어난다
- 위치: `Sync.kt:258-266` (`appendEvent` → `appendArchives` → `update(historyChangedAt)` → `updateMirror`), `MirrorEngine.kt:139` (`countNewCommits`는 ref를 적용한 뒤 실행)
- 상황: ref가 적용된 다음 `appendEvent`나 `appendArchives`가 `IOException`(저장 공간 부족이 대표적)을 던진다. 그러면 catch에서 `fail()`로 가서 STORAGE_FULL 실패가 기록되고, `historyChangedAt`, ARCHIVED 알림, 보존 종류가 모두 남지 않는다. `countNewCommits`가 ref 적용 뒤에 예외를 내도 같다. 프로세스가 그 사이에 죽어도 같다. 원본 사고를 사용자에게 알리는 2차의 핵심 표시가 조용히 빠진다(`archivedRefCount`는 다음 `refreshDerived`에서 늘어나므로 배지 없이 숫자만 바뀐다).
- 권장: `historyChangedAt`은 `updateMirror`와 한 번의 meta 쓰기로 합치고, 이력 파일 쓰기보다 **먼저** 한다. `appendEvent`/`appendArchives`는 `runCatching`으로 감싼다(이력은 버려도 되는 파일이라는 기존 원칙과 같다). `countNewCommits`도 예외를 삼키고 0이나 null로 둔다.

### R2GIT-07 트리 목록이 200바이트 이하의 모든 파일을 열어 LFS 포인터인지 본다. 작은 파일이 많은 디렉터리에서 트리와 README API가 느려진다
- 위치: `RepoBrowser.kt:168-171` (`tree`), `RepoBrowser.kt:227-228` (`readme`도 `tree`를 부른다), `RepoBrowser.kt:217-218` (blob 판정은 1024바이트까지)
- 상황: 테스트 픽스처나 생성된 소스처럼 작은 파일이 수천 개인 디렉터리를 열면, 엔트리마다 `getObjectSize`에 더해 `reader.open().bytes`(delta 해석 포함)를 한다. LFS를 쓰지 않는 레포에서도 같다. 폰에서는 수 초가 걸릴 수 있고, `heavy` 슬롯 2개를 그동안 점유한다 [규모는 확신 낮음, 측정하지 않음]. 또 트리는 200바이트, blob은 1024바이트까지 포인터로 보므로, 확장 줄이 있는 포인터는 blob 화면에서만 LFS로 표시된다.
- 권장: `meta.hasLfs`가 false면 건너뛴다(또는 해당 트리의 `.gitattributes`에 `filter=lfs`가 없으면 건너뛴다). 크기 하한을 둔다. 표준 포인터는 최소 약 120바이트이므로, 그보다 작은 파일은 열 필요가 없다. 상한은 blob과 같게 맞추거나 문서에 차이를 적는다.

### R2GIT-08 `markHistorySeen`이 조건 없이 지워서, 화면을 여는 순간에 끝난 보존의 배지가 사라질 수 있다
- 위치: `RepoStore.kt:253-255`, `ui/screens/ArchiveScreen.kt:69`
- 상황: `get()`으로 null이 아님을 본 뒤 `update { historyChangedAt = null }`를 한다. 그 사이에 업데이트가 새 시각을 쓰면 새 배지도 함께 지워진다. 창은 짧지만, 보존 기록 화면을 열어 둔 채 들어온 보존은 목록에 보이지 않을 수 있다(화면은 연 시점의 목록을 보여 준다).
- 권장: 화면이 실제로 읽은 최신 묶음 시각을 넘겨 `update { if ((it.historyChangedAt ?: 0) <= seenAt) it.copy(historyChangedAt = null) else it }`로 비교한 뒤 지운다.

### R2GIT-09 ngrok용 루프백 포트는 ngrok이 꺼져 있어도 "ngrok 경로"로 취급되어 SEC-03(익명 읽기는 같은 망 + 직접 Host)를 우회한다 [확신 중간, 보안 리뷰(03)와 겹침]
- 위치: `GitServer.kt:112` (ngrok 커넥터를 항상 `127.0.0.1:0`에 연다), `GitServer.kt:164` (`viaNgrok = localPort == ngrokPort`), `GitServer.kt:184-185` (`anonymousOk = viaNgrok || …`)
- 상황: "push만"(`authEnabled && anonymousReadOnLan`)이면 ngrok 설정과 무관하게 그 포트로 들어온 요청은 Host 검사 없이 익명 읽기가 된다. 폰의 다른 앱이나, 폰 브라우저에서 DNS rebinding으로 `127.0.0.1:<ngrokPort>`에 닿은 페이지가 레포를 읽을 수 있다. 같은 조건에서 본 포트의 루프백 익명 요청은 테스트(`GitServerTest`)가 401을 확인한다. 포트가 임의 번호라 실제 공격은 어렵지만 2차 이전에는 이 경로가 항상 인증을 요구했다.
- 권장: `viaNgrok`을 `localPort == ngrokPort && settings().ngrokEnabled`로 좁힌다. 또는 ngrok이 꺼져 있으면 그 커넥터 요청을 403으로 거절한다.

### R2GIT-10 보존 기록 API가 요청마다 이력 전체를 파싱하고, `archives.json` 쓰기는 store 전역 락 안에서 최대 1만 건을 다시 쓴다
- 위치: `RepoStore.kt:238-250` (`appendArchives`, `archiveNotes`), `RepoBrowser.kt:321-336`
- 상황: `archiveNotes`는 `archives.json`(최대 1만 건)과 `events.json`(200개 이벤트, 각 이벤트의 refChanges 전부)을 요청마다 읽는다. PR ref를 받는 레포는 첫 받기 이벤트 하나에 ref 수만 개가 들어 있을 수 있다. `appendArchives`는 `synchronized(this)`(RepoStore 전체) 안에서 파일 전체를 읽고 다시 쓴다. 그동안 모든 레포의 meta 갱신과 push 기록이 기다린다. 묶음 시각을 entry마다 `SimpleDateFormat`을 새로 만들어 파싱하는 것은 작은 비용이다.
- 권장: 2차 이전 보존을 위한 이력 스캔은 한 번만 해서 `archives.json`으로 옮기고(마이그레이션), 그 뒤로는 `archives.json`만 읽는다. 파일 쓰기는 레포별 락(`withRepoLock`, 이미 쥐고 있음)에 맡기고 전역 `synchronized`는 `exists` 확인에만 쓴다.

---

## 테스트 빈틈

### R2GIT-11 2차 변경에서 테스트가 닿지 않는 곳
- `ClassifyTest`: 원격 본문이 섞인 메시지(`PackProtocolException: invalid advertisement of <html>…not authorized…`), URL에 `timeout`이 들어간 500, 429/503/410, `Redirection blocked`가 없다(R2GIT-01). 스크래치에서 JGit 7.8.0이 실제로 내는 메시지를 위에 적었으니 그대로 고정값으로 쓸 수 있다.
- `droppedConnectionIsNetworkNotOriginGone`은 JDK `HttpURLConnection` 경로("Unexpected end of file from server")만 실제로 탄다. Android(OkHttp 기반) 문구는 합성 예외 한 건으로만 확인한다. 실기기 QA2-01 재검증 기록이 있으면 함께 남기는 것이 좋다.
- "확인 성공 → 업데이트 실패"가 반복될 때 `failingSince`가 유지되는지, 알림이 반복되지 않는지 보는 테스트가 없다(R2GIT-02).
- GONE 상태에서 NETWORK 실패가 날 때의 알림 수(GONE → UNKNOWN → GONE)를 보는 테스트가 없다(R2GIT-03). 지금 테스트는 GONE → UNKNOWN 전이 자체를 "올바름"으로 고정한다.
- `checksStalled`에서 미러 하나만 방금 확인된 경우(R2GIT-04), PR ref의 생성·삭제와 PR ref 받기 끄기에서 나오는 `kind`(R2GIT-05)를 다루지 않는다.
- 보존 직후 이력 쓰기가 실패할 때 `historyChangedAt`이 남는지 보는 테스트가 없다(R2GIT-06).
- LFS: 200~1024바이트 포인터, LFS가 없는 레포에서 작은 파일이 많은 트리의 응답 시간(R2GIT-07).
- `archives.json`이 `MAX_ARCHIVE_NOTES`를 넘을 때, 손상됐을 때(`readList`가 빈 목록) `kind`가 null로 내려가는지 확인하는 테스트가 없다.
- ngrok 커넥터 포트로 ngrok이 꺼진 상태의 익명 요청(R2GIT-09)을 확인하는 테스트가 없다.

---

## 잘 된 점

- 실패 분류를 예외 타입 우선으로 바꾼 방향이 맞다. `ConnectException`/`NoRouteToHostException`을 `SocketException` 하나로 묶었고, HTTP 상태는 `^404\b`처럼 URI 접두를 뗀 메시지의 맨 앞에서만 본다. `404.git` 같은 이름이 상태로 오인되지 않는 테스트도 있다.
- `RefChange.kind`를 저장하지 않고 파생 속성으로 둔 덕분에, 1차 이력에도 종류가 바로 나온다. "보존되지 않은 브랜치 갱신 = FF"라는 불변식이 `MirrorEngine`의 보존 조건(삭제, 비FF, 태그 이동, FF 판정 실패 시 보존)과 정확히 맞는다.
- 보존 종류를 `archives.json`에 따로 남겨 이력 200개 한도와 분리했다. 키를 `archivedAs`로 잡아 `distinctBy`로 새 기록을 우선하고, 2차 이전 보존은 남은 이력에서 찾는다. 테스트는 두 파일을 차례로 지워 가며 확인한다.
- 보존 목록 API는 주석 태그를 커밋까지 따라가고, 커밋이 아닌 대상과 형식이 다른 `refs/archive/*`(로컬 레포에 사용자가 push한 것)를 안전하게 건너뛴다. 같은 초 묶음(`-2`, `-10`)의 정렬도 길이 → 사전순으로 올바르다.
- 주의/심각도 판정이 앱 목록, 요약, 웹 목록·상세에서 같은 코드(`Badges.of` → `RepoListItem`)를 거친다. CR-11의 "멈춘 동안 지연을 레포별로 세지 않음" 규칙도 한 곳(`checksStalled` 인자)에서 적용된다.
- LFS 포인터 판정은 크기를 먼저 보고 작은 blob만 연다. 예외는 `runCatching`으로 false 처리해 트리 목록 전체가 실패하지 않는다.

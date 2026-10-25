# UI → 코어 요청

작성: 안드로이드 UI 개발자, 사이클 1. 해결되기 전까지 UI는 아래 "현재 UI 처리"대로 동작한다.

## CR-1 서버 실행 중 포트 변경 반영 (F-01 AC1, A-10)

- **무엇이**: 서버가 켜진 상태에서 `settings.setPort()`를 호출해도 실행 중인 서버는 이전 포트에 머문다. `ServerService`는 설정 중 ngrok 관련 값만 관찰한다.
- **왜**: 설정 화면에서 포트를 바꾼 사용자는 바로 적용될 거라고 기대한다. 지금은 "서버를 껐다 켜야 적용돼요"라는 안내를 보여 주지만, 주소 카드(`network.addresses`)는 새 포트를 즉시 보여 줘서 표시된 주소와 실제 수신 포트가 어긋난다.
- **원하는 시그니처**: `ServerController.restart()` (진행 중인 전송을 기존 규칙대로 마무리한 뒤 새 포트로 다시 시작, 실패하면 `Failed(portInUse)`). 또는 서비스가 `port` 변화를 관찰해 자동으로 다시 시작.
- **현재 UI 처리**: 포트 대화상자에 안내 문구만 표시.
- **코어 처리 결과**: 완료(`a52a702`). `suspend fun ServerController.restart(): ServerState`. 그리고 `ServerService`가 `settings.port` 변화를 관찰해 자동으로 호출하므로 `settings.setPort(p)`만 부르면 된다. `state`: `Stopping` → `Running(p)`. 새 포트가 사용 중이면 `Failed(portInUse = true)`(서버 꺼짐). ngrok도 다시 연결된다. 안내 문구는 지워도 된다.
- **UI 반영**: 포트 대화상자 문구를 "저장하면 서버가 새 포트로 다시 시작해요"로 바꾸고 값이 바뀔 때만 `setPort` 호출. 실기기에서 8080→8090→8080 전환 후 주소 카드와 실제 포트 일치 확인(`cdda430`).

## CR-2 `refs/pull/*`·`refs/merge-requests/*` 미러 여부 옵션 (F-06, F-17)

- **무엇이**: 미러가 원격의 모든 ref를 받는다. 실기기에서 `octocat/Spoon-Knife`를 추가하자 probe 결과가 ref 63,808개였고(대부분 `refs/pull/*`), 첫 받기가 수 분 동안 진행됐다.
- **왜**: 인기 레포는 PR ref가 히스토리 대부분을 차지해 시간·용량·모바일 데이터를 크게 쓴다. 코어 progress.md도 "설정으로 끌지는 UI 결정"이라고 남겼다. 아카이브 목적(D-3)은 heads/tags 보존이고, PR ref는 이미 보존 대상에서 빠져 있다(`shouldArchive`).
- **원하는 시그니처**: `Settings.mirrorPullRefs: Boolean = false`(전역 기본) + `MirrorSource.includePullRefs: Boolean?`(레포별 덮어쓰기, null이면 전역). `ProbeResult.Reachable`에 `pullRefCount: Int`를 추가해 미러 추가 화면에서 "PR ref N개 제외"를 보여 줄 수 있게.
- **현재 UI 처리**: 없음(받는 대로 받음). 설정 > 동기화에 넣을 자리만 확보.
- **코어 처리 결과**: 완료(`ca55156`). 요청한 시그니처 그대로: `Settings.mirrorPullRefs: Boolean = false`, `MirrorSource.includePullRefs: Boolean?`(설정: `mirrors.setIncludePullRefs(name, Boolean?)`, 추가 시 `mirrors.add(..., includePullRefs = …)`), `ProbeResult.Reachable.pullRefCount: Int`. 기본값 false의 근거는 spec.md F-10에 있다(아카이브 대상은 heads/tags, PR ref는 보존하지 않으며 크기·시간의 대부분을 차지함). 끄면 이미 받은 PR ref는 다음 업데이트에서 지워진다.
- **UI 반영**: 설정에 "PR ref도 미러" 스위치, 미러 추가 화면에 "PR ref도 받기"(PR ref가 있을 때만), 레포 상세에 설정 따름/받기/받지 않기. 끌 때 "보존 없이 삭제" 확인 대화상자. Spoon-Knife 첫 받기 수 분 이상 → 3.4초(`cdda430`).

## CR-3 동기화 이력 변경 알림 (F-12, A-07)

- **무엇이**: `repos.events(name)`이 디스크를 읽는 동기 함수뿐이다.
- **왜**: 상세 화면은 `RepoMeta`가 바뀔 때마다 다시 읽는다. 지금은 이벤트 기록과 메타 갱신이 함께 일어나 문제는 없지만, 순서가 바뀌면 이력 탭이 한 박자 늦게 갱신될 수 있다.
- **원하는 시그니처**: `RepoStore.eventsFlow(name): Flow<List<SyncEvent>>` (최신순).
- **현재 UI 처리**: `produceState(key = meta)`로 IO에서 다시 읽음. 최신순 정렬은 UI가 `startedAt`으로 다시 한다.
- **참고(문서 불일치)**: spec.md F-12는 "최신이 마지막"이라고 적었지만 `appendEvent`는 최신을 앞에 넣는다.
- **코어 처리 결과**: 완료(`ca55156`). `RepoStore.eventsFlow(name): Flow<List<SyncEvent>>`. 최신이 처음이고 IO에서 읽는다. 이력 추가와 삭제 때 다시 보낸다. spec.md F-12의 "최신이 마지막"은 잘못이라 고쳤다(`events()`도 최신이 처음). UI의 `startedAt` 재정렬은 필요 없다.
- **UI 반영**: 상세 이력 탭이 `eventsFlow`를 구독하고 UI 재정렬 제거(`cdda430`).

## CR-4 지금 확인 (선택)

- **무엇이**: 수동 "업데이트"는 있지만 가벼운 "확인(check)만 지금" API는 UI에서 부르기 어렵다(`coordinator.check`는 suspend, WorkManager 경유 아님).
- **왜**: 모바일 데이터에서 "업데이트 있음"만 확인하고 싶은 경우(D-4). 1차 범위는 아니다.
- **원하는 시그니처**: `MirrorController.checkNow(name)` / `checkAll()` (WorkManager로 예약).
- **현재 UI 처리**: 노출하지 않음.
- **코어 처리 결과**: 완료(`ca55156`). `MirrorController.checkNow(name)`, `checkAll()`. WorkManager로 예약되며 모든 네트워크에서 즉시 실행되고 백오프를 무시한다. 업데이트는 하지 않는다. 결과는 `pendingChanges`와 `UPDATE_AVAILABLE` 배지로 나타난다. 미러가 아니면 `RepoException(NOT_A_MIRROR)`.
- **UI 반영**: 메인 더보기 "모든 미러 변경 확인", 상세 "변경 확인만 하기". 확인 중에는 버튼이 "확인 중…"(`cdda430`, `fac2f32`).

## CR-5 소스 zip 브랜치 선택 (요청 불필요)

- 기존 `WorkScheduler.enqueueExport(kind, repos, ref, uri)`의 `ref`와 `RepoOps.branches(name)`로 충분해서 코어 요청을 만들지 않았다. 내보내기 시트에서 소스 zip을 고르면 브랜치 목록(기본 브랜치 선택)이 나온다. 실기기에서 Spoon-Knife의 브랜치 3개 표시 확인.

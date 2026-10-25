import assert from "node:assert/strict"
import { test } from "node:test"
import { mirrorErrorText, nameProblem, previewProblem, scopeLine, syncState, type MirrorPreview } from "./mirror.ts"

const err = (code: string, status = 400, extra = {}) => mirrorErrorText({ status, code, ...extra })

test("each refusal code has its own words (WM-04 AC4)", () => {
  assert.equal(mirrorErrorText({ status: 401, code: "UNAUTHORIZED" }), "미러를 추가하려면 이 폰의 비밀번호가 필요해요.")
  assert.match(err("EXTERNAL_NOT_ALLOWED", 403), /외부 공개\(ngrok\) 주소에서는/)
  assert.match(err("CROSS_SITE", 403), /이 페이지에서 보낸 요청이 아니라서/)
  assert.match(err("HTTPS_ONLY"), /^https 주소만/)
  assert.match(err("UNSUPPORTED_URL"), /git 저장소 주소가 아니에요/)
  assert.match(err("MULTIPLE_URLS"), /한 번에 주소 하나만/)
  assert.match(err("TOKEN_NOT_ACCEPTED"), /토큰을 받지 않아요/)
  assert.match(err("ALREADY_MIRRORED", 409, { repo: "chalk" }), /이미 보존 중이에요/)
  assert.equal(err("INVALID_NAME", 400, { message: "이름은 .git으로 끝날 수 없습니다" }), "이름은 .git으로 끝날 수 없습니다")
  assert.match(err("NAME_TAKEN", 409), /같은 이름의 레포가 있어요/)
  assert.match(err("STORAGE_LOW", 507), /최소 여유 공간보다 적어서/)
  assert.match(err("BUSY", 503), /다른 확인이 진행 중/)
  assert.match(err("NETWORK", 0), /폰에 연결할 수 없어요/)
  assert.match(err("ORIGIN_NOT_FOUND", 404), /원본을 찾을 수 없어요/)
  assert.equal(err("PROBE_FAILED", 502, { category: "TIMEOUT" }), "원본을 확인하지 못했어요 (시간 초과). 주소를 확인하고 잠시 뒤 다시 시도하세요.")
  assert.equal(err("SOMETHING_NEW", 500, { message: "서버 메시지" }), "서버 메시지")
})

test("token required: points to the phone app, and to a password visit when anonymous", () => {
  const signedIn = mirrorErrorText({ status: 403, code: "TOKEN_REQUIRED" })
  assert.match(signedIn, /폰 앱의 설정 → 원본 접속 토큰/)
  assert.doesNotMatch(signedIn, /비밀번호/)
  assert.match(mirrorErrorText({ status: 403, code: "TOKEN_REQUIRED" }, true), /비밀번호로 접속하세요/)
})

const p: MirrorPreview = {
  url: "https://github.com/chalk/chalk.git", suggestedName: "chalk", existingRepo: null, access: "REACHABLE",
  refCount: 12, defaultBranch: "main", pullRefCount: 3, failure: null, includePullRefs: false,
}

test("preview: scope line and problems", () => {
  assert.equal(scopeLine(p), "브랜치·태그 12개 받기 · PR 브랜치 제외 · Git LFS 파일과 서브모듈은 받지 않아요")
  assert.equal(scopeLine({ ...p, includePullRefs: true }), "브랜치·태그 12개 받기 · PR 브랜치 포함 (3개) · Git LFS 파일과 서브모듈은 받지 않아요")
  assert.equal(previewProblem(p), null)
  assert.equal(previewProblem({ ...p, existingRepo: "chalk", access: null }), null)
  assert.match(previewProblem({ ...p, access: "TOKEN_REQUIRED" })!, /비공개 레포예요/)
  assert.match(previewProblem({ ...p, access: "ORIGIN_NOT_FOUND" })!, /원본을 찾을 수 없어요/)
  assert.match(previewProblem({ ...p, access: "FAILED", failure: "NETWORK" })!, /\(네트워크 오류\)/)
})

test("name rule matches the core", () => {
  assert.equal(nameProblem("chalk"), null)
  assert.equal(nameProblem("a.b_c-1"), null)
  assert.match(nameProblem("")!, /입력하세요/)
  assert.match(nameProblem("-x")!, /시작해야 해요/)
  assert.match(nameProblem("a b")!, /만 쓸 수 있어요/)
  assert.match(nameProblem("x.git")!, /\.git으로 끝날 수 없어요/)
  assert.match(nameProblem("a".repeat(101))!, /100자/)
})

test("first fetch state (WM-05)", () => {
  assert.equal(syncState({ syncing: false, waiting: null }), null)
  assert.deepEqual(syncState({ syncing: true, waiting: null }), { kind: "syncing", label: "받는 중" })
  assert.equal((syncState({ syncing: false, waiting: "FOREGROUND" }) as { text: string }).text, "폰에서 mobilegit 앱을 열면 받기를 시작해요")
  assert.match((syncState({ syncing: false, waiting: "STORAGE" }) as { text: string }).text, /저장 공간/)
  assert.equal((syncState({ syncing: false, waiting: "NEW" as never }) as { text: string }).text, "조건이 맞으면 받기를 시작해요")
})

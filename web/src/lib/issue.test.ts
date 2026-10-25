import assert from "node:assert/strict"
import { test } from "node:test"
import { ago, issueText, running } from "./issue.ts"

const now = Date.UTC(2026, 9, 25, 12)
const H = 3_600_000

test("same wording as the app's row line", () => {
  assert.equal(issueText({ badge: "FAILED", since: now - 50 * H, category: "NETWORK" }, now), "업데이트 실패 · 네트워크 오류 · 3일째")
  assert.equal(issueText({ badge: "FAILED", since: null, category: null }, now), "업데이트 실패")
  assert.equal(issueText({ badge: "ORIGIN_GONE", since: now - 49 * H, category: null }, now), "원본 없음 · 2일 전부터")
  assert.equal(issueText({ badge: "AUTH_REQUIRED", since: null, category: "AUTH_REQUIRED" }, now), "토큰 확인 필요")
  assert.equal(issueText({ badge: "DELAYED", since: now - 30 * H, category: null }, now), "2일째 확인 못 함")
  assert.equal(issueText({ badge: "HISTORY_CHANGED", since: now - 2 * H, category: null }, now), "히스토리 바뀜 · 2시간 전 보존")
})

test("ago and running", () => {
  assert.equal(ago(now - 30_000, now), "방금")
  assert.equal(ago(now - 5 * 60_000, now), "5분 전")
  assert.equal(running(now - 10 * 60_000, now), "방금부터")
  assert.equal(running(now - 5 * H, now), "5시간째")
  assert.equal(running(now - 24 * H, now), "2일째")
})

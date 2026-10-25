import assert from "node:assert/strict"
import { test } from "node:test"
import type { Overview } from "./api.ts"
import { issueText, running, summaryParts } from "./issue.ts"
import { absoluteTime, ago, clockTime, dayLabel } from "./format.ts"

const now = Date.UTC(2026, 9, 25, 12)
const H = 3_600_000

test("same wording as the app's row line", () => {
  assert.equal(issueText({ badge: "FAILED", since: now - 50 * H, category: "NETWORK" }, now), "업데이트 실패 · 네트워크 오류 · 3일째")
  assert.equal(issueText({ badge: "FAILED", since: null, category: null }, now), "업데이트 실패")
  assert.equal(issueText({ badge: "ORIGIN_GONE", since: now - 49 * H, category: null }, now), "원본 없음 · 3일째")
  assert.equal(issueText({ badge: "AUTH_REQUIRED", since: null, category: "AUTH_REQUIRED" }, now), "토큰 확인 필요")
  assert.equal(issueText({ badge: "DELAYED", since: now - 30 * H, category: null }, now), "2일째 확인 못 함")
  assert.equal(issueText({ badge: "HISTORY_CHANGED", since: now - 2 * H, category: null }, now), "히스토리 바뀜 · 2시간 전 보존")
  assert.equal(issueText({ badge: "HISTORY_CHANGED", since: null, category: null }, now), "히스토리 바뀜")
  assert.equal(issueText({ badge: "DELAYED", since: null, category: null }, now), "확인 지연")
  assert.equal(issueText({ badge: "FAILED", since: null, category: "SOMETHING_NEW" }, now), "업데이트 실패 · 알 수 없는 오류")
  assert.equal(issueText({ badge: "NEW_BADGE" as never, since: null, category: null }, now), "주의 필요")
})

test("ago and running", () => {
  assert.equal(ago(now - 30_000, now), "방금")
  assert.equal(ago(now - 5 * 60_000, now), "5분 전")
  assert.equal(ago(new Date(2026, 8, 3, 9).getTime(), now), "2026.09.03")
  assert.equal(running(now - 10 * 60_000, now), `${clockTime(now - 10 * 60_000)}부터`)
  assert.match(running(now - 10 * 60_000, now), /^\d\d:\d\d부터$/)
  assert.equal(running(now - 5 * H, now), "5시간째")
  assert.equal(running(now - 24 * H, now), "2일째")
})

test("absolute time is 2026.10.25 16:35 whatever the locale", () => {
  const t = new Date(2026, 9, 5, 7, 3).getTime()
  assert.equal(absoluteTime(t), "2026.10.05 07:03")
  assert.equal(clockTime(t), "07:03")
  assert.equal(dayLabel(t), "2026.10.05 (월)")
  assert.equal(absoluteTime(null), "")
})

const o: Overview = { mirrors: 12, healthy: 10, attention: 2, locals: 3, lastCheckAt: now - 10 * 60_000, checksStalled: false, errors: 1, warnings: 1 }
const text = (x: Overview) => summaryParts(x, now).map((p) => p.text).join(" · ")

test("summary line matches the app's rules", () => {
  assert.equal(text(o), "미러 12개 · 정상 10 · 오류 1 · 주의 1 · 마지막 확인 10분 전 · 로컬 3개")
  assert.equal(text({ ...o, healthy: 12, errors: 0, warnings: 0, attention: 0 }), "미러 12개 · 모두 정상 · 마지막 확인 10분 전 · 로컬 3개")
  assert.equal(text({ ...o, healthy: 11, errors: 0, warnings: 1, lastCheckAt: null }), "미러 12개 · 정상 11 · 주의 1 · 로컬 3개")
  assert.equal(text({ ...o, mirrors: 0, healthy: 0, errors: 0, warnings: 0, lastCheckAt: null }), "로컬 3개")
  assert.deepEqual(summaryParts(o, now).filter((p) => p.filter).map((p) => p.text), ["오류 1", "주의 1"])
  assert.equal(summaryParts({ ...o, errors: 0, warnings: 0 }, now).some((p) => p.filter), false)
})

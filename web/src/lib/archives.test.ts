import assert from "node:assert/strict"
import { test } from "node:test"
import type { ArchiveEntry } from "./api.ts"
import { groupArchives, kindInfo } from "./archives.ts"

const e = (group: string, ref: string): ArchiveEntry => ({
  at: 1, group, kind: null, ref, name: ref, archiveRef: `refs/archive/${group}/${ref}`, sha: "a", shortSha: "a", summary: null, committedAt: null, currentSha: null,
})

test("runs of the same group form one section, order kept", () => {
  const g = groupArchives([e("g2", "a"), e("g2", "b"), e("g1", "c")])
  assert.deepEqual(g.map((x) => [x.group, x.entries.length]), [["g2", 2], ["g1", 1]])
  assert.deepEqual(groupArchives([]), [])
})

test("kind labels, with unknown values falling back", () => {
  assert.equal(kindInfo("FORCED").label, "히스토리 바뀜")
  assert.equal(kindInfo("REVIEW").label, "PR 브랜치 바뀜")
  assert.equal(kindInfo("REVIEW").t, "neutral")
  assert.equal(kindInfo(null).label, "강제 push 또는 삭제")
  assert.equal(kindInfo("FAST_FORWARD").label, "강제 push 또는 삭제")
  assert.equal(kindInfo("toString").label, "강제 push 또는 삭제")
})

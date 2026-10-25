import assert from "node:assert/strict"
import { test } from "node:test"
import { href, repoHref } from "./router.ts"

test("href encodes each segment and skips empty params", () => {
  assert.equal(href(["r", "my repo", "src/a b.ts"], { ref: "main", L: undefined, x: "" }), "/r/my%20repo/src/a%20b.ts?ref=main")
  assert.equal(href(["r", "x", null, ""]), "/r/x")
  assert.equal(href(["r", "x", "blob", "a/#?.md"]), "/r/x/blob/a/%23%3F.md")
})

test("repo routes", () => {
  assert.equal(repoHref.tree("x"), "/r/x")
  assert.equal(repoHref.tree("x", "docs", "v1"), "/r/x/tree/docs?ref=v1")
  assert.equal(repoHref.blob("x", "a.md", null, 3, "source"), "/r/x/blob/a.md?L=3&view=source")
  assert.equal(repoHref.commits("x", "", null, 1), "/r/x/commits")
  assert.equal(repoHref.commits("x", "src", "dev", 2), "/r/x/commits/src?ref=dev&page=2")
})

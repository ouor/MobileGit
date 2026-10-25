import assert from "node:assert/strict"
import { test } from "node:test"
import { resolve, slugify } from "./readme.ts"

test("README-relative paths", () => {
  const cases: [string, string, string][] = [
    ["README.md", "docs/a.md", "docs/a.md"],
    ["docs/README.md", "./img/x.png", "docs/img/x.png"],
    ["docs/sub/notes.md", "../../assets/logo.png", "assets/logo.png"],
    ["docs/README.md", "/src/", "src"],
    ["docs/README.md", "a%20b.md", "docs/a b.md"],
    ["README.md", "../../x", "x"], // can't climb above the repo root
    ["README.md", "bad%zz", "bad%zz"],
  ]
  for (const [readme, target, want] of cases) assert.equal(resolve(readme, target), want, `${readme} + ${target}`)
})

test("GitHub-style heading slugs", () => {
  assert.equal(slugify("Getting Started"), "getting-started")
  assert.equal(slugify("API (v2) & more!"), "api-v2--more")
  assert.equal(slugify("설치 방법"), "설치-방법")
  assert.equal(slugify("  snake_case-name "), "snake_case-name")
})

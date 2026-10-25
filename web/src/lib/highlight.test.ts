// Run: npm test  (node's built-in runner with type stripping)
import assert from "node:assert/strict"
import { test } from "node:test"
import { languageFor, splitHtmlLines } from "./highlight.ts"

test("spans crossing newlines are closed and reopened", () => {
  const html = 'a <span class="c">/* x\ny */</span> b\n<span class="k">if</span>'
  assert.deepEqual(splitHtmlLines(html), [
    'a <span class="c">/* x</span>',
    '<span class="c">y */</span> b',
    '<span class="k">if</span>',
  ])
})

test("nested spans", () => {
  assert.deepEqual(splitHtmlLines('<span class="a"><span class="b">1\n2</span>3</span>'), [
    '<span class="a"><span class="b">1</span></span>',
    '<span class="a"><span class="b">2</span>3</span>',
  ])
})

test("language detection", () => {
  assert.equal(languageFor("src/Main.kt"), "kt")
  assert.equal(languageFor("app/build.gradle.kts"), "kotlin")
  assert.equal(languageFor("Dockerfile"), "dockerfile")
  assert.equal(languageFor("LICENSE"), null)
})

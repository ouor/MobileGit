// Syntax highlighting is loaded on demand (separate chunk) so the list/tree pages stay small.
export const HIGHLIGHT_MAX_BYTES = 256 * 1024

const byName: Record<string, string> = {
  dockerfile: "dockerfile",
  makefile: "makefile",
  "cmakelists.txt": "cmake",
  "build.gradle": "gradle",
  ".gitignore": "bash",
  ".bashrc": "bash",
}
const byExt: Record<string, string> = {
  kts: "kotlin",
  gradle: "groovy",
  h: "c",
  hpp: "cpp",
  cc: "cpp",
  mjs: "javascript",
  cjs: "javascript",
  vue: "xml",
  svg: "xml",
  toml: "ini",
  conf: "ini",
  properties: "properties",
  lock: "json",
}

export function languageFor(path: string): string | null {
  const name = path.split("/").pop()!.toLowerCase()
  if (byName[name]) return byName[name]
  const ext = name.includes(".") ? name.split(".").pop()! : ""
  return byExt[ext] ?? (ext || null)
}

// Minified or generated files: many lines or one huge line make highlighting slow and the colours useless (WEB-04).
export const HIGHLIGHT_MAX_LINES = 5000
export const HIGHLIGHT_MAX_LINE_LENGTH = 10000

export function canHighlight(text: string) {
  if (text.length > HIGHLIGHT_MAX_BYTES) return false
  const lines = text.split("\n")
  return lines.length <= HIGHLIGHT_MAX_LINES && lines.every((l) => l.length <= HIGHLIGHT_MAX_LINE_LENGTH)
}

// highlight.js runs in a worker (its own lazy chunk) so a big file doesn't freeze the tab (WEB-04).
let worker: Worker | null = null
let seq = 0

/** Returns one HTML string per line, or null when the language is unknown or the file is not worth highlighting. */
export function highlightLines(text: string, path: string): Promise<string[] | null> {
  const lang = languageFor(path)
  if (!lang || !canHighlight(text)) return Promise.resolve(null)
  const w = (worker ??= new Worker(new URL("./highlight.worker.ts", import.meta.url), { type: "module" }))
  const id = ++seq
  return new Promise((resolve) => {
    const done = (e: Event) => {
      const data = (e as MessageEvent<{ id: number; html: string | null }>).data
      if (data && data.id !== id) return
      w.removeEventListener("message", done)
      w.removeEventListener("error", done)
      resolve(data?.html != null ? splitHtmlLines(data.html) : null)
    }
    w.addEventListener("message", done)
    w.addEventListener("error", done)
    w.postMessage({ id, text, lang })
  })
}

/**
 * Splits highlight.js output into lines. Spans may cross newlines (block comments, strings),
 * so open spans are closed at each line end and reopened on the next line.
 */
export function splitHtmlLines(html: string): string[] {
  const lines: string[] = []
  const open: string[] = []
  let cur = ""
  for (const tok of html.split(/(<span[^>]*>|<\/span>|\n)/)) {
    if (!tok) continue
    if (tok === "\n") {
      lines.push(cur + "</span>".repeat(open.length))
      cur = open.join("")
    } else {
      if (tok.startsWith("<span")) open.push(tok)
      else if (tok === "</span>") open.pop()
      cur += tok
    }
  }
  lines.push(cur)
  return lines
}

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

/** Returns one HTML string per line, or null when the language is unknown. */
export async function highlightLines(text: string, path: string): Promise<string[] | null> {
  const lang = languageFor(path)
  if (!lang) return null
  const { default: hljs } = await import("highlight.js/lib/common")
  if (!hljs.getLanguage(lang)) return null
  return splitHtmlLines(hljs.highlight(text, { language: lang, ignoreIllegals: true }).value)
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

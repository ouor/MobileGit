// Runs highlight.js off the main thread so a big file doesn't freeze the tab (WEB-04).
import hljs from "highlight.js/lib/common"

onmessage = (e: MessageEvent<{ id: number; text: string; lang: string }>) => {
  const { id, text, lang } = e.data
  let html: string | null = null
  try {
    if (hljs.getLanguage(lang)) html = hljs.highlight(text, { language: lang, ignoreIllegals: true }).value
  } catch {
    html = null
  }
  postMessage({ id, html })
}

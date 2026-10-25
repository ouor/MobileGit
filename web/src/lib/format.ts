// Times follow design-system 8.2: absolute "2026.10.25 16:35" (24h, never the browser locale), relative only for recency.
const pad = (n: number) => String(n).padStart(2, "0")
const ymd = (d: Date) => `${d.getFullYear()}.${pad(d.getMonth() + 1)}.${pad(d.getDate())}`
export const clockTime = (ms: number) => {
  const d = new Date(ms)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}
export const absoluteTime = (ms: number | null | undefined) => (ms ? `${ymd(new Date(ms))} ${clockTime(ms)}` : "")
/** "방금", "5분 전", "3시간 전", "2일 전", then "2026.10.01" (app `ago`). */
export function ago(t: number, now = Date.now()) {
  const d = Math.floor(Math.max(0, now - t) / 1000)
  if (d < 60) return "방금"
  if (d < 3600) return `${Math.floor(d / 60)}분 전`
  if (d < 86400) return `${Math.floor(d / 3600)}시간 전`
  if (d < 86400 * 7) return `${Math.floor(d / 86400)}일 전`
  return ymd(new Date(t))
}

export const relativeTime = (ms: number | null | undefined, now = Date.now()) => (ms ? ago(ms, now) : "없음")
/** "2026.10.25 (일)" for commit day groups. */
export const dayLabel = (ms: number) => `${ymd(new Date(ms))} (${"일월화수목금토"[new Date(ms).getDay()]})`

export function formatBytes(n: number | null | undefined) {
  if (n === null || n === undefined) return ""
  if (n < 1024) return `${n} B`
  const units = ["KB", "MB", "GB", "TB"]
  let v = n / 1024
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v < 10 ? v.toFixed(1) : Math.round(v)} ${units[i]}`
}

/** "https://github.com/a/b.git" -> "github.com/a/b" */
export function shortUrl(url: string) {
  return url.replace(/^[a-z]+:\/\//i, "").replace(/\.git$/, "")
}

export const isSha = (ref: string | null | undefined) => !!ref && /^[0-9a-f]{7,40}$/i.test(ref)

const rtf = new Intl.RelativeTimeFormat("ko", { numeric: "auto" })
const dtf = new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short" })
const dayFmt = new Intl.DateTimeFormat("ko-KR", { year: "numeric", month: "long", day: "numeric", weekday: "long" })

export function relativeTime(ms: number | null | undefined, now = Date.now()) {
  if (!ms) return "기록 없음"
  const sec = Math.round((ms - now) / 1000)
  const abs = Math.abs(sec)
  if (abs < 45) return "방금 전"
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ["minute", 60],
    ["hour", 3600],
    ["day", 86400],
    ["week", 604800],
    ["month", 2629800],
    ["year", 31557600],
  ]
  let unit = units[0]
  for (const u of units) if (abs >= u[1]) unit = u
  return rtf.format(Math.round(sec / unit[1]), unit[0])
}

export const absoluteTime = (ms: number | null | undefined) => (ms ? dtf.format(ms) : "")
export const dayLabel = (ms: number) => dayFmt.format(ms)

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

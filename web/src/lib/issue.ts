// Row reason for a repo that needs attention (R2-01, WR-6), in the same words as the app (ui/Format.kt RepoIssue.text).
import type { Overview, RepoIssue, RepoSummary } from "./api.ts"
import { ago, clockTime } from "./format.ts"

const issueLabel: Record<RepoIssue["badge"], string> = {
  ORIGIN_GONE: "원본 없음",
  AUTH_REQUIRED: "토큰 확인 필요",
  FAILED: "업데이트 실패",
  DELAYED: "확인 지연",
  HISTORY_CHANGED: "히스토리 바뀜",
}

const categoryLabel: Record<string, string> = {
  NETWORK: "네트워크 오류",
  AUTH_REQUIRED: "로그인 필요",
  ORIGIN_GONE: "원본 없음",
  STORAGE_FULL: "저장 공간 부족",
  REMOTE_ERROR: "원본 서버 오류",
  TIMEOUT: "시간 초과",
}

/** "3일째", "5시간째", or under an hour "16:35부터" (app `running`, design-system 8.2). */
export function running(since: number, now = Date.now()) {
  const h = Math.floor(Math.max(0, now - since) / 3_600_000)
  return h >= 24 ? `${Math.floor(h / 24) + 1}일째` : h >= 1 ? `${h}시간째` : `${clockTime(since)}부터`
}

export function issueText(i: RepoIssue, now = Date.now()): string {
  switch (i.badge) {
    case "ORIGIN_GONE":
      return i.since ? `원본 없음 · ${running(i.since, now)}` : "원본 없음"
    case "AUTH_REQUIRED":
      return "토큰 확인 필요"
    case "FAILED":
      return ["업데이트 실패", i.category ? (categoryLabel[i.category] ?? "알 수 없는 오류") : null, i.since ? running(i.since, now) : null].filter(Boolean).join(" · ")
    case "DELAYED":
      // The app says "6시간 주기인데 2일째 확인 못 함"; the web API has no check interval, so the period is left out.
      return i.since ? `${running(i.since, now)} 확인 못 함` : "확인 지연"
    case "HISTORY_CHANGED":
      return i.since ? `히스토리 바뀜 · ${ago(i.since, now)} 보존` : "히스토리 바뀜"
    default:
      // A badge from a newer core: still say something (R2WEB-02).
      return issueLabel[i.badge] ?? "주의 필요"
  }
}

// CR-11: the core decides the tone; ERROR = 오류 (red), WARNING = 주의 (orange).
export const issueTone = (r: Pick<RepoSummary, "severity">) => (r.severity === "ERROR" ? "danger" : "warning")

export interface SummaryPart {
  text: string
  tone?: "danger" | "warning" | "success"
  /** Opens the "주의 필요" filter. */
  filter?: boolean
}

/**
 * CR-11: the app's main summary (MainScreen Summary) from GET /api/v1/overview, e.g.
 * "미러 12개 · 정상 10 · 오류 1 · 주의 1 · 마지막 확인 10분 전 · 로컬 3개".
 */
export function summaryParts(o: Overview, now = Date.now()): SummaryPart[] {
  const parts: SummaryPart[] = []
  const problems = o.errors + o.warnings
  if (o.mirrors > 0) parts.push({ text: `미러 ${o.mirrors}개` })
  if (o.mirrors > 0 && problems > 0) parts.push({ text: `정상 ${o.healthy}` })
  if (o.errors > 0) parts.push({ text: `오류 ${o.errors}`, tone: "danger", filter: true })
  if (o.warnings > 0) parts.push({ text: `주의 ${o.warnings}`, tone: "warning", filter: true })
  if (o.mirrors > 0 && problems === 0) parts.push({ text: "모두 정상", tone: "success" })
  if (o.lastCheckAt) parts.push({ text: `마지막 확인 ${ago(o.lastCheckAt, now)}` })
  if (o.locals > 0) parts.push({ text: `로컬 ${o.locals}개` })
  return parts
}

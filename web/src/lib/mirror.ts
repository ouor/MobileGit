// F-26 (PRD 15장 WM-04, WM-05): adding a mirror from the web. Words follow the PRD tables and design-system 8.
import type { RepoSummary } from "./api.ts"
import { categoryLabel } from "./issue.ts"

/** POST /api/v1/mirrors/preview (spec 3.1 WebMirrorPreviewDto). */
export interface MirrorPreview {
  url: string
  suggestedName: string
  /** Already mirrored as this repo; then `access` is null (the original is not asked). */
  existingRepo: string | null
  access: "REACHABLE" | "TOKEN_REQUIRED" | "ORIGIN_NOT_FOUND" | "FAILED" | null
  refCount: number | null
  defaultBranch: string | null
  pullRefCount: number | null
  failure: string | null
  includePullRefs: boolean
}

export interface MirrorError {
  status: number
  code: string
  message?: string
  category?: string | null
}

const tokenText = "비공개 레포예요. 폰 앱의 설정 → 원본 접속 토큰에서 토큰을 등록한 뒤 다시 시도하세요."
const failedText = (category: string | null | undefined) =>
  `원본을 확인하지 못했어요${category ? ` (${categoryLabel[category] ?? "알 수 없는 오류"})` : ""}. 주소를 확인하고 잠시 뒤 다시 시도하세요.`

/**
 * Message for a refused preview or add (WM-04 AC4). `anonymous`: the phone does not ask for a password
 * ("끄기"), so the stored token is not used and a private repo needs a password-signed-in visit.
 */
export function mirrorErrorText(e: MirrorError, anonymous = false): string {
  if (e.status === 401) return "미러를 추가하려면 이 폰의 비밀번호가 필요해요."
  switch (e.code) {
    case "NETWORK":
      return "폰에 연결할 수 없어요. 서버가 켜져 있는지 확인하세요."
    case "EXTERNAL_NOT_ALLOWED":
      return "외부 공개(ngrok) 주소에서는 미러를 추가할 수 없어요. 같은 와이파이나 Tailscale 주소로 접속하세요."
    case "CROSS_SITE":
      return "이 페이지에서 보낸 요청이 아니라서 거부했어요. 주소창에 폰 주소를 직접 입력해 다시 열어 주세요."
    case "HTTPS_ONLY":
      return "https 주소만 추가할 수 있어요. http 주소는 폰 앱에서 추가하세요."
    case "UNSUPPORTED_URL":
      return "git 저장소 주소가 아니에요. GitHub·GitLab 등의 레포 주소를 붙여 넣으세요."
    case "MULTIPLE_URLS":
      return "한 번에 주소 하나만 추가할 수 있어요. 여러 개는 폰 앱에서 추가하세요."
    case "TOKEN_NOT_ACCEPTED":
      return "웹에서는 토큰을 받지 않아요. 토큰은 폰 앱에서 등록하세요."
    case "TOKEN_REQUIRED":
      return anonymous ? `${tokenText} 비밀번호 없이 접속하면 저장된 토큰을 쓸 수 없어요. 앱에서 비밀번호 요구를 켜고 비밀번호로 접속하세요.` : tokenText
    case "ORIGIN_NOT_FOUND":
      return "원본을 찾을 수 없어요. 주소를 확인하세요."
    case "PROBE_FAILED":
      return failedText(e.category)
    case "ALREADY_MIRRORED":
      return "이미 보존 중이에요."
    case "INVALID_NAME":
      return e.message || "쓸 수 없는 이름이에요."
    case "NAME_TAKEN":
      return "같은 이름의 레포가 있어요. 이름을 바꿔 주세요."
    case "STORAGE_LOW":
      return "폰의 남은 공간이 최소 여유 공간보다 적어서 추가할 수 없어요."
    case "BUSY":
      return "지금 다른 확인이 진행 중이에요. 잠시 뒤 다시 시도하세요."
    case "BLOCKED":
      return "로그인 실패가 많아 잠시 차단됐어요. 잠시 뒤에 다시 시도하세요."
    default:
      return e.message || `서버 오류가 났어요 (${e.status})`
  }
}

/** A preview whose original can't be mirrored as is: the same words as the add refusal. null = can add. */
export function previewProblem(p: MirrorPreview, anonymous = false): string | null {
  if (p.existingRepo) return null // shown separately, with a link to the repo
  switch (p.access) {
    case "REACHABLE":
      return null
    case "TOKEN_REQUIRED":
      return mirrorErrorText({ status: 200, code: "TOKEN_REQUIRED" }, anonymous)
    case "ORIGIN_NOT_FOUND":
      return mirrorErrorText({ status: 200, code: "ORIGIN_NOT_FOUND" })
    default:
      return failedText(p.failure)
  }
}

/** "브랜치·태그 12개 받기 · PR 브랜치 제외 · Git LFS 파일과 서브모듈은 받지 않아요" (WM-04 AC2). */
export function scopeLine(p: MirrorPreview): string {
  const pr = p.includePullRefs ? `PR 브랜치 포함${p.pullRefCount ? ` (${p.pullRefCount}개)` : ""}` : "PR 브랜치 제외"
  return [`브랜치·태그 ${p.refCount ?? 0}개 받기`, pr, "Git LFS 파일과 서브모듈은 받지 않아요"].join(" · ")
}

/** Same rule as the core (RepoNames.validate), in the web's voice. null = fine. */
export function nameProblem(name: string): string | null {
  if (!name) return "이름을 입력하세요."
  if (name.length > 100) return `이름은 100자까지 쓸 수 있어요 (지금 ${name.length}자).`
  if (!/^[A-Za-z0-9]/.test(name)) return "이름은 영문이나 숫자로 시작해야 해요."
  if (!/^[A-Za-z0-9._-]+$/.test(name)) return "이름에는 영문, 숫자, '.', '_', '-'만 쓸 수 있어요."
  if (/\.git$/i.test(name)) return "이름은 .git으로 끝날 수 없어요."
  return null
}

const waitingText: Record<NonNullable<RepoSummary["waiting"]>, string> = {
  FOREGROUND: "폰에서 mobilegit 앱을 열면 받기를 시작해요",
  STORAGE: "폰의 저장 공간이 부족해서 받기를 기다리고 있어요",
  WIFI: "와이파이에 연결되면 받기를 시작해요",
  CHARGING: "충전을 시작하면 받기를 시작해요",
}

export type SyncState = { kind: "syncing"; label: string } | { kind: "waiting"; label: string; text: string }

/** Fetch state of a mirror (WM-05). null when nothing is in progress or waiting; failures come through `issue`. */
export function syncState(r: Pick<RepoSummary, "syncing" | "waiting">): SyncState | null {
  if (r.syncing) return { kind: "syncing", label: "받는 중" }
  if (r.waiting) return { kind: "waiting", label: "대기", text: waitingText[r.waiting] ?? "조건이 맞으면 받기를 시작해요" }
  return null
}

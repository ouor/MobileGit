import { useEffect, useRef, useState, useSyncExternalStore } from "react"

// Types mirror the DTOs in docs/core/spec.md section 3 (RepoBrowser.kt).
export type OriginStatus = "OK" | "GONE" | "AUTH_REQUIRED" | "UNKNOWN"

export interface Info {
  app: string
  version: string
  baseUrl: string
  authRequired: boolean
  anonymousRead: boolean
}

export interface RepoSummary {
  name: string
  type: "MIRROR" | "LOCAL"
  description: string
  defaultBranch: string | null
  lastActivityAt: number | null
  lastUpdateAt: number | null
  lastPushAt: number | null
  originUrl: string | null
  originStatus: OriginStatus | null
  hasLfs: boolean
  sizeBytes: number
}

export interface Ref {
  name: string
  sha: string
}

export interface RepoDetail {
  repo: RepoSummary
  empty: boolean
  head: string | null
  branches: Ref[]
  tags: Ref[]
  archivedRefCount: number
  submoduleUrls: string[]
  readOnly: boolean
  forkedFromUrl: string | null
  cloneUrl: string | null
}

export interface Commit {
  sha: string
  shortSha: string
  summary: string
  message: string
  authorName: string
  authorEmail: string
  authoredAt: number
  committedAt: number
  parents: string[]
}

export interface TreeEntry {
  name: string
  path: string
  type: "dir" | "file" | "submodule" | "symlink"
  size: number | null
  mode: string
}

export interface Tree {
  ref: string
  commit: Commit
  path: string
  entries: TreeEntry[]
}

export interface Blob {
  ref: string
  path: string
  size: number
  binary: boolean
  tooLarge: boolean
  lfs: { oid: string; size: number } | null
  text: string | null
}

export interface Readme {
  ref: string
  path: string
  format: "markdown" | "text"
  raw: string
  html: string
}

export interface CommitPage {
  ref: string
  path: string
  page: number
  perPage: number
  hasMore: boolean
  /** The server stopped scanning (200k commits) before filling the page; older commits exist but can't be listed (GIT-09). */
  truncated?: boolean
  commits: Commit[]
}

/** How the original moved when the ref was preserved (R2-03). null: preserved before round 2, kind unknown. */
export type ArchiveKind = "FORCED" | "DELETED" | "TAG_MOVED" | "TAG_DELETED"

export interface ArchiveEntry {
  at: number
  group: string
  kind: ArchiveKind | null
  ref: string
  name: string
  archiveRef: string
  sha: string
  shortSha: string
  summary: string | null
  committedAt: number | null
  /** The ref in the repo now (last update's view of the original); null = gone from the original. */
  currentSha: string | null
}

export class ApiError extends Error {
  status: number
  code: string
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

export function apiUrl(path: string, params: Record<string, string | number | undefined | null> = {}) {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== "") q.set(k, String(v))
  const qs = q.toString()
  return `/api/v1/${path}${qs ? `?${qs}` : ""}`
}

export const repoApi = (repo: string, sub = "") => `repos/${encodeURIComponent(repo)}${sub ? `/${sub}` : ""}`

const messages: Record<number, [string, string]> = {
  401: ["UNAUTHORIZED", "로그인이 필요해요. 페이지를 새로 고쳐 앱 설정의 사용자 이름과 비밀번호로 로그인하세요."],
  403: ["FORBIDDEN", "이 주소로는 접속할 수 없어요. 앱 화면에 보이는 주소로 접속하세요."],
  429: ["BLOCKED", "로그인 실패가 많아 잠시 차단됐어요. 잠시 뒤에 다시 시도하세요."],
}

async function getJson<T>(url: string): Promise<T> {
  let res: Response
  try {
    res = await fetch(url, { headers: { Accept: "application/json" } })
  } catch {
    throw new ApiError(0, "NETWORK", "폰에 연결할 수 없어요. 서버가 켜져 있는지, 같은 네트워크인지 확인하세요.")
  }
  if (res.ok) return res.json() as Promise<T>
  const fixed = messages[res.status]
  if (fixed) throw new ApiError(res.status, ...fixed)
  const body = await res.json().catch(() => null)
  const code: string = body?.error?.code ?? (res.status === 503 ? "BUSY" : "INTERNAL")
  if (code === "BUSY") throw new ApiError(503, code, "폰이 다른 요청을 처리하느라 바빠요. 잠시 뒤 다시 시도하세요.")
  throw new ApiError(res.status, code, body?.error?.message ?? `서버 오류가 났어요 (${res.status})`)
}

// shortcut: in-memory LRU for the tab's lifetime, revalidated on every mount; enough for a read-only single-user browser.
const CACHE_MAX = 100
const cache = new Map<string, unknown>()
const remember = (url: string, data: unknown) => {
  cache.delete(url)
  cache.set(url, data)
  if (cache.size > CACHE_MAX) cache.delete(cache.keys().next().value!)
}

// A page prefetches what its body will ask for; the body mounts a moment later and reuses that request
// (pending or settled within REUSE_MS) instead of sending a second one. "다시 시도" forces a new request.
const REUSE_MS = 3000
const recent = new Map<string, Promise<unknown>>()
function load<T>(url: string, force = false): Promise<T> {
  let p = recent.get(url) as Promise<T> | undefined
  if (!p || force) {
    p = getJson<T>(url).then((d) => (remember(url, d), d))
    recent.set(url, p)
    const mine = p
    p.catch(() => {}).finally(() => setTimeout(() => recent.get(url) === mine && recent.delete(url), REUSE_MS))
  }
  return p
}

/** Starts requests a page will need, before the parts that use them are mounted (WEB-06). */
export function usePrefetch(...urls: (string | null)[]) {
  const key = urls.join("\n")
  useEffect(() => {
    for (const u of key.split("\n")) if (u) load(u).catch(() => {})
  }, [key])
}

// Pages whose last refresh failed while older data stays on screen (WEB-03). App shows one banner for them.
const stale = new Set<string>()
const STALE_EVENT = "mobilegit:stale"
const RELOAD_EVENT = "mobilegit:reload"
const setStale = (url: string, on: boolean) => {
  if (stale.has(url) === on) return
  if (on) stale.add(url)
  else stale.delete(url)
  dispatchEvent(new Event(STALE_EVENT))
}
const staleSnapshot = () => stale.size
export function useStaleCount() {
  return useSyncExternalStore((cb) => (addEventListener(STALE_EVENT, cb), () => removeEventListener(STALE_EVENT, cb)), staleSnapshot)
}
/** Re-requests everything on screen (error "다시 시도" and the stale banner). */
export const reloadAll = () => dispatchEvent(new Event(RELOAD_EVENT))

export function useApi<T>(url: string | null) {
  const [state, setState] = useState<{ url: string | null; data?: T; error?: ApiError }>(() => ({
    url,
    data: url ? (cache.get(url) as T | undefined) : undefined,
  }))
  const [nonce, setNonce] = useState(0)
  const usedNonce = useRef(0)
  useEffect(() => {
    const bump = () => setNonce((n) => n + 1)
    addEventListener(RELOAD_EVENT, bump)
    return () => removeEventListener(RELOAD_EVENT, bump)
  }, [])
  const current = state.url === url ? state : { url, data: url ? (cache.get(url) as T | undefined) : undefined }

  useEffect(() => {
    if (!url) return
    let alive = true
    const force = nonce !== usedNonce.current // only the request right after "다시 시도" skips reuse
    usedNonce.current = nonce
    load<T>(url, force).then(
      (data) => {
        setStale(url, false)
        if (alive) setState({ url, data })
      },
      (error: ApiError) => {
        // Keep what is already on screen; only a page with nothing to show becomes an error page.
        const data = cache.get(url) as T | undefined
        if (data !== undefined) setStale(url, true)
        if (alive) setState({ url, data, error: data === undefined ? error : undefined })
      },
    )
    return () => {
      alive = false
      setStale(url, false)
    }
  }, [url, nonce])

  return { data: current.data, error: current.error, loading: !current.data && !current.error && !!url }
}

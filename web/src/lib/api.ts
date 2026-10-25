import { useEffect, useState } from "react"

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
  commits: Commit[]
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

async function getJson<T>(url: string): Promise<T> {
  let res: Response
  try {
    res = await fetch(url, { headers: { Accept: "application/json" } })
  } catch {
    throw new ApiError(0, "NETWORK", "폰에 연결할 수 없어요. 서버가 켜져 있는지, 같은 네트워크인지 확인하세요.")
  }
  if (res.ok) return res.json() as Promise<T>
  if (res.status === 401) throw new ApiError(401, "UNAUTHORIZED", "로그인이 필요해요. 페이지를 새로 고쳐 앱 설정의 사용자 이름과 비밀번호로 로그인하세요.")
  if (res.status === 429) throw new ApiError(429, "BLOCKED", "로그인 실패가 많아 잠시 차단됐어요. 5분 뒤에 다시 시도하세요.")
  const body = await res.json().catch(() => null)
  throw new ApiError(res.status, body?.error?.code ?? "INTERNAL", body?.error?.message ?? `서버 오류 (${res.status})`)
}

// shortcut: in-memory cache for the tab's lifetime, revalidated on every mount; enough for a read-only single-user browser.
const cache = new Map<string, unknown>()

export function useApi<T>(url: string | null) {
  const [state, setState] = useState<{ url: string | null; data?: T; error?: ApiError }>(() => ({
    url,
    data: url ? (cache.get(url) as T | undefined) : undefined,
  }))
  const current = state.url === url ? state : { url, data: url ? (cache.get(url) as T | undefined) : undefined }

  useEffect(() => {
    if (!url) return
    let alive = true
    getJson<T>(url).then(
      (data) => {
        cache.set(url, data)
        if (alive) setState({ url, data })
      },
      (error: ApiError) => alive && setState({ url, error }),
    )
    return () => {
      alive = false
    }
  }, [url])

  return { data: current.data, error: current.error, loading: !current.data && !current.error && !!url }
}

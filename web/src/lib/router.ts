import { useSyncExternalStore } from "react"

// Hash routing (#/r/<repo>/tree/<path>?ref=...). The server's SPA fallback only covers URL paths
// whose last segment has no ".", so file paths like README.md would 404 on reload with history
// routing (docs/web/core-requests.md WR-1). Links are plain <a href="#..."> so new tabs work.

const subscribe = (cb: () => void) => {
  addEventListener("hashchange", cb)
  return () => removeEventListener("hashchange", cb)
}
const getHash = () => location.hash.slice(1) || "/"

export interface Route {
  segments: string[] // decoded path segments
  query: URLSearchParams
}

export function useRoute(): Route {
  const hash = useSyncExternalStore(subscribe, getHash)
  const [path, search = ""] = hash.split("?", 2) as [string, string?]
  return {
    segments: path.split("/").filter(Boolean).map(safeDecode),
    query: new URLSearchParams(search),
  }
}

function safeDecode(s: string) {
  try {
    return decodeURIComponent(s)
  } catch {
    return s
  }
}

/** Builds "#/a/b?x=1". Each path part may contain "/" (a repo path); every segment is encoded. */
export function href(parts: (string | undefined | null)[], params: Record<string, string | number | undefined | null> = {}) {
  const path = parts
    .filter((p): p is string => !!p)
    .flatMap((p) => p.split("/"))
    .filter(Boolean)
    .map(encodeURIComponent)
    .join("/")
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== "") q.set(k, String(v))
  const qs = q.toString()
  return `#/${path}${qs ? `?${qs}` : ""}`
}

export const navigate = (to: string) => {
  location.hash = to.slice(1)
}

/** Routes for one repo. `ref` is dropped when it is the default branch to keep URLs short. */
export const repoHref = {
  tree: (repo: string, path = "", ref?: string | null) => href(["r", repo, path ? "tree" : null, path], { ref }),
  blob: (repo: string, path: string, ref?: string | null, line?: number) => href(["r", repo, "blob", path], { ref, L: line }),
  commits: (repo: string, path = "", ref?: string | null, page?: number) =>
    href(["r", repo, "commits", path], { ref, page: page && page > 1 ? page : undefined }),
}

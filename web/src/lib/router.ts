import { useSyncExternalStore } from "react"

// History routing under /r/ (the server always answers /r/... with index.html, spec 4 / WR-1).
// Links are plain <a href="/r/..."> so new tabs and copy-link work; same-tab clicks are
// intercepted in installLinkInterceptor() and turned into pushState.

const NAV_EVENT = "mobilegit:navigate"

const subscribe = (cb: () => void) => {
  addEventListener("popstate", cb)
  addEventListener(NAV_EVENT, cb)
  return () => {
    removeEventListener("popstate", cb)
    removeEventListener(NAV_EVENT, cb)
  }
}
const getLocation = () => location.pathname + location.search

export interface Route {
  segments: string[] // decoded path segments
  query: URLSearchParams
}

export function useRoute(): Route {
  const loc = useSyncExternalStore(subscribe, getLocation)
  const [path, search = ""] = loc.split("?", 2) as [string, string?]
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

/** Builds "/a/b?x=1". Each path part may contain "/" (a repo path); every segment is encoded. */
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
  return `/${path}${qs ? `?${qs}` : ""}`
}

export function navigate(to: string, { replace = false } = {}) {
  if (to === getLocation()) return
  history[replace ? "replaceState" : "pushState"](null, "", to)
  dispatchEvent(new Event(NAV_EVENT))
  if (!new URLSearchParams(to.split("?")[1] ?? "").has("L")) scrollTo(0, 0)
}

/** Same-tab clicks on internal links become client-side navigation. Downloads, /api/ and modified clicks are left to the browser. */
export function installLinkInterceptor() {
  // Old hash links (#/r/...) from the first version keep working.
  if (location.hash.startsWith("#/")) history.replaceState(null, "", location.hash.slice(1))
  document.addEventListener("click", (e) => {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
    const a = (e.target as Element).closest?.("a")
    if (!a || a.target || a.hasAttribute("download")) return
    const url = new URL(a.href, location.href)
    if (url.origin !== location.origin || url.pathname.startsWith("/api/")) return
    if (url.pathname === location.pathname && url.search === location.search && url.hash) return // in-page anchor
    e.preventDefault()
    navigate(url.pathname + url.search)
  })
}

/** Routes for one repo. `ref` is dropped when it is the default branch to keep URLs short. */
export const repoHref = {
  tree: (repo: string, path = "", ref?: string | null) => href(["r", repo, path ? "tree" : null, path], { ref }),
  blob: (repo: string, path: string, ref?: string | null, line?: number, view?: "source") =>
    href(["r", repo, "blob", path], { ref, L: line, view }),
  archives: (repo: string) => href(["r", repo, "archives"]), // the app's "웹에서 보기" opens this exact path (R2-03 AC3)
  commits: (repo: string, path = "", ref?: string | null, page?: number) =>
    href(["r", repo, "commits", path], { ref, page: page && page > 1 ? page : undefined }),
}

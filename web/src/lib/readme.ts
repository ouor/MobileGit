// Relative imports so `node --test` can load this file (WEB-15).
import { apiUrl, repoApi } from "./api.ts"
import { repoHref } from "./router.ts"

const external = /^[a-z][a-z0-9+.-]*:/i

/** Resolves a README-relative link against the README's directory ("/x" = repo root, like GitHub). */
export function resolve(readmePath: string, target: string) {
  const base = target.startsWith("/") ? [] : readmePath.split("/").slice(0, -1)
  for (const part of target.split("/")) {
    if (!part || part === ".") continue
    if (part === "..") base.pop()
    else
      try {
        base.push(decodeURIComponent(part))
      } catch {
        base.push(part)
      }
  }
  return base.join("/")
}

// Prefix keeps README ids from clashing with the page's own (GitHub uses the same one).
export const HEADING_ID_PREFIX = "user-content-"

/** GitHub-style heading anchor: lower case, punctuation dropped, spaces to "-". */
export function slugify(text: string) {
  return text
    .trim()
    .toLowerCase()
    .replace(/[^\p{L}\p{M}\p{N}\s_-]/gu, "")
    .replace(/\s/g, "-")
}

/**
 * Makes server-rendered README HTML work inside the SPA: relative links open the file view,
 * relative images load from the raw API, and external images (blocked by the CSP img-src 'self')
 * become plain links instead of broken images.
 */
export function rewriteReadme(html: string, repo: string, readmePath: string, ref: string | null) {
  const tpl = document.createElement("template")
  tpl.innerHTML = html
  for (const a of tpl.content.querySelectorAll("a[href]")) {
    const href = a.getAttribute("href")!
    if (href.startsWith("#")) {
      let id = href.slice(1)
      try {
        id = decodeURIComponent(id)
      } catch {
        /* keep as written */
      }
      a.setAttribute("href", `#${HEADING_ID_PREFIX}${id.toLowerCase()}`)
    } else if (external.test(href) || href.startsWith("//")) {
      a.setAttribute("target", "_blank")
      a.setAttribute("rel", "noopener noreferrer")
    } else {
      const path = resolve(readmePath, href.split(/[?#]/)[0])
      a.setAttribute("href", href.endsWith("/") || !path ? repoHref.tree(repo, path, ref) : repoHref.blob(repo, path, ref))
    }
  }
  // <picture> sources are not rewritten; dropping them makes the browser use the inner <img> handled below.
  for (const source of tpl.content.querySelectorAll("picture source")) source.remove()
  for (const img of tpl.content.querySelectorAll("img[src]")) {
    const src = img.getAttribute("src")!
    if (external.test(src) || src.startsWith("//")) {
      if (src.startsWith("data:")) continue
      // Badges are often wrapped in a link already; anchors must not nest.
      const inLink = !!img.closest("a")
      const link = document.createElement(inLink ? "span" : "a")
      if (!inLink) {
        link.setAttribute("href", src)
        link.setAttribute("target", "_blank")
        link.setAttribute("rel", "noopener noreferrer")
      }
      link.className = "text-xs"
      link.textContent = `[외부 이미지${img.getAttribute("alt") ? `: ${img.getAttribute("alt")}` : ""}]`
      img.replaceWith(link)
    } else {
      img.setAttribute("src", apiUrl(repoApi(repo, "raw"), { ref, path: resolve(readmePath, src.split(/[?#]/)[0]) }))
      img.setAttribute("loading", "lazy")
    }
  }
  // commonmark emits no heading ids; add GitHub's so table-of-contents links work (WEB-13). Repeats get -1, -2.
  const seen = new Map<string, number>()
  for (const h of tpl.content.querySelectorAll("h1, h2, h3, h4, h5, h6")) {
    const base = slugify(h.textContent ?? "")
    if (!base) continue // e.g. a heading that is only a logo image
    const n = seen.get(base) ?? 0
    seen.set(base, n + 1)
    h.id = HEADING_ID_PREFIX + (n ? `${base}-${n}` : base)
  }
  return tpl.innerHTML
}

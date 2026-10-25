import { apiUrl, repoApi } from "@/lib/api"
import { repoHref } from "@/lib/router"

const external = /^[a-z][a-z0-9+.-]*:/i

/** Resolves a README-relative link against the README's directory ("/x" = repo root, like GitHub). */
function resolve(readmePath: string, target: string) {
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
    if (href.startsWith("#")) a.removeAttribute("href") // commonmark emits no heading ids, so in-page anchors lead nowhere
    else if (external.test(href) || href.startsWith("//")) {
      a.setAttribute("target", "_blank")
      a.setAttribute("rel", "noopener noreferrer")
    } else {
      const path = resolve(readmePath, href.split(/[?#]/)[0])
      a.setAttribute("href", href.endsWith("/") || !path ? repoHref.tree(repo, path, ref) : repoHref.blob(repo, path, ref))
    }
  }
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
  return tpl.innerHTML
}

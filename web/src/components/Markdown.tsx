import { useMemo } from "react"
import type { Readme } from "@/lib/api"
import { rewriteReadme } from "@/lib/readme"
import { cn } from "@/lib/utils"

/** Server-rendered, server-sanitized Markdown (spec 3: raw HTML escaped, javascript: links removed). */
export function MarkdownBody({ doc, repo, refParam }: { doc: Readme; repo: string; refParam: string | null }) {
  const html = useMemo(() => rewriteReadme(doc.html, repo, doc.path, refParam), [doc, repo, refParam])
  return (
    <div
      className={cn(
        "markdown prose prose-sm max-w-none px-4 py-5 sm:px-8 sm:py-6 dark:prose-invert prose-headings:scroll-mt-20 prose-a:text-primary prose-code:before:content-none prose-code:after:content-none prose-code:rounded prose-code:bg-muted prose-code:px-1 prose-code:font-normal prose-pre:bg-muted prose-pre:text-foreground prose-img:inline prose-img:my-0",
        doc.format === "text" && "prose-pre:whitespace-pre-wrap",
      )}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  )
}

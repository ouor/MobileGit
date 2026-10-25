import { useEffect, useMemo, useState } from "react"
import { DownloadIcon, FileWarningIcon, HistoryIcon, ImageIcon } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Skeleton } from "@/components/ui/skeleton"
import { CopyButton, ErrorState } from "@/components/common"
import { PathBar } from "@/components/PathBar"
import { RepoLayout } from "@/components/RepoLayout"
import { apiUrl, repoApi, useApi, type Blob, type RepoDetail } from "@/lib/api"
import { formatBytes } from "@/lib/format"
import { HIGHLIGHT_MAX_BYTES, highlightLines } from "@/lib/highlight"
import { navigate, repoHref } from "@/lib/router"
import { cn } from "@/lib/utils"

const imageExt = /\.(png|jpe?g|gif|webp|bmp|ico|avif)$/i

/** W-03 */
export function FileView({ repo, path, refParam, line }: { repo: string; path: string; refParam: string | null; line: number | null }) {
  return (
    <RepoLayout repo={repo} tab="code">
      {(detail) => <FileBody detail={detail} path={path} refParam={refParam} line={line} />}
    </RepoLayout>
  )
}

function FileBody({ detail, path, refParam, line }: { detail: RepoDetail; path: string; refParam: string | null; line: number | null }) {
  const repo = detail.repo.name
  const { data: blob, error } = useApi<Blob>(apiUrl(repoApi(repo, "blob"), { ref: refParam, path }))
  const rawUrl = apiUrl(repoApi(repo, "raw"), { ref: refParam, path })

  // README links without a trailing slash may point at a folder.
  useEffect(() => {
    if (error?.code === "NOT_A_FILE") navigate(repoHref.tree(repo, path, refParam))
  }, [error, repo, path, refParam])

  const lines = useMemo(() => {
    if (!blob?.text && blob?.text !== "") return null
    const l = blob.text.split("\n")
    if (l.length > 1 && l[l.length - 1] === "") l.pop()
    return l
  }, [blob])

  return (
    <div className="flex flex-col gap-4">
      <PathBar
        detail={detail}
        refName={refParam ?? detail.head ?? ""}
        path={path}
        hrefForRef={(r) => repoHref.blob(repo, path, r)}
        actions={
          <Button variant="ghost" size="sm" asChild>
            <a href={repoHref.commits(repo, path, refParam)}>
              <HistoryIcon />
              기록
            </a>
          </Button>
        }
      />
      {error ? (
        <ErrorState error={error}>
          <Button variant="outline" asChild>
            <a href={repoHref.tree(repo, "", refParam)}>레포 처음으로</a>
          </Button>
        </ErrorState>
      ) : !blob ? (
        <div className="flex flex-col gap-2 rounded-xl border bg-card p-4">
          {Array.from({ length: 12 }, (_, i) => (
            <Skeleton key={i} className={cn("h-4", i % 3 ? "w-2/3" : "w-1/2")} />
          ))}
        </div>
      ) : (
        <section className="overflow-hidden rounded-xl border bg-card">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b bg-muted/40 py-1.5 pr-2 pl-4 text-sm">
            <span className="font-medium break-all">{path.split("/").pop()}</span>
            <span className="text-xs text-muted-foreground">
              {lines ? `${lines.length.toLocaleString()}줄 · ` : ""}
              {formatBytes(blob.size)}
            </span>
            <div className="ml-auto flex items-center gap-1">
              {blob.text !== null && <CopyButton text={blob.text} label="내용 복사" />}
              <Button variant="ghost" size="sm" asChild>
                <a href={rawUrl} download={path.split("/").pop()}>
                  <DownloadIcon />
                  원본 다운로드
                </a>
              </Button>
            </div>
          </div>
          {lines ? (
            <CodeView repo={repo} path={path} refParam={refParam} text={blob.text!} lines={lines} line={line} />
          ) : (
            <NotShown blob={blob} rawUrl={rawUrl} />
          )}
        </section>
      )}
    </div>
  )
}

function NotShown({ blob, rawUrl }: { blob: Blob; rawUrl: string }) {
  if (blob.binary && imageExt.test(blob.path) && !blob.tooLarge)
    return (
      <div className="flex justify-center bg-[repeating-conic-gradient(var(--muted)_0_25%,transparent_0_50%)] bg-size-[16px_16px] p-6">
        <img src={rawUrl} alt={blob.path} className="max-h-[70vh] max-w-full" />
      </div>
    )
  const [title, desc] = blob.lfs
    ? ["LFS 파일 (저장되지 않음)", `이 파일은 Git LFS 포인터예요. 실제 파일(${formatBytes(blob.lfs.size)})은 폰에 저장되지 않았어요. OID ${blob.lfs.oid.slice(0, 12)}…`]
    : blob.tooLarge
      ? ["파일이 너무 커서 표시할 수 없어요", `1MB를 넘는 파일은 내용을 보여 주지 않아요. 크기 ${formatBytes(blob.size)}`]
      : ["바이너리 파일이라 표시할 수 없어요", `크기 ${formatBytes(blob.size)}`]
  return (
    <Empty className="py-12">
      <EmptyHeader>
        <EmptyMedia variant="icon">{blob.lfs ? <FileWarningIcon /> : <ImageIcon />}</EmptyMedia>
        <EmptyTitle>{title}</EmptyTitle>
        <EmptyDescription>{desc}</EmptyDescription>
      </EmptyHeader>
      <EmptyContent>
        <Button variant="outline" asChild>
          <a href={rawUrl} download={blob.path.split("/").pop()}>
            <DownloadIcon />
            {blob.lfs ? "포인터 파일 다운로드" : "원본 다운로드"}
          </a>
        </Button>
      </EmptyContent>
    </Empty>
  )
}

function CodeView({
  repo,
  path,
  refParam,
  text,
  lines,
  line,
}: {
  repo: string
  path: string
  refParam: string | null
  text: string
  lines: string[]
  line: number | null
}) {
  const [html, setHtml] = useState<{ text: string; lines: string[] } | null>(null)
  useEffect(() => {
    if (text.length > HIGHLIGHT_MAX_BYTES) return
    let alive = true
    highlightLines(text, path).then((l) => alive && l && setHtml({ text, lines: l }), () => {})
    return () => {
      alive = false
    }
  }, [text, path])
  const hl = html?.text === text ? html.lines : null

  useEffect(() => {
    if (line) document.getElementById(`L${line}`)?.scrollIntoView({ block: "center" })
  }, [line, hl])

  return (
    <div className="overflow-x-auto py-2 font-mono text-[13px] leading-5">
      <table className="w-full border-collapse">
        <tbody>
          {lines.map((l, i) => {
            const n = i + 1
            return (
              <tr key={i} id={`L${n}`} className={cn(n === line && "bg-amber-400/15")}>
                <td className="w-px py-0 pr-3 pl-4 text-right align-top text-muted-foreground/70 select-none">
                  <a href={repoHref.blob(repo, path, refParam, n)} className="code-line-no hover:text-foreground" data-line={n} aria-label={`${n}번째 줄`} />
                </td>
                {hl ? (
                  <td className="py-0 pr-4 whitespace-pre" dangerouslySetInnerHTML={{ __html: hl[i] || "\n" }} />
                ) : (
                  <td className="py-0 pr-4 whitespace-pre">{l || "\n"}</td>
                )}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

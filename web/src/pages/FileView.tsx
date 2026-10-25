import { useEffect, useMemo, useState } from "react"
import { DownloadIcon, HistoryIcon, ImageIcon, PackageIcon, WrapTextIcon } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Skeleton } from "@/components/ui/skeleton"
import { CopyButton, ErrorState } from "@/components/common"
import { PathBar } from "@/components/PathBar"
import { RepoLayout } from "@/components/RepoLayout"
import { apiUrl, repoApi, useApi, type Blob, type Readme, type RepoDetail } from "@/lib/api"
import { MarkdownBody } from "@/components/Markdown"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { formatBytes } from "@/lib/format"
import { HIGHLIGHT_MAX_BYTES, highlightLines } from "@/lib/highlight"
import { navigate, repoHref } from "@/lib/router"
import { cn } from "@/lib/utils"

// Long lines wrap by default (round 1 review: clipped lines looked cut off); the choice is remembered per browser.
function useWrapSetting() {
  const [wrap, setWrap] = useState(() => {
    try {
      return localStorage.getItem("wrap") !== "off"
    } catch {
      return true
    }
  })
  const set = (v: boolean) => {
    setWrap(v)
    try {
      localStorage.setItem("wrap", v ? "on" : "off")
    } catch {
      /* not remembered in private mode */
    }
  }
  return [wrap, set] as const
}

const markdownExt = /\.(md|markdown)$/i
const imageExt = /\.(png|jpe?g|gif|webp|bmp|ico|avif)$/i

/** W-03 */
export function FileView({ repo, path, refParam, line, source }: { repo: string; path: string; refParam: string | null; line: number | null; source: boolean }) {
  return (
    <RepoLayout repo={repo} tab="code">
      {(detail) => <FileBody detail={detail} path={path} refParam={refParam} line={line} source={source} />}
    </RepoLayout>
  )
}

function FileBody({ detail, path, refParam, line, source }: { detail: RepoDetail; path: string; refParam: string | null; line: number | null; source: boolean }) {
  const repo = detail.repo.name
  const { data: blob, error } = useApi<Blob>(apiUrl(repoApi(repo, "blob"), { ref: refParam, path }))
  const rawUrl = apiUrl(repoApi(repo, "raw"), { ref: refParam, path })
  const [wrap, setWrap] = useWrapSetting()
  // Markdown files open as a preview (WR-5); a line link (?L=) or ?view=source shows the source.
  const isMd = markdownExt.test(path) && blob?.text != null
  const wantPreview = isMd && !source && !line
  const { data: md, error: mdError } = useApi<Readme>(wantPreview ? apiUrl(repoApi(repo, "readme"), { ref: refParam, path }) : null)
  const preview = wantPreview && !mdError

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
              이 파일의 커밋
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
              {isMd && !mdError && (
                <ToggleGroup
                  type="single"
                  variant="outline"
                  size="sm"
                  value={preview ? "preview" : "source"}
                  onValueChange={(v) => v && navigate(repoHref.blob(repo, path, refParam, undefined, v === "source" ? "source" : undefined), { replace: true })}
                  aria-label="보기 방식"
                >
                  <ToggleGroupItem value="preview">미리보기</ToggleGroupItem>
                  <ToggleGroupItem value="source">원문</ToggleGroupItem>
                </ToggleGroup>
              )}
              {lines && !preview && (
                <Button variant="ghost" size="sm" aria-pressed={wrap} onClick={() => setWrap(!wrap)} title="긴 줄을 화면 폭에 맞춰 접어요">
                  <WrapTextIcon />
                  줄바꿈 {wrap ? "켜짐" : "꺼짐"}
                </Button>
              )}
              {blob.text !== null && <CopyButton text={blob.text} label="내용 복사" />}
              <Button variant="ghost" size="sm" asChild>
                <a href={rawUrl} download={path.split("/").pop()}>
                  <DownloadIcon />
                  파일 다운로드
                </a>
              </Button>
            </div>
          </div>
          {mdError && (
            <p className="border-b bg-amber-500/10 px-4 py-2 text-xs text-amber-800 dark:text-amber-300">미리보기를 만들 수 없어 원문으로 보여요. {mdError.message}</p>
          )}
          {preview ? (
            md ? (
              <MarkdownBody doc={md} repo={repo} refParam={refParam} />
            ) : (
              <div className="flex flex-col gap-2 p-6">
                <Skeleton className="h-6 w-1/2" />
                <Skeleton className="h-4 w-full" />
                <Skeleton className="h-4 w-5/6" />
              </div>
            )
          ) : lines ? (
            <CodeView repo={repo} path={path} refParam={refParam} text={blob.text!} lines={lines} line={line} wrap={wrap} />
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
        <EmptyMedia variant="icon">{blob.lfs ? <PackageIcon /> : <ImageIcon />}</EmptyMedia>
        <EmptyTitle>{title}</EmptyTitle>
        <EmptyDescription>{desc}</EmptyDescription>
      </EmptyHeader>
      <EmptyContent>
        <Button variant="outline" asChild>
          <a href={rawUrl} download={blob.path.split("/").pop()}>
            <DownloadIcon />
            {blob.lfs ? "포인터 파일 다운로드" : "파일 다운로드"}
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
  wrap,
}: {
  repo: string
  path: string
  refParam: string | null
  text: string
  lines: string[]
  line: number | null
  wrap: boolean
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
  const codeCell = cn("py-0 pr-4", wrap ? "whitespace-pre-wrap [overflow-wrap:anywhere]" : "whitespace-pre")

  useEffect(() => {
    if (line) document.getElementById(`L${line}`)?.scrollIntoView({ block: "center" })
  }, [line, hl])

  return (
    <div className={cn("py-2 font-mono text-[13px] leading-5", !wrap && "overflow-x-auto")}>
      <table className="w-full border-collapse">
        <tbody>
          {lines.map((l, i) => {
            const n = i + 1
            return (
              <tr key={i} id={`L${n}`} className={cn(n === line && "bg-amber-400/15")}>
                <td className="w-px py-0 pr-3 pl-4 text-right align-top text-muted-foreground/70 select-none">
                  <a href={repoHref.blob(repo, path, refParam, n, markdownExt.test(path) ? "source" : undefined)} className="code-line-no hover:text-foreground" data-line={n} aria-label={`${n}번째 줄`} />
                </td>
                {hl ? (
                  <td className={codeCell} dangerouslySetInnerHTML={{ __html: hl[i] || "\n" }} />
                ) : (
                  <td className={codeCell}>{l || "\n"}</td>
                )}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

import { useState } from "react"
import { ChevronLeftIcon, ChevronRightIcon, EllipsisIcon, FolderTreeIcon, GitCommitHorizontalIcon } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { CopyButton, ErrorState, ListSkeleton, Time } from "@/components/common"
import { PathBar } from "@/components/PathBar"
import { RepoLayout } from "@/components/RepoLayout"
import { apiUrl, repoApi, useApi, type Commit, type CommitPage, type RepoDetail } from "@/lib/api"
import { dayLabel } from "@/lib/format"
import { repoHref } from "@/lib/router"

const PER_PAGE = 50

/** W-04 */
export function Commits({ repo, path, refParam, page }: { repo: string; path: string; refParam: string | null; page: number }) {
  return (
    <RepoLayout repo={repo} tab="commits">
      {(detail) => <CommitsBody detail={detail} path={path} refParam={refParam} page={page} />}
    </RepoLayout>
  )
}

function CommitsBody({ detail, path, refParam, page }: { detail: RepoDetail; path: string; refParam: string | null; page: number }) {
  const repo = detail.repo.name
  const { data, error } = useApi<CommitPage>(apiUrl(repoApi(repo, "commits"), { ref: refParam, path, page, perPage: PER_PAGE }))

  // Group by day like GitHub/Gitea so long histories are easy to scan.
  const groups: { day: string; commits: Commit[] }[] = []
  for (const c of data?.commits ?? []) {
    const day = dayLabel(c.committedAt)
    if (groups.at(-1)?.day !== day) groups.push({ day, commits: [] })
    groups.at(-1)!.commits.push(c)
  }

  return (
    <div className="flex flex-col gap-4">
      <PathBar detail={detail} refName={refParam ?? detail.head ?? ""} path={path} hrefForRef={(r) => repoHref.commits(repo, path, r)} />
      {path && (
        <p className="text-sm text-muted-foreground">
          <span className="font-mono text-foreground">{path}</span> 경로를 바꾼 커밋만 보여요.{" "}
          <a className="text-primary hover:underline" href={repoHref.commits(repo, "", refParam)}>
            전체 기록 보기
          </a>
        </p>
      )}
      {error ? (
        <ErrorState error={error} />
      ) : !data ? (
        <ListSkeleton rows={8} />
      ) : data.commits.length === 0 ? (
        <Empty className="border bg-card">
          <EmptyHeader>
            <EmptyTitle>커밋이 없어요</EmptyTitle>
            <EmptyDescription>{page > 1 ? "마지막 페이지를 지났어요." : "이 경로를 바꾼 커밋이 없어요."}</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <div className="flex flex-col gap-4">
          {groups.map((g) => (
            <section key={g.day} className="flex flex-col gap-2">
              <h2 className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
                <GitCommitHorizontalIcon className="size-4" />
                {g.day}
              </h2>
              <ul className="divide-y overflow-hidden rounded-xl border bg-card">
                {g.commits.map((c) => (
                  <CommitRow key={c.sha} repo={repo} commit={c} />
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
      {data && (page > 1 || data.hasMore) && (
        <nav className="flex items-center justify-center gap-2" aria-label="페이지 이동">
          {page > 1 ? (
            <Button variant="outline" asChild>
              <a href={repoHref.commits(repo, path, refParam, page - 1)}>
                <ChevronLeftIcon />
                이전
              </a>
            </Button>
          ) : (
            <Button variant="outline" disabled>
              <ChevronLeftIcon />
              이전
            </Button>
          )}
          <span className="px-2 text-sm text-muted-foreground tabular-nums">{page} 페이지</span>
          {data.hasMore ? (
            <Button variant="outline" asChild>
              <a href={repoHref.commits(repo, path, refParam, page + 1)}>
                다음
                <ChevronRightIcon />
              </a>
            </Button>
          ) : (
            <Button variant="outline" disabled>
              다음
              <ChevronRightIcon />
            </Button>
          )}
        </nav>
      )}
    </div>
  )
}

function CommitRow({ repo, commit: c }: { repo: string; commit: Commit }) {
  const [open, setOpen] = useState(false)
  const body = c.message.slice(c.message.indexOf("\n") + 1).trim()
  const hasBody = c.message.includes("\n") && body.length > 0
  return (
    <li className="flex items-start gap-2 px-4 py-3 sm:gap-4">
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex items-start gap-1">
          <p className="min-w-0 font-medium break-words">{c.summary}</p>
          {hasBody && (
            <Button variant="ghost" size="icon-xs" className="shrink-0" onClick={() => setOpen(!open)} aria-expanded={open} aria-label="커밋 메시지 전체 보기">
              <EllipsisIcon />
            </Button>
          )}
        </div>
        {open && <pre className="font-mono text-xs whitespace-pre-wrap text-muted-foreground">{body}</pre>}
        <p className="text-xs text-muted-foreground">
          <span className="font-medium text-foreground/80" title={c.authorEmail}>
            {c.authorName}
          </span>{" "}
          · <Time ms={c.committedAt} />
        </p>
      </div>
      <div className="flex shrink-0 items-center gap-0.5 sm:gap-1">
        <code className="hidden rounded-md bg-muted sm:inline px-1.5 py-0.5 font-mono text-xs" title={c.sha}>
          {c.shortSha}
        </code>
        <CopyButton text={c.sha} label="전체 SHA 복사" />
        <Tooltip>
          <TooltipTrigger asChild>
            <Button variant="ghost" size="icon-sm" asChild>
              <a href={repoHref.tree(repo, "", c.sha)} aria-label="이 커밋 시점의 파일 보기">
                <FolderTreeIcon />
              </a>
            </Button>
          </TooltipTrigger>
          <TooltipContent>이 시점의 파일 보기</TooltipContent>
        </Tooltip>
      </div>
    </li>
  )
}

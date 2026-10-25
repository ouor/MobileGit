import type { ReactNode } from "react"
import { ArchiveIcon, CodeIcon, DownloadIcon, ExternalLinkIcon, GitCommitHorizontalIcon, GitForkIcon } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverDescription, PopoverHeader, PopoverTitle, PopoverTrigger } from "@/components/ui/popover"
import { Skeleton } from "@/components/ui/skeleton"
import { CodeField, ErrorState, LfsBadge, LocalIcon, MirrorIcon, OriginBadge, RepoTypeBadge, Time } from "@/components/common"
import { apiUrl, repoApi, useApi, type RepoDetail } from "@/lib/api"
import { shortUrl } from "@/lib/format"
import { repoHref, useRoute } from "@/lib/router"
import { cn } from "@/lib/utils"

/** Clone URL relative to the host the browser used (PRD W-02), so Wi-Fi, Tailscale and ngrok all work. */
export const cloneUrlFor = (repo: string) => `${location.origin}/${repo}.git`

/** Same command as the app's A-15 "PC로 받기"; works from any clone, whatever its remotes are called. */
export const archiveFetchCmd = (repo: string) => `git fetch ${cloneUrlFor(repo)} 'refs/archive/*:refs/archive/*'`

export function ClonePanel({ detail }: { detail: RepoDetail }) {
  const url = cloneUrlFor(detail.repo.name)
  return (
    <div className="flex flex-col gap-2">
      <CodeField value={url} label="clone 주소 복사" />
      <CodeField value={`git clone ${url}`} label="clone 명령 복사" />
      {detail.readOnly && (
        <p className="text-xs text-muted-foreground">
          읽기 전용이라 push는 거부돼요. 고치려면 앱에서 로컬 레포로 분리하세요.
        </p>
      )}
      {detail.archivedRefCount > 0 && (
        <div className="flex flex-col gap-1.5">
          <p className="text-xs text-muted-foreground">
            원본에서 강제 push나 삭제로 사라진 브랜치·태그 {detail.archivedRefCount}개를 보존했어요. 일반 clone에는 들어가지 않으니 clone한 뒤 아래 명령으로 받으세요.{" "}
            <a href={repoHref.archives(detail.repo.name)} className="text-primary hover:underline">
              보존된 기록 보기
            </a>
          </p>
          <CodeField value={archiveFetchCmd(detail.repo.name)} label="보존된 기록 받기 명령 복사" />
        </div>
      )}
    </div>
  )
}

export function useRepoDetail(repo: string) {
  return useApi<RepoDetail>(apiUrl(repoApi(repo)))
}

export function RepoLayout({
  repo,
  tab,
  cloneInSidebar = false,
  children,
}: {
  repo: string
  tab: "code" | "commits" | "archives"
  /** The repo home shows the clone card in its sidebar on wide screens, so the header button would repeat it. */
  cloneInSidebar?: boolean
  children: (detail: RepoDetail) => ReactNode
}) {
  const { data: detail, error } = useRepoDetail(repo)
  const ref = useRoute().query.get("ref")
  if (error) return <ErrorState error={error} />
  if (!detail) return <HeaderSkeleton />
  const r = detail.repo
  return (
    <div className="flex flex-col gap-4">
      <header className="flex flex-col gap-3">
        <div className="flex flex-wrap items-start gap-x-3 gap-y-2">
          <div className="flex min-w-0 flex-1 flex-col gap-1.5">
            <div className="flex flex-wrap items-center gap-2">
              <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary" aria-hidden>
                {r.type === "MIRROR" ? <MirrorIcon className="size-4" /> : <LocalIcon className="size-4" />}
              </span>
              <h1 className="min-w-0 text-xl font-semibold break-all">
                <a href={repoHref.tree(r.name)} className="hover:underline">
                  {r.name}
                </a>
              </h1>
              <RepoTypeBadge repo={r} />
              <OriginBadge status={r.originStatus} />
              {r.hasLfs && <LfsBadge />}
            </div>
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
              {r.originUrl && (
                <a href={r.originUrl.replace(/\.git$/, "")} target="_blank" rel="noopener noreferrer" className="inline-flex min-w-0 items-center gap-1 hover:text-foreground hover:underline">
                  <ExternalLinkIcon className="size-3.5 shrink-0" />
                  <span className="truncate">{shortUrl(r.originUrl)}</span>
                </a>
              )}
              {detail.forkedFromUrl && (
                <span className="inline-flex items-center gap-1">
                  <GitForkIcon className="size-3.5" />
                  {shortUrl(detail.forkedFromUrl)}에서 분리
                </span>
              )}
              {r.type === "MIRROR" ? <Time ms={r.lastUpdateAt} prefix="업데이트 " /> : <Time ms={r.lastPushAt} prefix="마지막 push " />}
            </div>
          </div>
          {!detail.empty && (
            <Popover>
              <PopoverTrigger asChild>
                <Button className={cloneInSidebar ? "lg:hidden" : undefined}>
                  <DownloadIcon />
                  clone 주소
                </Button>
              </PopoverTrigger>
              <PopoverContent align="end" className="w-[min(26rem,calc(100vw-2rem))]">
                <PopoverHeader>
                  <PopoverTitle>PC에서 clone</PopoverTitle>
                  <PopoverDescription>이 브라우저가 접속한 주소 기준이에요. git이 묻는 계정은 앱의 사용자 이름과 비밀번호예요.</PopoverDescription>
                </PopoverHeader>
                <ClonePanel detail={detail} />
              </PopoverContent>
            </Popover>
          )}
        </div>
        {!detail.empty && (
          // Real links, not Radix tabs: middle-click, new tab and copy-link work, and arrow keys don't navigate (WEB-09).
          <nav aria-label="레포 화면" className="flex gap-1 border-b">
            <TabLink href={repoHref.tree(repo, "", ref)} active={tab === "code"}>
              <CodeIcon />
              코드
            </TabLink>
            <TabLink href={repoHref.commits(repo, "", ref)} active={tab === "commits"}>
              <GitCommitHorizontalIcon />
              커밋
            </TabLink>
            {/* Mirrors always have the tab (R2-03 AC1); a local repo only when it kept records from its mirror days. */}
            {(r.type === "MIRROR" || detail.archivedRefCount > 0) && (
              <TabLink href={repoHref.archives(repo)} active={tab === "archives"}>
                <ArchiveIcon />
                보존된 기록
                {detail.archivedRefCount > 0 && <span className="rounded-full bg-muted px-1.5 text-xs tabular-nums">{detail.archivedRefCount}</span>}
              </TabLink>
            )}
          </nav>
        )}
      </header>
      {children(detail)}
    </div>
  )
}

function TabLink({ href, active, children }: { href: string; active: boolean; children: ReactNode }) {
  return (
    <a
      href={href}
      aria-current={active ? "page" : undefined}
      className={cn(
        "relative -mb-px inline-flex items-center gap-1.5 border-b-2 px-2.5 pt-1 pb-2 text-sm font-medium transition-colors [&_svg]:size-4",
        "focus-visible:rounded-sm focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none",
        active ? "border-foreground text-foreground" : "border-transparent text-muted-foreground hover:text-foreground",
      )}
    >
      {children}
    </a>
  )
}

function HeaderSkeleton() {
  return (
    <div className="flex flex-col gap-3" aria-busy="true" aria-label="불러오는 중">
      <div className="flex items-center gap-2">
        <Skeleton className="size-8 rounded-lg" />
        <Skeleton className="h-6 w-48" />
      </div>
      <Skeleton className="h-4 w-72" />
      <Skeleton className="h-8 w-40" />
      <Skeleton className="h-64 w-full rounded-xl" />
    </div>
  )
}

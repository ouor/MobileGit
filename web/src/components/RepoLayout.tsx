import type { ReactNode } from "react"
import { CodeIcon, DownloadIcon, ExternalLinkIcon, FolderIcon, GitCommitHorizontalIcon, GitForkIcon, RefreshCwIcon } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverDescription, PopoverHeader, PopoverTitle, PopoverTrigger } from "@/components/ui/popover"
import { Skeleton } from "@/components/ui/skeleton"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { CodeField, ErrorState, LfsBadge, OriginBadge, ReadOnlyBadge, RepoTypeBadge, Time } from "@/components/common"
import { apiUrl, repoApi, useApi, type RepoDetail } from "@/lib/api"
import { shortUrl } from "@/lib/format"
import { navigate, repoHref, useRoute } from "@/lib/router"

/** Clone URL relative to the host the browser used (PRD W-02), so Wi-Fi, Tailscale and ngrok all work. */
export const cloneUrlFor = (repo: string) => `${location.origin}/${repo}.git`

export const archiveFetchCmd = "git fetch origin 'refs/archive/*:refs/archive/*'"

export function ClonePanel({ detail }: { detail: RepoDetail }) {
  const url = cloneUrlFor(detail.repo.name)
  return (
    <div className="flex flex-col gap-2">
      <CodeField value={url} label="clone 주소 복사" />
      <CodeField value={`git clone ${url}`} label="clone 명령 복사" />
      {detail.readOnly && (
        <p className="text-xs text-muted-foreground">
          읽기 전용 미러예요. clone·pull만 되고 push는 거부돼요. push하려면 앱에서 로컬 레포로 분리하세요.
        </p>
      )}
      {detail.archivedRefCount > 0 && (
        <div className="flex flex-col gap-1.5">
          <p className="text-xs text-muted-foreground">
            보존 ref {detail.archivedRefCount}개는 일반 clone에 포함되지 않아요. clone한 뒤 아래 명령으로 받으세요.
          </p>
          <CodeField value={archiveFetchCmd} label="보존 ref 받기 명령 복사" />
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
  children,
}: {
  repo: string
  tab: "code" | "commits"
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
                {r.type === "MIRROR" ? <RefreshCwIcon className="size-4" /> : <FolderIcon className="size-4" />}
              </span>
              <h1 className="min-w-0 text-xl font-semibold break-all">
                <a href={repoHref.tree(r.name)} className="hover:underline">
                  {r.name}
                </a>
              </h1>
              <RepoTypeBadge repo={r} />
              {detail.readOnly && <ReadOnlyBadge />}
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
              {r.type === "MIRROR" ? <Time ms={r.lastUpdateAt} prefix="업데이트 " /> : <Time ms={r.lastPushAt} prefix="push " />}
            </div>
          </div>
          {!detail.empty && (
            <Popover>
              <PopoverTrigger asChild>
                <Button>
                  <DownloadIcon />
                  Clone
                </Button>
              </PopoverTrigger>
              <PopoverContent align="end" className="w-[min(26rem,calc(100vw-2rem))]">
                <PopoverHeader>
                  <PopoverTitle>HTTP로 clone</PopoverTitle>
                  <PopoverDescription>이 브라우저가 접속한 주소 기준이에요. 인증 정보는 git이 물어봐요.</PopoverDescription>
                </PopoverHeader>
                <ClonePanel detail={detail} />
              </PopoverContent>
            </Popover>
          )}
        </div>
        {!detail.empty && (
          <Tabs value={tab} onValueChange={(v) => navigate(v === "code" ? repoHref.tree(repo, "", ref) : repoHref.commits(repo, "", ref))}>
            <TabsList variant="line" className="w-full justify-start border-b">
              <TabsTrigger value="code" className="flex-none">
                <CodeIcon />
                코드
              </TabsTrigger>
              <TabsTrigger value="commits" className="flex-none">
                <GitCommitHorizontalIcon />
                커밋
              </TabsTrigger>
            </TabsList>
          </Tabs>
        )}
      </header>
      {children(detail)}
    </div>
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

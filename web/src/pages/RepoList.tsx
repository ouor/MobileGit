import { useMemo, useState } from "react"
import { FolderIcon, InboxIcon, RefreshCwIcon, SearchIcon, XIcon } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from "@/components/ui/input-group"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { CodeField, ErrorState, LfsBadge, ListSkeleton, OriginBadge, Time } from "@/components/common"
import { apiUrl, useApi, type RepoSummary } from "@/lib/api"
import { formatBytes, shortUrl } from "@/lib/format"
import { repoHref } from "@/lib/router"

type Filter = "all" | "mirror" | "local" | "problem"
type Sort = "recent" | "name"

const hasProblem = (r: RepoSummary) => r.originStatus === "GONE" || r.originStatus === "AUTH_REQUIRED"

/** W-01 */
export function RepoList() {
  const { data: repos, error } = useApi<RepoSummary[]>(apiUrl("repos"))
  const [q, setQ] = useState("")
  const [filter, setFilter] = useState<Filter>("all")
  const [sort, setSort] = useState<Sort>("recent")

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return (repos ?? [])
      .filter((r) => !needle || r.name.toLowerCase().includes(needle) || r.description.toLowerCase().includes(needle))
      .filter((r) => filter === "all" || (filter === "mirror" ? r.type === "MIRROR" : filter === "local" ? r.type === "LOCAL" : hasProblem(r)))
      .sort((a, b) => (sort === "name" ? a.name.localeCompare(b.name) : (b.lastActivityAt ?? 0) - (a.lastActivityAt ?? 0)))
  }, [repos, q, filter, sort])

  if (error) return <ErrorState error={error} />

  const count = (f: Filter) =>
    (repos ?? []).filter((r) => (f === "mirror" ? r.type === "MIRROR" : f === "local" ? r.type === "LOCAL" : f === "problem" ? hasProblem(r) : true)).length

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-xl font-semibold">레포지토리</h1>
          <p className="text-sm text-muted-foreground">
            {repos ? `미러 ${count("mirror")}개 · 로컬 ${count("local")}개` : "불러오는 중"}
          </p>
        </div>
      </div>

      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <InputGroup className="bg-card sm:max-w-sm">
          <InputGroupAddon>
            <SearchIcon />
          </InputGroupAddon>
          <InputGroupInput value={q} onChange={(e) => setQ(e.target.value)} placeholder="이름이나 설명으로 찾기" aria-label="레포 이름 필터" />
          {q && (
            <InputGroupAddon align="inline-end">
              <InputGroupButton size="icon-xs" onClick={() => setQ("")} aria-label="필터 지우기">
                <XIcon />
              </InputGroupButton>
            </InputGroupAddon>
          )}
        </InputGroup>
        <div className="flex flex-wrap items-center gap-2 sm:ml-auto">
          <ToggleGroup type="single" variant="outline" size="sm" value={filter} onValueChange={(v) => v && setFilter(v as Filter)} aria-label="종류 필터">
            <ToggleGroupItem value="all">전체</ToggleGroupItem>
            <ToggleGroupItem value="mirror">미러</ToggleGroupItem>
            <ToggleGroupItem value="local">로컬</ToggleGroupItem>
            <ToggleGroupItem value="problem">문제 {repos && count("problem") > 0 ? count("problem") : ""}</ToggleGroupItem>
          </ToggleGroup>
          <ToggleGroup type="single" variant="outline" size="sm" value={sort} onValueChange={(v) => v && setSort(v as Sort)} aria-label="정렬">
            <ToggleGroupItem value="recent">최근순</ToggleGroupItem>
            <ToggleGroupItem value="name">이름순</ToggleGroupItem>
          </ToggleGroup>
        </div>
      </div>

      {!repos ? (
        <ListSkeleton rows={5} />
      ) : repos.length === 0 ? (
        <NoRepos />
      ) : shown.length === 0 ? (
        <Empty className="border bg-card">
          <EmptyHeader>
            <EmptyTitle>조건에 맞는 레포가 없어요</EmptyTitle>
            <EmptyDescription>다른 이름으로 찾거나 필터를 바꿔 보세요.</EmptyDescription>
          </EmptyHeader>
          <Button
            variant="outline"
            onClick={() => {
              setQ("")
              setFilter("all")
            }}
          >
            필터 초기화
          </Button>
        </Empty>
      ) : (
        <ul className="divide-y overflow-hidden rounded-xl border bg-card">
          {shown.map((r) => (
            <RepoRow key={r.name} repo={r} />
          ))}
        </ul>
      )}
    </div>
  )
}

function RepoRow({ repo: r }: { repo: RepoSummary }) {
  const mirror = r.type === "MIRROR"
  return (
    <li className="relative flex gap-3 px-4 py-3 transition-colors hover:bg-muted/50">
      <span
        className={`mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-lg ${mirror ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground"}`}
        title={mirror ? "미러" : "로컬 레포"}
      >
        {mirror ? <RefreshCwIcon className="size-4" /> : <FolderIcon className="size-4" />}
        <span className="sr-only">{mirror ? "미러" : "로컬 레포"}</span>
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          {/* The whole row is clickable through the stretched link. */}
          <a href={repoHref.tree(r.name)} className="font-semibold break-all text-foreground after:absolute after:inset-0 hover:underline">
            {r.name}
          </a>
          {mirror && <OriginBadge status={r.originStatus} />}
          {r.hasLfs && <LfsBadge />}
        </div>
        {r.description && <p className="line-clamp-2 text-sm text-muted-foreground">{r.description}</p>}
        <p className="flex flex-wrap gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
          <span>{mirror ? "미러" : "로컬"}</span>
          {mirror && r.originUrl && <span className="max-w-full truncate">{shortUrl(r.originUrl)}</span>}
          {mirror ? <Time ms={r.lastUpdateAt} prefix="업데이트 " /> : <Time ms={r.lastPushAt} prefix="push " />}
          {r.defaultBranch && <span>{r.defaultBranch}</span>}
          {r.sizeBytes > 0 && <span>{formatBytes(r.sizeBytes)}</span>}
        </p>
      </div>
    </li>
  )
}

function NoRepos() {
  const example = `git push ${location.origin}/my-project.git main`
  return (
    <Empty className="border bg-card py-12">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <InboxIcon />
        </EmptyMedia>
        <EmptyTitle>아직 레포가 없어요</EmptyTitle>
        <EmptyDescription>앱에서 미러를 추가하거나, PC에서 없는 이름으로 push하면 레포가 새로 만들어져요.</EmptyDescription>
      </EmptyHeader>
      <EmptyContent className="max-w-md">
        <CodeField value={example} label="push 명령 복사" />
      </EmptyContent>
    </Empty>
  )
}

import { useMemo, useState } from "react"
import { ArrowDownUpIcon, HourglassIcon, InboxIcon, SearchIcon, UploadIcon, XIcon } from "lucide-react"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Input } from "@/components/ui/input"
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from "@/components/ui/input-group"
import { Popover, PopoverContent, PopoverDescription, PopoverHeader, PopoverTitle, PopoverTrigger } from "@/components/ui/popover"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { CodeField, ErrorState, LfsBadge, ListSkeleton, IssueBadge, LocalIcon, MirrorIcon, Time } from "@/components/common"
import { apiUrl, useApi, type Overview, type RepoSummary } from "@/lib/api"
import { ago, formatBytes, shortUrl } from "@/lib/format"
import { repoHref } from "@/lib/router"

type Filter = "all" | "mirror" | "local" | "problem"
type Sort = "recent" | "name"
const sortLabel: Record<Sort, string> = { recent: "최근 활동순", name: "이름순" }

const matches = (r: RepoSummary, f: Filter) =>
  f === "all" || (f === "mirror" ? r.type === "MIRROR" : f === "local" ? r.type === "LOCAL" : r.needsAttention)

/** W-01. Words and filters follow the app's main screen (design-system 8). */
export function RepoList() {
  const { data: repos, error } = useApi<RepoSummary[]>(apiUrl("repos"))
  const { data: overview } = useApi<Overview>(apiUrl("overview"))
  const [q, setQ] = useState("")
  const [filter, setFilter] = useState<Filter>("all")
  const [sort, setSort] = useState<Sort>("recent")

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return (repos ?? [])
      .filter((r) => !needle || r.name.toLowerCase().includes(needle) || r.description.toLowerCase().includes(needle))
      .filter((r) => matches(r, filter))
      // "최근 활동순" puts repos that need attention first, like the app (R2-01 AC5).
      .sort((a, b) =>
        sort === "name"
          ? a.name.localeCompare(b.name)
          : Number(b.needsAttention) - Number(a.needsAttention) || (b.lastActivityAt ?? 0) - (a.lastActivityAt ?? 0),
      )
  }, [repos, q, filter, sort])

  if (error) return <ErrorState error={error} />

  const count = (f: Filter) => (repos ?? []).filter((r) => matches(r, f)).length
  const problems = count("problem")

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-xl font-semibold">레포</h1>
          {overview ? (
            <Summary o={overview} onProblems={() => setFilter("problem")} />
          ) : (
            <p className="text-sm text-muted-foreground">미러는 원본을 자동으로 따라가는 읽기 전용 레포, 로컬 레포는 PC에서 push한 레포예요</p>
          )}
        </div>
        <PushGuide />
      </div>

      {overview?.checksStalled && (
        <Alert className="border-amber-500/40 bg-amber-500/10 text-amber-900 dark:text-amber-200">
          <HourglassIcon />
          <AlertTitle>백그라운드 확인이 멈춘 것 같아요</AlertTitle>
          <AlertDescription className="text-current">백그라운드 확인이 멈췄어요. 폰의 배터리 최적화에서 mobilegit을 빼면 다시 확인해요.</AlertDescription>
        </Alert>
      )}

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
          <ToggleGroup type="single" variant="outline" value={filter} onValueChange={(v) => v && setFilter(v as Filter)} aria-label="종류 필터">
            <ToggleGroupItem value="all">전체 {repos?.length ?? ""}</ToggleGroupItem>
            <ToggleGroupItem value="mirror">미러 {repos ? count("mirror") : ""}</ToggleGroupItem>
            <ToggleGroupItem value="local">로컬 {repos ? count("local") : ""}</ToggleGroupItem>
            {/* Shown only when there is something wrong (design-system principle 7); same name as the app (R2-01 AC4). */}
            {(problems > 0 || filter === "problem") && <ToggleGroupItem value="problem">주의 필요 {problems}</ToggleGroupItem>}
          </ToggleGroup>
          {/* Non-modal: modal menus inject a <style> tag that the CSP blocks. */}
          <DropdownMenu modal={false}>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" aria-label={`정렬: ${sortLabel[sort]}`}>
                <ArrowDownUpIcon />
                {sortLabel[sort]}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-40">
              <DropdownMenuLabel>정렬</DropdownMenuLabel>
              <DropdownMenuRadioGroup value={sort} onValueChange={(v) => setSort(v as Sort)}>
                <DropdownMenuRadioItem value="recent">{sortLabel.recent}</DropdownMenuRadioItem>
                <DropdownMenuRadioItem value="name">{sortLabel.name}</DropdownMenuRadioItem>
              </DropdownMenuRadioGroup>
            </DropdownMenuContent>
          </DropdownMenu>
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

/**
 * CR-11: the app's main summary, from the same numbers (GET /api/v1/overview), e.g.
 * "미러 12개 · 정상 10 · 오류 1 · 주의 1 · 마지막 확인 10분 전 · 로컬 3개". 오류/주의 open the "주의 필요" filter.
 */
function Summary({ o, onProblems }: { o: Overview; onProblems: () => void }) {
  const parts: { text: string; tone?: string }[] = []
  if (o.mirrors > 0) parts.push({ text: `미러 ${o.mirrors}개` })
  if (o.mirrors > 0 && o.errors + o.warnings > 0) parts.push({ text: `정상 ${o.healthy}` })
  if (o.errors > 0) parts.push({ text: `오류 ${o.errors}`, tone: "text-red-700 dark:text-red-400" })
  if (o.warnings > 0) parts.push({ text: `주의 ${o.warnings}`, tone: "text-amber-800 dark:text-amber-300" })
  if (o.mirrors > 0 && o.errors + o.warnings === 0) parts.push({ text: "모두 정상", tone: "text-emerald-700 dark:text-emerald-400" })
  if (o.lastCheckAt) parts.push({ text: `마지막 확인 ${ago(o.lastCheckAt)}` })
  if (o.locals > 0) parts.push({ text: `로컬 ${o.locals}개` })
  return (
    <p className="text-sm text-muted-foreground">
      {parts.map((p, i) => (
        // Each part keeps its own " · " so a wrapped line never starts with the separator.
        <span key={p.text} className="whitespace-nowrap">
          {p.tone && p.text !== "모두 정상" ? (
            <button type="button" onClick={onProblems} className={`tap font-semibold hover:underline ${p.tone}`}>
              {p.text}
            </button>
          ) : (
            <span className={p.tone ? `font-semibold ${p.tone}` : undefined}>{p.text}</span>
          )}
          {i < parts.length - 1 && " ·"}
        </span>
      )).flatMap((el, i) => (i ? [" ", el] : [el]))}
    </p>
  )
}

function RepoRow({ repo: r }: { repo: RepoSummary }) {
  const mirror = r.type === "MIRROR"
  const meta = [mirror ? "미러" : "로컬", mirror && r.originUrl ? shortUrl(r.originUrl) : null, r.defaultBranch].filter(Boolean)
  return (
    <li className="relative flex items-center gap-3 px-4 py-3 transition-colors hover:bg-muted/50">
      <span
        className={`flex size-9 shrink-0 items-center justify-center rounded-lg ${mirror ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground"}`}
        title={mirror ? "미러" : "로컬 레포"}
      >
        {mirror ? <MirrorIcon className="size-4" /> : <LocalIcon className="size-4" />}
        <span className="sr-only">{mirror ? "미러" : "로컬 레포"}</span>
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          {/* The whole row is clickable through the stretched link. */}
          <a href={repoHref.tree(r.name)} className="font-semibold break-all text-foreground after:absolute after:inset-0 hover:underline">
            {r.name}
          </a>
          <IssueBadge repo={r} />
          {r.hasLfs && <LfsBadge />}
        </div>
        {r.description && <p className="line-clamp-2 text-sm">{r.description}</p>}
        <p className="truncate text-sm text-muted-foreground">
          {meta.join(" · ")}
          {/* On narrow screens time and size join the meta line; on wide screens they get their own columns. */}
          <span className="sm:hidden">
            {" · "}
            {mirror ? <Time ms={r.lastUpdateAt} prefix="업데이트 " empty="첫 받기 전" /> : <Time ms={r.lastPushAt} prefix="push " empty="push 전" />}
            {r.sizeBytes > 0 && ` · ${formatBytes(r.sizeBytes)}`}
          </span>
        </p>
      </div>
      <div className="hidden w-32 shrink-0 text-right text-sm text-muted-foreground sm:block">
        {mirror ? <Time ms={r.lastUpdateAt} prefix="업데이트 " empty="첫 받기 전" /> : <Time ms={r.lastPushAt} prefix="push " empty="push 전" />}
      </div>
      <div className="hidden w-16 shrink-0 text-right text-sm text-muted-foreground tabular-nums sm:block">{r.sizeBytes > 0 ? formatBytes(r.sizeBytes) : ""}</div>
    </li>
  )
}

const validName = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/

/** Same guide as the app's "PC 프로젝트 올리기" sheet: pushing to a new name creates the repo (F-13). */
function PushGuide() {
  const [name, setName] = useState("my-project")
  const ok = validName.test(name) && !name.endsWith(".git")
  const url = `${location.origin}/${ok ? name : "my-project"}.git`
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="outline">
          <UploadIcon />
          PC 프로젝트 올리기
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-[min(28rem,calc(100vw-2rem))]">
        <PopoverHeader>
          <PopoverTitle>PC 프로젝트 올리기</PopoverTitle>
          <PopoverDescription>PC의 프로젝트 폴더에서 아래 두 줄을 실행하면 이 이름으로 레포가 만들어져요. 앱에서 따로 만들 필요는 없어요.</PopoverDescription>
        </PopoverHeader>
        <label className="flex flex-col gap-1 text-sm">
          레포 이름
          <Input value={name} onChange={(e) => setName(e.target.value.trim())} aria-invalid={!ok} spellCheck={false} />
          {!ok && <span className="text-xs text-destructive">영문·숫자로 시작하고 영문, 숫자, . _ - 만 쓸 수 있어요 (.git으로 끝나면 안 돼요)</span>}
        </label>
        <CodeField value={`git remote add phone ${url}`} label="remote 추가 명령 복사" />
        <CodeField value="git push -u phone main" label="push 명령 복사" />
        <p className="text-xs text-muted-foreground">git이 계정을 물으면 앱의 사용자 이름과 비밀번호를 넣으세요. 비밀번호를 모르면 앱 설정에서 다시 정할 수 있어요.</p>
      </PopoverContent>
    </Popover>
  )
}

function NoRepos() {
  return (
    <Empty className="border bg-card py-12">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <InboxIcon />
        </EmptyMedia>
        <EmptyTitle>아직 레포가 없어요</EmptyTitle>
        <EmptyDescription>앱에서 미러를 추가하거나, PC에서 없는 이름으로 push하면 레포가 새로 만들어져요.</EmptyDescription>
      </EmptyHeader>
      <EmptyContent>
        <PushGuide />
      </EmptyContent>
    </Empty>
  )
}

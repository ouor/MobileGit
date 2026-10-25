import type { ReactNode } from "react"
import { ArchiveIcon, FolderTreeIcon, GitBranchIcon, GitCommitHorizontalIcon, TagIcon } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { CodeField, CopyButton, ErrorState, ListSkeleton, ToneBadge } from "@/components/common"
import { archiveFetchCmd, RepoLayout } from "@/components/RepoLayout"
import { apiUrl, repoApi, useApi, usePrefetch, type ArchiveEntry, type RepoDetail } from "@/lib/api"
import { groupArchives, kindInfo, type ArchivesResponse } from "@/lib/archives"
import { absoluteTime } from "@/lib/format"
import { repoHref } from "@/lib/router"

const archivesUrl = (repo: string) => apiUrl(repoApi(repo, "archives"))

/** W-05 (R2-03 AC5): what the mirror kept when the original rewrote or deleted branches and tags. */
export function Archives({ repo }: { repo: string }) {
  usePrefetch(archivesUrl(repo))
  return (
    <RepoLayout repo={repo} tab="archives">
      {(detail) => <ArchivesBody detail={detail} />}
    </RepoLayout>
  )
}

function ArchivesBody({ detail }: { detail: RepoDetail }) {
  const repo = detail.repo.name
  const { data, error } = useApi<ArchivesResponse>(archivesUrl(repo))
  const groups = groupArchives(data?.entries ?? [])

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted-foreground">원본이 강제 push하거나 브랜치·태그를 지우면 사라지기 전 상태를 여기에 남겨요. 보존된 커밋은 지워지지 않아요.</p>
      {error ? (
        <ErrorState error={error} />
      ) : !data ? (
        <ListSkeleton rows={4} />
      ) : groups.length === 0 ? (
        <Empty className="border bg-card py-12">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <ArchiveIcon />
            </EmptyMedia>
            <EmptyTitle>아직 보존된 기록이 없어요</EmptyTitle>
            <EmptyDescription>
              {detail.repo.type === "MIRROR" ? "원본에서 강제 push나 삭제가 일어나면 업데이트할 때 여기에 남아요." : "이 레포에는 보존된 기록이 없어요."}
            </EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <>
          {groups.map((g) => (
            <section key={g.group} className="flex flex-col gap-2" aria-label={g.at ? `${absoluteTime(g.at)}에 보존` : g.group}>
              <h2 className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
                <ArchiveIcon className="size-4" />
                <span>
                  {/* at = 0: a group name that is not a time (hand-made refs/archive/foo), so show the name (R2WEB-04). */}
                  {g.at ? <time dateTime={new Date(g.at).toISOString()}>{absoluteTime(g.at)}</time> : g.group} · {g.entries.length}개
                </span>
              </h2>
              <GroupTable repo={repo} entries={g.entries} />
              <GroupList repo={repo} entries={g.entries} />
            </section>
          ))}
          {data.truncated && <p className="text-sm text-muted-foreground">가장 최근 500개만 보여 줘요. 오래된 기록은 생략했어요.</p>}
          <Card size="sm">
            <CardHeader>
              <CardTitle className="font-semibold">PC로 받기</CardTitle>
              <CardDescription>보존된 기록은 일반 clone에 들어가지 않아요. clone한 레포에서 아래 명령을 실행하면 refs/archive/ 아래로 받아져요.</CardDescription>
            </CardHeader>
            <CardContent>
              <CodeField value={archiveFetchCmd(repo)} label="보존된 기록 받기 명령 복사" />
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}

function KindBadge({ kind }: { kind: string | null }) {
  const k = kindInfo(kind)
  return (
    <ToneBadge t={k.t} title={k.title}>
      {k.label}
    </ToneBadge>
  )
}

function RefName({ entry: e }: { entry: ArchiveEntry }) {
  const Icon = e.ref.startsWith("refs/tags/") ? TagIcon : GitBranchIcon
  return (
    <span className="inline-flex min-w-0 items-center gap-1.5 font-medium" title={e.ref}>
      <Icon className="size-4 shrink-0 text-muted-foreground" />
      <span className="break-all">{e.name}</span>
    </span>
  )
}

/** The preserved commit: short SHA (→ its commit log), first line, time. */
function KeptCommit({ entry: e }: { entry: ArchiveEntry }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <div className="flex items-center gap-1">
        {/* Text, not a link: "커밋 로그" in the same row already goes there (R2WEB-09). */}
        <code className="rounded-md bg-muted px-1.5 py-0.5 font-mono text-sm" title={e.sha}>
          {e.shortSha}
        </code>
        <CopyButton text={e.sha} label="보존된 커밋의 전체 SHA 복사" />
      </div>
      {e.summary && <span className="break-words">{e.summary}</span>}
      {e.committedAt && <span className="text-sm text-muted-foreground">{absoluteTime(e.committedAt)}</span>}
    </div>
  )
}

// 44px tall on touch screens (RV2-10).
const shaChip = "inline-flex items-center rounded-md bg-muted px-1.5 py-0.5 font-mono text-sm hover:underline pointer-coarse:min-h-11 pointer-coarse:px-2.5"

/** Where the original's ref is now, shown like the kept commit (RV2-08). */
function Current({ repo, entry: e }: { repo: string; entry: ArchiveEntry }) {
  if (!e.currentSha) return <span className="text-muted-foreground">없음</span>
  return (
    <span className="inline-flex items-center gap-1">
      <a href={repoHref.commits(repo, "", e.currentSha)} className={shaChip} title={`${e.currentSha} 기준 커밋 보기`}>
        {e.currentSha.slice(0, 7)}
      </a>
      <CopyButton text={e.currentSha} label="지금 원본 커밋의 전체 SHA 복사" />
    </span>
  )
}

function Links({ repo, entry: e }: { repo: string; entry: ArchiveEntry }) {
  return (
    <div className="flex flex-wrap gap-1">
      <Button variant="ghost" size="sm" className="pointer-coarse:h-11" asChild>
        <a href={repoHref.commits(repo, "", e.sha)}>
          <GitCommitHorizontalIcon />
          커밋 로그
        </a>
      </Button>
      <Button variant="ghost" size="sm" className="pointer-coarse:h-11" asChild>
        <a href={repoHref.tree(repo, "", e.sha)}>
          <FolderTreeIcon />
          파일 보기
        </a>
      </Button>
    </div>
  )
}

/** lg and up: a table, as PRD R2-03 AC5 asks. Below 1024px the fixed columns would squeeze the commit column (R2WEB-01). */
function GroupTable({ repo, entries }: { repo: string; entries: ArchiveEntry[] }) {
  return (
    <div className="hidden overflow-hidden rounded-xl border bg-card lg:block">
      <Table className="table-fixed text-sm">
        <TableHeader>
          <TableRow>
            <TableHead className="w-36 pl-4">종류</TableHead>
            <TableHead className="w-44">브랜치·태그</TableHead>
            <TableHead>보존된 커밋</TableHead>
            <TableHead className="w-36">지금 원본</TableHead>
            <TableHead className="w-60 pr-4">이동</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {entries.map((e) => (
            <TableRow key={e.archiveRef} className="align-top">
              <TableCell className="pl-4 whitespace-normal">
                <KindBadge kind={e.kind} />
              </TableCell>
              <TableCell className="whitespace-normal">
                <RefName entry={e} />
              </TableCell>
              <TableCell className="whitespace-normal">
                <KeptCommit entry={e} />
              </TableCell>
              <TableCell>
                <Current repo={repo} entry={e} />
              </TableCell>
              <TableCell className="pr-4">
                <Links repo={repo} entry={e} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

/** Narrow screens: the same fields stacked, so nothing scrolls sideways. */
function GroupList({ repo, entries }: { repo: string; entries: ArchiveEntry[] }) {
  return (
    <ul className="divide-y overflow-hidden rounded-xl border bg-card lg:hidden">
      {entries.map((e) => (
        <li key={e.archiveRef} className="flex flex-col gap-2 px-4 py-3">
          <div className="flex flex-wrap items-center gap-2">
            <RefName entry={e} />
            <KindBadge kind={e.kind} />
          </div>
          <KeptCommit entry={e} />
          <Field label="지금 원본:">
            <Current repo={repo} entry={e} />
          </Field>
          <Links repo={repo} entry={e} />
        </li>
      ))}
    </ul>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <p className="flex items-center gap-2 text-sm">
      <span className="text-muted-foreground">{label}</span>
      {children}
    </p>
  )
}

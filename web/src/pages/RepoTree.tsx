import type { ReactNode } from "react"
import {
  AlertTriangleIcon,
  ArchiveIcon,
  BookOpenIcon,
  CloudOffIcon,
  FileIcon,
  FileSymlinkIcon,
  FolderGitIcon,
  FolderIcon,
  HistoryIcon,
  HourglassIcon,
  InboxIcon,
  KeyRoundIcon,
  LoaderCircleIcon,
} from "lucide-react"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Skeleton } from "@/components/ui/skeleton"
import { BreakableText, CodeField, ErrorState, ListSkeleton, Time } from "@/components/common"
import { PathBar } from "@/components/PathBar"
import { ClonePanel, cloneUrlFor, RepoLayout } from "@/components/RepoLayout"
import { apiUrl, repoApi, useApi, usePrefetch, type Readme, type RepoDetail, type Tree, type TreeEntry } from "@/lib/api"
import { formatBytes, shortUrl } from "@/lib/format"
import { syncState } from "@/lib/mirror"
import { repoHref } from "@/lib/router"
import { MarkdownBody } from "@/components/Markdown"

/** W-02 (path = "") and W-02b (sub directory). */
const treeUrl = (repo: string, path: string, ref: string | null) => apiUrl(repoApi(repo, "tree"), { ref, path })

export function RepoTree({ repo, path, refParam }: { repo: string; path: string; refParam: string | null }) {
  usePrefetch(treeUrl(repo, path, refParam)) // in parallel with the repo detail (WEB-06)
  return (
    <RepoLayout repo={repo} tab="code" cloneInSidebar={!path}>
      {(detail) => (detail.empty ? <EmptyRepo detail={detail} /> : <TreeBody detail={detail} path={path} refParam={refParam} />)}
    </RepoLayout>
  )
}

function TreeBody({ detail, path, refParam }: { detail: RepoDetail; path: string; refParam: string | null }) {
  const repo = detail.repo.name
  const refName = refParam ?? detail.head ?? ""
  const { data: tree, error } = useApi<Tree>(treeUrl(repo, path, refParam))
  const isRoot = !path

  const main = (
    <div className="flex min-w-0 flex-col gap-4">
      <PathBar
        detail={detail}
        refName={refName}
        path={path}
        hrefForRef={(r) => repoHref.tree(repo, path, r)}
        actions={
          // At the root this would repeat the 커밋 tab.
          !isRoot && (
            <Button variant="ghost" size="sm" asChild>
              <a href={repoHref.commits(repo, path, refParam)}>
                <HistoryIcon />
                이 폴더의 커밋
              </a>
            </Button>
          )
        }
      />
      {error ? (
        <ErrorState error={error}>
          {path && (
            <Button variant="outline" asChild>
              <a href={repoHref.tree(repo, "", refParam)}>레포 처음으로</a>
            </Button>
          )}
        </ErrorState>
      ) : !tree ? (
        <ListSkeleton />
      ) : (
        <>
          <FileList repo={repo} tree={tree} refParam={refParam} />
          <ReadmeCard repo={repo} refParam={refParam} path={path} hasReadme={tree.entries.some((e) => /^readme(\.md|\.markdown|\.txt)?$/i.test(e.name))} />
        </>
      )}
    </div>
  )

  if (!isRoot) return main
  // Files come first on narrow screens; the header's Clone button covers cloning there.
  return (
    <div className="flex flex-col gap-4">
      <OriginAlerts detail={detail} />
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_18rem]">
        {main}
        <aside className="flex flex-col gap-4">
          <Card size="sm" className="hidden lg:flex">
            <CardHeader>
              <CardTitle>PC에서 clone</CardTitle>
            </CardHeader>
            <CardContent>
              <ClonePanel detail={detail} />
            </CardContent>
          </Card>
          <ContentAlerts detail={detail} />
          <AboutCard detail={detail} />
        </aside>
      </div>
    </div>
  )
}

function FileList({ repo, tree, refParam }: { repo: string; tree: Tree; refParam: string | null }) {
  // The API already lists directories first, then by name (spec 3, RepoBrowser.tree).
  const entries = tree.entries
  const parent = tree.path.split("/").slice(0, -1).join("/")
  const c = tree.commit
  return (
    <div className="overflow-hidden rounded-xl border bg-card">
      {/* Latest commit for this ref/path (W-02). */}
      <div className="flex items-center gap-3 border-b bg-muted/40 px-4 py-2.5 text-sm">
        <span className="shrink-0 font-medium">{c.authorName}</span>
        <a href={repoHref.commits(repo, tree.path, refParam)} className="min-w-0 flex-1 truncate text-muted-foreground hover:text-foreground hover:underline" title={c.message}>
          {c.summary}
        </a>
        <a href={repoHref.commits(repo, tree.path, refParam)} className="hidden shrink-0 font-mono text-xs text-muted-foreground hover:underline sm:inline" title={c.sha}>
          {c.shortSha}
        </a>
        <span className="shrink-0 text-xs text-muted-foreground">
          <Time ms={c.committedAt} />
        </span>
      </div>
      <ul className="divide-y text-sm">
        {tree.path && (
          <li>
            <a href={repoHref.tree(repo, parent, refParam)} className="flex items-center gap-3 px-4 py-2 text-muted-foreground hover:bg-muted/50" aria-label="상위 폴더로">
              <FolderIcon className="size-4 shrink-0 text-primary" />
              ..
            </a>
          </li>
        )}
        {entries.map((e) => (
          <EntryRow key={e.path} repo={repo} entry={e} refParam={refParam}  />
        ))}
        {entries.length === 0 && <li className="px-4 py-6 text-center text-muted-foreground">빈 폴더예요</li>}
      </ul>
    </div>
  )
}

function EntryRow({ repo, entry: e, refParam }: { repo: string; entry: TreeEntry; refParam: string | null }) {
  const icon =
    e.type === "dir" ? (
      <FolderIcon className="size-4 shrink-0 fill-primary/20 text-primary" />
    ) : e.type === "submodule" ? (
      <FolderGitIcon className="size-4 shrink-0 text-muted-foreground" />
    ) : e.type === "symlink" ? (
      <FileSymlinkIcon className="size-4 shrink-0 text-muted-foreground" />
    ) : (
      <FileIcon className="size-4 shrink-0 text-muted-foreground" />
    )
  if (e.type === "submodule")
    return (
      <li className="flex items-center gap-3 px-4 py-2" title="서브모듈은 함께 보존되지 않아요">
        {icon}
        <span className="truncate">{e.name}</span>
        <span className="ml-auto text-xs text-muted-foreground">서브모듈</span>
      </li>
    )
  return (
    <li>
      <a
        href={e.type === "dir" ? repoHref.tree(repo, e.path, refParam) : repoHref.blob(repo, e.path, refParam)}
        className="flex items-center gap-3 px-4 py-2 hover:bg-muted/50"
      >
        {icon}
        <span className="min-w-0 truncate hover:underline">{e.name}</span>
        {e.lfs && (
          <span className="shrink-0 rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground" title="LFS 파일은 받지 않아요. 큰 파일을 가리키는 작은 표시 파일이에요">
            LFS 포인터
          </span>
        )}
        {e.size !== null && <span className="ml-auto shrink-0 text-xs text-muted-foreground tabular-nums">{formatBytes(e.size)}</span>}
      </a>
    </li>
  )
}

function ReadmeCard({ repo, refParam, path, hasReadme }: { repo: string; refParam: string | null; path: string; hasReadme: boolean }) {
  const { data, error } = useApi<Readme>(hasReadme ? apiUrl(repoApi(repo, "readme"), { ref: refParam, path }) : null)
  if (!hasReadme || error) return null
  return (
    <section className="overflow-hidden rounded-xl border bg-card" aria-label="README">
      <div className="flex items-center gap-2 border-b px-4 py-2.5 text-sm font-medium">
        <BookOpenIcon className="size-4 text-muted-foreground" />
        {data ? (
          <a href={repoHref.blob(repo, data.path, refParam)} className="hover:underline">
            {data.path.split("/").pop()}
          </a>
        ) : (
          "README"
        )}
      </div>
      {!data ? (
        <div className="flex flex-col gap-2 p-6">
          <Skeleton className="h-6 w-1/2" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-5/6" />
        </div>
      ) : (
        <MarkdownBody doc={data} repo={repo} refParam={refParam} />
      )}
    </section>
  )
}

function OriginAlerts({ detail }: { detail: RepoDetail }) {
  const r = detail.repo
  return (
    <>
      {r.originStatus === "GONE" && (
        <Alert variant="destructive">
          <CloudOffIcon />
          <AlertTitle>원본에 접근할 수 없어요</AlertTitle>
          <AlertDescription>원본이 삭제되었거나 비공개로 바뀌었어요. 이 미러가 마지막 남은 복사본일 수 있으니 앱에서 내보내기로 백업하세요.</AlertDescription>
        </Alert>
      )}
      {r.originStatus === "AUTH_REQUIRED" && (
        <Alert>
          <KeyRoundIcon />
          <AlertTitle>원본 접속 토큰을 확인해야 해요</AlertTitle>
          <AlertDescription>앱에서 원본 접속 토큰을 등록하면 다시 업데이트돼요.</AlertDescription>
        </Alert>
      )}
    </>
  )
}

function ContentAlerts({ detail }: { detail: RepoDetail }) {
  return (
    <>
      {detail.repo.hasLfs && (
        <Alert>
          <AlertTriangleIcon />
          <AlertTitle>LFS 파일은 받지 않아요 (포인터만)</AlertTitle>
          <AlertDescription>이 레포는 Git LFS를 써요. 큰 파일 본체는 받지 않고, 그 파일을 가리키는 작은 포인터 파일만 저장해요. 파일 목록에서 &lsquo;LFS 포인터&rsquo;로 표시돼요.</AlertDescription>
        </Alert>
      )}
      {detail.submoduleUrls.length > 0 && (
        <Alert>
          <FolderGitIcon />
          <AlertTitle>서브모듈은 함께 보존되지 않아요</AlertTitle>
          <AlertDescription>
            <p>필요하면 앱에서 각각 미러로 추가하세요.</p>
            <ul className="mt-1 flex flex-col gap-0.5">
              {detail.submoduleUrls.map((u) => (
                <li key={u} className="font-mono text-xs">
                  <BreakableText text={u} />
                </li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
    </>
  )
}

function AboutCard({ detail }: { detail: RepoDetail }) {
  const r = detail.repo
  const rows: [string, ReactNode][] = [
    ["종류", r.type === "MIRROR" ? "미러 (읽기 전용)" : "로컬 레포"],
  ]
  // The last update / push time is in the header already (RV2 review), so it is not repeated here.
  rows.push(["기본 브랜치", detail.head ?? "-"])
  // R2-07: what a mirror fetches. Local repos hold just what was pushed, so they get no row (RV2-09).
  if (r.type === "MIRROR")
    rows.push([
      "받는 범위",
      <span key="s" className="flex flex-col">
        <span>모든 브랜치·태그</span>
        {r.includePullRefs !== null && <span className="text-muted-foreground">{r.includePullRefs ? "PR 브랜치 포함" : "PR 브랜치 제외"}</span>}
      </span>,
    ])
  if (r.hasLfs) rows.push(["LFS", "포인터만"])
  rows.push(["브랜치·태그", `브랜치 ${detail.branches.length} · 태그 ${detail.tags.length}`])
  if (r.sizeBytes > 0) rows.push(["용량", formatBytes(r.sizeBytes)])
  const showArchives = r.type === "MIRROR" || detail.archivedRefCount > 0
  if (showArchives)
    rows.push([
      "보존된 기록",
      <a key="a" href={repoHref.archives(r.name)} className="tap inline-flex items-center gap-1 text-primary hover:underline">
        <ArchiveIcon className="size-3.5" />
        {detail.archivedRefCount}개 보기
      </a>,
    ])
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle>정보</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 text-sm">
        {r.description && <p>{r.description}</p>}
        {r.originUrl && (
          <a href={r.originUrl.replace(/\.git$/, "")} target="_blank" rel="noopener noreferrer" className="tap break-all text-primary hover:underline">
            <BreakableText text={shortUrl(r.originUrl)} />
          </a>
        )}
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2">
          {rows.map(([k, v]) => (
            <div key={k} className="contents">
              <dt className="whitespace-nowrap text-muted-foreground">{k}</dt>
              <dd className="justify-self-end text-right">{v}</dd>
            </div>
          ))}
        </dl>
        {showArchives && (
          <p className="text-xs text-muted-foreground">
            {detail.archivedRefCount > 0
              ? "원본이 강제 push하거나 지운 브랜치·태그의 사라지기 전 상태예요."
              : "원본이 강제 push하거나 브랜치·태그를 지우면 사라지기 전 상태를 여기에 남겨요."}
          </p>
        )}
      </CardContent>
    </Card>
  )
}

/** W-E: repository without commits. */
function EmptyRepo({ detail }: { detail: RepoDetail }) {
  const r = detail.repo
  const url = cloneUrlFor(r.name)
  const mirror = r.type === "MIRROR"
  const sync = syncState(r)
  // WM-05: say what the first fetch is doing; a failure shows as the issue badge, retried from the phone.
  const [icon, title, desc] = !mirror
    ? [<InboxIcon key="i" />, "아직 커밋이 없어요", "PC의 기존 프로젝트에서 아래 명령으로 push하세요."]
    : sync?.kind === "syncing"
      ? [<LoaderCircleIcon key="i" className="animate-spin motion-reduce:animate-none" />, "원본에서 받는 중이에요", "끝나면 자동으로 보여 줘요."]
      : sync?.kind === "waiting"
        ? [<HourglassIcon key="i" />, "받기를 기다리고 있어요", `${sync.text}.`]
        : r.issue
          ? [<AlertTriangleIcon key="i" />, "원본에서 받지 못했어요", "폰 앱에서 다시 시도하세요."]
          : [<InboxIcon key="i" />, "아직 커밋이 없어요", "원본이 비어 있어요. 원본에 커밋이 생기면 업데이트할 때 받아요."]
  return (
    <Empty className="border bg-card py-12">
      <EmptyHeader>
        <EmptyMedia variant="icon">{icon}</EmptyMedia>
        <EmptyTitle>{title}</EmptyTitle>
        <EmptyDescription>{desc}</EmptyDescription>
      </EmptyHeader>
      {!mirror && (
        <EmptyContent className="max-w-lg">
          <CodeField value={`git remote add phone ${url}`} label="remote 추가 명령 복사" />
          <CodeField value="git push -u phone main" label="push 명령 복사" />
        </EmptyContent>
      )}
    </Empty>
  )
}

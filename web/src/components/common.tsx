import { Fragment, useEffect, useState, type ReactNode } from "react"
import {
  AlertTriangleIcon,
  CheckIcon,
  CloudOffIcon,
  CopyIcon,
  ArrowLeftRightIcon,
  FolderIcon,
  HistoryIcon,
  HourglassIcon,
  KeyRoundIcon,
  LoaderCircleIcon,
  PackageIcon,
  RotateCwIcon,
  ServerCrashIcon,
  XIcon,
} from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Skeleton } from "@/components/ui/skeleton"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { reloadAll, type ApiError, type RepoIssue, type RepoSummary } from "@/lib/api"
import { issueText, issueTone } from "@/lib/issue"
import { syncState } from "@/lib/mirror"
import { absoluteTime, relativeTime } from "@/lib/format"
import { cn } from "@/lib/utils"

// Status tones follow docs/ui/design-system.md (icon + text, never color alone).
const tone = {
  success: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  warning: "bg-amber-500/15 text-amber-800 dark:text-amber-300",
  danger: "bg-red-500/10 text-red-700 dark:text-red-400",
  info: "bg-primary/10 text-primary",
  neutral: "bg-muted text-muted-foreground",
}

export function ToneBadge({ t, children, title }: { t: keyof typeof tone; children: ReactNode; title?: string }) {
  return (
    // relative z-10: lifts the badge above a row's stretched link so its title tooltip shows (WEB-12).
    <Badge variant="secondary" className={cn("relative z-10", tone[t])} title={title}>
      {children}
      {title && <span className="sr-only">: {title}</span>}
    </Badge>
  )
}

/** Icons match the Android app (design-system 5): mirror = SyncAlt (⇄), local = Folder. */
export const MirrorIcon = ArrowLeftRightIcon
export const LocalIcon = FolderIcon

export function RepoTypeBadge({ repo }: { repo: RepoSummary }) {
  return repo.type === "MIRROR" ? (
    <ToneBadge t="info" title="원본을 자동으로 따라가는 읽기 전용 미러예요. clone·pull만 되고 push는 안 돼요">
      <MirrorIcon />
      읽기 전용 미러
    </ToneBadge>
  ) : (
    <ToneBadge t="neutral" title="PC에서 push해 만든 레포예요">
      <LocalIcon />
      로컬 레포 · push 가능
    </ToneBadge>
  )
}

// Only repos that need attention get a badge (design-system principle 7), with the app's row wording (R2-01, WR-6).
const issueIcon: Record<RepoIssue["badge"], ReactNode> = {
  ORIGIN_GONE: <CloudOffIcon />,
  AUTH_REQUIRED: <KeyRoundIcon />,
  FAILED: <AlertTriangleIcon />,
  DELAYED: <HourglassIcon />,
  HISTORY_CHANGED: <HistoryIcon />,
}
const issueHint: Record<RepoIssue["badge"], string> = {
  ORIGIN_GONE: "원본이 삭제되었거나 비공개로 바뀌었어요. 이 미러가 마지막 남은 복사본일 수 있어요",
  AUTH_REQUIRED: "원본에 접근하려면 앱에서 원본 접속 토큰을 확인해야 해요",
  FAILED: "마지막 업데이트가 실패했어요. 앱의 업데이트 기록에서 자세히 볼 수 있어요",
  DELAYED: "확인 주기보다 오래 원본을 확인하지 못했어요. 폰의 배터리 최적화 설정을 확인하세요",
  HISTORY_CHANGED: "원본이 강제 push하거나 브랜치·태그를 지워 사라지기 전 상태를 보존했어요",
}

export function IssueBadge({ repo }: { repo: RepoSummary }) {
  const issue = repo.issue
  if (!issue) return null
  return (
    <ToneBadge t={issueTone(repo)} title={issueHint[issue.badge]}>
      {issueIcon[issue.badge] ?? <AlertTriangleIcon />}
      {issueText(issue)}
    </ToneBadge>
  )
}

/** WM-05: "받는 중" while a fetch runs, "대기" with the reason while it waits. */
export function SyncBadge({ repo }: { repo: RepoSummary }) {
  const s = syncState(repo)
  if (!s) return null
  return s.kind === "syncing" ? (
    <ToneBadge t="info">
      <LoaderCircleIcon className="animate-spin motion-reduce:animate-none" />
      {s.label}
    </ToneBadge>
  ) : (
    <ToneBadge t="warning" title={s.text}>
      <HourglassIcon />
      {s.label}
    </ToneBadge>
  )
}

export function LfsBadge() {
  return (
    <ToneBadge t="neutral" title="LFS 파일은 받지 않아요. 큰 파일을 가리키는 작은 표시 파일(포인터)만 있어요">
      <PackageIcon />
      LFS 포인터만
    </ToneBadge>
  )
}

/** Relative time with the absolute one on hover; `empty` is said instead (without the prefix) when there is no time yet. */
export function Time({ ms, prefix, empty }: { ms: number | null | undefined; prefix?: string; empty?: string }) {
  if (!ms && empty) return <span>{empty}</span>
  return (
    <time dateTime={ms ? new Date(ms).toISOString() : undefined} title={absoluteTime(ms)}>
      {prefix}
      {relativeTime(ms)}
    </time>
  )
}

/** Copies with the Clipboard API, or (plain http on the LAN has none) a hidden textarea. Returns whether it worked. */
function copyText(text: string, from: HTMLElement): Promise<boolean> {
  if (window.isSecureContext && navigator.clipboard) return navigator.clipboard.writeText(text).then(() => true, () => false)
  // The textarea goes next to the button, not into <body>: focus leaving a popover would close it (WEB-02).
  const ta = document.createElement("textarea")
  ta.value = text
  ta.setAttribute("readonly", "")
  ta.setAttribute("aria-hidden", "true")
  ta.className = "fixed -left-[9999px] opacity-0"
  from.parentElement!.appendChild(ta)
  ta.select()
  let ok = false
  try {
    ok = document.execCommand("copy")
  } catch {
    ok = false
  }
  ta.remove()
  from.focus()
  return Promise.resolve(ok)
}

export function CopyButton({ text, label, className }: { text: string; label: string; className?: string }) {
  const [result, setResult] = useState<"ok" | "fail" | null>(null)
  useEffect(() => {
    if (!result) return
    const t = setTimeout(() => setResult(null), result === "ok" ? 1500 : 4000)
    return () => clearTimeout(t)
  }, [result])
  const message = result === "ok" ? "복사했어요" : result === "fail" ? "복사하지 못했어요. 글자를 직접 선택해 복사하세요" : label
  return (
    <Tooltip open={result ? true : undefined}>
      <TooltipTrigger asChild>
        <Button
          variant="ghost"
          size="icon-sm"
          className={cn("pointer-coarse:size-11", className)} // RV2-10: 44px on touch screens
          onClick={(e) => copyText(text, e.currentTarget).then((ok) => setResult(ok ? "ok" : "fail"))}
          aria-label={label}
        >
          {result === "ok" ? <CheckIcon className="text-emerald-600 dark:text-emerald-400" /> : result === "fail" ? <XIcon className="text-destructive" /> : <CopyIcon />}
        </Button>
      </TooltipTrigger>
      <TooltipContent>{message}</TooltipContent>
      <span className="sr-only" aria-live="polite">
        {result ? message : ""}
      </span>
    </Tooltip>
  )
}

/**
 * Text that may wrap only after "/", ":" or a space, never inside a word like "field-notes"
 * (round 1 review: people retype these addresses by hand). Each piece is nowrap; <wbr> marks the allowed breaks.
 */
export function BreakableText({ text }: { text: string }) {
  const parts = text.split(/(?<= )|(?<=\/)(?!\/)|(?<=:)(?![\d/])/) // keep "host:port" and "://" together
  return (
    <>
      {parts.map((p, i) => (
        <Fragment key={i}>
          {i > 0 && <wbr />}
          <span className="whitespace-nowrap">{p}</span>
        </Fragment>
      ))}
    </>
  )
}

/** A copyable code field (clone address, commands). */
export function CodeField({ value, label }: { value: string; label: string }) {
  return (
    <div className="flex min-w-0 items-center gap-1 rounded-lg border bg-muted/50 pl-2.5">
      {/* RV2-08 / design-system 8.3: commands and addresses never wrap; they scroll sideways. */}
      {/* Focusable so the keyboard can scroll it; a named region so screen readers don't meet a nameless stop (R2WEB-07). */}
      <code tabIndex={0} role="region" aria-label={label.replace(/ 복사$/, "")} className="min-w-0 flex-1 overflow-x-auto py-2 font-mono text-sm leading-5 whitespace-pre select-all">
        {value}
      </code>
      <CopyButton text={value} label={label} />
    </div>
  )
}

const errorTitle: Record<string, string> = {
  REPO_NOT_FOUND: "레포를 찾을 수 없어요",
  REF_NOT_FOUND: "브랜치나 태그를 찾을 수 없어요",
  PATH_NOT_FOUND: "파일이나 폴더를 찾을 수 없어요",
  NOT_FOUND: "페이지를 찾을 수 없어요",
  UNAUTHORIZED: "로그인이 필요해요",
  BLOCKED: "잠시 차단됐어요",
  NETWORK: "연결할 수 없어요",
  FORBIDDEN: "접속할 수 없는 주소예요",
  BUSY: "폰이 바빠요",
  INTERNAL: "서버 오류가 났어요",
  PAGE_TOO_DEEP: "너무 뒤 페이지예요",
}
// Errors that may go away on their own; these get a "다시 시도" button.
const retryable = new Set(["NETWORK", "BUSY", "INTERNAL"])

export function ErrorState({ error, children }: { error: ApiError | { code: string; message: string }; children?: ReactNode }) {
  return (
    <Empty className="border bg-card py-16">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          {error.code === "NETWORK" ? <ServerCrashIcon /> : <AlertTriangleIcon />}
        </EmptyMedia>
        <EmptyTitle>{errorTitle[error.code] ?? "문제가 생겼어요"}</EmptyTitle>
        <EmptyDescription>
          {error.message}
          {/* The server only sends an error ID for 500s; the details are in the app log (SEC-10). */}
          {error.code === "INTERNAL" && " 계속되면 앱의 로그에서 이 오류 ID를 찾아 보세요."}
        </EmptyDescription>
      </EmptyHeader>
      <EmptyContent>
        <div className="flex flex-wrap justify-center gap-2">
          {retryable.has(error.code) && (
            <Button onClick={reloadAll}>
              <RotateCwIcon />
              다시 시도
            </Button>
          )}
          {children}
          <Button variant="outline" asChild>
            <a href="/">레포 목록으로</a>
          </Button>
        </div>
      </EmptyContent>
    </Empty>
  )
}

export function ListSkeleton({ rows = 6, className }: { rows?: number; className?: string }) {
  return (
    <div className={cn("divide-y rounded-xl border bg-card", className)} aria-busy="true" aria-label="불러오는 중">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-center gap-3 px-4 py-3">
          <Skeleton className="size-4 rounded" />
          <Skeleton className="h-4 w-1/3" />
          <Skeleton className="ml-auto h-4 w-16" />
        </div>
      ))}
    </div>
  )
}

import { Fragment, useEffect, useState, type ReactNode } from "react"
import {
  AlertTriangleIcon,
  CheckIcon,
  CloudOffIcon,
  CopyIcon,
  ArrowLeftRightIcon,
  FolderIcon,
  KeyRoundIcon,
  PackageIcon,
  ServerCrashIcon,
} from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { Skeleton } from "@/components/ui/skeleton"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import type { ApiError, OriginStatus, RepoSummary } from "@/lib/api"
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
    <Badge variant="secondary" className={tone[t]} title={title}>
      {children}
    </Badge>
  )
}

/** Icons match the Android app (design-system 5): mirror = SyncAlt (⇄), local = Folder. */
export const MirrorIcon = ArrowLeftRightIcon
export const LocalIcon = FolderIcon

export function RepoTypeBadge({ repo }: { repo: RepoSummary }) {
  return repo.type === "MIRROR" ? (
    <ToneBadge t="info" title="원본을 자동으로 따라가는 사본이에요. clone·pull만 되고 push는 안 돼요">
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

// Only problems get a badge (design-system principle 7): "OK" and "not checked yet" draw nothing.
const originProblem: Partial<Record<OriginStatus, { t: keyof typeof tone; label: string; icon: ReactNode; title: string }>> = {
  GONE: { t: "danger", label: "원본 없음", icon: <CloudOffIcon />, title: "원본이 삭제되었거나 비공개로 바뀌었어요. 이 미러가 남은 사본일 수 있어요" },
  AUTH_REQUIRED: { t: "warning", label: "인증 필요", icon: <KeyRoundIcon />, title: "원본에 접근하려면 앱에서 원본 접속 토큰을 등록해야 해요" },
}

export function OriginBadge({ status }: { status: OriginStatus | null }) {
  const o = status && originProblem[status]
  if (!o) return null
  return (
    <ToneBadge t={o.t} title={o.title}>
      {o.icon}
      {o.label}
    </ToneBadge>
  )
}

export function LfsBadge() {
  return (
    <ToneBadge t="neutral" title="이 레포는 Git LFS를 쓰지만 LFS 파일은 저장되지 않아요">
      <PackageIcon />
      LFS 누락
    </ToneBadge>
  )
}

export function Time({ ms, prefix }: { ms: number | null | undefined; prefix?: string }) {
  return (
    <time dateTime={ms ? new Date(ms).toISOString() : undefined} title={absoluteTime(ms)}>
      {prefix}
      {relativeTime(ms)}
    </time>
  )
}

export function CopyButton({ text, label, className }: { text: string; label: string; className?: string }) {
  const [done, setDone] = useState(false)
  useEffect(() => {
    if (!done) return
    const t = setTimeout(() => setDone(false), 1500)
    return () => clearTimeout(t)
  }, [done])
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      // Clipboard API needs a secure context; the phone is served over plain http on the LAN.
      const ta = document.createElement("textarea")
      ta.value = text
      ta.setAttribute("readonly", "")
      ta.className = "fixed -left-[9999px]"
      document.body.appendChild(ta)
      ta.select()
      document.execCommand("copy")
      ta.remove()
    }
    setDone(true)
  }
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button variant="ghost" size="icon-sm" className={className} onClick={copy} aria-label={label}>
          {done ? <CheckIcon className="text-emerald-600 dark:text-emerald-400" /> : <CopyIcon />}
        </Button>
      </TooltipTrigger>
      <TooltipContent>{done ? "복사했어요" : label}</TooltipContent>
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
    <div className="flex min-w-0 items-start gap-1 rounded-lg border bg-muted/50 pl-2.5">
      {/* overflow-x-auto only matters for a single piece longer than the box. */}
      <code className="min-w-0 flex-1 overflow-x-auto py-1.5 font-mono text-[13px] leading-5 select-all">
        <BreakableText text={value} />
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
}

export function ErrorState({ error, children }: { error: ApiError | { code: string; message: string }; children?: ReactNode }) {
  return (
    <Empty className="border bg-card py-16">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          {error.code === "NETWORK" ? <ServerCrashIcon /> : <AlertTriangleIcon />}
        </EmptyMedia>
        <EmptyTitle>{errorTitle[error.code] ?? "문제가 생겼어요"}</EmptyTitle>
        <EmptyDescription>{error.message}</EmptyDescription>
      </EmptyHeader>
      <EmptyContent>
        <div className="flex flex-wrap justify-center gap-2">
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

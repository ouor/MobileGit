import { useEffect, useState, type ReactNode } from "react"
import {
  AlertTriangleIcon,
  CheckIcon,
  CircleHelpIcon,
  CloudOffIcon,
  CopyIcon,
  FileWarningIcon,
  FolderIcon,
  KeyRoundIcon,
  LockIcon,
  RefreshCwIcon,
  ServerCrashIcon,
  ShieldCheckIcon,
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

export function RepoTypeBadge({ repo }: { repo: RepoSummary }) {
  return repo.type === "MIRROR" ? (
    <ToneBadge t="info" title="원본을 따라가는 읽기 전용 미러">
      <RefreshCwIcon />
      미러
    </ToneBadge>
  ) : (
    <ToneBadge t="neutral" title="폰에서 만든 레포 (push 가능)">
      <FolderIcon />
      로컬
    </ToneBadge>
  )
}

const origin: Record<OriginStatus, { t: keyof typeof tone; label: string; icon: ReactNode; title: string }> = {
  OK: { t: "success", label: "원본 정상", icon: <ShieldCheckIcon />, title: "마지막 확인에서 원본에 접근할 수 있었어요" },
  GONE: { t: "danger", label: "원본 없음", icon: <CloudOffIcon />, title: "원본이 삭제되었거나 비공개로 바뀌었어요. 이 미러가 남은 사본일 수 있어요" },
  AUTH_REQUIRED: { t: "warning", label: "인증 필요", icon: <KeyRoundIcon />, title: "원본에 접근하려면 앱에서 토큰을 등록해야 해요" },
  UNKNOWN: { t: "neutral", label: "확인 전", icon: <CircleHelpIcon />, title: "아직 원본 상태를 확인하지 않았어요" },
}

export function OriginBadge({ status }: { status: OriginStatus | null }) {
  if (!status) return null
  const o = origin[status]
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
      <FileWarningIcon />
      LFS 누락
    </ToneBadge>
  )
}

export function ReadOnlyBadge() {
  return (
    <ToneBadge t="neutral" title="웹과 git 모두 읽기 전용이에요. push하려면 앱에서 로컬 레포로 분리하세요">
      <LockIcon />
      읽기 전용
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

/** A copyable one-line code field (clone URL, commands). */
export function CodeField({ value, label }: { value: string; label: string }) {
  return (
    // Wraps instead of scrolling: a clipped URL in the narrow sidebar looked complete but was not (QA-11).
    <div className="flex min-w-0 items-start gap-1 rounded-lg border bg-muted/50 pl-2.5">
      <code className="min-w-0 flex-1 py-1.5 font-mono text-xs leading-5 break-all select-all">{value}</code>
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

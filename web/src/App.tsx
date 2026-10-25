import { Component, useEffect, useRef, useState, type ReactNode } from "react"
import { AlertTriangleIcon, CloudOffIcon, MonitorIcon, MoonIcon, RotateCwIcon, SunIcon } from "lucide-react"
import { Alert, AlertAction, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"
import { ErrorState } from "@/components/common"
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty"
import { apiUrl, reloadAll, useApi, useStaleCount, type Info } from "@/lib/api"
import { useRoute } from "@/lib/router"
import { Commits } from "@/pages/Commits"
import { AddMirror } from "@/pages/AddMirror"
import { Archives } from "@/pages/Archives"
import { FileView } from "@/pages/FileView"
import { RepoList } from "@/pages/RepoList"
import { RepoTree } from "@/pages/RepoTree"

export default function App() {
  const { segments, query } = useRoute()
  const main = useRef<HTMLElement>(null)
  const pathKey = JSON.stringify(segments)
  const first = useRef(true)
  // WEB-05: tabs, history and bookmarks get a name per page; after a client-side move, focus goes to the new content.
  useEffect(() => {
    const [, repo, view, ...rest] = JSON.parse(pathKey) as string[]
    const path = rest.join("/")
    document.title = JSON.parse(pathKey)[0] === "add" ? "미러 추가 · mobilegit" : repo ? `${path ? `${path} · ` : ""}${{ commits: "커밋 · ", archives: "보존된 기록 · " }[view as string] ?? ""}${repo} · mobilegit` : "mobilegit"
    if (first.current) first.current = false
    else main.current?.focus({ preventScroll: true })
  }, [pathKey])
  return (
    <TooltipProvider delayDuration={300}>
      <div className="flex min-h-svh flex-col">
        <AppHeader />
        <main ref={main} tabIndex={-1} className="mx-auto w-full max-w-6xl flex-1 px-4 py-5 outline-none sm:px-6 sm:py-6">
          <StaleBanner />
          {/* Keyed by page: moving to another page clears a render error. */}
          <PageBoundary key={pathKey}>
            <Page segments={segments} query={query} />
          </PageBoundary>
        </main>
        <AppFooter />
      </div>
    </TooltipProvider>
  )
}

function Page({ segments, query }: { segments: string[]; query: URLSearchParams }) {
  const ref = query.get("ref")
  if (segments.length === 0) return <RepoList />
  if (segments.length === 1 && segments[0] === "add") return <AddMirror />
  const [r, repo, view, ...rest] = segments
  const path = rest.join("/")
  if (r === "r" && repo) {
    if (!view || view === "tree") return <RepoTree repo={repo} path={path} refParam={ref} />
    if (view === "blob" && path) return <FileView repo={repo} path={path} refParam={ref} line={Number(query.get("L")) || null} source={query.get("view") === "source"} />
    if (view === "archives" && !path) return <Archives repo={repo} />
    if (view === "commits") return <Commits repo={repo} path={path} refParam={ref} page={Math.max(1, Number(query.get("page")) || 1)} />
  }
  return <ErrorState error={{ code: "NOT_FOUND", message: "주소가 올바른지 확인하세요." }} />
}

/** R2WEB-02: a render error shows a message with a reload button instead of a blank page. */
class PageBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() {
    return { failed: true }
  }
  componentDidCatch(error: unknown) {
    console.error(error)
  }
  render() {
    if (!this.state.failed) return this.props.children
    return (
      <Empty className="border bg-card py-12">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <AlertTriangleIcon />
          </EmptyMedia>
          <EmptyTitle>화면을 그리지 못했어요</EmptyTitle>
          <EmptyDescription>새로 고치면 대부분 해결돼요. 계속되면 앱을 최신 버전으로 업데이트하세요.</EmptyDescription>
        </EmptyHeader>
        <EmptyContent>
          <Button onClick={() => location.reload()}>
            <RotateCwIcon />
            새로 고침
          </Button>
        </EmptyContent>
      </Empty>
    )
  }
}

/** WEB-03: a refresh failed but the older copy is still shown. */
function StaleBanner() {
  if (!useStaleCount()) return null
  return (
    <Alert className="mb-4">
      <CloudOffIcon />
      <AlertTitle>최신 내용을 받지 못했어요</AlertTitle>
      <AlertDescription>폰에서 새 내용을 받지 못해 전에 받은 내용을 보여 주고 있어요.</AlertDescription>
      <AlertAction>
        <Button variant="outline" size="sm" onClick={reloadAll}>
          <RotateCwIcon />
          다시 시도
        </Button>
      </AlertAction>
    </Alert>
  )
}

type Theme = "light" | "dark" | "system"

function readTheme(): Theme {
  try {
    const t = localStorage.getItem("theme")
    return t === "light" || t === "dark" ? t : "system"
  } catch {
    return "system"
  }
}

function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(readTheme)
  useEffect(() => {
    const mq = matchMedia("(prefers-color-scheme: dark)")
    const apply = () => document.documentElement.classList.toggle("dark", theme === "dark" || (theme === "system" && mq.matches))
    apply()
    try {
      if (theme === "system") localStorage.removeItem("theme")
      else localStorage.setItem("theme", theme)
    } catch {
      /* private mode: theme just isn't remembered */
    }
    mq.addEventListener("change", apply)
    return () => mq.removeEventListener("change", apply)
  }, [theme])
  const next: Record<Theme, Theme> = { system: "light", light: "dark", dark: "system" }
  const label = { system: "시스템 설정 따름", light: "밝게", dark: "어둡게" }[theme]
  // Icon plus the one word "테마" (round 1: the icon alone was not recognised; round 2: the full state was too long).
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button variant="ghost" onClick={() => setTheme(next[theme])} aria-label={`화면 테마: ${label}. 눌러서 바꾸기`}>
          {theme === "system" ? <MonitorIcon /> : theme === "light" ? <SunIcon /> : <MoonIcon />}
          <span className="hidden sm:inline">테마</span>
        </Button>
      </TooltipTrigger>
      <TooltipContent>지금: {label}. 누를 때마다 시스템 설정 따름 → 밝게 → 어둡게</TooltipContent>
    </Tooltip>
  )
}

function AppHeader() {
  return (
    <header className="sticky top-0 z-40 border-b bg-background/85 backdrop-blur supports-[backdrop-filter]:bg-background/70">
      <div className="mx-auto flex h-14 w-full max-w-6xl items-center gap-3 px-4 sm:px-6">
        <a href="/" className="flex items-center gap-2 font-semibold">
          <img src="/favicon.svg" alt="" className="size-7" />
          mobilegit
        </a>
        {/* "읽기 전용" alone read as if the whole server refused pushes (round 1 review); F-26 adds the one write (WM-04 AC8). */}
        <span className="hidden text-sm text-muted-foreground md:inline">폰 속 git 서버 · 웹에서는 보기와 미러 추가만 할 수 있어요</span>
        <div className="ml-auto">
          <ThemeToggle />
        </div>
      </div>
    </header>
  )
}

function AppFooter() {
  const { data: info } = useApi<Info>(apiUrl("info"))
  return (
    // The header already says the web is view-only, so the footer does not repeat it (round 2 review).
    <footer className="border-t px-4 py-4 text-center text-sm text-muted-foreground">
      mobilegit{info ? ` ${info.version}` : ""} · 미러 업데이트와 레포 관리는 폰 앱에서, push는 PC의 git으로 해요.
      {info && !info.authRequired && " · 비밀번호 요구: 안 함"}
      {/* The setting only applies on the LAN and Tailscale; loopback and ngrok always ask (SEC-03). */}
      {info?.anonymousRead && " · 같은 Wi-Fi·Tailscale에서는 로그인 없이 보기 허용"}
    </footer>
  )
}

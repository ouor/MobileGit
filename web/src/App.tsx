import { useEffect, useState } from "react"
import { MonitorIcon, MoonIcon, SunIcon } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"
import { ErrorState } from "@/components/common"
import { apiUrl, useApi, type Info } from "@/lib/api"
import { useRoute } from "@/lib/router"
import { Commits } from "@/pages/Commits"
import { FileView } from "@/pages/FileView"
import { RepoList } from "@/pages/RepoList"
import { RepoTree } from "@/pages/RepoTree"

export default function App() {
  const { segments, query } = useRoute()
  return (
    <TooltipProvider delayDuration={300}>
      <div className="flex min-h-svh flex-col">
        <AppHeader />
        <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-5 sm:px-6 sm:py-6">
          <Page segments={segments} query={query} />
        </main>
        <AppFooter />
      </div>
    </TooltipProvider>
  )
}

function Page({ segments, query }: { segments: string[]; query: URLSearchParams }) {
  const ref = query.get("ref")
  if (segments.length === 0) return <RepoList />
  const [r, repo, view, ...rest] = segments
  const path = rest.join("/")
  if (r === "r" && repo) {
    if (!view || view === "tree") return <RepoTree repo={repo} path={path} refParam={ref} />
    if (view === "blob" && path) return <FileView repo={repo} path={path} refParam={ref} line={Number(query.get("L")) || null} source={query.get("view") === "source"} />
    if (view === "commits") return <Commits repo={repo} path={path} refParam={ref} page={Math.max(1, Number(query.get("page")) || 1)} />
  }
  return <ErrorState error={{ code: "NOT_FOUND", message: "주소가 올바른지 확인하세요." }} />
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
  // Icon plus words: the icon alone was not recognised as a theme switch (round 1 review).
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button variant="ghost" onClick={() => setTheme(next[theme])} aria-label={`화면 테마: ${label}. 눌러서 바꾸기`}>
          {theme === "system" ? <MonitorIcon /> : theme === "light" ? <SunIcon /> : <MoonIcon />}
          <span className="hidden sm:inline">테마: {label}</span>
        </Button>
      </TooltipTrigger>
      <TooltipContent>누를 때마다 시스템 설정 따름 → 밝게 → 어둡게</TooltipContent>
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
        {/* "읽기 전용" alone read as if the whole server refused pushes (round 1 review). */}
        <span className="hidden text-sm text-muted-foreground md:inline">폰 속 git 서버 · 웹에서는 보기만 할 수 있어요</span>
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
    <footer className="border-t py-4 text-center text-xs text-muted-foreground">
      mobilegit{info ? ` ${info.version}` : ""} · 웹에서는 보기만 할 수 있어요. 미러 업데이트와 레포 관리는 폰 앱에서, push는 PC의 git으로 해요.
      {info && !info.authRequired && " · 비밀번호 요구 꺼짐"}
      {info?.anonymousRead && " · 익명 읽기 허용"}
    </footer>
  )
}

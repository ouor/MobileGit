import { useEffect, useRef, useState } from "react"
import { CheckCircle2Icon, CircleAlertIcon, LoaderCircleIcon, PlusIcon } from "lucide-react"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { MirrorIcon } from "@/components/common"
import { apiUrl, forget, postJson, useApi, type ApiError, type Info, type RepoSummary } from "@/lib/api"
import { mirrorErrorText, nameProblem, previewProblem, scopeLine, type MirrorPreview } from "@/lib/mirror"
import { navigate, repoHref } from "@/lib/router"

const DEBOUNCE_MS = 500

/**
 * W-06 (F-26, PRD 15 WM-04): the web's only write. Paste one link → it is checked → name → 추가 → the repo home,
 * which shows the first fetch (WM-05). No token field (D-14): private repos use the token stored in the phone app.
 */
export function AddMirror() {
  const { data: info } = useApi<Info>(apiUrl("info"))
  // "끄기": requests are anonymous, so the core does not use stored tokens (D-14).
  const anonymous = info?.authRequired === false
  const [text, setText] = useState("")
  const [preview, setPreview] = useState<MirrorPreview | null>(null)
  const [checking, setChecking] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [name, setName] = useState("")
  const [adding, setAdding] = useState(false)
  // A refused "추가", shown by the button and cleared once the name or link changes.
  const [addError, setAddError] = useState<ApiError | null>(null)
  const seq = useRef(0)

  const check = (value: string) => {
    const id = ++seq.current
    const url = value.trim()
    setPreview(null)
    setError(null)
    setAddError(null)
    if (!url) return setChecking(false)
    setChecking(true)
    postJson<MirrorPreview>("mirrors/preview", { url }).then(
      (p) => {
        if (id !== seq.current) return
        setPreview(p)
        setName(p.existingRepo ? "" : p.suggestedName)
        setChecking(false)
      },
      (e: ApiError) => {
        if (id !== seq.current) return
        setError(e)
        setChecking(false)
      },
    )
  }

  // WM-04 AC2: checks 0.5 s after the text stops changing.
  useEffect(() => {
    const t = setTimeout(() => check(text), DEBOUNCE_MS)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text])

  const problem = preview && previewProblem(preview, anonymous)
  const nameError = preview && !preview.existingRepo && !problem ? nameProblem(name) : null
  const canAdd = !!preview && !preview.existingRepo && !problem && !nameError && !adding && !checking

  const add = () => {
    if (!preview || !canAdd) return
    setAdding(true)
    setAddError(null)
    postJson<RepoSummary>("mirrors", { url: preview.url, name }).then(
      (r) => {
        forget(apiUrl("repos"))
        forget(apiUrl("overview"))
        navigate(repoHref.tree(r.name))
      },
      (e: ApiError) => {
        setAddError(e)
        setAdding(false)
      },
    )
  }

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-4">
      <div>
        <h1 className="text-xl font-semibold">미러 추가</h1>
        <p className="text-sm text-muted-foreground">원본 레포 주소를 붙여 넣으면 폰이 원본을 자동으로 따라가는 읽기 전용 미러로 보존해요.</p>
      </div>

      <Card>
        <CardContent className="flex flex-col gap-4">
          <label className="flex flex-col gap-1.5 text-sm font-medium">
            원본 레포 주소
            {/* A textarea, so a pasted list keeps its line breaks and the core can say "one at a time" (D-15). */}
            <Textarea
              value={text}
              onChange={(e) => setText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") e.preventDefault() // one link; the check runs by itself
              }}
              rows={2}
              placeholder="https://github.com/owner/repo"
              spellCheck={false}
              autoComplete="off"
              autoFocus
              className="min-h-0 font-mono text-sm font-normal"
              aria-describedby="add-hint"
            />
            <span id="add-hint" className="font-normal text-muted-foreground">
              https 주소 하나만 받아요. 브라우저 주소창의 레포 주소(…/tree/main 포함)도 돼요.
            </span>
          </label>

          {checking && (
            <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
              <LoaderCircleIcon className="size-4 animate-spin motion-reduce:animate-none" />
              원본을 확인하는 중이에요
            </p>
          )}

          {error && <ErrorBox error={error} anonymous={anonymous} />}

          {preview && <PreviewBox preview={preview} problem={problem} />}

          {preview && !preview.existingRepo && !problem && (
            <label className="flex flex-col gap-1.5 text-sm font-medium">
              레포 이름
              <Input
                value={name}
                onChange={(e) => {
                  setName(e.target.value.trim())
                  setAddError(null)
                }}
                aria-invalid={!!nameError}
                aria-describedby="name-hint"
                spellCheck={false}
                autoComplete="off"
                className="font-normal"
              />
              <span id="name-hint" className={nameError ? "font-normal text-destructive" : "font-normal text-muted-foreground"}>
                {nameError ?? "영문·숫자로 시작, 영문·숫자·. _ - 만 사용"}
              </span>
            </label>
          )}

          {addError && <ErrorBox error={addError} anonymous={anonymous} />}

          <div className="flex flex-wrap items-center gap-3">
            <Button onClick={add} disabled={!canAdd}>
              {adding ? <LoaderCircleIcon className="animate-spin motion-reduce:animate-none" /> : <PlusIcon />}
              {adding ? "추가하는 중" : "추가"}
            </Button>
            {/* WM-04 AC5: "push만" lets people browse without a password but adding asks for one. */}
            {info?.authRequired && info.anonymousRead && <span className="text-sm text-muted-foreground">추가하려면 비밀번호가 필요해요</span>}
          </div>
        </CardContent>
      </Card>

      <p className="text-sm text-muted-foreground">
        비공개 레포는 폰 앱의 설정 → 원본 접속 토큰에서 토큰을 등록한 뒤 추가하세요. 웹에서는 토큰을 받지 않아요. 여러 레포를 한꺼번에 추가하는 것도 폰 앱에서 할 수 있어요.
      </p>
    </div>
  )
}

function PreviewBox({ preview: p, problem }: { preview: MirrorPreview; problem: string | null }) {
  if (p.existingRepo)
    return (
      <Alert>
        <CheckCircle2Icon />
        <AlertTitle>이미 보존 중이에요</AlertTitle>
        <AlertDescription>
          <a href={repoHref.tree(p.existingRepo)} className="tap font-medium text-primary hover:underline">
            {p.existingRepo} 레포로 가기
          </a>
        </AlertDescription>
      </Alert>
    )
  if (problem)
    return (
      <Alert variant="destructive">
        <CircleAlertIcon />
        <AlertTitle>{problem}</AlertTitle>
        <AlertDescription>
          <code className="text-xs">{p.access === "FAILED" ? `PROBE_FAILED${p.failure ? ` · ${p.failure}` : ""}` : p.access}</code>
        </AlertDescription>
      </Alert>
    )
  return (
    <Card size="sm" className="bg-muted/40">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <MirrorIcon className="size-4 text-primary" />
          추가할 수 있어요
        </CardTitle>
        <CardDescription className="flex flex-col gap-1">
          <code className="font-mono text-sm break-all text-foreground">{p.url}</code>
          <span>{scopeLine(p)}</span>
          {p.defaultBranch && <span>기본 브랜치 {p.defaultBranch}</span>}
        </CardDescription>
      </CardHeader>
    </Card>
  )
}

function ErrorBox({ error: e, anonymous }: { error: ApiError; anonymous: boolean }) {
  return (
    <Alert variant="destructive" role="alert">
      <CircleAlertIcon />
      <AlertTitle>{mirrorErrorText(e, anonymous)}</AlertTitle>
      <AlertDescription className="flex flex-col gap-1">
        {e.code === "ALREADY_MIRRORED" && e.repo && (
          <a href={repoHref.tree(e.repo)} className="tap font-medium text-primary hover:underline">
            {e.repo} 레포로 가기
          </a>
        )}
        {/* The raw code, small, for support (WM-04 AC4). */}
        <code className="text-xs">{e.status === 401 ? "401" : e.code}</code>
      </AlertDescription>
    </Alert>
  )
}

import { useState } from "react"
import { ChevronDownIcon, GitBranchIcon, GitCommitHorizontalIcon, TagIcon } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from "@/components/ui/command"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import type { RepoDetail } from "@/lib/api"
import { isSha } from "@/lib/format"
import { navigate } from "@/lib/router"

// Big mirrors can have thousands of tags; cmdk would draw and score every one (WEB-08).
const LIMIT = 100

function filterRefs<T extends { name: string }>(refs: T[], q: string) {
  const needle = q.trim().toLowerCase()
  const hits = needle ? refs.filter((r) => r.name.toLowerCase().includes(needle)) : refs
  return { shown: hits.slice(0, LIMIT), more: Math.max(0, hits.length - LIMIT) }
}

/** Branch/tag selector (F-18 AC4). Archive refs are never listed: the API only returns heads and tags. */
export function RefPicker({ detail, current, hrefFor }: { detail: RepoDetail; current: string; hrefFor: (ref: string | null) => string }) {
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState("")
  const branches = filterRefs(detail.branches, q)
  const tags = filterRefs(detail.tags, q)
  const isTag = detail.tags.some((t) => t.name === current)
  const commit = !isTag && !detail.branches.some((b) => b.name === current) && isSha(current)
  const pick = (ref: string) => {
    setOpen(false)
    setQ("")
    navigate(hrefFor(ref === detail.head ? null : ref))
  }
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="outline" className="max-w-56 justify-start" aria-label={`브랜치/태그 선택, 현재 ${current}`}>
          {commit ? <GitCommitHorizontalIcon /> : isTag ? <TagIcon /> : <GitBranchIcon />}
          <span className="truncate font-mono text-xs">{commit ? current.slice(0, 7) : current}</span>
          <ChevronDownIcon className="ml-auto text-muted-foreground" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-72 p-0">
        {/* Filtering is ours (name only, capped), so "tag" doesn't match every tag through the item value. */}
        <Command shouldFilter={false}>
          <CommandInput placeholder="브랜치나 태그 찾기" value={q} onValueChange={setQ} />
          <CommandList className="max-h-80">
            {branches.shown.length + tags.shown.length === 0 && <CommandEmpty>일치하는 브랜치나 태그가 없어요</CommandEmpty>}
            <CommandGroup heading={`브랜치 ${detail.branches.length}`}>
              {branches.shown.map((b) => (
                <CommandItem key={b.name} value={`branch:${b.name}`} keywords={[b.name]} data-checked={b.name === current} onSelect={() => pick(b.name)}>
                  <GitBranchIcon className="text-muted-foreground" />
                  <span className="truncate">{b.name}</span>
                  {b.name === detail.head && (
                    <Badge variant="outline" className="ml-1">
                      기본
                    </Badge>
                  )}
                </CommandItem>
              ))}
            </CommandGroup>
            {branches.more > 0 && <MoreHint n={branches.more} />}
            {tags.shown.length > 0 && (
              <CommandGroup heading={`태그 ${detail.tags.length}`}>
                {tags.shown.map((t) => (
                  <CommandItem key={t.name} value={`tag:${t.name}`} keywords={[t.name]} data-checked={t.name === current} onSelect={() => pick(t.name)}>
                    <TagIcon className="text-muted-foreground" />
                    <span className="truncate">{t.name}</span>
                  </CommandItem>
                ))}
              </CommandGroup>
            )}
            {tags.more > 0 && <MoreHint n={tags.more} />}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}

function MoreHint({ n }: { n: number }) {
  return <p className="px-3 py-2 text-xs text-muted-foreground">{n.toLocaleString()}개 더 있어요. 이름으로 찾아 좁히세요</p>
}

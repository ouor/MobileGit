import { useState } from "react"
import { ChevronDownIcon, GitBranchIcon, GitCommitHorizontalIcon, TagIcon } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from "@/components/ui/command"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import type { RepoDetail } from "@/lib/api"
import { isSha } from "@/lib/format"
import { navigate } from "@/lib/router"

/** Branch/tag selector (F-18 AC4). Archive refs are never listed: the API only returns heads and tags. */
export function RefPicker({ detail, current, hrefFor }: { detail: RepoDetail; current: string; hrefFor: (ref: string | null) => string }) {
  const [open, setOpen] = useState(false)
  const isTag = detail.tags.some((t) => t.name === current)
  const commit = !isTag && !detail.branches.some((b) => b.name === current) && isSha(current)
  const pick = (ref: string) => {
    setOpen(false)
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
        <Command>
          <CommandInput placeholder="브랜치나 태그 찾기" />
          <CommandList className="max-h-80">
            <CommandEmpty>일치하는 브랜치나 태그가 없어요</CommandEmpty>
            <CommandGroup heading={`브랜치 ${detail.branches.length}`}>
              {detail.branches.map((b) => (
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
            {detail.tags.length > 0 && (
              <CommandGroup heading={`태그 ${detail.tags.length}`}>
                {detail.tags.map((t) => (
                  <CommandItem key={t.name} value={`tag:${t.name}`} keywords={[t.name]} data-checked={t.name === current} onSelect={() => pick(t.name)}>
                    <TagIcon className="text-muted-foreground" />
                    <span className="truncate">{t.name}</span>
                  </CommandItem>
                ))}
              </CommandGroup>
            )}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}

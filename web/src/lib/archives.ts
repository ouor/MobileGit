// Pure helpers for the 보존된 기록 page (W-05), kept out of the component so they can be tested (R2WEB-10).
import type { ArchiveEntry } from "./api.ts"

export interface ArchivesResponse {
  entries: ArchiveEntry[]
  /** More than the core's limit (500) exist; only the newest are listed (R2SEC-05). */
  truncated?: boolean
}

type Kind = { t: "warning" | "danger" | "neutral"; label: string; title: string }

// Same words as the app's change kinds (A-15, R2-04, change_*).
const kinds: Record<string, Kind> = {
  FORCED: { t: "warning", label: "히스토리 바뀜", title: "원본이 강제 push로 이 브랜치의 기록을 바꿨어요" },
  DELETED: { t: "danger", label: "브랜치 삭제됨", title: "원본에서 이 브랜치가 지워졌어요" },
  TAG_MOVED: { t: "warning", label: "태그 이동됨", title: "원본에서 이 태그가 다른 커밋으로 옮겨졌어요" },
  TAG_DELETED: { t: "danger", label: "태그 삭제됨", title: "원본에서 이 태그가 지워졌어요" },
  REVIEW: { t: "neutral", label: "PR 브랜치 바뀜", title: "원본의 PR 브랜치가 바뀌거나 지워졌어요" },
}
const unknownKind: Kind = { t: "neutral", label: "강제 push 또는 삭제", title: "어떤 변화였는지 기록이 없어요" }

/** Any value the web does not know yet (a newer core) falls back to the neutral label instead of breaking the page (R2WEB-02). */
export const kindInfo = (kind: string | null): Kind => (kind && Object.hasOwn(kinds, kind) ? kinds[kind] : unknownKind)

export interface ArchiveGroup {
  group: string
  at: number
  entries: ArchiveEntry[]
}

/** Entries come newest group first; runs of the same `group` form one section, in that order. */
export function groupArchives(entries: ArchiveEntry[]): ArchiveGroup[] {
  const groups: ArchiveGroup[] = []
  for (const e of entries) {
    if (groups.at(-1)?.group !== e.group) groups.push({ group: e.group, at: e.at, entries: [] })
    groups.at(-1)!.entries.push(e)
  }
  return groups
}

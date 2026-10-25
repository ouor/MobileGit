import { Fragment, type ReactNode } from "react"
import { Breadcrumb, BreadcrumbItem, BreadcrumbLink, BreadcrumbList, BreadcrumbPage, BreadcrumbSeparator } from "@/components/ui/breadcrumb"
import { RefPicker } from "@/components/RefPicker"
import type { RepoDetail } from "@/lib/api"
import { repoHref } from "@/lib/router"

/** Toolbar shared by tree, file and commit pages: ref selector + path breadcrumb + page actions. */
export function PathBar({
  detail,
  refName,
  path,
  hrefForRef,
  actions,
}: {
  detail: RepoDetail
  refName: string
  path: string
  hrefForRef: (ref: string | null) => string
  actions?: ReactNode
}) {
  const repo = detail.repo.name
  const parts = path.split("/").filter(Boolean)
  const ref = refName === detail.head ? null : refName
  return (
    <div className="flex flex-wrap items-center gap-2">
      <RefPicker detail={detail} current={refName} hrefFor={hrefForRef} />
      <Breadcrumb className="min-w-0 flex-1">
        <BreadcrumbList className="flex-nowrap overflow-x-auto font-medium sm:flex-wrap">
          <BreadcrumbItem>
            {parts.length ? (
              <BreadcrumbLink href={repoHref.tree(repo, "", ref)}>{repo}</BreadcrumbLink>
            ) : (
              <BreadcrumbPage>{repo}</BreadcrumbPage>
            )}
          </BreadcrumbItem>
          {parts.map((p, i) => (
            <Fragment key={i}>
              <BreadcrumbSeparator />
              <BreadcrumbItem className="whitespace-nowrap">
                {i === parts.length - 1 ? (
                  <BreadcrumbPage>{p}</BreadcrumbPage>
                ) : (
                  <BreadcrumbLink href={repoHref.tree(repo, parts.slice(0, i + 1).join("/"), ref)}>{p}</BreadcrumbLink>
                )}
              </BreadcrumbItem>
            </Fragment>
          ))}
        </BreadcrumbList>
      </Breadcrumb>
      {actions && <div className="flex items-center gap-1">{actions}</div>}
    </div>
  )
}

package com.ouor.mobilegit.core

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.AndTreeFilter
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.eclipse.jgit.treewalk.filter.TreeFilter
import java.io.OutputStream

// ---- Web JSON API DTOs (docs/core/spec.md "웹 JSON API") ----
@Serializable data class RefDto(val name: String, val sha: String)

@Serializable
data class RepoSummaryDto(
    val name: String, val type: RepoType, val description: String, val defaultBranch: String?,
    val lastActivityAt: Long?, val lastUpdateAt: Long?, val lastPushAt: Long?,
    val originUrl: String?, val originStatus: OriginStatus?, val hasLfs: Boolean, val sizeBytes: Long,
)

@Serializable
data class RepoDetailDto(
    val repo: RepoSummaryDto, val empty: Boolean, val head: String?, val branches: List<RefDto>, val tags: List<RefDto>,
    val archivedRefCount: Int, val submoduleUrls: List<String>, val readOnly: Boolean, val forkedFromUrl: String?,
    val cloneUrl: String? = null,
)

@Serializable
data class CommitDto(
    val sha: String, val shortSha: String, val summary: String, val message: String,
    val authorName: String, val authorEmail: String, val authoredAt: Long, val committedAt: Long, val parents: List<String>,
)

@Serializable data class TreeEntryDto(val name: String, val path: String, val type: String, val size: Long? = null, val mode: String)
@Serializable data class TreeDto(val ref: String, val commit: CommitDto, val path: String, val entries: List<TreeEntryDto>)
@Serializable data class LfsPointerDto(val oid: String, val size: Long)

@Serializable
data class BlobDto(
    val ref: String, val path: String, val size: Long, val binary: Boolean, val tooLarge: Boolean,
    val lfs: LfsPointerDto? = null, val text: String? = null,
)

@Serializable data class ReadmeDto(val ref: String, val path: String, val format: String, val raw: String, val html: String)
/** [truncated]: the scan limit ended this page early; older matching commits may exist (GIT-09). */
/**
 * R2-03 one preserved ref. [group]: the archive stamp shared by everything one update preserved (A-15 groups by it;
 * a [RefChange.archivedAs] in the history contains it). [ref]: the original ref (refs/heads/main), [name] its short form.
 * [kind]: FORCED, DELETED (branch), TAG_MOVED or TAG_DELETED; null for archives made before round 2 whose change is no
 * longer in the history ("강제 push 또는 삭제"). [summary]/[committedAt]: the preserved commit (null if not a commit).
 * [currentSha]: what the ref points to now in this repo (the origin as of the last update); null = "원본에 없음".
 */
@Serializable data class ArchiveEntryDto(
    val at: Long, val group: String, val kind: RefChangeKind?, val ref: String, val name: String, val archiveRef: String,
    val sha: String, val shortSha: String, val summary: String?, val committedAt: Long?, val currentSha: String?,
)
@Serializable data class ArchivesDto(val entries: List<ArchiveEntryDto>)

@Serializable data class CommitPageDto(val ref: String, val path: String, val page: Int, val perPage: Int, val hasMore: Boolean, val commits: List<CommitDto>, val truncated: Boolean = false)

class BrowseException(val status: Int, val code: String, message: String) : Exception(message)

/** Read-only views for the web UI (F-18, PRD 7.3). Works on a consistent snapshot; never takes repo locks. */
object RepoBrowser {
    const val MAX_TEXT_BYTES = 1_000_000L
    const val MAX_COMMIT_PAGE = 1_000
    const val MAX_SCANNED_COMMITS = 200_000

    fun summary(m: RepoMeta) = RepoSummaryDto(
        name = m.name, type = m.type, description = m.description, defaultBranch = m.defaultBranch,
        lastActivityAt = listOfNotNull(m.lastPushAt, m.mirror?.lastUpdateAt, m.createdAt).maxOrNull(),
        lastUpdateAt = m.mirror?.lastSuccessUpdateAt, lastPushAt = m.lastPushAt,
        originUrl = m.mirror?.remoteUrl ?: m.forkedFromUrl, originStatus = m.mirror?.originStatus,
        hasLfs = m.hasLfs, sizeBytes = m.sizeBytes,
    )

    fun detail(repo: Repository, m: RepoMeta): RepoDetailDto {
        val refs = repo.refDatabase.refs
        val head = repo.exactRef(Constants.HEAD)
        fun list(prefix: String) = refs.filter { it.name.startsWith(prefix) && it.objectId != null }
            .map { RefDto(it.name.removePrefix(prefix), it.objectId.name) }.sortedBy { it.name }
        return RepoDetailDto(
            repo = summary(m), empty = head?.objectId == null && refs.none { it.name.startsWith(Constants.R_HEADS) },
            head = head?.target?.name?.removePrefix(Constants.R_HEADS),
            branches = list(Constants.R_HEADS), tags = list(Constants.R_TAGS),
            archivedRefCount = refs.count { it.name.startsWith(RepoStore.ARCHIVE_PREFIX) },
            submoduleUrls = m.submoduleUrls, readOnly = m.type == RepoType.MIRROR, forkedFromUrl = m.forkedFromUrl,
        )
    }

    /** Resolves branch/tag short name, full ref name or commit SHA; null/blank = HEAD. */
    fun resolveCommit(repo: Repository, ref: String?): Pair<String, RevCommit> {
        val name = ref?.takeIf { it.isNotBlank() } ?: Constants.HEAD
        val id: ObjectId? = when {
            name == Constants.HEAD -> repo.exactRef(Constants.HEAD)?.objectId
            else -> repo.exactRef(Constants.R_HEADS + name)?.objectId
                ?: repo.exactRef(Constants.R_TAGS + name)?.let { repo.refDatabase.peel(it).peeledObjectId ?: it.objectId }
                ?: repo.exactRef(name)?.objectId
                ?: if (ObjectId.isId(name) || name.matches(Regex("[0-9a-fA-F]{7,39}"))) runCatching { repo.resolve("$name^{commit}") }.getOrNull() else null
        }
        if (id == null) {
            if (repo.refDatabase.refs.isEmpty()) throw BrowseException(404, "EMPTY_REPO", "아직 커밋이 없습니다")
            throw BrowseException(404, "REF_NOT_FOUND", "브랜치/태그 '$name'을(를) 찾을 수 없습니다")
        }
        val commit = RevWalk(repo).use { rw -> runCatching { rw.parseCommit(id) }.getOrNull() }
            ?: throw BrowseException(404, "REF_NOT_FOUND", "'$name'은(는) 커밋이 아닙니다")
        val display = if (name == Constants.HEAD) repo.exactRef(Constants.HEAD)?.target?.name?.removePrefix(Constants.R_HEADS) ?: name else name
        return display to commit
    }

    private fun normPath(p: String?): String {
        val s = (p ?: "").trim('/')
        if (s.split('/').any { it == ".." || it == "." }) throw BrowseException(400, "BAD_PATH", "잘못된 경로")
        return s
    }

    fun tree(repo: Repository, ref: String?, path: String?): TreeDto {
        val (refName, commit) = resolveCommit(repo, ref)
        val p = normPath(path)
        val tw: TreeWalk = if (p.isEmpty()) TreeWalk(repo).apply { addTree(commit.tree) } else {
            val at = TreeWalk.forPath(repo, p, commit.tree) ?: throw BrowseException(404, "PATH_NOT_FOUND", "경로 '$p'이(가) 없습니다")
            if (at.getFileMode(0) != FileMode.TREE) throw BrowseException(400, "NOT_A_DIRECTORY", "'$p'은(는) 디렉터리가 아닙니다")
            TreeWalk(repo).apply { addTree(at.getObjectId(0)); at.close() }
        }
        val entries = mutableListOf<TreeEntryDto>()
        tw.use {
            it.isRecursive = false
            val reader = it.objectReader
            while (it.next()) {
                val mode = it.getFileMode(0)
                val type = when {
                    mode == FileMode.TREE -> "dir"
                    mode == FileMode.GITLINK -> "submodule"
                    mode == FileMode.SYMLINK -> "symlink"
                    else -> "file"
                }
                val size = if (type == "file" || type == "symlink") runCatching { reader.getObjectSize(it.getObjectId(0), Constants.OBJ_BLOB) }.getOrNull() else null
                entries += TreeEntryDto(it.nameString, if (p.isEmpty()) it.nameString else "$p/${it.nameString}", type, size, mode.toString())
            }
        }
        entries.sortWith(compareBy({ it.type != "dir" }, { it.name.lowercase() }))
        return TreeDto(refName, commitDto(commit), p, entries)
    }

    private fun blobId(repo: Repository, commit: RevCommit, p: String): ObjectId {
        val tw = TreeWalk.forPath(repo, p, commit.tree) ?: throw BrowseException(404, "PATH_NOT_FOUND", "파일 '$p'이(가) 없습니다")
        tw.use {
            if (it.getFileMode(0) == FileMode.TREE || it.getFileMode(0) == FileMode.GITLINK) throw BrowseException(400, "NOT_A_FILE", "'$p'은(는) 파일이 아닙니다")
            return it.getObjectId(0)
        }
    }

    fun blob(repo: Repository, ref: String?, path: String?): BlobDto {
        val (refName, commit) = resolveCommit(repo, ref)
        val p = normPath(path).ifEmpty { throw BrowseException(400, "BAD_PATH", "경로가 필요합니다") }
        val loader = repo.open(blobId(repo, commit, p))
        val size = loader.size
        if (size > MAX_TEXT_BYTES) return BlobDto(refName, p, size, binary = false, tooLarge = true)
        val bytes = loader.getCachedBytes(MAX_TEXT_BYTES.toInt())
        parseLfsPointer(bytes)?.let { return BlobDto(refName, p, size, binary = false, tooLarge = false, lfs = it) }
        if (RawText.isBinary(bytes)) return BlobDto(refName, p, size, binary = true, tooLarge = false)
        return BlobDto(refName, p, size, binary = false, tooLarge = false, text = String(bytes, Charsets.UTF_8))
    }

    /** Streams the raw blob (served with Content-Disposition: attachment). Returns the file name. */
    fun raw(repo: Repository, ref: String?, path: String?, out: () -> OutputStream): String {
        val (_, commit) = resolveCommit(repo, ref)
        val p = normPath(path).ifEmpty { throw BrowseException(400, "BAD_PATH", "경로가 필요합니다") }
        val loader = repo.open(blobId(repo, commit, p))
        out().use { loader.copyTo(it) }
        return p.substringAfterLast('/')
    }

    fun rawSize(repo: Repository, ref: String?, path: String?): Long = repo.open(rawBlob(repo, ref, path)).size

    /** GIT-14: the blob a raw request refers to, resolved once (the ref may move during the download). */
    fun rawBlob(repo: Repository, ref: String?, path: String?): ObjectId {
        val (_, commit) = resolveCommit(repo, ref)
        val p = normPath(path).ifEmpty { throw BrowseException(400, "BAD_PATH", "경로가 필요합니다") }
        return blobId(repo, commit, p)
    }

    /** F-24 AC3 */
    fun parseLfsPointer(bytes: ByteArray): LfsPointerDto? {
        if (bytes.size > 1024) return null
        val text = String(bytes, Charsets.UTF_8)
        if (!text.startsWith("version https://git-lfs.github.com/spec/v1")) return null
        val oid = Regex("""oid sha256:([0-9a-f]{64})""").find(text)?.groupValues?.get(1) ?: return null
        val size = Regex("""size (\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return LfsPointerDto(oid, size)
    }

    /** F-18 AC5: README.md (rendered, sanitized) → README / README.txt (plain). Null when none. */
    fun readme(repo: Repository, ref: String?, dir: String? = null): ReadmeDto? {
        val tree = try { tree(repo, ref, dir) } catch (e: BrowseException) {
            if (e.code == "NOT_A_DIRECTORY") return markdownFile(repo, ref, dir) else throw e
        }
        val files = tree.entries.filter { it.type == "file" }
        val md = files.firstOrNull { it.name.equals("README.md", true) || it.name.equals("README.markdown", true) }
        val txt = files.firstOrNull { it.name.equals("README", true) || it.name.equals("README.txt", true) }
        val pick = md ?: txt ?: return null
        val blob = blob(repo, tree.ref, pick.path)
        val raw = blob.text ?: return null
        val html = if (pick === md) renderMarkdown(raw) else "<pre>" + escapeHtml(raw) + "</pre>"
        return ReadmeDto(tree.ref, pick.path, if (pick === md) "markdown" else "text", raw, html)
    }

    /** WR-5: one Markdown file rendered like a README. 400 NOT_MARKDOWN for other files, 400 TOO_LARGE over the blob limit. */
    fun markdownFile(repo: Repository, ref: String?, path: String?): ReadmeDto {
        val b = blob(repo, ref, path)
        val ext = b.path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (ext != "md" && ext != "markdown" || b.binary || b.lfs != null) throw BrowseException(400, "NOT_MARKDOWN", "Markdown 파일이 아닙니다")
        val raw = b.text ?: throw BrowseException(400, "TOO_LARGE", "파일이 너무 커서 렌더링할 수 없습니다")
        return ReadmeDto(b.ref, b.path, "markdown", raw, renderMarkdown(raw))
    }

    private val mdParser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create())).build()
    private val mdRenderer = HtmlRenderer.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create()))
        .escapeHtml(false).sanitizeUrls(true).build()

    /**
     * QA-04: GitHub-like safe subset of raw HTML in Markdown (headings with align, img, picture, details, tables, ...).
     * jsoup's allowlist drops scripts, event handlers, style, iframes/forms and non-http(s)/mailto URLs; relative
     * links stay as written so the web UI can resolve them against the file's path. No id/class except code language.
     */
    private val htmlSafelist: Safelist = Safelist.relaxed()
        .addTags("details", "summary", "picture", "kbd", "samp", "var", "ins", "del", "s", "hr", "abbr", "mark")
        .addAttributes(":all", "align", "title")
        .addAttributes("img", "width", "height", "alt")
        .addAttributes("td", "align", "valign", "colspan", "rowspan").addAttributes("th", "align", "valign", "colspan", "rowspan")
        .addAttributes("details", "open").addAttributes("abbr", "title")
        .addAttributes("code", "class")
        .addProtocols("a", "href", "#")
        .preserveRelativeLinks(true)

    private val cleanOutput = org.jsoup.nodes.Document.OutputSettings().prettyPrint(false)

    /** Markdown → HTML, then the HTML (including raw HTML blocks) is cleaned with [htmlSafelist] (NFR-보안). */
    fun renderMarkdown(md: String): String {
        val doc = Jsoup.parseBodyFragment(Jsoup.clean(mdRenderer.render(mdParser.parse(md)), "https://repo.invalid/", htmlSafelist, cleanOutput))
        // class is only for code-block languages; anything else could borrow the web UI's own CSS classes
        doc.select("code[class]").forEach { if (!codeClass.matches(it.className())) it.removeAttr("class") }
        return doc.outputSettings(cleanOutput).body().html()
    }

    private val codeClass = Regex("language-[A-Za-z0-9_+#.-]{1,40}")

    fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    /**
     * F-18 W-04: 50 per page; [path] limits to that file/dir's history.
     * GIT-09 / SEC-11: page ≤ [MAX_COMMIT_PAGE] (else 400 PAGE_TOO_DEEP); bodies are parsed only for the listed
     * commits; at most [MAX_SCANNED_COMMITS] commits are examined per request. When that limit is hit the page ends
     * there with `truncated = true` (path history of a very large repo).
     */
    fun commits(repo: Repository, ref: String?, path: String?, page: Int, perPage: Int = 50): CommitPageDto {
        if (page > MAX_COMMIT_PAGE) throw BrowseException(400, "PAGE_TOO_DEEP", "커밋 목록은 ${MAX_COMMIT_PAGE}쪽까지 볼 수 있습니다")
        val (refName, commit) = resolveCommit(repo, ref)
        val p = normPath(path)
        val pg = page.coerceAtLeast(1); val per = perPage.coerceIn(1, 100)
        val skip = (pg - 1).toLong() * per
        val list = mutableListOf<CommitDto>()
        var hasMore = false
        var scanned = 0
        var truncated = false
        RevWalk(repo).use { rw ->
            rw.isRetainBody = false
            rw.revFilter = object : org.eclipse.jgit.revwalk.filter.RevFilter() {
                override fun include(walker: RevWalk, c: RevCommit): Boolean {
                    if (++scanned > MAX_SCANNED_COMMITS) { truncated = true; throw org.eclipse.jgit.errors.StopWalkException.INSTANCE }
                    return true
                }
                override fun clone() = this
            }
            if (p.isNotEmpty()) rw.treeFilter = AndTreeFilter.create(PathFilterGroup.createFromStrings(p), TreeFilter.ANY_DIFF)
            rw.markStart(rw.parseCommit(commit))
            var i = 0L
            for (c in rw) {
                if (i >= skip) { if (list.size == per) { hasMore = true; break }; rw.parseBody(c); list += commitDto(c) }
                i++
            }
        }
        return CommitPageDto(refName, p, pg, per, hasMore, list, truncated && !hasMore)
    }

    /** R2-03 AC4: every `refs/archive/<stamp>/<ref>`, newest group first. [notes] from [RepoStore.archiveNotes]. */
    fun archives(repo: Repository, notes: Map<String, RefChange>): ArchivesDto = RevWalk(repo).use { rw ->
        val list = repo.refDatabase.getRefsByPrefix(RepoStore.ARCHIVE_PREFIX).mapNotNull { r ->
            val rest = r.name.removePrefix(RepoStore.ARCHIVE_PREFIX)
            val group = rest.substringBefore('/')
            val original = "refs/" + rest.substringAfter('/', "").ifEmpty { return@mapNotNull null }
            val id = r.objectId ?: return@mapNotNull null
            val commit = runCatching { rw.peel(rw.parseAny(id)) as? RevCommit }.getOrNull()?.also { rw.parseBody(it) }
            ArchiveEntryDto(
                at = archiveTime(group) ?: 0L, group = group, kind = notes[r.name]?.kind, ref = original,
                name = original.removePrefix(Constants.R_HEADS).removePrefix(Constants.R_TAGS), archiveRef = r.name,
                sha = id.name, shortSha = id.name.take(7), summary = commit?.shortMessage,
                committedAt = commit?.committerIdent?.whenAsInstant?.toEpochMilli(), currentSha = repo.exactRef(original)?.objectId?.name,
            )
        }
        ArchivesDto(list.sortedWith(compareByDescending<ArchiveEntryDto> { it.at }.thenByDescending { it.group.length }.thenByDescending { it.group }.thenBy { it.ref }))
    }

    /** `20261025T101010Z` or `20261025T101010Z-2` (GIT-12) → epoch ms. */
    fun archiveTime(group: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC"); isLenient = false }
            .parse(group.substringBefore('-'))!!.time
    }.getOrNull()

    fun commitDto(c: RevCommit) = CommitDto(
        sha = c.name, shortSha = c.name.take(7), summary = c.shortMessage, message = c.fullMessage,
        authorName = c.authorIdent.name, authorEmail = c.authorIdent.emailAddress,
        authoredAt = c.authorIdent.whenAsInstant.toEpochMilli(), committedAt = c.committerIdent.whenAsInstant.toEpochMilli(),
        parents = c.parents.map { it.name },
    )
}

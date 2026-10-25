package com.ouor.mobilegit.core

import kotlinx.serialization.Serializable
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
@Serializable data class CommitPageDto(val ref: String, val path: String, val page: Int, val perPage: Int, val hasMore: Boolean, val commits: List<CommitDto>)

class BrowseException(val status: Int, val code: String, message: String) : Exception(message)

/** Read-only views for the web UI (F-18, PRD 7.3). Works on a consistent snapshot; never takes repo locks. */
object RepoBrowser {
    const val MAX_TEXT_BYTES = 1_000_000L

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

    fun rawSize(repo: Repository, ref: String?, path: String?): Long {
        val (_, commit) = resolveCommit(repo, ref)
        return repo.open(blobId(repo, commit, normPath(path))).size
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
        .escapeHtml(true).sanitizeUrls(true).build()

    /** Markdown → HTML with raw HTML escaped and javascript:/data: URLs neutralized (NFR-보안). */
    fun renderMarkdown(md: String): String = mdRenderer.render(mdParser.parse(md))

    fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    /** F-18 W-04: 50 per page; [path] limits to that file/dir's history. */
    fun commits(repo: Repository, ref: String?, path: String?, page: Int, perPage: Int = 50): CommitPageDto {
        val (refName, commit) = resolveCommit(repo, ref)
        val p = normPath(path)
        val pg = page.coerceAtLeast(1); val per = perPage.coerceIn(1, 100)
        val list = mutableListOf<CommitDto>()
        var hasMore = false
        RevWalk(repo).use { rw ->
            if (p.isNotEmpty()) rw.treeFilter = AndTreeFilter.create(PathFilterGroup.createFromStrings(p), TreeFilter.ANY_DIFF)
            rw.markStart(rw.parseCommit(commit))
            var i = 0
            for (c in rw) {
                if (i >= (pg - 1) * per) { if (list.size == per) { hasMore = true; break }; list += commitDto(c) }
                i++
            }
        }
        return CommitPageDto(refName, p, pg, per, hasMore, list)
    }

    fun commitDto(c: RevCommit) = CommitDto(
        sha = c.name, shortSha = c.name.take(7), summary = c.shortMessage, message = c.fullMessage,
        authorName = c.authorIdent.name, authorEmail = c.authorIdent.emailAddress,
        authoredAt = c.authorIdent.whenAsInstant.toEpochMilli(), committedAt = c.committerIdent.whenAsInstant.toEpochMilli(),
        parents = c.parents.map { it.name },
    )
}

package com.ouor.mobilegit.core

import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.lib.BatchingProgressMonitor
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.URIish
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException

class SyncFailure(val category: ErrorCategory, val detail: String, cause: Throwable? = null) : Exception(detail, cause)

data class SyncProgress(val task: String, val done: Int, val total: Int) {
    val fraction: Float? get() = if (total > 0) done.toFloat() / total else null
}

data class CheckOutcome(val changed: Int, val remoteHead: String?)

data class UpdateOutcome(
    val refChanges: List<RefChange>,
    val newCommitCount: Int,
    val archivedRefCount: Int,
)

sealed interface ProbeResult {
    /** [pullRefCount]: review refs (refs/pull/..., refs/merge-requests/...) among [refCount] (CR-2, shown as "PR ref N개 제외"). */
    data class Reachable(val refCount: Int, val defaultBranch: String?, val pullRefCount: Int = 0) : ProbeResult
    data object AuthRequired : ProbeResult
    data object NotFound : ProbeResult
    data class Failed(val category: ErrorCategory, val detail: String) : ProbeResult
}

/**
 * Talks to remote git hosts with JGit (smart HTTP). Implements F-06 probe, F-10 check (ls-remote level) and
 * F-09/F-11 archive-preserving update:
 *
 * 1. list remote refs, 2. download only the missing objects (FetchConnection, no ref writes yet),
 * 3. compute which local refs are deleted or rewritten non-fast-forward and copy their old values to
 *    `refs/archive/<UTC>/<ref>`, 4. apply archive refs + mirror refs in ONE atomic BatchRefUpdate.
 * A crash before step 4 leaves the refs untouched (unreferenced objects only), so the repo is never half-updated.
 */
class MirrorEngine(private val clock: Clock = SystemClock, private val timeoutSeconds: Int = 120) {

    fun probe(url: String, creds: CredentialsProvider?): ProbeResult = try {
        val refs = lsRemote(url, creds)
        ProbeResult.Reachable(refs.size, refs[Constants.HEAD]?.takeIf { it.isSymbolic }?.target?.name?.removePrefix(Constants.R_HEADS),
            refs.keys.count(::isReviewRef))
    } catch (e: Exception) {
        val f = classify(e)
        when (f.category) {
            ErrorCategory.AUTH_REQUIRED -> ProbeResult.AuthRequired
            ErrorCategory.ORIGIN_GONE -> ProbeResult.NotFound
            else -> ProbeResult.Failed(f.category, f.detail)
        }
    }

    /** F-10 check: compares remote refs with local mirror refs without downloading objects. */
    fun check(repo: Repository, url: String, creds: CredentialsProvider?, includePullRefs: Boolean = true): CheckOutcome = wrap {
        val remote = mirrorable(lsRemote(url, creds), includePullRefs)
        val local = localMirrorRefs(repo)
        val changed = (remote.keys + local.keys).count { remote[it] != local[it] }
        CheckOutcome(changed, remote.headTarget)
    }

    fun update(
        repo: Repository, url: String, creds: CredentialsProvider?,
        onProgress: (SyncProgress) -> Unit = {}, isCancelled: () -> Boolean = { false },
        /** CR-2: false skips review refs; ones already mirrored are then deleted (not archived, see [shouldArchive]). */
        includePullRefs: Boolean = true,
    ): UpdateOutcome = wrap {
        val monitor = Monitor(onProgress, isCancelled)
        Transport.open(repo, URIish(url)).use { tn ->
            tn.credentialsProvider = creds
            tn.timeout = timeoutSeconds
            tn.openFetch().use { conn ->
                val all = conn.refsMap
                val remoteHead = all[Constants.HEAD]?.takeIf { it.isSymbolic }?.target?.name
                val remoteRefs = all.filterKeys { isMirrorable(it) && (includePullRefs || !isReviewRef(it)) }
                val want = remoteRefs.values.filter { r -> r.objectId != null && !repo.objectDatabase.has(r.objectId) }
                if (want.isNotEmpty()) {
                    val have = repo.refDatabase.refs.mapNotNull { it.objectId }.toSet()
                    conn.fetch(monitor, want, have)
                }
                try {
                    return@wrap applyRefs(repo, remoteRefs.mapValues { it.value.objectId }, remoteHead)
                } finally {
                    conn.packLocks.forEach { it.unlock() }
                }
            }
        }
    }

    private fun applyRefs(repo: Repository, remote: Map<String, ObjectId>, remoteHead: String?): UpdateOutcome {
        val local = localMirrorRefs(repo)
        // GIT-12: two updates in the same second must not collide on the archive ref name (LOCK_FAILURE)
        val base = archiveStamp(clock.now())
        val stamp = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }
            .first { repo.refDatabase.getRefsByPrefix("${RepoStore.ARCHIVE_PREFIX}$it/").isEmpty() }
        val batch = repo.refDatabase.newBatchUpdate().setAtomic(true).setAllowNonFastForwards(true)
        val changes = mutableListOf<RefChange>()
        RevWalk(repo).use { rw ->
            rw.isRetainBody = false // GIT-02: FF checks only need the commit graph
            for (name in (local.keys + remote.keys).sorted()) {
                val old = local[name]
                val new = remote[name]
                if (old == new) continue
                var archivedAs: String? = null
                if (old != null && shouldArchive(name) && (new == null || !isFastForward(rw, old, new, name))) {
                    archivedAs = "${RepoStore.ARCHIVE_PREFIX}$stamp/${name.removePrefix("refs/")}"
                    batch.addCommand(ReceiveCommand(ObjectId.zeroId(), old, archivedAs))
                }
                batch.addCommand(ReceiveCommand(old ?: ObjectId.zeroId(), new ?: ObjectId.zeroId(), name))
                changes += RefChange(name, old?.name, new?.name, archivedAs)
            }
            if (batch.commands.isNotEmpty()) {
                batch.execute(rw, org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE)
                val failed = batch.commands.filter { it.result != ReceiveCommand.Result.OK }
                if (failed.isNotEmpty()) throw IOException("ref update failed: " + failed.joinToString { "${it.refName}=${it.result}" })
            }
        }
        if (remoteHead != null && remote.containsKey(remoteHead)) {
            val cur = repo.exactRef(Constants.HEAD)?.target?.name
            if (cur != remoteHead) repo.updateRef(Constants.HEAD).link(remoteHead)
        }
        val newCommits = runCatching { countNewCommits(repo, local.filterKeys { !isReviewRef(it) }.values, remote.filterKeys { !isReviewRef(it) }.values) }.getOrDefault(0) // R2GIT-06: refs are already applied
        return UpdateOutcome(changes, newCommits, changes.count { it.archivedAs != null })
    }

    private fun isFastForward(rw: RevWalk, old: ObjectId, new: ObjectId, name: String): Boolean {
        if (name.startsWith(Constants.R_TAGS)) return false // a moved tag is always a rewrite
        return try {
            val o = rw.parseAny(old); val n = rw.parseAny(new)
            o is RevCommit && n is RevCommit && rw.isMergedInto(o, n)
        } catch (_: IOException) { false } finally { rw.reset() }
    }

    /**
     * GIT-02: bodies are not kept and the walk stops at [MAX_COUNTED_COMMITS] (shown as "N+" by the UI), so the first
     * fetch of a huge history does not hold every commit in memory. Review refs are not counted.
     */
    private fun countNewCommits(repo: Repository, oldTips: Collection<ObjectId>, newTips: Collection<ObjectId>): Int =
        RevWalk(repo).use { rw ->
            rw.isRetainBody = false
            fun peel(id: ObjectId) = runCatching { rw.peel(rw.parseAny(id)) as? RevCommit }.getOrNull()
            newTips.mapNotNull(::peel).forEach(rw::markStart)
            oldTips.mapNotNull(::peel).forEach(rw::markUninteresting)
            var n = 0
            while (n < MAX_COUNTED_COMMITS && rw.next() != null) n++
            n
        }

    private fun lsRemote(url: String, creds: CredentialsProvider?): Map<String, Ref> {
        // A throwaway in-memory repo: ls-remote does not need a local repository.
        val dummy = org.eclipse.jgit.internal.storage.dfs.InMemoryRepository(org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription("probe"))
        return Transport.open(dummy, URIish(url)).use { tn ->
            tn.credentialsProvider = creds
            tn.timeout = timeoutSeconds
            tn.openFetch().use { it.refsMap }
        }
    }

    private class RemoteRefs(map: Map<String, ObjectId>, val headTarget: String?) : Map<String, ObjectId> by map

    private fun mirrorable(refs: Map<String, Ref>, includePullRefs: Boolean) = RemoteRefs(
        refs.filter { isMirrorable(it.key) && (includePullRefs || !isReviewRef(it.key)) && it.value.objectId != null }.mapValues { it.value.objectId },
        refs[Constants.HEAD]?.takeIf { it.isSymbolic }?.target?.name,
    )

    fun localMirrorRefs(repo: Repository): Map<String, ObjectId> =
        repo.refDatabase.refs.filter { isMirrorable(it.name) && !it.isSymbolic && it.objectId != null }.associate { it.name to it.objectId }

    private fun <T> wrap(block: () -> T): T = try { block() } catch (e: SyncFailure) { throw e } catch (e: Exception) { throw classify(e) }

    private class Monitor(val cb: (SyncProgress) -> Unit, val cancelled: () -> Boolean) : BatchingProgressMonitor() {
        override fun onUpdate(taskName: String, workCurr: Int, duration: java.time.Duration) = cb(SyncProgress(taskName, workCurr, 0))
        override fun onEndTask(taskName: String, workCurr: Int, duration: java.time.Duration) = cb(SyncProgress(taskName, workCurr, 0))
        override fun onUpdate(taskName: String, workCurr: Int, workTotal: Int, percentDone: Int, duration: java.time.Duration) = cb(SyncProgress(taskName, workCurr, workTotal))
        override fun onEndTask(taskName: String, workCurr: Int, workTotal: Int, percentDone: Int, duration: java.time.Duration) = cb(SyncProgress(taskName, workCurr, workTotal))
        override fun isCancelled() = cancelled()
    }

    companion object {
        /** Upper bound of [UpdateOutcome.newCommitCount]; the real number may be larger. */
        const val MAX_COUNTED_COMMITS = 10_000

        /** Everything the remote advertises except HEAD (handled as a symref) and our own archive namespace. */
        fun isMirrorable(name: String) = name.startsWith("refs/") && !name.startsWith(RepoStore.ARCHIVE_PREFIX) && !name.endsWith("^{}")

        /** Host-managed review refs move constantly (synthetic merges); they are mirrored but not archived. */
        fun shouldArchive(name: String) = !isReviewRef(name)

        fun isReviewRef(name: String) = name.startsWith("refs/pull/") || name.startsWith("refs/merge-requests/")

        fun archiveStamp(now: Long): String = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))

        /** F-12 AC3 failure classification. Messages are redacted; never contain tokens. */
        fun classify(e: Throwable): SyncFailure {
            if (e is SyncFailure) return e
            val chain = generateSequence(e) { it.cause }.toList()
            val msg = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }
            val lower = msg.lowercase()
            // QA2-01: decide by exception type and by JGit's own HTTP-status messages, never by free text from the
            // network stack (Android's HTTP client reports a dropped connection as "unexpected end of stream … not found").
            // TransportException messages are "<uri>: <text>"; JGit's text for an unexpected status is "<code> <reason>".
            val transport = chain.filterIsInstance<TransportException>().mapNotNull { t -> t.message?.let { it.substringAfter(": ", it).lowercase() } }
            fun status(re: String) = Regex(re).let { r -> transport.any { r.containsMatchIn(it) } }
            val cat = when {
                chain.any { it is SocketTimeoutException } -> ErrorCategory.TIMEOUT
                chain.any { it is UnknownHostException || it is java.net.SocketException || it is java.io.EOFException || it is SSLException } ||
                    "unexpected end of stream" in lower || "connection reset" in lower -> ErrorCategory.NETWORK
                chain.any { it is NoRemoteRepositoryException } || status("""^404\b""") -> ErrorCategory.ORIGIN_GONE
                // JGitText: notAuthorized, noCredentialsProvider, authenticationNotSupported, serviceNotPermitted (403)
                status("""^40[13]\b""") || transport.any { "not authorized" in it || "authentication is required" in it ||
                    "authentication not supported" in it || " not permitted on " in it } -> ErrorCategory.AUTH_REQUIRED
                "timed out" in lower || "timeout" in lower -> ErrorCategory.TIMEOUT
                "no space left" in lower || "enospc" in lower -> ErrorCategory.STORAGE_FULL
                "cannot open git-upload-pack" in lower -> ErrorCategory.NETWORK
                else -> ErrorCategory.REMOTE_ERROR
            }
            return SyncFailure(cat, Redact.text(msg) ?: "", e)
        }
    }
}

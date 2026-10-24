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
import java.net.ConnectException
import java.net.NoRouteToHostException
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
    data class Reachable(val refCount: Int, val defaultBranch: String?) : ProbeResult
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
        ProbeResult.Reachable(refs.size, refs[Constants.HEAD]?.takeIf { it.isSymbolic }?.target?.name?.removePrefix(Constants.R_HEADS))
    } catch (e: Exception) {
        val f = classify(e)
        when (f.category) {
            ErrorCategory.AUTH_REQUIRED -> ProbeResult.AuthRequired
            ErrorCategory.ORIGIN_GONE -> ProbeResult.NotFound
            else -> ProbeResult.Failed(f.category, f.detail)
        }
    }

    /** F-10 check: compares remote refs with local mirror refs without downloading objects. */
    fun check(repo: Repository, url: String, creds: CredentialsProvider?): CheckOutcome = wrap {
        val remote = mirrorable(lsRemote(url, creds))
        val local = localMirrorRefs(repo)
        val changed = (remote.keys + local.keys).count { remote[it] != local[it] }
        CheckOutcome(changed, remote.headTarget)
    }

    fun update(
        repo: Repository, url: String, creds: CredentialsProvider?,
        onProgress: (SyncProgress) -> Unit = {}, isCancelled: () -> Boolean = { false },
    ): UpdateOutcome = wrap {
        val monitor = Monitor(onProgress, isCancelled)
        Transport.open(repo, URIish(url)).use { tn ->
            tn.credentialsProvider = creds
            tn.timeout = timeoutSeconds
            tn.openFetch().use { conn ->
                val all = conn.refsMap
                val remoteHead = all[Constants.HEAD]?.takeIf { it.isSymbolic }?.target?.name
                val remoteRefs = all.filterKeys { isMirrorable(it) }
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
        val stamp = archiveStamp(clock.now())
        val batch = repo.refDatabase.newBatchUpdate().setAtomic(true).setAllowNonFastForwards(true)
        val changes = mutableListOf<RefChange>()
        RevWalk(repo).use { rw ->
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
        val newCommits = countNewCommits(repo, local.values, remote.values)
        return UpdateOutcome(changes, newCommits, changes.count { it.archivedAs != null })
    }

    private fun isFastForward(rw: RevWalk, old: ObjectId, new: ObjectId, name: String): Boolean {
        if (name.startsWith(Constants.R_TAGS)) return false // a moved tag is always a rewrite
        return try {
            val o = rw.parseAny(old); val n = rw.parseAny(new)
            o is RevCommit && n is RevCommit && rw.isMergedInto(o, n)
        } catch (_: IOException) { false } finally { rw.reset() }
    }

    private fun countNewCommits(repo: Repository, oldTips: Collection<ObjectId>, newTips: Collection<ObjectId>): Int =
        RevWalk(repo).use { rw ->
            fun peel(id: ObjectId) = runCatching { rw.peel(rw.parseAny(id)) as? RevCommit }.getOrNull()
            newTips.mapNotNull(::peel).forEach(rw::markStart)
            oldTips.mapNotNull(::peel).forEach(rw::markUninteresting)
            rw.count()
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

    private fun mirrorable(refs: Map<String, Ref>) = RemoteRefs(
        refs.filter { isMirrorable(it.key) && it.value.objectId != null }.mapValues { it.value.objectId },
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
        /** Everything the remote advertises except HEAD (handled as a symref) and our own archive namespace. */
        fun isMirrorable(name: String) = name.startsWith("refs/") && !name.startsWith(RepoStore.ARCHIVE_PREFIX) && !name.endsWith("^{}")

        /** Host-managed review refs move constantly (synthetic merges); they are mirrored but not archived. */
        fun shouldArchive(name: String) = !name.startsWith("refs/pull/") && !name.startsWith("refs/merge-requests/")

        fun archiveStamp(now: Long): String = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))

        /** F-12 AC3 failure classification. Messages are redacted; never contain tokens. */
        fun classify(e: Throwable): SyncFailure {
            if (e is SyncFailure) return e
            val chain = generateSequence(e) { it.cause }.toList()
            val msg = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }
            val lower = msg.lowercase()
            val cat = when {
                chain.any { it is NoRemoteRepositoryException } || "not found" in lower || Regex("\b404\b").containsMatchIn(lower) -> ErrorCategory.ORIGIN_GONE
                "not authorized" in lower || "authentication is required" in lower || Regex("\b40[13]\b").containsMatchIn(lower) ||
                    "not permitted" in lower || "forbidden" in lower -> ErrorCategory.AUTH_REQUIRED
                chain.any { it is SocketTimeoutException } || "timed out" in lower || "timeout" in lower -> ErrorCategory.TIMEOUT
                "no space left" in lower || "enospc" in lower -> ErrorCategory.STORAGE_FULL
                chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is SSLException } ||
                    "connection reset" in lower || "unknown host" in lower || "cannot open git-upload-pack" in lower -> ErrorCategory.NETWORK
                e is TransportException -> ErrorCategory.REMOTE_ERROR
                else -> ErrorCategory.REMOTE_ERROR
            }
            return SyncFailure(cat, Redact.text(msg) ?: "", e)
        }
    }
}

package com.ouor.mobilegit.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.storage.pack.PackConfig
import org.eclipse.jgit.transport.BundleWriter
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ForkMode { CONVERT, COPY }

/** Repo management: F-14 create, F-20 fork, F-21 delete, F-13 AC5 default branch. */
class RepoOps(
    private val store: RepoStore,
    private val scheduler: SyncScheduler,
    private val settings: () -> Settings,
    private val env: SyncEnvironment,
    private val disconnect: (String) -> Unit = {},
    private val clock: Clock = SystemClock,
) {
    /** F-14. Throws [RepoException] INVALID_NAME / NAME_TAKEN. */
    fun createEmpty(name: String, description: String = ""): RepoMeta =
        store.create(name.trim(), RepoType.LOCAL, CreatedBy.APP_EMPTY, description.trim())

    /** F-14 AC2 command examples. */
    fun pushCommands(cloneUrl: String) = listOf("git remote add phone $cloneUrl", "git push -u phone main")

    /** F-20. COPY (default, D-6) keeps the mirror and creates [newName]; CONVERT turns the mirror itself into a local repo. */
    suspend fun fork(name: String, mode: ForkMode = ForkMode.COPY, newName: String? = null): RepoMeta = withContext(Dispatchers.IO) {
        val src = store.require(name)
        val url = src.mirror?.remoteUrl ?: throw RepoException(RepoException.Code.NOT_A_MIRROR, "미러만 분리할 수 있습니다")
        when (mode) {
            ForkMode.CONVERT -> store.withRepoLock(name) {
                scheduler.cancel(name)
                store.update(name) { it.copy(type = RepoType.LOCAL, mirror = null, forkedFromUrl = url, forkedAt = clock.now(), createdBy = it.createdBy) }
            }
            ForkMode.COPY -> {
                val target = (newName ?: "${src.name}-local").trim()
                RepoNames.validate(target)?.let { throw RepoException(RepoException.Code.INVALID_NAME, it) }
                if (store.exists(target)) throw RepoException(RepoException.Code.NAME_TAKEN, "'$target' 이름은 이미 쓰고 있습니다")
                val size = store.dir(name).dirSize()
                if (env.freeBytes() - size < settings().storageFloorBytes) throw RepoException(RepoException.Code.STORAGE_LOW, "공간이 부족해 복제할 수 없습니다") // AC5
                store.withRepoLock(name) {
                    val tmp = File(store.reposDir, ".tmp-${UUID.randomUUID()}")
                    try {
                        store.dir(name).copyRecursively(tmp)
                        File(tmp, "mobilegit").deleteRecursively()
                        val dest = File(store.reposDir, "$target.git")
                        if (!tmp.renameTo(dest)) throw java.io.IOException("rename failed")
                    } finally { tmp.deleteRecursively() }
                    val meta = RepoMeta(
                        id = UUID.randomUUID().toString(), name = target, type = RepoType.LOCAL, description = src.description,
                        createdAt = clock.now(), createdBy = CreatedBy.FORK, defaultBranch = src.defaultBranch,
                        forkedFromUrl = url, forkedAt = clock.now(),
                    )
                    store.adopt(meta)
                    store.refreshDerived(target)
                }
            }
        }
    }

    /** F-21: cuts live transfers (AC3), cancels background work, deletes data + history. */
    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        store.require(name)
        disconnect(name)
        scheduler.cancel(name)
        store.withRepoLock(name) { store.deleteData(name) }
    }

    /** F-13 AC5: change HEAD of a local repo. */
    suspend fun setDefaultBranch(name: String, branch: String) = withContext(Dispatchers.IO) {
        store.withRepoLock(name) {
            store.open(name).use { r ->
                if (r.exactRef(Constants.R_HEADS + branch) == null) throw RepoException(RepoException.Code.NOT_FOUND, "브랜치 '$branch'이(가) 없습니다")
                r.updateRef(Constants.HEAD).link(Constants.R_HEADS + branch)
            }
            store.refreshDerived(name)
        }
    }

    /**
     * GIT-03: repack + pack refs + bitmaps when a repo has accumulated packs or loose objects, under the repo lock
     * (no push/fetch can run meanwhile). Archive-safe: unreachable *loose* objects are never pruned
     * (expire = epoch), and every archived ref keeps its history reachable. Returns false when nothing was needed.
     * Very large repos are skipped: JGit's PackWriter keeps per-object state for the whole repo in the heap.
     */
    suspend fun gcIfNeeded(name: String, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        store.withRepoLock(name) {
            if (!store.exists(name)) return@withRepoLock false
            store.sweepLocks(name)
            store.open(name).use { repo ->
                val gc = org.eclipse.jgit.internal.storage.file.GC(repo as org.eclipse.jgit.internal.storage.file.FileRepository)
                val st = gc.statistics
                val needed = st.numberOfPackFiles > GC_MAX_PACKS || st.numberOfLooseObjects > GC_MAX_LOOSE || st.numberOfLooseRefs > GC_MAX_LOOSE_REFS
                if (!force && !needed || st.numberOfPackedObjects + st.numberOfLooseObjects > GC_MAX_OBJECTS) return@withRepoLock false
                gc.setExpire(java.time.Instant.EPOCH)
                gc.setPackConfig(PackConfig(repo).apply { threads = 1 })
                gc.gc().get()
            }
            runCatching { store.refreshDerived(name) }
            true
        }
    }

    fun branches(name: String): List<String> = store.open(name).use { r -> r.refDatabase.getRefsByPrefix(Constants.R_HEADS).map { it.name.removePrefix(Constants.R_HEADS) } }

    companion object {
        const val GC_MAX_PACKS = 20
        const val GC_MAX_LOOSE = 2_000
        const val GC_MAX_LOOSE_REFS = 500
        const val GC_MAX_OBJECTS = 2_000_000L
    }
}

@Serializable data class ManifestRepo(
    val name: String, val type: RepoType, val originUrl: String?, val originStatus: OriginStatus?,
    val lastUpdateAt: Long?, val lastPushAt: Long?, val refs: Map<String, String>, val bundle: String,
)
@Serializable data class BackupManifest(val app: String, val appVersion: String, val exportedAt: Long, val repos: List<ManifestRepo>)

/** F-19 exports. Never includes credentials/passwords/ngrok token (they are not in the git data). */
class Exporter(private val store: RepoStore, private val appVersion: String, private val clock: Clock = SystemClock) {

    /** AC1: every ref (archive refs included) plus HEAD, so `git clone x.bundle` checks out the default branch. */
    fun writeBundle(name: String, out: OutputStream, monitor: ProgressMonitor = NullProgressMonitor.INSTANCE) {
        store.open(name).use { repo ->
            val w = BundleWriter(repo)
            w.setPackConfig(PackConfig(repo).apply { threads = 1 })
            val refs = repo.refDatabase.refs.filter { !it.isSymbolic && it.objectId != null }
            if (refs.isEmpty()) throw RepoException(RepoException.Code.NOT_FOUND, "빈 레포는 bundle로 만들 수 없습니다")
            refs.forEach { w.include(it.name, it.objectId) }
            repo.exactRef(Constants.HEAD)?.objectId?.let { w.include(Constants.HEAD, it) }
            w.writeBundle(monitor, out)
        }
    }

    /** AC3: latest tree of [ref] without .git. */
    fun writeSourceZip(name: String, ref: String?, out: OutputStream) {
        store.open(name).use { repo ->
            val (refName, commit) = RepoBrowser.resolveCommit(repo, ref)
            val prefix = "$name-${refName.substringAfterLast('/')}/"
            ZipOutputStream(out).use { zip ->
                TreeWalk(repo).use { tw ->
                    tw.addTree(commit.tree); tw.isRecursive = true
                    while (tw.next()) {
                        val mode = tw.getFileMode(0)
                        if (mode == FileMode.GITLINK) continue
                        zip.putNextEntry(ZipEntry(prefix + tw.pathString).apply { time = commit.commitTime * 1000L })
                        repo.open(tw.getObjectId(0)).copyTo(zip)
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    /** AC2: zip of `<name>.bundle` per repo + manifest.json. Empty repos are listed in the manifest without a bundle. */
    fun writeFullBackup(names: List<String>, out: OutputStream, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val entries = mutableListOf<ManifestRepo>()
        ZipOutputStream(out).use { zip ->
            names.forEachIndexed { i, n ->
                val meta = store.require(n)
                val refs = store.open(n).use { r -> r.refDatabase.refs.filter { !it.isSymbolic && it.objectId != null }.associate { it.name to it.objectId.name } }
                val bundleName = if (refs.isEmpty()) "" else "${meta.name}.bundle"
                if (refs.isNotEmpty()) {
                    zip.putNextEntry(ZipEntry(bundleName))
                    writeBundle(n, NonClosing(zip))
                    zip.closeEntry()
                }
                entries += ManifestRepo(meta.name, meta.type, meta.mirror?.remoteUrl ?: meta.forkedFromUrl, meta.mirror?.originStatus,
                    meta.mirror?.lastSuccessUpdateAt, meta.lastPushAt, refs, bundleName)
                onProgress(i + 1, names.size)
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(CoreJson.encodeToString(BackupManifest.serializer(), BackupManifest("mobilegit", appVersion, clock.now(), entries)).toByteArray())
            zip.closeEntry()
        }
    }

    private class NonClosing(val o: OutputStream) : OutputStream() {
        override fun write(b: Int) = o.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = o.write(b, off, len)
        override fun flush() = o.flush()
        override fun close() = o.flush()
    }

    companion object {
        /** AC5: writes to `<target>.part` then renames; a failure never leaves a partial file. */
        fun toFileAtomically(target: File, writer: (OutputStream) -> Unit): File {
            val part = File(target.parentFile, target.name + ".part")
            try {
                FileOutputStream(part).use { fos -> fos.buffered().let { writer(NonClosing(it)); it.flush() }; fos.fd.sync() }
                // ARC-15: one atomic replace; the old file stays until the new one is complete
                java.nio.file.Files.move(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                return target
            } finally { part.delete() }
        }

        fun suggestedFileName(name: String, kind: String, now: Long): String {
            val stamp = MirrorEngine.archiveStamp(now).take(8)
            return when (kind) { "bundle" -> "$name.bundle"; "zip" -> "$name-$stamp.zip"; else -> "mobilegit-backup-$stamp.zip" }
        }
    }
}

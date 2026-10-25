package com.ouor.mobilegit.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A repo directory whose meta.json could not be read (ARC-01). Shown by the UI; nothing is overwritten. */
data class DamagedRepo(val name: String, val path: String, val issue: StoreIssue)

class RepoException(val code: Code, message: String) : Exception(message) {
    enum class Code { INVALID_NAME, NAME_TAKEN, NOT_FOUND, ALREADY_MIRRORED, STORAGE_LOW, NOT_A_MIRROR, BUSY }
}

/**
 * Owns the bare repositories under `<root>/repos/<name>.git` and their metadata.
 * Metadata lives next to the git data (`<repo>/mobilegit/meta.json`, `events.json`) so deleting a repo deletes everything.
 * All mutating operations on one repo (push, update, fork, delete) are serialized with [withRepoLock] (NFR-무결성).
 */
class RepoStore(root: File, private val clock: Clock = SystemClock) {
    val reposDir = File(root, "repos").apply { mkdirs() }
    private val metas = ConcurrentHashMap<String, RepoMeta>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val _repos = MutableStateFlow<List<RepoMeta>>(emptyList())

    /** All repos, sorted by name. Updates in real time (F-13 AC4). */
    val repos: StateFlow<List<RepoMeta>> = _repos.asStateFlow()

    private fun key(name: String) = name.lowercase()
    private fun metaFile(dir: File) = File(dir, "mobilegit/meta.json")
    private fun eventsFile(dir: File) = File(dir, "mobilegit/events.json")
    private fun pushesFile(dir: File) = File(dir, "mobilegit/pushes.json")
    /** R2-03: the [RefChange] behind each archive ref, so its kind survives the 200-entry history limit. */
    private fun archivesFile(dir: File) = File(dir, "mobilegit/archives.json")

    /** ARC-01/GIT-15: repos whose meta.json exists but cannot be read. They are not served or adopted, and their files are left untouched. */
    private val _damaged = MutableStateFlow<List<DamagedRepo>>(emptyList())
    val damaged: StateFlow<List<DamagedRepo>> = _damaged.asStateFlow()

    init { load() }

    private fun load() {
        StoreCleanup.sweepReposDir(reposDir)
        val damaged = mutableListOf<DamagedRepo>()
        reposDir.listFiles { f -> f.isDirectory && f.name.endsWith(".git") }?.forEach { dir ->
            val mf = metaFile(dir)
            val meta = try { JsonFile.read(mf, RepoMeta.serializer()) } catch (e: CorruptFileException) {
                damaged += DamagedRepo(dir.name.removeSuffix(".git"), dir.absolutePath, StoreIssue.of(e)); return@forEach
            }
            if (meta != null) metas[key(meta.name)] = meta
            else if (File(dir, "HEAD").isFile) {
                // Crash between `git init` and writing meta (e.g. push-create): adopt it as a local repo.
                // Only when meta.json is *missing*: an unreadable one is reported in [damaged] instead (ARC-01).
                val name = dir.name.removeSuffix(".git")
                if (RepoNames.isValid(name)) {
                    val m = RepoMeta(UUID.randomUUID().toString(), name, RepoType.LOCAL, createdAt = dir.lastModified(), createdBy = CreatedBy.PUSH)
                    JsonFile.write(mf, RepoMeta.serializer(), m); metas[key(name)] = m
                }
            } else if (!mf.exists()) {
                // ARC-04: neither HEAD nor meta: an interrupted create/delete. Nothing to serve or keep.
                dir.deleteRecursively()
            }
        }
        _damaged.value = damaged
        publish()
    }

    /**
     * Startup housekeeping, off the main thread (ARC-05): per repo, under its lock, removes leftovers of a killed
     * process (stale locks, pack temp files; GIT-01/ARC-04) and applies [tuneConfig] as a migration (GIT-06).
     */
    suspend fun sweepAll() {
        for (m in metas.values.toList()) withRepoLock(m.name) {
            if (!exists(m.name)) return@withRepoLock
            runCatching { StoreCleanup.sweepRepo(dir(m.name)) }
            runCatching { tuneConfig(dir(m.name)) }
        }
    }

    private fun publish() { _repos.value = metas.values.sortedBy { it.name.lowercase() } }

    fun get(name: String): RepoMeta? = metas[key(name)]
    fun require(name: String): RepoMeta = get(name) ?: throw RepoException(RepoException.Code.NOT_FOUND, "레포 '$name'이(가) 없습니다")
    fun exists(name: String) = metas.containsKey(key(name))
    fun dir(name: String): File = File(reposDir, (get(name)?.name ?: name) + ".git")
    fun findMirrorByUrl(url: String): RepoMeta? = RemoteUrls.identity(url).let { id -> metas.values.firstOrNull { it.mirror != null && RemoteUrls.identity(it.mirror.remoteUrl) == id } }

    /**
     * GIT-04: one shared [Repository] per repo via JGit's [RepositoryCache] (as JGit's own servlets do), so pack indexes
     * are loaded once instead of per request. `use {}` / close() only releases this caller's reference.
     */
    fun open(name: String): Repository = RepositoryCache.open(RepositoryCache.FileKey.exact(dir(name), FS.DETECTED), true)

    /** Drops the shared instance and closes it once its last user is done (unregister alone does not close it). */
    private fun uncache(dir: File) {
        val key = RepositoryCache.FileKey.exact(dir, FS.DETECTED)
        if (key !in RepositoryCache.getRegisteredKeys()) return
        val r = runCatching { RepositoryCache.open(key, false) }.getOrNull()
        RepositoryCache.unregister(key)
        r?.close()
    }

    /** GIT-01: call while holding [withRepoLock] before writing refs. Returns how many stale locks were removed. */
    fun sweepLocks(name: String) = StoreCleanup.sweepLocks(dir(name))

    suspend fun <T> withRepoLock(name: String, block: suspend () -> T): T = locks.computeIfAbsent(key(name)) { Mutex() }.withLock { block() }
    fun isLocked(name: String) = locks[key(name)]?.isLocked == true

    /** Creates an empty bare repo + metadata. Throws [RepoException] for invalid/taken names. */
    fun create(
        name: String, type: RepoType, createdBy: CreatedBy, description: String = "",
        mirror: MirrorSource? = null, initialBranch: String = "main",
    ): RepoMeta = synchronized(this) {
        RepoNames.validate(name)?.let { throw RepoException(RepoException.Code.INVALID_NAME, it) }
        if (exists(name)) throw RepoException(RepoException.Code.NAME_TAKEN, "'$name' 이름은 이미 쓰고 있습니다")
        val dir = File(reposDir, "$name.git")
        if (dir.exists()) {
            // GIT-11/ARC-04: never wipe a directory that may hold data (damaged meta, fork rename in flight).
            if (File(dir, "HEAD").exists() || metaFile(dir).exists()) throw RepoException(RepoException.Code.NAME_TAKEN, "'$name' 이름은 이미 쓰고 있습니다")
            dir.deleteRecursively()
        }
        Git.init().setBare(true).setGitDir(dir).setInitialBranch(initialBranch).call().close()
        tuneConfig(dir)
        val meta = RepoMeta(
            id = UUID.randomUUID().toString(), name = name, type = type, description = description,
            createdAt = clock.now(), createdBy = createdBy, defaultBranch = initialBranch, mirror = mirror,
        )
        JsonFile.write(metaFile(dir), RepoMeta.serializer(), meta)
        metas[key(name)] = meta
        publish()
        meta
    }

    /** Applied on create and, as a migration, to every existing repo at startup (saved only when something changes). */
    private fun tuneConfig(dir: File) {
        FileRepositoryBuilder().setGitDir(dir).build().use { r ->
            val c = r.config
            val before = c.toText()
            c.setBoolean("core", null, "logallrefupdates", false)
            c.setBoolean("receive", null, "fsckobjects", true)
            // GIT-06: real-world history often has zero-padded tree modes and odd author lines (git itself only warns);
            // mirrors fetch them unchecked, so pushes of the same history must not be refused for that.
            c.setBoolean("fsck", null, "allowLeadingZeroFileMode", true)
            c.setBoolean("fsck", null, "allowInvalidPersonIdent", true)
            c.setBoolean("http", null, "receivepack", true)
            c.setBoolean("uploadpack", null, "allowfilter", true)
            if (c.toText() != before) c.save()
        }
    }

    /** Atomically transforms and persists metadata. */
    fun update(name: String, transform: (RepoMeta) -> RepoMeta): RepoMeta = synchronized(this) {
        val cur = require(name)
        val next = transform(cur)
        JsonFile.write(metaFile(dir(cur.name)), RepoMeta.serializer(), next)
        metas[key(name)] = next
        publish()
        next
    }

    fun updateMirror(name: String, transform: (MirrorSource) -> MirrorSource): RepoMeta =
        update(name) { m -> m.copy(mirror = transform(m.mirror ?: throw RepoException(RepoException.Code.NOT_A_MIRROR, "미러가 아닙니다"))) }

    /** Removes the repo from the registry first (new requests get 404) and then deletes data. Caller holds the repo lock. */
    fun deleteData(name: String) {
        val meta = require(name)
        synchronized(this) { metas.remove(key(name)); publish() }
        val dir = File(reposDir, meta.name + ".git")
        uncache(dir)
        // GIT-05: rename first (atomic), then delete. A crash mid-delete leaves `.trash-*`, swept at the next start,
        // instead of a half-deleted repo that would be re-adopted as a local repo.
        val trash = File(reposDir, ".trash-${UUID.randomUUID()}")
        if (dir.renameTo(trash)) { JsonFile.syncDir(reposDir); trash.deleteRecursively() } else dir.deleteRecursively()
        bumpEvents(name)
        bumpEvents("push:$name")
    }

    /** Registers a repo directory that was produced elsewhere (fork copy). */
    fun adopt(meta: RepoMeta) = synchronized(this) {
        uncache(dir(meta.name))
        JsonFile.write(metaFile(dir(meta.name)), RepoMeta.serializer(), meta)
        metas[key(meta.name)] = meta
        publish()
    }

    // ---- F-12 history (newest first, 200 per repo) ----
    /** Per-repo write counter so [eventsFlow] re-reads only when that repo's history changed. */
    private val eventsVersion = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Newest first. Reads the disk: call off the main thread (or use [eventsFlow]). */
    fun events(name: String): List<SyncEvent> = readList(eventsFile(dir(name)), SyncEvent.serializer())

    /** History files are disposable: an unreadable one is preserved by [JsonFile] and treated as empty. */
    private fun <T> readList(f: File, ser: kotlinx.serialization.KSerializer<T>): List<T> =
        try { JsonFile.readList(f, ser) ?: emptyList() } catch (_: CorruptFileException) { emptyList() }

    /** CR-3: history of [name], newest first; emits on subscribe and after every [appendEvent]/delete. Reads on IO. */
    fun eventsFlow(name: String): Flow<List<SyncEvent>> = eventsVersion.map { it[key(name)] ?: 0L }.distinctUntilChanged()
        .map { if (exists(name)) events(name) else emptyList() }.flowOn(Dispatchers.IO)

    private fun bumpEvents(name: String) = eventsVersion.update { it + (key(name) to (it[key(name)] ?: 0L) + 1) }

    // ---- CR-7 received pushes (newest first, MAX_PUSH_EVENTS per repo) ----
    fun pushEvents(name: String): List<PushEvent> = readList(pushesFile(dir(name)), PushEvent.serializer())

    /** CR-7: one entry per updated ref of each accepted push, newest first; re-emits after every push or delete. Reads on IO. */
    fun pushEventsFlow(name: String): Flow<List<PushEvent>> = eventsVersion.map { it["push:" + key(name)] ?: 0L }.distinctUntilChanged()
        .map { if (exists(name)) pushEvents(name) else emptyList() }.flowOn(Dispatchers.IO)

    fun appendPushEvents(name: String, events: List<PushEvent>) {
        if (events.isEmpty()) return
        synchronized(this) {
            if (!exists(name)) return // ARC-04: never recreate the directory of a deleted repo
            JsonFile.writeList(pushesFile(dir(name)), PushEvent.serializer(), (events + pushEvents(name)).take(MAX_PUSH_EVENTS))
        }
        bumpEvents("push:$name")
    }

    /** R2-03: records the changes that produced archive refs (entries without [RefChange.archivedAs] are ignored). */
    fun appendArchives(name: String, changes: List<RefChange>) {
        val archived = changes.filter { it.archivedAs != null }
        if (archived.isEmpty()) return
        synchronized(this) {
            if (!exists(name)) return
            JsonFile.writeList(archivesFile(dir(name)), RefChange.serializer(), (archived + readList(archivesFile(dir(name)), RefChange.serializer())).take(MAX_ARCHIVE_NOTES))
        }
    }

    /** archive ref → the change that created it. From [appendArchives], plus history for archives made before it existed. */
    fun archiveNotes(name: String): Map<String, RefChange> =
        (readList(archivesFile(dir(name)), RefChange.serializer()) + events(name).flatMap { it.refChanges })
            .filter { it.archivedAs != null }.distinctBy { it.archivedAs }.associateBy { it.archivedAs!! }

    /** R2-01 AC2: the user opened the archive screen; drops the "히스토리 바뀜" badge. */
    fun markHistorySeen(name: String) {
        if (get(name)?.historyChangedAt != null) update(name) { it.copy(historyChangedAt = null) }
    }

    fun appendEvent(name: String, event: SyncEvent) {
        synchronized(this) {
            if (!exists(name)) return // ARC-04
            val list = (listOf(event) + events(name)).take(MAX_EVENTS)
            JsonFile.writeList(eventsFile(dir(name)), SyncEvent.serializer(), list)
        }
        bumpEvents(name)
    }

    /**
     * Recomputes cached facts after update/push/fork (F-17 AC2, F-24, F-11 AC5):
     * size, LFS usage, submodule URLs, archive ref count, default branch.
     */
    fun refreshDerived(name: String): RepoMeta {
        val d = dir(name)
        val size = d.dirSize()
        var hasLfs = false
        var submodules = emptyList<String>()
        var archived = 0
        var head: String? = null
        open(name).use { repo ->
            archived = repo.refDatabase.getRefsByPrefix(ARCHIVE_PREFIX).size
            val headRef = repo.exactRef(Constants.HEAD)
            head = headRef?.target?.name?.removePrefix(Constants.R_HEADS)
            val tip = headRef?.objectId
            if (tip != null) {
                val tree = repo.parseCommit(tip).tree
                readFile(repo, tree, ".gitattributes")?.let { hasLfs = it.contains("filter=lfs") }
                readFile(repo, tree, ".gitmodules")?.let { txt ->
                    val cfg = Config().apply { fromText(txt) }
                    submodules = cfg.getSubsections("submodule").mapNotNull { cfg.getString("submodule", it, "url") }
                }
            }
        }
        return update(name) {
            it.copy(sizeBytes = size, sizeMeasuredAt = clock.now(), hasLfs = hasLfs, submoduleUrls = submodules,
                archivedRefCount = archived, defaultBranch = head ?: it.defaultBranch)
        }
    }

    private fun Repository.parseCommit(id: org.eclipse.jgit.lib.ObjectId) = org.eclipse.jgit.revwalk.RevWalk(this).use { it.parseCommit(id) }

    private fun readFile(repo: Repository, tree: org.eclipse.jgit.revwalk.RevTree, path: String): String? =
        TreeWalk.forPath(repo, path, tree)?.use { tw ->
            val loader = repo.open(tw.getObjectId(0))
            if (loader.size > 1_000_000) null else String(loader.bytes)
        }

    companion object {
        const val MAX_EVENTS = 200
        const val MAX_PUSH_EVENTS = 200
        const val MAX_ARCHIVE_NOTES = 10_000
        const val ARCHIVE_PREFIX = "refs/archive/"
    }
}

/**
 * ARC-04 / GIT-01 / GIT-05: leftovers of a killed process. mobilegit is the only writer of its repos and runs in one
 * process, so at startup every lock or temp file is stale. (Gitaly deletes ref locks older than an hour during
 * housekeeping for the same reason; JGit and git never remove them by themselves.)
 */
object StoreCleanup {
    private val repoLocks = setOf("packed-refs.lock", "config.lock", "HEAD.lock", "shallow.lock", "index.lock", "FETCH_HEAD.lock")

    /** `repos/.tmp-*` (fork copy in progress), `repos/.trash-*` (delete in progress). */
    fun sweepReposDir(reposDir: File) {
        reposDir.listFiles()?.forEach { f ->
            if (f.isDirectory && (f.name.startsWith(".tmp-") || f.name.startsWith(".trash-"))) f.deleteRecursively()
            else if (f.isFile && JsonFile.isTmp(f)) f.delete()
        }
    }

    /** Stale ref/config locks, interrupted pack/loose-object writes and JSON tmp files inside one bare repo. Returns the count. */
    fun sweepRepo(dir: File): Int {
        var n = sweepLocks(dir)
        fun del(f: File) { if (f.delete()) n++ }
        File(dir, "objects/pack").listFiles()?.forEach { if (it.isFile && (it.name.startsWith("incoming_") || it.name.endsWith("_tmp") || it.name.startsWith("gc_"))) del(it) }
        File(dir, "objects").listFiles()?.forEach { if (it.isFile && it.name.startsWith("noz")) del(it) }
        // history files are also written outside the repo lock (checks): only tmp files older than a minute are stale
        val old = System.currentTimeMillis() - 60_000
        File(dir, "mobilegit").listFiles()?.forEach { if (it.isFile && JsonFile.isTmp(it) && it.lastModified() < old) del(it) }
        return n
    }

    /**
     * Ref/config lock files only. Also safe at runtime while holding the repo lock, because every writer of a repo
     * (push, mirror update, fork, GC, delete) runs under [RepoStore.withRepoLock].
     */
    fun sweepLocks(dir: File): Int {
        val stale = (dir.listFiles()?.filter { it.isFile && it.name in repoLocks }.orEmpty() +
            File(dir, "refs").walkTopDown().filter { it.isFile && it.name.endsWith(".lock") })
        return stale.count { it.delete() }
    }

    fun sweepTmpFiles(dir: File) { dir.listFiles()?.forEach { if (it.isFile && JsonFile.isTmp(it)) it.delete() } }
}

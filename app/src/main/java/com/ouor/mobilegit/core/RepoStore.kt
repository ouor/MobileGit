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
import kotlinx.serialization.builtins.ListSerializer
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    init { load() }

    private fun key(name: String) = name.lowercase()
    private fun metaFile(dir: File) = File(dir, "mobilegit/meta.json")
    private fun eventsFile(dir: File) = File(dir, "mobilegit/events.json")
    private fun pushesFile(dir: File) = File(dir, "mobilegit/pushes.json")

    private fun load() {
        reposDir.listFiles { f -> f.isDirectory && f.name.endsWith(".git") }?.forEach { dir ->
            val meta = JsonFile.read(metaFile(dir), RepoMeta.serializer())
            if (meta != null) metas[key(meta.name)] = meta
            else if (File(dir, "HEAD").isFile) {
                // Crash between `git init` and writing meta (e.g. push-create): adopt it as a local repo.
                val name = dir.name.removeSuffix(".git")
                if (RepoNames.isValid(name)) {
                    val m = RepoMeta(UUID.randomUUID().toString(), name, RepoType.LOCAL, createdAt = dir.lastModified(), createdBy = CreatedBy.PUSH)
                    JsonFile.write(metaFile(dir), RepoMeta.serializer(), m); metas[key(name)] = m
                }
            }
        }
        publish()
    }

    private fun publish() { _repos.value = metas.values.sortedBy { it.name.lowercase() } }

    fun get(name: String): RepoMeta? = metas[key(name)]
    fun require(name: String): RepoMeta = get(name) ?: throw RepoException(RepoException.Code.NOT_FOUND, "레포 '$name'이(가) 없습니다")
    fun exists(name: String) = metas.containsKey(key(name))
    fun dir(name: String): File = File(reposDir, (get(name)?.name ?: name) + ".git")
    fun findMirrorByUrl(url: String): RepoMeta? = RemoteUrls.identity(url).let { id -> metas.values.firstOrNull { it.mirror != null && RemoteUrls.identity(it.mirror.remoteUrl) == id } }

    fun open(name: String): Repository = FileRepositoryBuilder().setGitDir(dir(name)).setMustExist(true).build()

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
        if (dir.exists()) dir.deleteRecursively()
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

    private fun tuneConfig(dir: File) {
        FileRepositoryBuilder().setGitDir(dir).build().use { r ->
            r.config.apply {
                setBoolean("core", null, "logallrefupdates", false)
                setBoolean("receive", null, "fsckobjects", true)
                setBoolean("http", null, "receivepack", true)
                setBoolean("uploadpack", null, "allowfilter", true)
                save()
            }
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
        File(reposDir, meta.name + ".git").deleteRecursively()
        bumpEvents(name)
        bumpEvents("push:$name")
    }

    /** Registers a repo directory that was produced elsewhere (fork copy). */
    fun adopt(meta: RepoMeta) = synchronized(this) {
        JsonFile.write(metaFile(dir(meta.name)), RepoMeta.serializer(), meta)
        metas[key(meta.name)] = meta
        publish()
    }

    // ---- F-12 history (newest first, 200 per repo) ----
    private val eventsSer = ListSerializer(SyncEvent.serializer())

    /** Per-repo write counter so [eventsFlow] re-reads only when that repo's history changed. */
    private val eventsVersion = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Newest first. Reads the disk: call off the main thread (or use [eventsFlow]). */
    fun events(name: String): List<SyncEvent> = JsonFile.read(eventsFile(dir(name)), eventsSer) ?: emptyList()

    /** CR-3: history of [name], newest first; emits on subscribe and after every [appendEvent]/delete. Reads on IO. */
    fun eventsFlow(name: String): Flow<List<SyncEvent>> = eventsVersion.map { it[key(name)] ?: 0L }.distinctUntilChanged()
        .map { if (exists(name)) events(name) else emptyList() }.flowOn(Dispatchers.IO)

    private fun bumpEvents(name: String) = eventsVersion.update { it + (key(name) to (it[key(name)] ?: 0L) + 1) }

    // ---- CR-7 received pushes (newest first, MAX_PUSH_EVENTS per repo) ----
    private val pushesSer = ListSerializer(PushEvent.serializer())

    fun pushEvents(name: String): List<PushEvent> = JsonFile.read(pushesFile(dir(name)), pushesSer) ?: emptyList()

    /** CR-7: one entry per updated ref of each accepted push, newest first; re-emits after every push or delete. Reads on IO. */
    fun pushEventsFlow(name: String): Flow<List<PushEvent>> = eventsVersion.map { it["push:" + key(name)] ?: 0L }.distinctUntilChanged()
        .map { if (exists(name)) pushEvents(name) else emptyList() }.flowOn(Dispatchers.IO)

    fun appendPushEvents(name: String, events: List<PushEvent>) {
        if (events.isEmpty()) return
        synchronized(this) {
            JsonFile.write(pushesFile(dir(name)), pushesSer, (events + pushEvents(name)).take(MAX_PUSH_EVENTS))
        }
        bumpEvents("push:$name")
    }

    fun appendEvent(name: String, event: SyncEvent) {
        synchronized(this) {
            val list = (listOf(event) + events(name)).take(MAX_EVENTS)
            JsonFile.write(eventsFile(dir(name)), eventsSer, list)
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
        const val ARCHIVE_PREFIX = "refs/archive/"
    }
}

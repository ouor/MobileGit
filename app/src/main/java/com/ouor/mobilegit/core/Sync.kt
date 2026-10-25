package com.ouor.mobilegit.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Device state the sync logic depends on. Android: ConnectivityManager / BatteryManager / StatFs. */
interface SyncEnvironment {
    fun freeBytes(): Long
    fun isOnUnmeteredNetwork(): Boolean
    fun isCharging(): Boolean
}

/** Background execution (Android: WorkManager). */
interface SyncScheduler {
    /** Runs [SyncCoordinator.update] for [name] in the background; AUTO honours wifi/charging constraints. */
    fun enqueueUpdate(name: String, trigger: SyncTrigger)
    /** CR-4: ls-remote check only (never updates), any network, ignores backoff. */
    fun enqueueCheckNow(name: String)
    fun cancel(name: String)
}

enum class NoticeKind { FAILED, ORIGIN_GONE, AUTH_REQUIRED, ARCHIVED, RECOVERED, UPDATED }
data class SyncNotice(val repo: String, val kind: NoticeKind, val category: ErrorCategory? = null, val count: Int = 0)

fun interface SyncNotifier { fun notify(notice: SyncNotice) }

/** Per-repo running state for the UI ("업데이트 중", progress, F-09 AC2). */
data class RepoProgress(val kind: SyncKind, val trigger: SyncTrigger, val progress: SyncProgress? = null)

object NotificationRules {
    private enum class Health { OK, FAILED, GONE, AUTH }

    private fun health(m: MirrorSource) = when {
        m.originStatus == OriginStatus.GONE -> Health.GONE
        m.originStatus == OriginStatus.AUTH_REQUIRED -> Health.AUTH
        m.lastError != null -> Health.FAILED
        else -> Health.OK
    }

    /** F-12 AC4: notify only on state transitions (plus archive events and optional success). */
    fun decide(repo: String, before: MirrorSource, after: MirrorSource, archived: Int, updatedWithChanges: Boolean): List<SyncNotice> {
        val out = mutableListOf<SyncNotice>()
        val b = health(before); val a = health(after)
        if (a != b) when (a) {
            Health.OK -> out += SyncNotice(repo, NoticeKind.RECOVERED)
            Health.FAILED -> out += SyncNotice(repo, NoticeKind.FAILED, after.lastError)
            Health.GONE -> out += SyncNotice(repo, NoticeKind.ORIGIN_GONE)
            Health.AUTH -> out += SyncNotice(repo, NoticeKind.AUTH_REQUIRED)
        }
        if (archived > 0) out += SyncNotice(repo, NoticeKind.ARCHIVED, count = archived)
        if (updatedWithChanges) out += SyncNotice(repo, NoticeKind.UPDATED)
        return out
    }
}

object Badges {
    /** F-05 AC2 / F-12 AC5. */
    fun of(m: RepoMeta, s: Settings, now: Long, running: RepoProgress?): Set<Badge> {
        val out = mutableSetOf<Badge>()
        if (m.hasLfs) out += Badge.LFS_MISSING
        if (m.historyChangedAt != null) out += Badge.HISTORY_CHANGED
        val mir = m.mirror ?: return out
        if (running != null) out += Badge.UPDATING
        if (mir.pendingChanges > 0) out += Badge.UPDATE_AVAILABLE
        when (mir.originStatus) {
            OriginStatus.GONE -> out += Badge.ORIGIN_GONE
            OriginStatus.AUTH_REQUIRED -> out += Badge.AUTH_REQUIRED
            else -> if (mir.lastError != null) out += Badge.FAILED
        }
        if (now - (mir.lastCheckAt ?: m.createdAt) > 3 * s.checkIntervalMillis) out += Badge.DELAYED
        return out
    }

    /** R2-01 AC4: "주의 필요" (filter [RepoFilter.PROBLEM], summary count). */
    val problem = setOf(Badge.FAILED, Badge.ORIGIN_GONE, Badge.AUTH_REQUIRED, Badge.DELAYED, Badge.HISTORY_CHANGED)

    /** R2-01 AC1: the one reason shown in the row's second line, most serious first. */
    fun issue(m: RepoMeta, badges: Set<Badge>): RepoIssue? {
        val mir = m.mirror
        return when {
            Badge.ORIGIN_GONE in badges -> RepoIssue(Badge.ORIGIN_GONE, mir?.failingSince, mir?.lastError)
            Badge.AUTH_REQUIRED in badges -> RepoIssue(Badge.AUTH_REQUIRED, mir?.failingSince, mir?.lastError)
            Badge.FAILED in badges -> RepoIssue(Badge.FAILED, mir?.failingSince, mir?.lastError)
            Badge.DELAYED in badges -> RepoIssue(Badge.DELAYED, mir?.lastCheckAt ?: m.createdAt, null)
            Badge.HISTORY_CHANGED in badges -> RepoIssue(Badge.HISTORY_CHANGED, m.historyChangedAt, null)
            else -> null
        }
    }
}

/**
 * R2-01 AC1 row reason. [since]: failures — start of the current failure run (null for failures recorded before
 * round 2); DELAYED — last check; HISTORY_CHANGED — when the archive happened. [category] for FAILED/ORIGIN_GONE/AUTH.
 */
@kotlinx.serialization.Serializable
data class RepoIssue(val badge: Badge, val since: Long?, val category: ErrorCategory?)

/**
 * R2-02 A-02 summary line: "미러 [mirrors]개 · 정상 [healthy] · 주의 [attention] · 마지막 확인 …" + "로컬 [locals]개".
 * [attention] counts every repo needing attention (the "주의 필요" filter), [healthy] the mirrors that do not.
 * [lastCheckAt]: newest successful contact with an origin over all mirrors. [checksStalled]: no mirror reached its
 * origin for more than 3 check intervals (F-12 AC5) → "백그라운드 확인이 멈춘 것 같아요".
 */
data class RepoOverview(val mirrors: Int, val healthy: Int, val attention: Int, val locals: Int, val lastCheckAt: Long?, val checksStalled: Boolean)

/**
 * F-09/F-10/F-11/F-12 orchestration: concurrency limit (2, NFR-원본 예의), exponential backoff (max 24 h),
 * history, origin status, notifications, storage floor. Pure Kotlin; Android wires it to WorkManager.
 */
class SyncCoordinator(
    private val store: RepoStore,
    private val engine: MirrorEngine,
    private val credentials: CredentialStore,
    private val settings: () -> Settings,
    private val env: SyncEnvironment,
    private val notifier: SyncNotifier,
    private val bulk: BulkJobStore? = null,
    private val clock: Clock = SystemClock,
    maxConcurrent: Int = 2,
) {
    private val slots = Semaphore(maxConcurrent)
    private val _running = MutableStateFlow<Map<String, RepoProgress>>(emptyMap())
    val running: StateFlow<Map<String, RepoProgress>> = _running.asStateFlow()

    private fun key(n: String) = n.lowercase()

    data class CheckDecision(val event: SyncEvent?, val changed: Int, val shouldAutoUpdate: Boolean)

    /** Lightweight ls-remote comparison. Returns whether an automatic update should follow (F-10 AC2/AC3). */
    suspend fun check(name: String, trigger: SyncTrigger = SyncTrigger.AUTO): CheckDecision {
        val meta = store.require(name)
        val mir = meta.mirror ?: throw RepoException(RepoException.Code.NOT_A_MIRROR, "미러가 아닙니다")
        val now = clock.now()
        if (trigger == SyncTrigger.AUTO && (mir.nextRetryAt ?: 0) > now) return CheckDecision(null, 0, false)
        if (_running.value.containsKey(key(name))) return CheckDecision(null, 0, false)
        val started = clock.now()
        return slots.withPermit {
            setRunning(name, RepoProgress(SyncKind.CHECK, trigger))
            try {
                val creds = provider(mir)
                val outcome = withContext(Dispatchers.IO) { store.open(meta.name).use { engine.check(it, mir.remoteUrl, creds, mir.includePullRefs ?: settings().mirrorPullRefs) } }
                val ev = if (outcome.changed > 0) SyncEvent(started, clock.now() - started, trigger, SyncKind.CHECK, SyncResult.SUCCESS) else null
                ev?.let { store.appendEvent(name, it) }
                val before = mir
                val after = store.updateMirror(name) {
                    it.copy(lastCheckAt = clock.now(), lastCheckResult = if (outcome.changed > 0) SyncResult.SUCCESS else SyncResult.NO_CHANGE,
                        pendingChanges = outcome.changed, originStatus = OriginStatus.OK, lastError = null, consecutiveFailures = 0, nextRetryAt = null,
                        lastSuccessCheckAt = clock.now(), failingSince = null)
                }.mirror!!
                NotificationRules.decide(meta.name, before, after, 0, false).forEach(notifier::notify)
                val s = settings()
                CheckDecision(ev, outcome.changed, outcome.changed > 0 && s.autoUpdate && !mir.autoUpdateExcluded && trigger == SyncTrigger.AUTO)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) throw CancellationException("check cancelled")
                if (!store.exists(name)) return@withPermit CheckDecision(null, 0, false) // deleted meanwhile
                CheckDecision(fail(meta, MirrorEngine.classify(e), SyncKind.CHECK, trigger, started), 0, false)
            } finally { clearRunning(name) }
        }
    }

    /** Computes why an automatic update cannot run now (F-10 AC4, F-17 AC3). */
    fun waitingReason(trigger: SyncTrigger): WaitingReason {
        val s = settings()
        if (trigger != SyncTrigger.MANUAL && env.freeBytes() < s.storageFloorBytes) return WaitingReason.STORAGE
        if (trigger == SyncTrigger.AUTO && s.updateWifiOnly && !env.isOnUnmeteredNetwork()) return WaitingReason.WIFI
        if (trigger == SyncTrigger.AUTO && s.updateChargingOnly && !env.isCharging()) return WaitingReason.CHARGING
        return WaitingReason.NONE
    }

    /** Marks a repo as waiting (shown as "조건 대기: …" in A-07). */
    fun markWaiting(name: String, reason: WaitingReason) {
        val cur = store.get(name)?.mirror ?: return
        if (cur.waitingReason == reason) return
        store.updateMirror(name) { it.copy(waitingReason = reason) }
        if (reason != WaitingReason.NONE)
            store.appendEvent(name, SyncEvent(clock.now(), 0, SyncTrigger.AUTO, SyncKind.UPDATE, SyncResult.WAITING, waitingReason = reason))
    }

    /**
     * Full archive-preserving update (F-09, F-11). Waits for a slot and the repo lock (one update per repo at a time).
     *
     * [onStart] runs once the slot and the lock are held, right before the transfer (AND-05: the worker becomes a
     * foreground job only then). It returns false when the work could not become a foreground job; a first fetch is
     * then not started in the background, where the 10-minute job limit would cut it off again and again (GIT-08):
     * the mirror is marked [MirrorSource.waitingForForeground] and resumed by [resumeWaitingForForeground].
     *
     * Cancellation (WorkManager stop, constraint lost, repo deleted) is not a failure (AND-04): no history entry, no
     * backoff, no notification; the [CancellationException] propagates. Any other error becomes a classified
     * failure (AND-11).
     */
    suspend fun update(name: String, trigger: SyncTrigger, onStart: suspend () -> Boolean = { true }): SyncEvent {
        val meta0 = store.require(name)
        meta0.mirror ?: throw RepoException(RepoException.Code.NOT_A_MIRROR, "미러가 아닙니다")
        val wait = waitingReason(trigger)
        if (wait == WaitingReason.STORAGE) {
            markWaiting(name, wait)
            bulk?.onRepoStatus(name, BulkItemStatus.FAILED, "저장 공간 부족")
            return SyncEvent(clock.now(), 0, trigger, SyncKind.UPDATE, SyncResult.WAITING, waitingReason = wait)
        }
        setRunning(name, RepoProgress(SyncKind.UPDATE, trigger))
        bulk?.onRepoStatus(name, BulkItemStatus.RUNNING, null)
        try {
            return slots.withPermit {
                store.withRepoLock(name) {
                    val meta = store.get(name)
                    // GIT-16: converted to a local repo (or deleted) while this update waited for the lock
                    val mir = meta?.mirror ?: return@withRepoLock SyncEvent(clock.now(), 0, trigger, SyncKind.UPDATE, SyncResult.NO_CHANGE)
                    if (!onStart() && !mir.initialFetchDone) {
                        store.updateMirror(name) { it.copy(waitingForForeground = true) }
                        bulk?.onRepoStatus(name, BulkItemStatus.QUEUED, null)
                        return@withRepoLock SyncEvent(clock.now(), 0, trigger, SyncKind.UPDATE, SyncResult.WAITING)
                    }
                    store.sweepLocks(meta.name) // GIT-01: we hold the repo lock, so any ref lock left is stale
                    val started = clock.now()
                    val ctx = currentCoroutineContext()
                    val space = SpaceGuard(trigger)
                    try {
                        val creds = provider(mir)
                        // NonCancellable: once refs are applied, the history/meta below must be written too (GIT-12).
                        // The transfer itself still stops on cancellation through isCancelled.
                        val out = withContext(Dispatchers.IO + NonCancellable) {
                            store.open(meta.name).use { repo ->
                                engine.update(repo, mir.remoteUrl, creds,
                                    onProgress = { p -> setRunning(name, RepoProgress(SyncKind.UPDATE, trigger, p)) },
                                    isCancelled = { !ctx.isActive || space.low() },
                                    includePullRefs = mir.includePullRefs ?: settings().mirrorPullRefs)
                            }
                        }
                        val ev = SyncEvent(started, clock.now() - started, trigger, SyncKind.UPDATE,
                            if (out.refChanges.isEmpty()) SyncResult.NO_CHANGE else SyncResult.SUCCESS,
                            out.refChanges, out.newCommitCount, out.archivedRefCount)
                        store.appendEvent(name, ev)
                        store.appendArchives(name, out.refChanges)
                        val now = clock.now()
                        if (out.archivedRefCount > 0) store.update(name) { it.copy(historyChangedAt = now) } // R2-01 AC2
                        val after = store.updateMirror(name) {
                            it.copy(lastCheckAt = now, lastCheckResult = SyncResult.SUCCESS, lastUpdateAt = now, lastSuccessUpdateAt = now,
                                pendingChanges = 0, originStatus = OriginStatus.OK, lastError = null, consecutiveFailures = 0, nextRetryAt = null,
                                waitingReason = WaitingReason.NONE, initialFetchDone = true, waitingForForeground = false,
                                lastSuccessCheckAt = now, failingSince = null)
                        }.mirror!!
                        runCatching { store.refreshDerived(name) }
                        NotificationRules.decide(meta.name, mir, after, out.archivedRefCount,
                            out.refChanges.isNotEmpty() && settings().notifyOnSuccess && trigger != SyncTrigger.MANUAL).forEach(notifier::notify)
                        bulk?.onRepoStatus(name, BulkItemStatus.DONE, null)
                        ev
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        if (!ctx.isActive && !space.wasLow) {
                            if (store.exists(name)) bulk?.onRepoStatus(name, BulkItemStatus.QUEUED, null)
                            throw CancellationException("update cancelled")
                        }
                        val f = if (space.wasLow) SyncFailure(ErrorCategory.STORAGE_FULL, "남은 공간이 하한 아래로 내려가 받기를 멈췄습니다", e)
                            else MirrorEngine.classify(e)
                        fail(meta, f, SyncKind.UPDATE, trigger, started)
                    }
                }
            }
        } finally { clearRunning(name) }
    }

    /** GIT-08: first fetches deferred because the worker could not run in the foreground; call when the app is visible. */
    fun resumeWaitingForForeground(scheduler: SyncScheduler) =
        store.repos.value.filter { it.mirror?.waitingForForeground == true }.forEach { scheduler.enqueueUpdate(it.name, SyncTrigger.INITIAL) }

    /**
     * GIT-08: stops a transfer when free space drops below the floor (automatic) or 64 MB (any trigger), so a large
     * fetch cannot fill the phone. StatFs is read at most every 2 s.
     */
    private inner class SpaceGuard(trigger: SyncTrigger) {
        private val limit = if (trigger == SyncTrigger.MANUAL) MIN_FREE_BYTES else maxOf(MIN_FREE_BYTES, settings().storageFloorBytes)
        private var checkedAt = 0L
        @Volatile var wasLow = false; private set
        fun low(): Boolean {
            val now = System.currentTimeMillis()
            if (!wasLow && now - checkedAt >= 2_000) { checkedAt = now; wasLow = env.freeBytes() < limit }
            return wasLow
        }
    }

    /** ARC-16 / AND-13: a token that can no longer be decrypted (Keystore reset) is "re-enter the token", not a crash. */
    private fun provider(mir: MirrorSource) = try { credentials.provider(credentials.effective(mir)) } catch (e: Exception) {
        throw SyncFailure(ErrorCategory.AUTH_REQUIRED, "저장된 토큰을 읽을 수 없습니다. 자격 증명에서 토큰을 다시 입력해 주세요 (${e.javaClass.simpleName})", e)
    }

    private fun fail(meta: RepoMeta, f: SyncFailure, kind: SyncKind, trigger: SyncTrigger, started: Long): SyncEvent {
        val mir = meta.mirror!!
        val hasCred = credentials.effective(mir) != null
        // A public mirror that suddenly needs auth was deleted or made private → "원본 없음" (F-11 AC3, SC-2).
        val status = when (f.category) {
            ErrorCategory.ORIGIN_GONE -> OriginStatus.GONE
            ErrorCategory.AUTH_REQUIRED -> if (!hasCred && mir.initialFetchDone) OriginStatus.GONE else OriginStatus.AUTH_REQUIRED
            ErrorCategory.STORAGE_FULL -> mir.originStatus // a local problem says nothing about the origin
            // QA2-01: the origin could not be asked, so an earlier "gone"/"needs auth" is no longer known to hold
            else -> if (mir.originStatus == OriginStatus.OK) OriginStatus.OK else OriginStatus.UNKNOWN
        }
        val category = if (status == OriginStatus.GONE) ErrorCategory.ORIGIN_GONE else f.category
        val n = mir.consecutiveFailures + 1
        // NFR: exponential, max 24 h; also capped at 2x the check interval so the "지연" badge stays meaningful.
        val backoff = minOf(24 * 3_600_000L, 2 * settings().checkIntervalMillis, 3_600_000L shl minOf(n - 1, 5))
        val ev = SyncEvent(started, clock.now() - started, trigger, kind, SyncResult.FAILED, errorCategory = category,
            errorDetail = Redact.text(f.detail, credentials.secrets()))
        store.appendEvent(meta.name, ev)
        val after = store.updateMirror(meta.name) {
            val now = clock.now()
            it.copy(lastCheckAt = now, lastCheckResult = SyncResult.FAILED, lastUpdateAt = if (kind == SyncKind.UPDATE) now else it.lastUpdateAt,
                originStatus = status, lastError = category, consecutiveFailures = n, nextRetryAt = now + backoff,
                failingSince = it.failingSince ?: now)
        }.mirror!!
        NotificationRules.decide(meta.name, mir, after, 0, false).forEach(notifier::notify)
        bulk?.onRepoStatus(meta.name, BulkItemStatus.FAILED, category.name)
        return ev
    }

    private fun setRunning(name: String, p: RepoProgress) = _running.update { it + (key(name) to p) }
    private fun clearRunning(name: String) = _running.update { it - key(name) }

    fun isRunning(name: String) = _running.value.containsKey(key(name))

    /** GIT-16: a running check must not swallow a manual update request; only a running update does. */
    fun isUpdating(name: String) = _running.value[key(name)]?.kind == SyncKind.UPDATE

    companion object { const val MIN_FREE_BYTES = 64L shl 20 }

    /** Blocking helper for Java/WorkManager callers. */
    fun updateBlocking(name: String, trigger: SyncTrigger) = runBlocking { update(name, trigger) }
}

data class AddPreview(
    val input: String,
    val normalized: NormalizedRemote?,
    val suggestedName: String?,
    val existing: RepoMeta?,
    val error: String?,
) {
    /** QA-03: which error [error] is, so the UI can offer "기존 레포로 이동" for [AddPreviewError.ALREADY_MIRRORED]. */
    val errorKind: AddPreviewError? get() = when {
        normalized == null -> AddPreviewError.UNSUPPORTED_URL
        existing != null -> AddPreviewError.ALREADY_MIRRORED
        else -> null
    }
}

enum class AddPreviewError { UNSUPPORTED_URL, ALREADY_MIRRORED }

/** F-06 / F-15 registration flow (A-03). */
class MirrorController(
    private val store: RepoStore,
    private val engine: MirrorEngine,
    private val credentials: CredentialStore,
    private val coordinator: SyncCoordinator,
    private val scheduler: SyncScheduler,
    private val settings: () -> Settings,
    private val env: SyncEnvironment,
    private val bulk: BulkJobStore,
    private val clock: Clock = SystemClock,
) {
    /** Normalizes, suggests a name, detects duplicates (AC1, AC2, AC8). Pure/fast: call on every keystroke. */
    fun preview(input: String, reserved: Set<String> = emptySet()): AddPreview {
        val n = RemoteUrls.normalize(input) ?: return AddPreview(input, null, null, null, "지원하지 않는 URL입니다 (https 주소를 넣어 주세요)")
        val existing = store.findMirrorByUrl(n.url)
        val name = RepoNames.suggest(n) { store.exists(it) || it.lowercase() in reserved }
        return AddPreview(input, n, name, existing, if (existing != null) "이미 미러됨: ${existing.name}" else null)
    }

    /** AC3: reachability/auth check before registering. Blocking network call: run off the main thread. */
    fun probe(url: String, credentialId: String? = null): ProbeResult {
        return engine.probe(url, credentials.provider(credentials.forUrl(url, credentialId)))
    }

    fun storageOk() = env.freeBytes() >= settings().storageFloorBytes

    /** Registers and starts the initial full fetch (AC4). Throws [RepoException] (INVALID_NAME, NAME_TAKEN, ALREADY_MIRRORED, STORAGE_LOW). */
    fun add(url: String, name: String, credentialId: String? = null, createdBy: CreatedBy = CreatedBy.MIRROR_ADD, includePullRefs: Boolean? = null, description: String = ""): RepoMeta {
        val n = RemoteUrls.normalize(url) ?: throw RepoException(RepoException.Code.INVALID_NAME, "지원하지 않는 URL입니다")
        store.findMirrorByUrl(n.url)?.let { throw RepoException(RepoException.Code.ALREADY_MIRRORED, "이미 미러됨: ${it.name}") }
        if (!storageOk()) throw RepoException(RepoException.Code.STORAGE_LOW, "남은 공간이 부족합니다 (하한 ${settings().storageFloorBytes / (1 shl 20)}MB)")
        val meta = store.create(name, RepoType.MIRROR, createdBy, description = description.trim(), mirror = MirrorSource(n.url, credentialId, includePullRefs = includePullRefs))
        scheduler.enqueueUpdate(meta.name, if (createdBy == CreatedBy.BULK) SyncTrigger.BULK else SyncTrigger.INITIAL)
        return meta
    }

    /** AC7: one mirror per line; progress is tracked as a [BulkJob] (A-05). */
    fun addMany(text: String, credentialId: String? = null): BulkJob {
        val reserved = mutableSetOf<String>()
        val items = RemoteUrls.extractCandidates(text).map { line ->
            val p = preview(line, reserved)
            when {
                p.normalized == null -> BulkItem(line, "", BulkItemStatus.FAILED, p.error)
                p.existing != null -> BulkItem(p.normalized.url, p.existing.name, BulkItemStatus.FAILED, p.error)
                else -> BulkItem(p.normalized.url, p.suggestedName!!).also { reserved += it.repoName.lowercase() }
            }
        }
        return startJob(BulkSourceKind.URL_LIST, "", "", items, credentialId)
    }

    /** F-15 AC5: registers selected catalog entries and queues them (2 at a time via the coordinator). */
    fun addFromCatalog(source: BulkSourceKind, account: String, host: String, selected: List<CatalogRepo>, credentialId: String?): BulkJob {
        val reserved = mutableSetOf<String>()
        val items = selected.map { r ->
            val n = RemoteUrls.normalize(r.cloneUrl)
            val name = n?.let { RepoNames.suggest(it) { c -> store.exists(c) || c.lowercase() in reserved } } ?: RepoNames.sanitize(r.name)
            reserved += name.lowercase()
            BulkItem(r.cloneUrl, name)
        }
        // QA-09: keep the host's description (W-01 description column)
        return startJob(source, account, host, items, credentialId, items.zip(selected).associate { (i, r) -> i.repoName to r.description.orEmpty() })
    }

    private fun startJob(source: BulkSourceKind, account: String, host: String, items: List<BulkItem>, credentialId: String?,
                         descriptions: Map<String, String> = emptyMap()): BulkJob {
        val job = bulk.create(source, account, host, items)
        for (item in job.items.filter { it.status == BulkItemStatus.QUEUED }) {
            try {
                add(item.remoteUrl, item.repoName, credentialId, CreatedBy.BULK, description = descriptions[item.repoName].orEmpty())
            } catch (e: RepoException) {
                bulk.onRepoStatus(item.repoName, BulkItemStatus.FAILED, e.message, job.id)
            }
        }
        return bulk.get(job.id)!!
    }

    /** F-15 AC6. */
    fun retryFailed(jobId: String) {
        val job = bulk.get(jobId) ?: return
        for (item in job.items.filter { it.status == BulkItemStatus.FAILED }) {
            val meta = store.get(item.repoName)
            if (meta?.mirror != null && RemoteUrls.identity(meta.mirror.remoteUrl) == RemoteUrls.identity(item.remoteUrl)) {
                bulk.onRepoStatus(item.repoName, BulkItemStatus.QUEUED, null, jobId)
                scheduler.enqueueUpdate(item.repoName, SyncTrigger.BULK)
            } else if (meta == null && RemoteUrls.normalize(item.remoteUrl) != null) {
                try { add(item.remoteUrl, item.repoName, null, CreatedBy.BULK); bulk.onRepoStatus(item.repoName, BulkItemStatus.QUEUED, null, jobId) }
                catch (e: RepoException) { bulk.onRepoStatus(item.repoName, BulkItemStatus.FAILED, e.message, jobId) }
            }
        }
    }

    /** F-09 AC1: manual update ignores conditions and backoff. */
    fun updateNow(name: String) = scheduler.enqueueUpdate(name, SyncTrigger.MANUAL)

    /** F-09 AC4. */
    fun updateAll() = store.repos.value.filter { it.mirror != null }.forEach { scheduler.enqueueUpdate(it.name, SyncTrigger.MANUAL) }

    /** CR-4: "지금 확인" without downloading objects; the result shows up as [MirrorSource.pendingChanges] / UPDATE_AVAILABLE. */
    fun checkNow(name: String) {
        if (store.require(name).mirror == null) throw RepoException(RepoException.Code.NOT_A_MIRROR, "미러가 아닙니다")
        scheduler.enqueueCheckNow(name)
    }

    fun checkAll() = store.repos.value.filter { it.mirror != null }.forEach { scheduler.enqueueCheckNow(it.name) }

    fun setAutoUpdateExcluded(name: String, excluded: Boolean) = store.updateMirror(name) { it.copy(autoUpdateExcluded = excluded) }
    fun setCredential(name: String, credentialId: String?) = store.updateMirror(name) { it.copy(credentialId = credentialId) }

    /** CR-2 per-repo override; null follows [Settings.mirrorPullRefs]. Applies from the next check/update. */
    fun setIncludePullRefs(name: String, include: Boolean?) = store.updateMirror(name) { it.copy(includePullRefs = include) }
}

enum class RepoSort { NAME, RECENT }
enum class RepoFilter { ALL, MIRROR, LOCAL, PROBLEM }

/** One row of the A-02 list. */
data class RepoListItem(val meta: RepoMeta, val badges: Set<Badge>, val progress: RepoProgress?) {
    val lastActivityAt: Long get() = listOfNotNull(meta.lastPushAt, meta.mirror?.lastSuccessUpdateAt, meta.createdAt).max()
    val needsAttention: Boolean get() = badges.any { it in Badges.problem }
    /** R2-01 AC1: null = show "업데이트 N분 전" as before. */
    val issue: RepoIssue? get() = Badges.issue(meta, badges)
}

/** F-05 AC3/AC4 sort, filter and search. Cheap enough for 500 repos on every keystroke. */
object RepoQuery {
    fun items(repos: List<RepoMeta>, s: Settings, now: Long, running: Map<String, RepoProgress>) =
        repos.map { m -> running[m.name.lowercase()].let { p -> RepoListItem(m, Badges.of(m, s, now, p), p) } }

    fun apply(items: List<RepoListItem>, sort: RepoSort, filter: RepoFilter, search: String): List<RepoListItem> = items
        .filter { i ->
            when (filter) {
                RepoFilter.ALL -> true
                RepoFilter.MIRROR -> i.meta.type == RepoType.MIRROR
                RepoFilter.LOCAL -> i.meta.type == RepoType.LOCAL
                RepoFilter.PROBLEM -> i.badges.any { it in Badges.problem }
            } && (search.isBlank() || i.meta.name.contains(search.trim(), ignoreCase = true))
        }
        // R2-01 AC5: "최근 활동순" puts repos needing attention first
        .let { l -> if (sort == RepoSort.NAME) l.sortedBy { it.meta.name.lowercase() }
            else l.sortedWith(compareByDescending<RepoListItem> { it.needsAttention }.thenByDescending { it.lastActivityAt }) }

    /** R2-02. */
    fun overview(items: List<RepoListItem>, s: Settings, now: Long): RepoOverview {
        val mirrors = items.filter { it.meta.mirror != null }
        val last = mirrors.mapNotNull { it.meta.mirror!!.lastOkAt }.maxOrNull()
        val since = last ?: mirrors.minOfOrNull { it.meta.createdAt }
        return RepoOverview(mirrors.size, mirrors.count { !it.needsAttention }, items.count { it.needsAttention },
            items.size - mirrors.size, last, since != null && now - since > 3 * s.checkIntervalMillis)
    }
}

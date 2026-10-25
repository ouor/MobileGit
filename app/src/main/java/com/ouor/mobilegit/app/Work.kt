package com.ouor.mobilegit.app

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ouor.mobilegit.R
import com.ouor.mobilegit.core.Exporter
import com.ouor.mobilegit.core.Settings
import com.ouor.mobilegit.core.SyncScheduler
import com.ouor.mobilegit.core.SyncTrigger
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * WorkManager wiring (NFR-백그라운드: runs without the app process, survives reboots).
 * - "sync-<repo>" unique one-time work per repo (update). AUTO carries wifi/charging constraints (F-10 AC4).
 * - "periodic-check" fans out per-mirror check work with jitter ≤ 10% of the interval (F-10 AC6).
 */
class WorkScheduler(private val context: Context, private val settings: () -> Settings, private val core: () -> Core) : SyncScheduler {
    private val wm get() = WorkManager.getInstance(context)

    override fun enqueueUpdate(name: String, trigger: SyncTrigger) {
        val c = core()
        if (c.coordinator.isUpdating(name)) return // F-09 AC2: one update per repo (a running check does not block it, GIT-16)
        val s = settings()
        val constraints = if (trigger == SyncTrigger.AUTO) Constraints.Builder()
            .setRequiredNetworkType(if (s.updateWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(s.updateChargingOnly).build()
        else Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        if (trigger == SyncTrigger.AUTO) c.coordinator.markWaiting(name, c.coordinator.waitingReason(trigger))
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.K_NAME to name, SyncWorker.K_TRIGGER to trigger.name))
            .setConstraints(constraints).addTag(TAG_SYNC).addTag("repo:${name.lowercase()}").build()
        wm.enqueueUniqueWork("sync-${name.lowercase()}", if (trigger == SyncTrigger.AUTO) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, req)
    }

    override fun cancel(name: String) { wm.cancelUniqueWork("sync-${name.lowercase()}"); wm.cancelUniqueWork("check-${name.lowercase()}") }

    /** (Re)schedules the periodic check; call on start and whenever interval / checkWifiOnly change. */
    fun schedulePeriodicCheck() {
        val s = settings()
        val req = PeriodicWorkRequestBuilder<PeriodicCheckWorker>(s.checkIntervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (s.checkWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .addTag(TAG_SYNC).build()
        wm.enqueueUniquePeriodicWork("periodic-check", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** GIT-03: daily repack of repos that need it, only while charging and idle. */
    fun scheduleMaintenance() {
        val req = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build()).build()
        wm.enqueueUniquePeriodicWork("maintenance", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun enqueueCheck(name: String, maxJitterMs: Long) {
        val req = OneTimeWorkRequestBuilder<CheckWorker>().setInputData(workDataOf(SyncWorker.K_NAME to name))
            .setInitialDelay(if (maxJitterMs > 0) Random.nextLong(maxJitterMs) else 0, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (settings().checkWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .addTag(TAG_SYNC).build()
        wm.enqueueUniqueWork("check-${name.lowercase()}", ExistingWorkPolicy.KEEP, req)
    }

    override fun enqueueCheckNow(name: String) {
        val req = OneTimeWorkRequestBuilder<CheckWorker>()
            .setInputData(workDataOf(SyncWorker.K_NAME to name, SyncWorker.K_TRIGGER to SyncTrigger.MANUAL.name))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG_SYNC).build()
        wm.enqueueUniqueWork("check-${name.lowercase()}", ExistingWorkPolicy.REPLACE, req)
    }

    /** F-19: returns the work id; observe with WorkManager.getWorkInfoByIdFlow(id). Output key [ExportWorker.K_OUT_FILE]. */
    fun enqueueExport(kind: ExportKind, repos: List<String>, ref: String? = null, targetUri: Uri? = null): UUID {
        // AND-10: WorkManager Data is limited to 10 KB, so a long selection goes through a file (empty list = all repos).
        val listFile = if (repos.joinToString("\n").length > 4_000) File(File(context.filesDir, "state"), "export-${UUID.randomUUID()}.txt")
            .apply { parentFile?.mkdirs(); writeText(repos.joinToString("\n")) } else null
        val req = OneTimeWorkRequestBuilder<ExportWorker>().setInputData(workDataOf(
            ExportWorker.K_KIND to kind.name, ExportWorker.K_REPOS to (if (listFile == null) repos.toTypedArray() else emptyArray()),
            ExportWorker.K_REPOS_FILE to listFile?.absolutePath, ExportWorker.K_REF to ref,
            ExportWorker.K_URI to targetUri?.toString())).addTag(TAG_EXPORT).build()
        wm.enqueue(req)
        return req.id
    }

    companion object { const val TAG_SYNC = "sync"; const val TAG_EXPORT = "export" }
}

enum class ExportKind { BUNDLE, SOURCE_ZIP, FULL_BACKUP }

private fun progressInfo(context: Context, id: Int, title: String, text: String, progress: Int = -1): ForegroundInfo {
    val id = Notifier.progressId(id)
    val n = NotificationCompat.Builder(context, Notifier.CH_PROGRESS).setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
        .apply { if (progress >= 0) setProgress(100, progress, false) else setProgress(0, 0, true) }.build()
    return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, n)
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object { const val K_NAME = "name"; const val K_TRIGGER = "trigger" }

    override suspend fun doWork(): Result {
        val core = MobileGitApp.core(applicationContext)
        val name = inputData.getString(K_NAME) ?: return Result.failure()
        val trigger = SyncTrigger.valueOf(inputData.getString(K_TRIGGER) ?: SyncTrigger.AUTO.name)
        if (core.repos.get(name)?.mirror == null) return Result.success()
        // AND-05: become a foreground job only once a slot and the repo lock are ours (not while queued).
        // Android 12+ may refuse when started from the background; the coordinator then defers a first fetch (GIT-08).
        core.coordinator.update(name, trigger) {
            runCatching { setForeground(progressInfo(applicationContext, name.hashCode(), applicationContext.getString(R.string.work_fetching, name), applicationContext.getString(R.string.work_update))) }.isSuccess
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        progressInfo(applicationContext, (inputData.getString(K_NAME) ?: "").hashCode(), applicationContext.getString(R.string.work_mirror_update), inputData.getString(K_NAME) ?: "")
}

class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val core = MobileGitApp.core(applicationContext)
        val name = inputData.getString(SyncWorker.K_NAME) ?: return Result.failure()
        if (core.repos.get(name)?.mirror == null) return Result.success()
        val d = core.coordinator.check(name, SyncTrigger.valueOf(inputData.getString(SyncWorker.K_TRIGGER) ?: SyncTrigger.AUTO.name))
        if (d.shouldAutoUpdate) core.scheduler.enqueueUpdate(name, SyncTrigger.AUTO)
        return Result.success()
    }
}

class PeriodicCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val core = MobileGitApp.core(applicationContext)
        val jitter = core.settings.current.checkIntervalMillis / 10
        core.repos.repos.value.filter { it.mirror != null }.forEach { core.scheduler.enqueueCheck(it.name, jitter) }
        return Result.success()
    }
}

class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val core = MobileGitApp.core(applicationContext)
        for (m in core.repos.repos.value) {
            if (isStopped) break
            runCatching { core.ops.gcIfNeeded(m.name) }
        }
        return Result.success()
    }
}

/**
 * F-19 AC4/AC5: "save to file" streams into the SAF document (deleted again on failure); "share" writes
 * `cacheDir/exports/<file>` via a `.part` file. Neither leaves a partial file behind.
 */
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val K_KIND = "kind"; const val K_REPOS = "repos"; const val K_REPOS_FILE = "reposFile"; const val K_REF = "ref"; const val K_URI = "uri"
        const val K_OUT_FILE = "file"; const val K_ERROR = "error"

        /** AND-12: shared export files are kept a day (the receiving app may read late), then removed. */
        fun sweepCache(context: Context, maxAgeMs: Long = 24 * 3_600_000L) {
            val now = System.currentTimeMillis()
            File(context.cacheDir, "exports").listFiles()?.forEach { if (it.name.endsWith(".part") || now - it.lastModified() > maxAgeMs) it.delete() }
        }
    }

    override suspend fun doWork(): Result {
        val core = MobileGitApp.core(applicationContext)
        val kind = ExportKind.valueOf(inputData.getString(K_KIND)!!)
        val listFile = inputData.getString(K_REPOS_FILE)?.let(::File)
        val selected = listFile?.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() } ?: inputData.getStringArray(K_REPOS)?.toList().orEmpty()
        val repos = selected.ifEmpty { core.repos.repos.value.map { it.name } }
        val uri = inputData.getString(K_URI)?.let(Uri::parse)
        val nid = id.hashCode()
        runCatching { setForeground(progressInfo(applicationContext, nid, applicationContext.getString(R.string.notif_channel_export), repos.joinToString(", ").take(60))) }
        sweepCache(applicationContext)
        val dir = File(applicationContext.cacheDir, "exports").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val fileName = when (kind) {
            ExportKind.BUNDLE -> Exporter.suggestedFileName(repos.first(), "bundle", now)
            ExportKind.SOURCE_ZIP -> Exporter.suggestedFileName(repos.first(), "zip", now)
            ExportKind.FULL_BACKUP -> Exporter.suggestedFileName("", "backup", now)
        }
        val write: (java.io.OutputStream) -> Unit = { out ->
            when (kind) {
                ExportKind.BUNDLE -> core.exporter.writeBundle(repos.first(), out)
                ExportKind.SOURCE_ZIP -> core.exporter.writeSourceZip(repos.first(), inputData.getString(K_REF), out)
                ExportKind.FULL_BACKUP -> core.exporter.writeFullBackup(repos, out) { d, t ->
                    setProgressAsync(workDataOf("done" to d, "total" to t))
                }
            }
        }
        return try {
            if (uri != null) {
                // ARC-07: straight into the chosen document; no second full copy in the app cache
                try {
                    applicationContext.contentResolver.openOutputStream(uri, "wt")!!.buffered().use { o -> write(o) }
                } catch (e: Exception) {
                    runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, uri) }
                    throw e
                }
                Result.success(workDataOf(K_OUT_FILE to null))
            } else {
                // Share: needs a local file. Refuse up front when it would push free space under the floor (ARC-07).
                val estimate = repos.sumOf { core.repos.get(it)?.sizeBytes ?: 0L }
                if (core.env.freeBytes() - estimate < core.settings.current.storageFloorBytes)
                    throw java.io.IOException(applicationContext.getString(R.string.work_export_no_space))
                val file = Exporter.toFileAtomically(File(dir, fileName), write)
                Result.success(workDataOf(K_OUT_FILE to file.absolutePath)) // share via FileProvider
            }
        } catch (e: Exception) {
            Result.failure(Data.Builder().putString(K_ERROR, e.message ?: e.javaClass.simpleName).build())
        } finally { listFile?.delete() }
    }

    override suspend fun getForegroundInfo() = progressInfo(applicationContext, id.hashCode(), applicationContext.getString(R.string.notif_channel_export), "")
}

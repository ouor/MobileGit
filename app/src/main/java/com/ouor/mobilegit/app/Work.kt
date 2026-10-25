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
        if (c.coordinator.isRunning(name)) return // F-09 AC2: one update per repo
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

    fun enqueueCheck(name: String, maxJitterMs: Long) {
        val req = OneTimeWorkRequestBuilder<CheckWorker>().setInputData(workDataOf(SyncWorker.K_NAME to name))
            .setInitialDelay(if (maxJitterMs > 0) Random.nextLong(maxJitterMs) else 0, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (settings().checkWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .addTag(TAG_SYNC).build()
        wm.enqueueUniqueWork("check-${name.lowercase()}", ExistingWorkPolicy.KEEP, req)
    }

    /** F-19: returns the work id; observe with WorkManager.getWorkInfoByIdFlow(id). Output key [ExportWorker.K_OUT_FILE]. */
    fun enqueueExport(kind: ExportKind, repos: List<String>, ref: String? = null, targetUri: Uri? = null): UUID {
        val req = OneTimeWorkRequestBuilder<ExportWorker>().setInputData(workDataOf(
            ExportWorker.K_KIND to kind.name, ExportWorker.K_REPOS to repos.toTypedArray(), ExportWorker.K_REF to ref,
            ExportWorker.K_URI to targetUri?.toString())).addTag(TAG_EXPORT).build()
        wm.enqueue(req)
        return req.id
    }

    companion object { const val TAG_SYNC = "sync"; const val TAG_EXPORT = "export" }
}

enum class ExportKind { BUNDLE, SOURCE_ZIP, FULL_BACKUP }

private fun progressInfo(context: Context, id: Int, title: String, text: String, progress: Int = -1): ForegroundInfo {
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
        // Long transfers: try to run as a foreground job; Android 12+ may refuse when started from background.
        runCatching { setForeground(progressInfo(applicationContext, name.hashCode(), "$name 받는 중", "업데이트")) }
        core.coordinator.update(name, trigger)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        progressInfo(applicationContext, (inputData.getString(K_NAME) ?: "").hashCode(), "미러 업데이트", inputData.getString(K_NAME) ?: "")
}

class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val core = MobileGitApp.core(applicationContext)
        val name = inputData.getString(SyncWorker.K_NAME) ?: return Result.failure()
        if (core.repos.get(name)?.mirror == null) return Result.success()
        val d = core.coordinator.check(name, SyncTrigger.AUTO)
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

/** F-19 AC4/AC5: writes to an app cache file first, then copies to the SAF Uri; failures leave no partial file. */
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object { const val K_KIND = "kind"; const val K_REPOS = "repos"; const val K_REF = "ref"; const val K_URI = "uri"; const val K_OUT_FILE = "file"; const val K_ERROR = "error" }

    override suspend fun doWork(): Result {
        val core = MobileGitApp.core(applicationContext)
        val kind = ExportKind.valueOf(inputData.getString(K_KIND)!!)
        val repos = inputData.getStringArray(K_REPOS)?.toList().orEmpty().ifEmpty { core.repos.repos.value.map { it.name } }
        val uri = inputData.getString(K_URI)?.let(Uri::parse)
        val nid = id.hashCode()
        runCatching { setForeground(progressInfo(applicationContext, nid, "내보내기", repos.joinToString(", ").take(60))) }
        val dir = File(applicationContext.cacheDir, "exports").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val fileName = when (kind) {
            ExportKind.BUNDLE -> Exporter.suggestedFileName(repos.first(), "bundle", now)
            ExportKind.SOURCE_ZIP -> Exporter.suggestedFileName(repos.first(), "zip", now)
            ExportKind.FULL_BACKUP -> Exporter.suggestedFileName("", "backup", now)
        }
        return try {
            val file = Exporter.toFileAtomically(File(dir, fileName)) { out ->
                when (kind) {
                    ExportKind.BUNDLE -> core.exporter.writeBundle(repos.first(), out)
                    ExportKind.SOURCE_ZIP -> core.exporter.writeSourceZip(repos.first(), inputData.getString(K_REF), out)
                    ExportKind.FULL_BACKUP -> core.exporter.writeFullBackup(repos, out) { d, t ->
                        setProgressAsync(workDataOf("done" to d, "total" to t))
                    }
                }
            }
            if (uri != null) {
                try {
                    applicationContext.contentResolver.openOutputStream(uri, "wt")!!.use { o -> file.inputStream().use { it.copyTo(o) } }
                } catch (e: Exception) {
                    runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, uri) }
                    throw e
                } finally { file.delete() }
                Result.success(workDataOf(K_OUT_FILE to null))
            } else Result.success(workDataOf(K_OUT_FILE to file.absolutePath)) // share via FileProvider
        } catch (e: Exception) {
            Result.failure(Data.Builder().putString(K_ERROR, e.message ?: e.javaClass.simpleName).build())
        }
    }

    override suspend fun getForegroundInfo() = progressInfo(applicationContext, id.hashCode(), "내보내기", "")
}

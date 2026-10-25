package com.ouor.mobilegit.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ouor.mobilegit.BuildConfig
import com.ouor.mobilegit.core.AccessLogStore
import com.ouor.mobilegit.core.AuthRateLimiter
import com.ouor.mobilegit.core.Authenticator
import com.ouor.mobilegit.core.BulkJobStore
import com.ouor.mobilegit.core.CredentialStore
import com.ouor.mobilegit.core.Exporter
import com.ouor.mobilegit.core.GitServer
import com.ouor.mobilegit.core.JGitEnv
import com.ouor.mobilegit.core.MirrorController
import com.ouor.mobilegit.core.MirrorEngine
import com.ouor.mobilegit.core.RemoteCatalog
import com.ouor.mobilegit.core.RepoListItem
import com.ouor.mobilegit.core.RepoMeta
import com.ouor.mobilegit.core.RepoOps
import com.ouor.mobilegit.core.RepoQuery
import com.ouor.mobilegit.core.RepoStore
import com.ouor.mobilegit.core.ServerAddress
import com.ouor.mobilegit.core.SettingsStore
import com.ouor.mobilegit.core.SyncCoordinator
import com.ouor.mobilegit.core.TunnelManager
import com.ouor.mobilegit.core.WebAssets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** F-17 A-09 data. */
data class StorageSummary(val appUsageBytes: Long, val freeBytes: Long, val totalBytes: Long, val floorBytes: Long, val reposBySize: List<RepoMeta>) {
    val belowFloor get() = freeBytes < floorBytes
}

/**
 * The object graph and the API surface for the Android UI (ViewModels call these directly).
 * Everything observable is a StateFlow; blocking/network calls are suspend or documented as "off main thread".
 * See docs/core/spec.md for the per-feature mapping.
 */
class Core(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateDir = File(context.filesDir, "state")
    val cipher = KeystoreCipher()
    val env = AndroidEnv(context)

    val settings = SettingsStore(File(stateDir, "settings.json"), cipher)
    val repos = RepoStore(context.filesDir)
    val credentials = CredentialStore(File(stateDir, "credentials.json"), cipher)
    val accessLog = AccessLogStore(File(stateDir, "accesslog.json"))
    val bulkJobs = BulkJobStore(File(stateDir, "bulk.json"))
    val catalog = RemoteCatalog()
    val engine = MirrorEngine()
    val notifier = Notifier(context) { settings.current.notifyOnSuccess }
    val scheduler = WorkScheduler(context, { settings.current }, { this })
    val coordinator = SyncCoordinator(repos, engine, credentials, { settings.current }, env, notifier, bulkJobs)
    val mirrors = MirrorController(repos, engine, credentials, coordinator, scheduler, { settings.current }, env, bulkJobs)
    val exporter = Exporter(repos, BuildConfig.VERSION_NAME)
    val tunnel = TunnelManager(NgrokBackend(), scope)
    private val tunnelUrl: StateFlow<String?> = tunnel.state.map { (it as? com.ouor.mobilegit.core.TunnelState.Connected)?.publicUrl }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val network = NetworkMonitor(context, scope, settings.state.map { it.port }.stateIn(scope, SharingStarted.Eagerly, settings.current.port), tunnelUrl)

    val gitServer = GitServer(
        store = repos,
        auth = Authenticator({ settings.current }, AuthRateLimiter()),
        accessLog = accessLog,
        settings = { settings.current },
        addresses = { network.snapshot(settings.current.port) },
        assets = WebAssets { path -> runCatching { context.assets.open("web/$path") }.getOrNull() },
        freeBytes = { env.freeBytes() },
        appVersion = BuildConfig.VERSION_NAME,
    )
    val ops = RepoOps(repos, scheduler, { settings.current }, env, disconnect = gitServer::disconnectRepo)
    val server = ServerController(context) { this }

    /** F-05: rows with badges; re-evaluated each minute so "지연" appears without other changes. */
    val repoItems: Flow<List<RepoListItem>> = combine(
        repos.repos, settings.state, coordinator.running, flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } },
    ) { r, s, run, now -> RepoQuery.items(r, s, now, run) }

    /** F-16 AC1: clone URL per address kind (no credentials, AC3). */
    fun cloneUrls(repo: String, addresses: List<ServerAddress> = network.addresses.value): List<Pair<ServerAddress, String>> =
        addresses.map { it to it.cloneUrl(repo) }

    /** F-17. Uses cached repo sizes; call [refreshSizes] to re-measure. */
    fun storageSummary(): StorageSummary {
        val list = repos.repos.value
        val stateBytes = stateDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return StorageSummary(list.sumOf { it.sizeBytes } + stateBytes, env.freeBytes(), env.totalBytes(), settings.current.storageFloorBytes,
            list.sortedByDescending { it.sizeBytes })
    }

    suspend fun refreshSizes() = withContext(Dispatchers.IO) { repos.repos.value.forEach { runCatching { repos.refreshDerived(it.name) } } }

    fun start() {
        scheduler.schedulePeriodicCheck()
        scope.launch {
            settings.state.map { it.checkIntervalHours to it.checkWifiOnly }.distinctUntilChanged().drop(1).collect { scheduler.schedulePeriodicCheck() }
        }
        scope.launch { settings.state.map { it.port }.distinctUntilChanged().drop(1).collect { server.onPortChanged() } }
        server.restoreIfEnabled()
    }
}

class MobileGitApp : Application() {
    val core: Core by lazy { Core(this) }

    override fun onCreate() {
        super.onCreate()
        JGitEnv.init()
        Notifier.createChannels(this)
        core.start()
        // QA-02: a blocked background restart (e.g. after an app update) is retried as soon as the user opens the app.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) = core.server.resumeIfBlocked()
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    companion object {
        fun core(context: Context): Core = (context.applicationContext as MobileGitApp).core
    }
}

/** F-22 AC2: restart the server after reboot if enabled and it was on. Work schedules persist on their own. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val core = MobileGitApp.core(context)
        if (core.settings.current.startOnBoot && core.settings.current.serverEnabled) core.server.start()
    }
}

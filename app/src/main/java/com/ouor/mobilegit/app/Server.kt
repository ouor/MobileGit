package com.ouor.mobilegit.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ouor.mobilegit.BuildConfig
import com.ouor.mobilegit.R
import com.ouor.mobilegit.core.ServerStartResult
import com.ouor.mobilegit.core.TunnelAuthException
import com.ouor.mobilegit.core.TunnelBackend
import com.ouor.mobilegit.core.TunnelSession
import com.ouor.mobilegit.core.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

/** F-01 server state for the main switch. */
sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data class Running(val port: Int) : ServerState
    data object Stopping : ServerState
    /**
     * The server is not running. [message] is Korean and safe to show as-is; [detail] is the raw technical cause
     * (for a "자세히" section or logs only). [kind] tells the UI what the user can do.
     */
    data class Failed(
        val portInUse: Boolean,
        val message: String,
        val kind: ServerFailure = if (portInUse) ServerFailure.PORT_IN_USE else ServerFailure.OTHER,
        val detail: String? = null,
    ) : ServerState
}

enum class ServerFailure {
    /** F-01 AC3: pick another port. The switch is off. */
    PORT_IN_USE,
    /**
     * QA-02: Android refused to start the foreground service from the background (e.g. after an app update,
     * Android 12+). The switch stays on (`serverEnabled`); the server comes back by itself the next time the app
     * is opened, and a notification asks the user to do that.
     */
    BACKGROUND_START_BLOCKED,
    OTHER,
}

/** UI entry point for F-01/F-04/F-22. The foreground service does the work. */
class ServerController(private val context: Context, private val core: () -> Core) {
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    /** F-01 AC1. Safe to call repeatedly. Returns false if Android refused to start a foreground service right now. */
    fun start(): Boolean {
        core().settings.update { it.copy(serverEnabled = true, serverBootCount = bootCount()) }
        if (_state.value is ServerState.Running) return true
        _state.value = ServerState.Starting
        return try {
            ContextCompat.startForegroundService(context, Intent(context, ServerService::class.java))
            true
        } catch (e: Exception) { // ForegroundServiceStartNotAllowedException when called from background
            blockedInBackground(e)
            false
        }
    }

    /** F-01 AC2. */
    fun stop() {
        core().settings.update { it.copy(serverEnabled = false) }
        if (_state.value is ServerState.Stopped || _state.value is ServerState.Failed) return
        context.startService(Intent(context, ServerService::class.java).setAction(ServerService.ACTION_STOP))
    }

    /** F-01 AC7: after a process restart within the same boot, bring the server back. Reboots are [BootReceiver]'s job. */
    fun restoreIfEnabled() {
        val s = core().settings.current
        if (s.serverEnabled && s.serverBootCount == bootCount() && _state.value !is ServerState.Running) start()
    }

    private fun bootCount(): Int = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)

    /**
     * AND-02: every bring-up, bring-down and re-bind runs under this one lock, so commands that arrive together
     * (process restart + START_STICKY, boot + package replaced, start then stop) are applied one at a time.
     */
    private val lifecycle = Mutex()

    /**
     * What the latest service command asked for. Set synchronously on the main thread when the command arrives; the
     * locked steps check it, so the final state follows the last command whatever order the coroutines run in.
     */
    @Volatile internal var desiredUp = false

    /**
     * CR-1: re-binds the running server on the current `settings.port` (in-flight transfers get the normal stop grace).
     * The service calls this on its own when the port setting changes. Returns [ServerState.Failed] (portInUse) if the
     * new port is taken; the server then stays off like a failed start. No-op when stopped or already on that port.
     */
    suspend fun restart(): ServerState = lifecycle.withLock {
        withContext(Dispatchers.IO) {
            val c = core()
            val cur = _state.value
            if (!c.gitServer.isRunning || cur == ServerState.Running(c.settings.current.port)) return@withContext cur
            _state.value = ServerState.Stopping
            c.tunnel.stop()
            c.gitServer.stop()
            startServer()
        }
    }

    /** Service only. Starts the server unless a later command asked for "off" (then reports [ServerState.Stopped]). */
    internal suspend fun bringUp(): ServerState = lifecycle.withLock {
        withContext(Dispatchers.IO) {
            if (!desiredUp) return@withContext _state.value.takeIf { it is ServerState.Failed } ?: ServerState.Stopped
            startServer()
        }
    }

    private fun startServer(): ServerState {
        val c = core()
        val port = c.settings.current.port
        val s = when (val r = c.gitServer.start(port)) {
            is ServerStartResult.Started -> ServerState.Running(r.port)
            is ServerStartResult.PortInUse -> ServerState.Failed(true, "포트 ${r.port} 사용 중")
            is ServerStartResult.Failed -> ServerState.Failed(false, "서버를 시작하지 못했어요", detail = r.message)
        }
        if (s is ServerState.Failed) c.settings.update { it.copy(serverEnabled = false) }
        else androidx.core.app.NotificationManagerCompat.from(context).cancel(Notifier.ID_SERVER_BLOCKED)
        _state.value = s
        return s
    }

    /** Service only. Stops the server unless a later command asked for "on" again. */
    internal suspend fun bringDown() = lifecycle.withLock {
        withContext(Dispatchers.IO) {
            if (desiredUp) return@withContext
            if (core().gitServer.isRunning || _state.value !is ServerState.Failed) {
                _state.value = ServerState.Stopping
                core().tunnel.stop()
                core().gitServer.stop()
                _state.value = ServerState.Stopped
            }
        }
    }

    /** The foreground service could not be (re)started from the background. Keeps serverEnabled so [resumeIfBlocked] retries. */
    internal fun blockedInBackground(e: Throwable) {
        _state.value = ServerState.Failed(false, "앱이 업데이트되었거나 백그라운드에 있어서 서버를 다시 켜지 못했어요. 앱을 열면 다시 켜져요.",
            ServerFailure.BACKGROUND_START_BLOCKED, e.message)
        Notifier.serverBlocked(context)
    }

    /** QA-13: a new port makes an old "port N in use" failure stale; go back to the plain stopped state. */
    fun onPortChanged() {
        val st = _state.value
        if (st is ServerState.Failed && st.kind == ServerFailure.PORT_IN_USE) _state.value = ServerState.Stopped
    }

    /** QA-02: called when an activity of the app becomes visible; foreground service starts are allowed then. */
    fun resumeIfBlocked() {
        val st = _state.value
        if (st is ServerState.Failed && st.kind == ServerFailure.BACKGROUND_START_BLOCKED && core().settings.current.serverEnabled) start()
    }
}

/**
 * Foreground service (type specialUse, see spec.md "12장") that hosts the HTTP server while the switch is on (F-22 AC1),
 * holds a partial wake lock + Wi-Fi lock only while running (NFR-백그라운드) and keeps the status notification
 * (address + ngrok state + "서버 끄기" action) up to date. START_STICKY restarts it after a process kill (F-01 AC7).
 */
class ServerService : Service() {
    companion object { const val ACTION_STOP = "com.ouor.mobilegit.STOP_SERVER" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var observers: Job? = null
    private val core get() = MobileGitApp.core(this)

    override fun onBind(intent: Intent?): IBinder? = null

    // All service callbacks and the continuations below run on the main thread, so the fields need no locking.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ctl = core.server
        if (intent?.action == ACTION_STOP) {
            ctl.desiredUp = false
            scope.launch { ctl.bringDown(); withContext(Dispatchers.Main.immediate) { if (!ctl.desiredUp) shutdown(startId) } }
            return START_NOT_STICKY
        }
        if (intent == null && !core.settings.current.serverEnabled) { stopSelf(startId); return START_NOT_STICKY }
        // AND-01: no foreground service, no server. A server in a cached (freezable) process would look "running"
        // while being unreachable, and its locks would leak.
        if (!startInForeground(buildNotification("서버 시작 중…", null))) {
            if (!ctl.desiredUp) stopSelf(startId)
            return START_NOT_STICKY
        }
        ctl.desiredUp = true
        scope.launch {
            val st = ctl.bringUp()
            withContext(Dispatchers.Main.immediate) {
                when {
                    !ctl.desiredUp -> Unit // a stop arrived meanwhile; its own continuation shuts the service down
                    st is ServerState.Running -> { acquireLocks(); observe() }
                    else -> { ctl.desiredUp = false; shutdown(startId) }
                }
            }
        }
        return START_STICKY
    }

    private fun startInForeground(n: Notification): Boolean = try {
        ServiceCompat.startForeground(this, Notifier.ID_SERVER, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        true
    } catch (e: Exception) {
        core.server.blockedInBackground(e)
        false
    }

    /** One set of collectors per service instance (AND-02: a second start must not add a second set). */
    private fun observe() {
        if (observers?.isActive == true) return
        val c = core
        observers = scope.launch {
            // Port changes re-bind the server (CR-1); ngrok follows settings while the server runs (F-04 AC3).
            // One collector so the tunnel always starts after the re-bind, on the new loopback port.
            launch {
                c.settings.state.map { Triple(it.ngrokEnabled, it.ngrokAuthtokenEnc, it.port) }.distinctUntilChanged().collect { (on, _, _) ->
                    if (c.server.restart() !is ServerState.Running) {
                        c.server.desiredUp = false
                        withContext(Dispatchers.Main.immediate) { shutdown(null) }
                        return@collect
                    }
                    val token = c.settings.ngrokAuthtoken()
                    if (on && token != null && c.gitServer.ngrokPort > 0) c.tunnel.start(token, c.gitServer.ngrokPort) else c.tunnel.stop()
                }
            }
            launch {
                combine(c.network.addresses, c.tunnel.state) { a, t -> a to t }.collect { (addrs, t) ->
                    val primary = addrs.firstOrNull()?.baseUrl ?: "접속 가능한 네트워크 없음"
                    val ngrok = when (t) {
                        TunnelState.Off -> null
                        is TunnelState.Connected -> "ngrok: ${t.publicUrl}"
                        is TunnelState.Connecting -> "ngrok: 연결 중"
                        is TunnelState.Retrying -> "ngrok: 재연결 대기 (${t.reason})"
                        is TunnelState.TokenRejected -> "ngrok: 토큰 확인 필요"
                    }
                    getSystemService(android.app.NotificationManager::class.java).notify(Notifier.ID_SERVER, buildNotification(primary, ngrok))
                }
            }
        }
    }

    private fun buildNotification(text: String, ngrok: String?): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, Notifier.CH_SERVER).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (ngrok != null) "mobilegit 서버 (외부 공개 중)" else "mobilegit 서버 실행 중")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(text, ngrok).joinToString("\n")))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(Notifier.openApp(this, null, "main", 0))
            .addAction(0, "서버 끄기", stop).build()
    }

    /**
     * Idempotent (AND-02). The Wi-Fi lock keeps the radio out of power save while the screen is off on Android 13 and
     * lower; from Android 14 the HIGH_PERF mode is a no-op (AND-14) and LOW_LATENCY only works with the screen on,
     * so no Wi-Fi lock is taken there.
     */
    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wake?.isHeld != true) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mobilegit:server").apply { acquire() }
        if (Build.VERSION.SDK_INT < 34 && wifi?.isHeld != true)
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mobilegit:server").apply { acquire() }
    }

    private fun releaseLocks() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }; runCatching { wifi?.takeIf { it.isHeld }?.release() }
        wake = null; wifi = null
    }

    /** [startId] null = stop regardless of later start commands (the server failed). */
    private fun shutdown(startId: Int?) {
        observers?.cancel(); observers = null
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    override fun onDestroy() {
        observers?.cancel()
        releaseLocks()
        // AND-07: the system tore the service down while the server was up. Stop it off the main thread (Ktor waits
        // up to 11 s for transfers) on the app scope, which outlives this service.
        val ctl = core.server
        if (ctl.desiredUp) { ctl.desiredUp = false; core.scope.launch { ctl.bringDown() } }
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * F-04 via ngrok-go v2 (official ngrok Agent SDK, MIT) bound with gomobile (ngrok-mobile/, app/libs/ngrokmobile.aar).
 * The forwarder targets the server's dedicated loopback connector so ngrok traffic is identifiable.
 */
class NgrokBackend : TunnelBackend {
    override fun connect(authtoken: String, localPort: Int): TunnelSession {
        val t = try {
            com.ouor.mobilegit.ngrok.ngrokmobile.Ngrokmobile.start(authtoken, localPort.toLong(), BuildConfig.VERSION_NAME)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            // the Go side prefixes authtoken rejections (ERR_NGROK_105/106/107/4018) with "AUTH:"
            if (msg.startsWith("AUTH:")) throw TunnelAuthException(msg.removePrefix("AUTH:").take(300))
            throw IOException(msg.take(300), e)
        }
        return object : TunnelSession {
            override val publicUrl: String = t.url()
            override fun awaitClosed() = t.awaitDone()
            override fun close() = t.close()
        }
    }
}

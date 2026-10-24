package com.ouor.mobilegit.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** F-23: last 2,000 requests, newest first. Never stores Authorization headers or passwords (AC3). */
class AccessLogStore(private val file: File?, private val capacity: Int = 2000) {
    private val ser = ListSerializer(AccessLogEntry.serializer())
    private val _entries = MutableStateFlow(file?.let { JsonFile.read(it, ser) } ?: emptyList())
    val entries: StateFlow<List<AccessLogEntry>> = _entries.asStateFlow()
    @Volatile private var dirty = false
    private val flusher = file?.let {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "accesslog-flush").apply { isDaemon = true } }.apply {
            scheduleWithFixedDelay({ flush() }, 5, 5, TimeUnit.SECONDS)
        }
    }

    @Synchronized
    fun add(e: AccessLogEntry) {
        _entries.value = (listOf(e) + _entries.value).take(capacity)
        dirty = true
    }

    fun flush() {
        val f = file ?: return
        if (!dirty) return
        dirty = false
        runCatching { JsonFile.write(f, ser, _entries.value) }
    }

    fun clear() { synchronized(this) { _entries.value = emptyList(); dirty = true }; flush() }

    companion object {
        fun filter(list: List<AccessLogEntry>, ngrokOnly: Boolean = false, authFailuresOnly: Boolean = false) = list.filter {
            (!ngrokOnly || it.route == AccessRoute.NGROK) &&
                (!authFailuresOnly || it.authResult == AuthResult.FAILED || it.authResult == AuthResult.BLOCKED)
        }
    }
}

/** Interfaces seen on the phone, used for F-02 address display and F-23 route classification. */
data class NetIface(val name: String, val ipv4: String)

object NetClassifier {
    private val hotspotIface = Regex("^(ap|swlan|softap|wlan|wigig|rndis|usb|ncm|bt-pan|eth)\\d*.*")

    fun isTailscale(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        return p.size == 4 && p[0] == 100 && p[1] in 64..127 // 100.64.0.0/10 (CGNAT range used by Tailscale)
    }

    fun isPrivate(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    /**
     * F-02 AC1/AC2: classify interfaces. [wifiIfaces] are the interface names of connected Wi-Fi (STA) networks,
     * taken from ConnectivityManager. Other private-address wlan/ap/usb interfaces are treated as hotspot/tethering.
     * Result sorted by priority (Wi-Fi → hotspot → Tailscale); the ngrok URL is appended by the caller.
     */
    fun addresses(ifaces: List<NetIface>, wifiIfaces: Set<String>, port: Int): List<ServerAddress> = ifaces.mapNotNull { i ->
        val kind = when {
            isTailscale(i.ipv4) -> AddressKind.TAILSCALE
            i.name in wifiIfaces && isPrivate(i.ipv4) -> AddressKind.WIFI
            isPrivate(i.ipv4) && hotspotIface.matches(i.name) && i.name !in wifiIfaces -> AddressKind.HOTSPOT
            else -> null
        } ?: return@mapNotNull null
        ServerAddress(kind, i.ipv4, "http://${i.ipv4}:$port")
    }.distinctBy { it.host }.sortedBy { it.kind.ordinal }

    /** F-23 route from the local address the client connected to. */
    fun route(localIp: String, viaNgrok: Boolean, current: List<ServerAddress>): AccessRoute = when {
        viaNgrok -> AccessRoute.NGROK
        localIp.startsWith("127.") || localIp == "::1" || localIp == "0:0:0:0:0:0:0:1" -> AccessRoute.LOCALHOST
        isTailscale(localIp) -> AccessRoute.TAILSCALE
        else -> when (current.firstOrNull { it.host == localIp }?.kind) {
            AddressKind.WIFI -> AccessRoute.WIFI
            AddressKind.HOTSPOT -> AccessRoute.HOTSPOT
            else -> AccessRoute.OTHER
        }
    }
}

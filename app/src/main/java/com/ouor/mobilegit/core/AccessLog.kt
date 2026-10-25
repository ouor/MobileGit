package com.ouor.mobilegit.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** F-23: last 2,000 requests, newest first. Never stores Authorization headers or passwords (AC3). */
class AccessLogStore(private val file: File?, private val capacity: Int = 2000) {
    @Volatile var loadIssue: StoreIssue? = null; private set
    private val _entries = MutableStateFlow<List<AccessLogEntry>>(emptyList())
    private val writeLock = Any()
    private val loaded = java.util.concurrent.CountDownLatch(1)
    @Volatile private var clearedBeforeLoad = false
    val entries: StateFlow<List<AccessLogEntry>> = _entries.asStateFlow()
    @Volatile private var dirty = false
    private val flusher = file?.let {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "accesslog-flush").apply { isDaemon = true } }.apply {
            // ARC-05: 2,000 entries are parsed off the main thread; requests logged meanwhile are kept in front
            execute {
                val old = try { JsonFile.readList(file, AccessLogEntry.serializer()) ?: emptyList() } catch (e: CorruptFileException) { loadIssue = StoreIssue.of(e); emptyList() }
                synchronized(this@AccessLogStore) { if (!clearedBeforeLoad) _entries.value = (_entries.value + old).take(capacity) }
                loaded.countDown()
            }
            scheduleWithFixedDelay({ flush() }, 5, 5, TimeUnit.SECONDS)
        }
    } ?: run { loaded.countDown(); null }

    /** Blocks until the saved log has been read (tests, and [flush] so a write never drops the saved entries). */
    fun awaitLoaded() { loaded.await(10, TimeUnit.SECONDS) }

    @Synchronized
    fun add(e: AccessLogEntry) {
        _entries.value = (listOf(e) + _entries.value).take(capacity)
        dirty = true
    }

    /** ARC-03: flusher thread, stop() and clear() may call this together; writes are serialized and retried on failure. */
    fun flush() {
        val f = file ?: return
        awaitLoaded()
        synchronized(writeLock) {
            val snapshot = synchronized(this) { if (!dirty) return; dirty = false; _entries.value }
            try { JsonFile.writeList(f, AccessLogEntry.serializer(), snapshot) } catch (e: Exception) { dirty = true }
        }
    }

    fun clear() { synchronized(this) { _entries.value = emptyList(); dirty = true; if (loaded.count > 0) clearedBeforeLoad = true }; flush() }

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
    private val hotspotIface = Regex("^(ap|swlan|softap|wlan|wigig|bt-pan|eth)\\d*.*")
    /** R2-15: USB tethering (RNDIS on most phones, NCM on newer ones). */
    private val usbTetherIface = Regex("^(rndis|usb|ncm)\\d*.*")

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
     * Result sorted by priority (Wi-Fi → hotspot → USB tethering → Tailscale); the ngrok URL is appended by the caller.
     */
    fun addresses(ifaces: List<NetIface>, wifiIfaces: Set<String>, port: Int): List<ServerAddress> = ifaces.mapNotNull { i ->
        val kind = when {
            isTailscale(i.ipv4) -> AddressKind.TAILSCALE
            i.name in wifiIfaces && isPrivate(i.ipv4) -> AddressKind.WIFI
            isPrivate(i.ipv4) && usbTetherIface.matches(i.name) -> AddressKind.USB_TETHER
            isPrivate(i.ipv4) && hotspotIface.matches(i.name) && i.name !in wifiIfaces -> AddressKind.HOTSPOT
            else -> null
        } ?: return@mapNotNull null
        ServerAddress(kind, i.ipv4, "http://${i.ipv4}:$port")
    }.distinctBy { it.host }.sortedBy { it.kind.ordinal }

    // Only ever called with IP literals (contains ':'), so getByName does not hit DNS. Java turns v4-mapped IPv6 into Inet4Address.
    private fun literal(ip: String) = runCatching { java.net.InetAddress.getByName(ip.substringBefore('%').trim('[', ']')) }.getOrNull()
    private fun v6(ip: String): ByteArray? = if (!ip.contains(':')) null else (literal(ip) as? java.net.Inet6Address)?.address
    /** IPv4 (also v4-mapped IPv6) as a dotted string, else null. */
    private fun v4(ip: String): String? = if (!ip.contains(':')) ip else (literal(ip) as? java.net.Inet4Address)?.hostAddress

    fun isLoopback(ip: String) = v4(ip)?.startsWith("127.") == true || v6(ip)?.let { b -> (0..14).all { b[it].toInt() == 0 } && b[15].toInt() == 1 } == true

    /**
     * SEC-03: addresses of the phone's own networks: RFC 1918, IPv4 link-local, Tailscale (100.64/10), IPv6 link-local
     * (fe80::/10) and unique-local (fc00::/7, Tailscale uses fd7a:115c:a1e0::/48). Loopback is *not* included: a local
     * reverse proxy or tunnel would make it the address of anyone.
     */
    fun isLocalNetwork(ip: String): Boolean {
        v4(ip)?.let { a -> return isPrivate(a) || isTailscale(a) || a.startsWith("169.254.") }
        val b = v6(ip) ?: return false
        val first = b[0].toInt() and 0xff
        return (first == 0xfe && (b[1].toInt() and 0xc0) == 0x80) || (first and 0xfe) == 0xfc
    }

    /**
     * SEC-04: the server socket is dual-stack (`[::]:port`, checked on the device), so it is also reachable on a global
     * IPv6 address (mobile data). Those addresses are never shown to the user; requests arriving on them are refused.
     */
    fun isPublicIpv6(localIp: String): Boolean {
        val b = v6(localIp) ?: return false
        if (isLoopback(localIp) || isLocalNetwork(localIp)) return false
        return true
    }

    /**
     * SEC-03 (DNS rebinding): Host values a browser can only send when the user typed the phone's address: IP
     * literals, localhost, single-label names, mDNS (.local) and Tailscale MagicDNS (.ts.net). A rebinding page runs
     * under an attacker's domain name and fails this check.
     */
    fun isDirectHost(hostHeader: String?): Boolean {
        val h = hostHeader?.trim()?.lowercase() ?: return false
        val name = if (h.startsWith("[")) return true else h.substringBeforeLast(':').takeIf { h.count { it == ':' } == 1 } ?: h
        if (name.isEmpty()) return false
        if (name.all { it.isDigit() || it == '.' }) return true
        return name == "localhost" || !name.contains('.') || name.endsWith(".local") || name.endsWith(".ts.net")
    }

    /** F-23 route from the local address the client connected to. */
    fun route(localIp: String, viaNgrok: Boolean, current: List<ServerAddress>): AccessRoute = when {
        viaNgrok -> AccessRoute.NGROK
        localIp.startsWith("127.") || localIp == "::1" || localIp == "0:0:0:0:0:0:0:1" -> AccessRoute.LOCALHOST
        isTailscale(localIp) -> AccessRoute.TAILSCALE
        else -> when (current.firstOrNull { it.host == localIp }?.kind) {
            AddressKind.WIFI -> AccessRoute.WIFI
            AddressKind.HOTSPOT -> AccessRoute.HOTSPOT
            AddressKind.USB_TETHER -> AccessRoute.USB_TETHER
            else -> AccessRoute.OTHER
        }
    }
}

package com.ouor.mobilegit.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.ouor.mobilegit.core.AddressKind
import com.ouor.mobilegit.core.NetClassifier
import com.ouor.mobilegit.core.NetIface
import com.ouor.mobilegit.core.SecretCipher
import com.ouor.mobilegit.core.ServerAddress
import com.ouor.mobilegit.core.SyncEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** F-07 AC2, F-04 AC6: AES-256-GCM key that never leaves the Android Keystore. Output = base64(iv || ciphertext). */
class KeystoreCipher(private val alias: String = "mobilegit-secrets") : SecretCipher {
    @Volatile private var cached: SecretKey? = null

    /** AND-13: loaded once; synchronized so two first calls cannot generate (and overwrite) the key twice. */
    @Synchronized
    private fun key(): SecretKey = cached ?: loadOrCreate().also { cached = it }

    private fun loadOrCreate(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    override fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(c.iv + c.doFinal(plain.toByteArray()))
    }

    override fun decrypt(cipherText: String): String {
        val all = Base64.getDecoder().decode(cipherText)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12)) }
        return String(c.doFinal(all, 12, all.size - 12))
    }
}

/** Device state for sync decisions and storage display (F-10 AC4, F-17). */
class AndroidEnv(private val context: Context) : SyncEnvironment {
    private val cm get() = context.getSystemService(ConnectivityManager::class.java)

    override fun freeBytes(): Long = StatFs(context.filesDir.absolutePath).availableBytes
    override fun isOnUnmeteredNetwork(): Boolean =
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    override fun isCharging(): Boolean {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
    }
    fun totalBytes(): Long = StatFs(context.filesDir.absolutePath).totalBytes
}

/**
 * F-02: server addresses by kind. Polls interfaces every 3 s while observed (hotspot/Tailscale changes do not
 * reliably produce ConnectivityManager callbacks), which satisfies the 5-second refresh in AC3.
 */
class NetworkMonitor(private val context: Context, scope: CoroutineScope, port: StateFlow<Int>, ngrokUrl: StateFlow<String?>) {
    @Volatile private var cache: Pair<Long, List<ServerAddress>>? = null

    /** Current LAN/Tailscale addresses (without ngrok). Cached 3 s; safe to call per request. */
    fun snapshot(port: Int): List<ServerAddress> {
        val c = cache
        if (c != null && System.currentTimeMillis() - c.first < 3_000 && c.second.firstOrNull()?.baseUrl?.endsWith(":$port") != false) return c.second
        val list = NetClassifier.addresses(interfaces(), wifiInterfaces(), port)
        cache = System.currentTimeMillis() to list
        return list
    }

    private val power = context.getSystemService(PowerManager::class.java)

    /**
     * AND-06: 3 s while the screen is on (AC3 needs a 5 s refresh, and hotspot/Tailscale changes do not reliably
     * produce ConnectivityManager callbacks); 60 s while it is off, when only the server notification is listening.
     */
    private val lan = flow {
        var last = 0L
        while (true) {
            val now = System.currentTimeMillis()
            if (power.isInteractive || now - last >= 60_000) { emit(snapshot(port.value)); last = now }
            delay(3_000)
        }
    }.distinctUntilChanged()

    /** All addresses, primary first (Wi-Fi → hotspot → Tailscale → ngrok). Empty = "접속 가능한 네트워크 없음" (AC4). */
    val addresses: StateFlow<List<ServerAddress>> = combine(lan, port, ngrokUrl) { l, p, ng ->
        val lanP = if (l.firstOrNull()?.baseUrl?.endsWith(":$p") == false) snapshot(p) else l
        lanP + listOfNotNull(ng?.let { ServerAddress(AddressKind.NGROK, Uri.parse(it).host ?: it, it.trimEnd('/')) })
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasTailscale: Boolean get() = snapshot(8080).any { it.kind == AddressKind.TAILSCALE }

    private fun interfaces(): List<NetIface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { ni ->
            ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { NetIface(ni.name, it.hostAddress ?: "") }
        }
    }.getOrDefault(emptyList())

    private fun wifiInterfaces(): Set<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
    }
}

/** F-22: battery optimisation status + system settings intents + manufacturer detection. */
object BatteryHelper {
    enum class Maker { SAMSUNG, XIAOMI, ONEPLUS, GOOGLE, OTHER }

    fun isIgnoringOptimizations(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Opens the system list; safe for Play policy. */
    fun settingsIntent() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** Direct request dialog (needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, declared in the manifest). */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun appDetailsIntent(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun maker(): Maker = when (Build.MANUFACTURER.lowercase()) {
        "samsung" -> Maker.SAMSUNG
        "xiaomi", "redmi", "poco" -> Maker.XIAOMI
        "oneplus" -> Maker.ONEPLUS
        "google" -> Maker.GOOGLE
        else -> Maker.OTHER
    }

    /** F-22 AC4: chip hidden for 30 days after dismissal. */
    fun shouldShowHint(context: Context, dismissedAt: Long?, now: Long = System.currentTimeMillis()) =
        !isIgnoringOptimizations(context) && (dismissedAt == null || now - dismissedAt > 30L * 24 * 3_600_000)
}

/** F-16 AC2/AC4 QR rendering (URL never contains credentials). */
object QrCodes {
    fun bitmap(text: String, size: Int = 768): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    }
}

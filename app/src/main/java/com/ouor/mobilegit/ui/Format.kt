package com.ouor.mobilegit.ui

import com.ouor.mobilegit.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.graphics.vector.ImageVector
import com.ouor.mobilegit.core.AccessRoute
import com.ouor.mobilegit.core.AddressKind
import com.ouor.mobilegit.core.AuthResult
import com.ouor.mobilegit.core.Badge
import com.ouor.mobilegit.core.OriginStatus
import com.ouor.mobilegit.core.SyncResult
import com.ouor.mobilegit.core.SyncTrigger
import com.ouor.mobilegit.core.WaitingReason
import com.ouor.mobilegit.ui.theme.Tone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Display formatting and enum → label/tone/icon mapping shared by every screen.

fun bytes(n: Long?): String {
    if (n == null) return "-"
    if (n < 1024) return "${n} B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = n / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}

/** "방금", "5분 전", "3시간 전", "2일 전", then a date. */
fun ago(t: Long?, now: Long = System.currentTimeMillis()): String {
    if (t == null) return str(R.string.fmt_001)
    val d = (now - t).coerceAtLeast(0) / 1000
    return when {
        d < 60 -> str(R.string.fmt_002)
        d < 3600 -> str(R.string.fmt_003, d / 60)
        d < 86400 -> str(R.string.fmt_004, d / 3600)
        d < 86400 * 7 -> str(R.string.fmt_005, d / 86400)
        else -> SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date(t))
    }
}

fun dateTime(t: Long?): String = t?.let { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(it)) } ?: str(R.string.fmt_001)
fun time(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(t))

data class BadgeStyle(val label: String, val tone: Tone, val icon: ImageVector)

fun Badge.style(): BadgeStyle = when (this) {
    Badge.UPDATE_AVAILABLE -> BadgeStyle(str(R.string.fmt_006), Tone.Info, Icons.Outlined.NewReleases)
    Badge.UPDATING -> BadgeStyle(str(R.string.fmt_007), Tone.Info, Icons.Outlined.Sync)
    Badge.FAILED -> BadgeStyle(str(R.string.fmt_008), Tone.Danger, Icons.Outlined.ErrorOutline)
    Badge.ORIGIN_GONE -> BadgeStyle(str(R.string.fmt_009), Tone.Danger, Icons.Outlined.CloudOff)
    Badge.AUTH_REQUIRED -> BadgeStyle(str(R.string.fmt_010), Tone.Warning, Icons.Outlined.Key)
    Badge.DELAYED -> BadgeStyle(str(R.string.fmt_011), Tone.Warning, Icons.Outlined.HourglassBottom)
    Badge.LFS_MISSING -> BadgeStyle(str(R.string.fmt_012), Tone.Neutral, Icons.Outlined.Inventory2)
}

/** Most severe first, so the first badge is the one that matters. */
val badgeOrder = listOf(Badge.ORIGIN_GONE, Badge.FAILED, Badge.AUTH_REQUIRED, Badge.DELAYED, Badge.UPDATING, Badge.UPDATE_AVAILABLE, Badge.LFS_MISSING)

fun AddressKind.label() = when (this) {
    AddressKind.WIFI -> str(R.string.fmt_013); AddressKind.HOTSPOT -> str(R.string.fmt_014); AddressKind.TAILSCALE -> "Tailscale"; AddressKind.NGROK -> "ngrok"
}

fun AccessRoute.label() = when (this) {
    AccessRoute.WIFI -> str(R.string.fmt_013); AccessRoute.HOTSPOT -> str(R.string.fmt_014); AccessRoute.TAILSCALE -> "Tailscale"
    AccessRoute.NGROK -> "ngrok"; AccessRoute.LOCALHOST -> str(R.string.fmt_015); AccessRoute.OTHER -> str(R.string.fmt_016)
}

fun AuthResult.style(): Pair<String, Tone> = when (this) {
    AuthResult.OK -> str(R.string.fmt_017) to Tone.Success
    AuthResult.ANONYMOUS -> str(R.string.fmt_018) to Tone.Neutral
    AuthResult.FAILED -> str(R.string.fmt_019) to Tone.Danger
    AuthResult.BLOCKED -> str(R.string.fmt_020) to Tone.Danger
}

fun OriginStatus.style(): Pair<String, Tone> = when (this) {
    OriginStatus.OK -> str(R.string.fmt_021) to Tone.Success
    OriginStatus.GONE -> str(R.string.fmt_009) to Tone.Danger
    OriginStatus.AUTH_REQUIRED -> str(R.string.fmt_010) to Tone.Warning
    OriginStatus.UNKNOWN -> str(R.string.fmt_022) to Tone.Neutral
}

fun SyncResult.style(): Pair<String, Tone> = when (this) {
    SyncResult.NO_CHANGE -> str(R.string.fmt_023) to Tone.Neutral
    SyncResult.SUCCESS -> str(R.string.fmt_024) to Tone.Success
    SyncResult.FAILED -> str(R.string.fmt_008) to Tone.Danger
    SyncResult.WAITING -> str(R.string.fmt_025) to Tone.Warning
}

fun SyncTrigger.label() = when (this) {
    SyncTrigger.MANUAL -> str(R.string.fmt_026); SyncTrigger.AUTO -> str(R.string.fmt_027); SyncTrigger.BULK -> str(R.string.fmt_028); SyncTrigger.INITIAL -> str(R.string.fmt_029)
}

fun WaitingReason.label(): String? = when (this) {
    WaitingReason.NONE -> null
    WaitingReason.WIFI -> str(R.string.fmt_030)
    WaitingReason.CHARGING -> str(R.string.fmt_031)
    WaitingReason.STORAGE -> str(R.string.fmt_032)
}

fun shortSha(s: String?) = s?.take(7) ?: str(R.string.fmt_001)

/**
 * Display form of a URL or command: line breaks only after "/" or at spaces, never inside a name like `field-notes`
 * (word joiners around "-"). Copy the original string, not this one.
 */
fun breakable(s: String): String = buildString {
    s.forEachIndexed { i, c ->
        when {
            c == '-' -> append('\u2060').append(c).append('\u2060')
            // no break inside "://" so the scheme stays on the host's line
            c == '/' && s.getOrNull(i + 1) != '/' && s.getOrNull(i - 1) != ':' && !(s.getOrNull(i - 1) == '/' && s.getOrNull(i - 2) == ':') -> append(c).append('\u200B')
            else -> append(c)
        }
    }
}

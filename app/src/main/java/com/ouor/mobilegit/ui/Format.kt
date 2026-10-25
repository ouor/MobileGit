package com.ouor.mobilegit.ui

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
    if (n < 1024) return "$n B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = n / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}

/** "방금", "5분 전", "3시간 전", "2일 전", then a date. */
fun ago(t: Long?, now: Long = System.currentTimeMillis()): String {
    if (t == null) return "없음"
    val d = (now - t).coerceAtLeast(0) / 1000
    return when {
        d < 60 -> "방금"
        d < 3600 -> "${d / 60}분 전"
        d < 86400 -> "${d / 3600}시간 전"
        d < 86400 * 7 -> "${d / 86400}일 전"
        else -> SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date(t))
    }
}

fun dateTime(t: Long?): String = t?.let { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(it)) } ?: "없음"
fun time(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(t))

data class BadgeStyle(val label: String, val tone: Tone, val icon: ImageVector)

fun Badge.style(): BadgeStyle = when (this) {
    Badge.UPDATE_AVAILABLE -> BadgeStyle("업데이트 있음", Tone.Info, Icons.Outlined.NewReleases)
    Badge.UPDATING -> BadgeStyle("업데이트 중", Tone.Info, Icons.Outlined.Sync)
    Badge.FAILED -> BadgeStyle("실패", Tone.Danger, Icons.Outlined.ErrorOutline)
    Badge.ORIGIN_GONE -> BadgeStyle("원본 없음", Tone.Danger, Icons.Outlined.CloudOff)
    Badge.AUTH_REQUIRED -> BadgeStyle("인증 필요", Tone.Warning, Icons.Outlined.Key)
    Badge.DELAYED -> BadgeStyle("지연", Tone.Warning, Icons.Outlined.HourglassBottom)
    Badge.LFS_MISSING -> BadgeStyle("LFS 누락", Tone.Neutral, Icons.Outlined.Inventory2)
}

/** Most severe first, so the first badge is the one that matters. */
val badgeOrder = listOf(Badge.ORIGIN_GONE, Badge.FAILED, Badge.AUTH_REQUIRED, Badge.DELAYED, Badge.UPDATING, Badge.UPDATE_AVAILABLE, Badge.LFS_MISSING)

fun AddressKind.label() = when (this) {
    AddressKind.WIFI -> "와이파이"; AddressKind.HOTSPOT -> "핫스팟"; AddressKind.TAILSCALE -> "Tailscale"; AddressKind.NGROK -> "ngrok"
}

fun AccessRoute.label() = when (this) {
    AccessRoute.WIFI -> "와이파이"; AccessRoute.HOTSPOT -> "핫스팟"; AccessRoute.TAILSCALE -> "Tailscale"
    AccessRoute.NGROK -> "ngrok"; AccessRoute.LOCALHOST -> "이 폰"; AccessRoute.OTHER -> "기타"
}

fun AuthResult.style(): Pair<String, Tone> = when (this) {
    AuthResult.OK -> "인증됨" to Tone.Success
    AuthResult.ANONYMOUS -> "익명" to Tone.Neutral
    AuthResult.FAILED -> "인증 실패" to Tone.Danger
    AuthResult.BLOCKED -> "차단됨" to Tone.Danger
}

fun OriginStatus.style(): Pair<String, Tone> = when (this) {
    OriginStatus.OK -> "정상" to Tone.Success
    OriginStatus.GONE -> "원본 없음" to Tone.Danger
    OriginStatus.AUTH_REQUIRED -> "인증 필요" to Tone.Warning
    OriginStatus.UNKNOWN -> "알 수 없음" to Tone.Neutral
}

fun SyncResult.style(): Pair<String, Tone> = when (this) {
    SyncResult.NO_CHANGE -> "변경 없음" to Tone.Neutral
    SyncResult.SUCCESS -> "성공" to Tone.Success
    SyncResult.FAILED -> "실패" to Tone.Danger
    SyncResult.WAITING -> "조건 대기" to Tone.Warning
}

fun SyncTrigger.label() = when (this) {
    SyncTrigger.MANUAL -> "수동"; SyncTrigger.AUTO -> "자동"; SyncTrigger.BULK -> "일괄"; SyncTrigger.INITIAL -> "첫 받기"
}

fun WaitingReason.label(): String? = when (this) {
    WaitingReason.NONE -> null
    WaitingReason.WIFI -> "조건 대기: 와이파이 필요"
    WaitingReason.CHARGING -> "조건 대기: 충전 필요"
    WaitingReason.STORAGE -> "조건 대기: 저장 공간 부족"
}

fun shortSha(s: String?) = s?.take(7) ?: "없음"

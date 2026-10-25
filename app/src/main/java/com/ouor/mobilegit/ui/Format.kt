package com.ouor.mobilegit.ui

import com.ouor.mobilegit.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
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
    if (t == null) return str(R.string.fmt_none)
    val d = (now - t).coerceAtLeast(0) / 1000
    return when {
        d < 60 -> str(R.string.fmt_just_now)
        d < 3600 -> str(R.string.fmt_minutes_ago, d / 60)
        d < 86400 -> str(R.string.fmt_hours_ago, d / 3600)
        d < 86400 * 7 -> str(R.string.fmt_days_ago, d / 86400)
        else -> SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date(t))
    }
}

fun dateTime(t: Long?): String = t?.let { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(it)) } ?: str(R.string.fmt_none)
fun time(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(t))

data class BadgeStyle(val label: String, val tone: Tone, val icon: ImageVector)

fun Badge.style(): BadgeStyle = when (this) {
    Badge.UPDATE_AVAILABLE -> BadgeStyle(str(R.string.badge_update_available), Tone.Info, Icons.Outlined.NewReleases)
    Badge.UPDATING -> BadgeStyle(str(R.string.badge_updating), Tone.Info, Icons.Outlined.Sync)
    Badge.FAILED -> BadgeStyle(str(R.string.badge_failed), Tone.Danger, Icons.Outlined.ErrorOutline)
    Badge.ORIGIN_GONE -> BadgeStyle(str(R.string.badge_origin_gone), Tone.Danger, Icons.Outlined.CloudOff)
    Badge.AUTH_REQUIRED -> BadgeStyle(str(R.string.badge_auth_required), Tone.Warning, Icons.Outlined.Key)
    Badge.DELAYED -> BadgeStyle(str(R.string.badge_delayed), Tone.Warning, Icons.Outlined.HourglassBottom)
    Badge.LFS_MISSING -> BadgeStyle(str(R.string.badge_lfs_missing), Tone.Neutral, Icons.Outlined.Inventory2)
    Badge.HISTORY_CHANGED -> BadgeStyle(str(R.string.badge_history_changed), Tone.Warning, Icons.Outlined.History)
}

/** Most severe first, so the first badge is the one that matters. */
val badgeOrder = listOf(Badge.ORIGIN_GONE, Badge.FAILED, Badge.AUTH_REQUIRED, Badge.DELAYED, Badge.HISTORY_CHANGED, Badge.UPDATING, Badge.UPDATE_AVAILABLE, Badge.LFS_MISSING)

fun AddressKind.label() = when (this) {
    AddressKind.WIFI -> str(R.string.route_wifi); AddressKind.HOTSPOT -> str(R.string.route_hotspot); AddressKind.USB_TETHER -> str(R.string.route_usb_tether); AddressKind.TAILSCALE -> "Tailscale"; AddressKind.NGROK -> "ngrok"
}

fun AccessRoute.label() = when (this) {
    AccessRoute.WIFI -> str(R.string.route_wifi); AccessRoute.HOTSPOT -> str(R.string.route_hotspot); AccessRoute.USB_TETHER -> str(R.string.route_usb_tether); AccessRoute.TAILSCALE -> "Tailscale"
    AccessRoute.NGROK -> "ngrok"; AccessRoute.LOCALHOST -> str(R.string.route_this_phone); AccessRoute.OTHER -> str(R.string.route_other)
}

fun AuthResult.style(): Pair<String, Tone> = when (this) {
    AuthResult.OK -> str(R.string.auth_result_ok) to Tone.Success
    AuthResult.ANONYMOUS -> str(R.string.auth_result_anonymous) to Tone.Neutral
    AuthResult.FAILED -> str(R.string.auth_result_failed) to Tone.Danger
    AuthResult.BLOCKED -> str(R.string.auth_result_blocked) to Tone.Danger
}

fun OriginStatus.style(): Pair<String, Tone> = when (this) {
    OriginStatus.OK -> str(R.string.origin_status_ok) to Tone.Success
    OriginStatus.GONE -> str(R.string.badge_origin_gone) to Tone.Danger
    OriginStatus.AUTH_REQUIRED -> str(R.string.badge_auth_required) to Tone.Warning
    OriginStatus.UNKNOWN -> str(R.string.origin_status_unknown) to Tone.Neutral
}

fun SyncResult.style(): Pair<String, Tone> = when (this) {
    SyncResult.NO_CHANGE -> str(R.string.sync_result_no_change) to Tone.Neutral
    SyncResult.SUCCESS -> str(R.string.sync_result_success) to Tone.Success
    SyncResult.FAILED -> str(R.string.badge_failed) to Tone.Danger
    SyncResult.WAITING -> str(R.string.sync_result_waiting) to Tone.Warning
}

fun SyncTrigger.label() = when (this) {
    SyncTrigger.MANUAL -> str(R.string.sync_trigger_manual); SyncTrigger.AUTO -> str(R.string.sync_trigger_auto); SyncTrigger.BULK -> str(R.string.sync_trigger_bulk); SyncTrigger.INITIAL -> str(R.string.sync_trigger_initial)
}

fun WaitingReason.label(): String? = when (this) {
    WaitingReason.NONE -> null
    WaitingReason.WIFI -> str(R.string.waiting_wifi)
    WaitingReason.CHARGING -> str(R.string.waiting_charging)
    WaitingReason.STORAGE -> str(R.string.waiting_storage)
}

fun shortSha(s: String?) = s?.take(7) ?: str(R.string.fmt_none)

/**
 * Display form of a URL or command: line breaks only after "/" or an IP dot, before ":port", or at spaces, never inside a name like `field-notes`
 * (word joiners around "-"). Copy the original string, not this one.
 */
fun breakable(s: String): String = buildString {
    s.forEachIndexed { i, c ->
        when {
            c == '-' -> append('\u2060').append(c).append('\u2060')
            // no break inside "://" so the scheme stays on the host's line
            c == '/' && s.getOrNull(i + 1) != '/' && s.getOrNull(i - 1) != ':' && !(s.getOrNull(i - 1) == '/' && s.getOrNull(i - 2) == ':') -> append(c).append('\u200B')
            // RV2-10: an IP address breaks after a dot or before ":port", never inside "120" or "8080"
            c == '.' && s.getOrNull(i - 1)?.isDigit() == true && s.getOrNull(i + 1)?.isDigit() == true -> append(c).append('​')
            c == ':' && s.getOrNull(i + 1)?.isDigit() == true -> append('​').append(c)
            else -> append(c)
        }
    }
}

/** "3일째", "5시간째", "방금부터": how long something has been going on. */
fun running(since: Long, now: Long = System.currentTimeMillis()): String {
    val h = (now - since).coerceAtLeast(0) / 3_600_000
    return when { h >= 24 -> str(R.string.fmt_days_running, h / 24 + 1); h >= 1 -> str(R.string.fmt_hours_running, h); else -> str(R.string.fmt_just_started, clock(since, now)) }
}

/** R2-01 AC1: the row's second line when the repo needs attention ([RepoListItem.issue]). */
fun com.ouor.mobilegit.core.RepoIssue.text(intervalHours: Int, now: Long = System.currentTimeMillis()): String = when (badge) {
    Badge.ORIGIN_GONE -> since?.let { str(R.string.issue_origin_gone_since, running(it, now)) } ?: str(R.string.badge_origin_gone)
    Badge.AUTH_REQUIRED -> str(R.string.badge_auth_required)
    Badge.FAILED -> listOfNotNull(str(R.string.issue_failed), category?.let { str(com.ouor.mobilegit.app.Notifier.categoryRes(it)) }, since?.let { running(it, now) }).joinToString(" · ")
    Badge.DELAYED -> str(R.string.issue_delayed, intervalHours, running(since ?: now, now))
    Badge.HISTORY_CHANGED -> since?.let { str(R.string.issue_history_changed, ago(it, now)) } ?: str(R.string.badge_history_changed)
    else -> badge.style().label
}

/** Row colour follows the core severity (CR-11): 오류 red, 주의 orange. */
val com.ouor.mobilegit.core.RepoIssue.tone get() = if (severity == com.ouor.mobilegit.core.Severity.ERROR) Tone.Danger else Tone.Warning

/** "15:20" (today) or "2026.10.26 15:20" (glossary 8.2: detail cards and history use this, not "N분 전"). */
fun clock(t: Long, now: Long = System.currentTimeMillis()): String {
    val day = SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    return SimpleDateFormat(if (day.format(Date(t)) == day.format(Date(now))) "HH:mm" else "yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(t))
}

/** R2-04 / R2-03: what happened to a ref. null (older archives) = "강제 push 또는 삭제". */
fun com.ouor.mobilegit.core.RefChangeKind?.label(ref: String? = null): String = when (this) {
    com.ouor.mobilegit.core.RefChangeKind.FAST_FORWARD -> str(R.string.change_fast_forward)
    com.ouor.mobilegit.core.RefChangeKind.FORCED -> str(R.string.change_forced)
    com.ouor.mobilegit.core.RefChangeKind.CREATED -> str(if (ref?.startsWith("refs/tags/") == true) R.string.change_created_tag else if (ref != null) R.string.change_created_branch else R.string.change_created)
    com.ouor.mobilegit.core.RefChangeKind.DELETED -> str(R.string.change_deleted)
    com.ouor.mobilegit.core.RefChangeKind.TAG_MOVED -> str(R.string.change_tag_moved)
    com.ouor.mobilegit.core.RefChangeKind.TAG_DELETED -> str(R.string.change_tag_deleted)
    com.ouor.mobilegit.core.RefChangeKind.REVIEW -> str(R.string.change_review)
    null -> str(R.string.change_unknown)
}

val com.ouor.mobilegit.core.RefChangeKind?.tone: Tone get() = when (this) {
    com.ouor.mobilegit.core.RefChangeKind.FAST_FORWARD, com.ouor.mobilegit.core.RefChangeKind.REVIEW -> Tone.Neutral
    com.ouor.mobilegit.core.RefChangeKind.CREATED -> Tone.Success
    com.ouor.mobilegit.core.RefChangeKind.DELETED, com.ouor.mobilegit.core.RefChangeKind.TAG_DELETED -> Tone.Danger
    else -> Tone.Warning
}

/** "refs/archive/<group>/heads/main" → "<group>" (R2-03 AC6). */
fun archiveGroupOf(archivedAs: String?): String? = archivedAs?.removePrefix("refs/archive/")?.substringBefore('/')?.takeIf { archivedAs.startsWith("refs/archive/") }

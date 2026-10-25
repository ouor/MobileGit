package com.ouor.mobilegit.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ouor.mobilegit.R
import com.ouor.mobilegit.core.ErrorCategory
import com.ouor.mobilegit.core.NoticeKind
import com.ouor.mobilegit.core.SyncNotice
import com.ouor.mobilegit.core.SyncNotifier

/**
 * Channels: server status (FGS), sync failures, update success (F-12 AC4: separate channel, off by setting),
 * background progress, export progress.
 * Tapping a notification opens [MainActivity] with [EXTRA_REPO] / [EXTRA_TARGET] for the UI to route.
 */
class Notifier(private val context: Context, private val notifyOnSuccess: () -> Boolean) : SyncNotifier {
    companion object {
        const val CH_SERVER = "server"
        const val CH_FAILURE = "sync_failure"
        const val CH_SUCCESS = "sync_success"
        const val CH_PROGRESS = "sync_progress"
        const val CH_EXPORT = "export"
        const val EXTRA_REPO = "com.ouor.mobilegit.REPO"
        const val EXTRA_TARGET = "com.ouor.mobilegit.TARGET" // "repo" | "credentials" | "battery"
        const val ID_SERVER = 1

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannels(listOf(
                NotificationChannel(CH_SERVER, context.getString(R.string.notif_channel_server), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_FAILURE, context.getString(R.string.notif_channel_failure), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_SUCCESS, context.getString(R.string.notif_channel_success), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_PROGRESS, context.getString(R.string.notif_channel_progress), NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CH_EXPORT, context.getString(R.string.notif_channel_export), NotificationManager.IMPORTANCE_LOW),
            ))
        }

        fun openApp(context: Context, repo: String?, target: String, requestCode: Int): PendingIntent =
            PendingIntent.getActivity(context, requestCode,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_REPO, repo).putExtra(EXTRA_TARGET, target),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        const val ID_SERVER_BLOCKED = 2

        /** QA-02: the server could not restart in the background; tapping opens the app, which restarts it. */
        @Suppress("MissingPermission")
        fun serverBlocked(context: Context, extra: String? = null) {
            if (!canPost(context)) return
            val body = listOfNotNull(context.getString(R.string.notif_server_blocked_body), extra).joinToString("\n")
            NotificationManagerCompat.from(context).notify(ID_SERVER_BLOCKED, NotificationCompat.Builder(context, CH_SERVER)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notif_server_blocked_title))
                .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(openApp(context, null, "main", ID_SERVER_BLOCKED))
                .setAutoCancel(true).build())
        }

        fun canPost(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < 33

        fun categoryRes(c: ErrorCategory?) = when (c) {
            ErrorCategory.NETWORK -> R.string.error_network
            ErrorCategory.AUTH_REQUIRED -> R.string.error_auth_required
            ErrorCategory.ORIGIN_GONE -> R.string.error_origin_gone
            ErrorCategory.STORAGE_FULL -> R.string.error_storage_full
            ErrorCategory.REMOTE_ERROR -> R.string.error_remote
            ErrorCategory.TIMEOUT -> R.string.error_timeout
            null -> R.string.error_unknown
        }

        /** AND-18: event notifications use this tag so their hash IDs never replace [ID_SERVER] / [ID_SERVER_BLOCKED]. */
        const val TAG_EVENT = "event"

        /** Foreground-progress IDs (WorkManager has no tag): 0x10000..0x1FFFF, so never 1 or 2 (R2AND-01: masked, not OR-ed). */
        fun progressId(seed: Int) = (seed and 0xFFFF) or 0x10000
        /** R2AND-01: "saved" notifications, 0x20000..0x2FFFF; can never equal a [progressId] that WorkManager cancels. */
        fun savedId(seed: Int) = (seed and 0xFFFF) or 0x20000
    }

    @Suppress("MissingPermission")
    override fun notify(notice: SyncNotice) {
        if (!canPost(context)) return
        val g: (Int) -> String = { context.getString(it) }
        val (channel, title, text, target) = when (notice.kind) {
            NoticeKind.FAILED -> Quad(CH_FAILURE, g(R.string.notif_failed_title).format(notice.repo), g(categoryRes(notice.category)), "repo")
            NoticeKind.ORIGIN_GONE -> Quad(CH_FAILURE, g(R.string.notif_origin_gone_title).format(notice.repo), g(R.string.notif_origin_gone_body), "repo")
            NoticeKind.AUTH_REQUIRED -> Quad(CH_FAILURE, g(R.string.notif_auth_title).format(notice.repo), g(R.string.notif_auth_body), "credentials")
            NoticeKind.ARCHIVED -> Quad(CH_FAILURE, g(R.string.notif_archived_title).format(notice.repo), g(R.string.notif_archived_body).format(notice.count), "repo")
            NoticeKind.RECOVERED -> Quad(CH_FAILURE, g(R.string.notif_recovered_title).format(notice.repo), g(R.string.notif_recovered_body), "repo")
            NoticeKind.UPDATED -> if (notifyOnSuccess()) Quad(CH_SUCCESS, g(R.string.notif_updated_title).format(notice.repo), g(R.string.notif_updated_body), "repo") else return
        }
        // requestCode shares the ID space of the server notifications' PendingIntents, so keep it off 0..2 too
        val id = progressId((notice.repo + notice.kind).hashCode())
        val n = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(openApp(context, notice.repo, target, id)).build()
        NotificationManagerCompat.from(context).notify(TAG_EVENT, id, n)
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)
}

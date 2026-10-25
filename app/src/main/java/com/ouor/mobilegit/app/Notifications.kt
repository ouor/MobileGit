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
                NotificationChannel(CH_SERVER, "서버 상태", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_FAILURE, "동기화 실패", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_SUCCESS, "업데이트 완료", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_PROGRESS, "백그라운드 작업", NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CH_EXPORT, "내보내기", NotificationManager.IMPORTANCE_LOW),
            ))
        }

        fun openApp(context: Context, repo: String?, target: String, requestCode: Int): PendingIntent =
            PendingIntent.getActivity(context, requestCode,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_REPO, repo).putExtra(EXTRA_TARGET, target),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun canPost(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < 33

        fun categoryText(c: ErrorCategory?) = when (c) {
            ErrorCategory.NETWORK -> "네트워크 오류"
            ErrorCategory.AUTH_REQUIRED -> "인증 필요"
            ErrorCategory.ORIGIN_GONE -> "원본 없음"
            ErrorCategory.STORAGE_FULL -> "저장 공간 부족"
            ErrorCategory.REMOTE_ERROR -> "원격 오류"
            ErrorCategory.TIMEOUT -> "시간 초과"
            null -> "알 수 없는 오류"
        }
    }

    @Suppress("MissingPermission")
    override fun notify(notice: SyncNotice) {
        if (!canPost(context)) return
        val (channel, title, text, target) = when (notice.kind) {
            NoticeKind.FAILED -> Quad(CH_FAILURE, "${notice.repo}: 동기화 실패", categoryText(notice.category), "repo")
            NoticeKind.ORIGIN_GONE -> Quad(CH_FAILURE, "${notice.repo}: 원본 없음", "원본에 접근할 수 없습니다. 이 미러가 남은 사본일 수 있습니다. 내보내기를 권장합니다.", "repo")
            NoticeKind.AUTH_REQUIRED -> Quad(CH_FAILURE, "${notice.repo}: 인증 필요", "원격 자격 증명을 확인해 주세요.", "credentials")
            NoticeKind.ARCHIVED -> Quad(CH_FAILURE, "${notice.repo}: force-push/삭제 감지", "${notice.count}개 ref를 보존했습니다.", "repo")
            NoticeKind.RECOVERED -> Quad(CH_FAILURE, "${notice.repo}: 복구됨", "동기화가 다시 정상입니다.", "repo")
            NoticeKind.UPDATED -> if (notifyOnSuccess()) Quad(CH_SUCCESS, "${notice.repo}: 업데이트 완료", "새 변경을 받았습니다.", "repo") else return
        }
        val id = (notice.repo + notice.kind).hashCode()
        val n = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(openApp(context, notice.repo, target, id)).build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)
}

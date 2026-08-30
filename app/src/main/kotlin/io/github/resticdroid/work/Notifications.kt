package io.github.resticdroid.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.resticdroid.MainActivity
import io.github.resticdroid.R
import io.github.resticdroid.util.Redact

object Notifications {
    private const val CHANNEL_PROGRESS: String = "backup-progress"
    private const val CHANNEL_RESULT: String = "backup-result"

    // Backups and prunes run concurrently, and WorkManager cancels a notification
    // id when any worker holding it finishes: a shared id leaves the survivor a
    // foreground service with nothing on screen and no way to stop it. One id per
    // running job, derived from its own identity.
    fun progressId(key: String): Int = 1000 + (key.hashCode() and 0xFFFF)

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_progress_description)
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                context.getString(R.string.channel_result),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_result_description)
            }
        )
    }

    fun progress(
        context: Context,
        profileName: String,
        text: String,
        percent: Int?,
        cancelIntent: PendingIntent?,
        title: String = context.getString(R.string.notification_backing_up, profileName),
    ): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_backup)
            .setContentTitle(title)
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .apply {
                if (percent != null) setProgress(100, percent, false) else setProgress(0, 0, true)
                cancelIntent?.let {
                    addAction(R.drawable.ic_stop, context.getString(R.string.action_stop), it)
                }
            }
            .build()

    fun result(context: Context, title: String, text: String, failed: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val safe = Redact.text(text)
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(if (failed) R.drawable.ic_warning else R.drawable.ic_backup)
            .setContentTitle(title)
            .setContentText(safe)
            .setStyle(NotificationCompat.BigTextStyle().bigText(safe))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setContentIntent(openApp(context))
            .build()
        manager.notify(title.hashCode(), notification)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

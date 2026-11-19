package io.github.resticdroid.work

import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Cancellation must not become a failed-backup notification or a retry result. */
internal suspend fun CoroutineWorker.rethrowIfStopped(
    log: RunLog,
    operation: String,
    name: String,
    error: Exception,
) {
    if (error !is CancellationException && !isStopped && currentCoroutineContext().isActive) return
    val cancelled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
    val message = "$operation ${if (cancelled) "cancelled" else "stopped"}"
    log.line(message)
    Notifications.result(applicationContext, message, name, failed = false)
    throw (error as? CancellationException
        ?: CancellationException(message).apply { initCause(error) })
}

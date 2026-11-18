package io.github.resticdroid.work

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException

/** A rejected foreground start must never fall through to launching restic. */
internal suspend fun startForeground(
    context: Context,
    log: RunLog,
    promote: suspend () -> Unit,
): Boolean {
    try {
        promote()
        return true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.line("foreground start failed: ${e.javaClass.simpleName}: ${e.message}")
        Log.e("ResticDroid", "Could not start foreground work", e)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            e is ForegroundServiceStartNotAllowedException
        ) {
            Notifications.result(
                context,
                "Could not start background work",
                "Android blocked foreground execution. Scheduled backups will retry; " +
                    "for a manual run, open ResticDroid and try again.",
                failed = true,
            )
            return false
        }
        throw e
    }
}

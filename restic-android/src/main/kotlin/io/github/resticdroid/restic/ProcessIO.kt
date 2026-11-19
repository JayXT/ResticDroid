package io.github.resticdroid.restic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Reader
import java.util.concurrent.TimeUnit

/** Stop the child before coroutineScope waits for its blocking pipe readers. */
internal suspend fun <T> withProcess(
    start: () -> Process,
    block: suspend CoroutineScope.(Process) -> T,
): T {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    return try {
        coroutineScope {
            val process = start()
            try {
                block(process)
            } finally {
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        process.waitFor()
                    }
                }
            }
        }
    } catch (e: Exception) {
        // A pipe reader can throw IOException while cancellation closes it.
        // Preserve the caller's cancellation rather than reporting a read failure.
        caller.ensureActive()
        throw e
    }
}

internal data class CapturedOutput(val text: String, val truncated: Boolean) {
    fun complete(): String {
        check(!truncated) { "Command finished, but its output exceeded the memory limit" }
        return text
    }
}

/** Drain oversized output too: stopping a reader early can strand a repo lock. */
internal fun Reader.readBounded(limit: Int = 4 * 1024 * 1024): CapturedOutput {
    val text = StringBuilder()
    val buffer = CharArray(8192)
    var truncated = false
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        val keep = minOf(count, limit - text.length)
        text.append(buffer, 0, keep)
        if (keep < count) truncated = true
    }
    return CapturedOutput(text.toString(), truncated)
}

package io.github.resticdroid.ui

import io.github.resticdroid.restic.ResticEvent
import io.github.resticdroid.restic.ResticExit
import io.github.resticdroid.restic.lockCleanupWarning
import kotlinx.coroutines.flow.Flow

internal const val FILE_LIMIT = 1_000

data class SnapshotFile(val path: String, val size: Long, val directory: Boolean)

data class FileListing(
    val files: List<SnapshotFile>,
    val truncated: Boolean,
    val warning: String? = null,
)

/** The limit bounds memory, not the subprocess lifetime. */
internal suspend fun Flow<ResticEvent>.readFiles(log: (String) -> Unit = {}): FileListing {
    val files = mutableListOf<SnapshotFile>()
    var truncated = false
    var exit: Int? = null
    var problem: String? = null
    var warning: String? = null
    collect { event ->
        when (event) {
            is ResticEvent.Json -> {
                val obj = event.obj
                if (!obj.has("path")) return@collect
                if (files.size == FILE_LIMIT) {
                    truncated = true
                } else {
                    files += SnapshotFile(obj.optString("path"), obj.optLong("size"), obj.optString("type") == "dir")
                }
            }
            is ResticEvent.ItemError -> {
                problem = event.message
                log(event.message)
            }
            is ResticEvent.Diagnostic -> {
                problem = event.line
                log(event.line)
                lockCleanupWarning(event.line)?.let { warning = it }
            }
            is ResticEvent.Finished -> {
                exit = event.exitCode
                log("listing exited with code ${event.exitCode}")
            }
            else -> Unit
        }
    }
    val code = exit ?: error("File listing ended without an exit status")
    if (!ResticExit.isSuccess(code)) error(problem ?: ResticExit.describe(code))
    return FileListing(files, truncated, warning)
}

package io.github.resticdroid.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialize local operations, including preparation and retention.
 * One gate also covers different destination names for the same repository.
 * Restic's own locks still protect against other devices.
 */
internal object RepositoryWork {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }
}

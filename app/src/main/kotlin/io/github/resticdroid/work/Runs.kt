package io.github.resticdroid.work

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Repositories whose contents a worker has just changed.
 *
 * A backup or a prune finishes long after the tap that started it, in another
 * process component entirely. Without this the UI would keep serving a snapshot
 * listing taken before the run.
 */
object Runs {
    val changed: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 16)

    fun changed(destinationId: String) {
        changed.tryEmit(destinationId)
    }
}

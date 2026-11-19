package io.github.resticdroid.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RepositoryWorkTest {
    @Test(timeout = 10_000)
    fun `cancelled operations release the gate only after cleanup`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        val first = launch {
            RepositoryWork.run {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally { cleaned = true }
            }
        }
        started.await()
        val second = launch {
            RepositoryWork.run { assertTrue(cleaned) }
        }
        withTimeout(5_000) {
            first.cancelAndJoin()
            second.join()
        }
    }
}

package io.github.resticdroid.restic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LockCleanupTest {
    @Test
    fun `cleanup failure is found among other stderr messages`() {
        assertEquals(
            "error while unlocking: request timed out",
            lockCleanupWarning("preparing\nerror while unlocking: request timed out\nfinished\n"),
        )
    }

    @Test
    fun `ordinary output is not a cleanup warning`() {
        assertNull(lockCleanupWarning(""))
        assertNull(lockCleanupWarning("repository opened successfully"))
    }
}

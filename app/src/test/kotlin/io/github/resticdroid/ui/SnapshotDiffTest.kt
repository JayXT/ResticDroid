package io.github.resticdroid.ui

import io.github.resticdroid.restic.ResticEvent
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SnapshotDiffTest {
    @Test
    fun `large comparisons drain through exit and report omissions`() = runTest {
        var finished = false
        val result = flow {
            repeat(3_000) { emit(ResticEvent.Output("file-$it")) }
            finished = true
            emit(ResticEvent.Finished(0))
        }.readDiff()
        assertTrue(finished)
        assertTrue(result.contains("file-1999"))
        assertFalse(result.contains("file-2000"))
        assertTrue(result.contains("1000 more lines"))
    }

    @Test
    fun `comparison failure after truncation is not hidden`() = runTest {
        try {
            flow {
                repeat(3_000) { emit(ResticEvent.Output("file-$it")) }
                emit(ResticEvent.Diagnostic("connection lost"))
                emit(ResticEvent.Finished(1))
            }.readDiff()
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertEquals("connection lost", e.message)
        }
    }

    @Test
    fun `comparison memory is bounded even for long lines`() = runTest {
        val result = flow {
            repeat(100) { emit(ResticEvent.Output("x".repeat(65_536))) }
            emit(ResticEvent.Finished(0))
        }.readDiff()
        assertTrue(result.length < 513 * 1024)
        assertTrue(result.contains("more lines"))
    }
}

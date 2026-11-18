package io.github.resticdroid.ui

import io.github.resticdroid.restic.ResticEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SnapshotFilesTest {
    private fun entry(index: Int) = ResticEvent.Json(
        JSONObject().put("path", "/file-$index").put("type", "file").put("size", index)
    )

    @Test
    fun `large listings are drained through cleanup but retain only 1000 entries`() = runTest {
        var cleanedUp = false
        val result = flow {
            emit(ResticEvent.Json(JSONObject().put("message_type", "snapshot")))
            repeat(6_949) { emit(entry(it)) }
            // Model restic releasing its lock before process exit.
            cleanedUp = true
            emit(ResticEvent.Finished(0))
        }.readFiles()
        assertTrue(cleanedUp)
        assertEquals(1_000, result.files.size)
        assertEquals("/file-999", result.files.last().path)
        assertTrue(result.truncated)
    }

    @Test
    fun `exactly 1000 entries are not marked as truncated`() = runTest {
        val result = flow {
            repeat(1_000) { emit(entry(it)) }
            emit(ResticEvent.Finished(0))
        }.readFiles()
        assertEquals(1_000, result.files.size)
        assertFalse(result.truncated)
    }

    @Test
    fun `a failure after the display limit is still a failure`() = runTest {
        try {
            flow {
                repeat(1_001) { emit(entry(it)) }
                emit(ResticEvent.Diagnostic("connection lost"))
                emit(ResticEvent.Finished(1))
            }.readFiles()
            fail("truncation hid the failure")
        } catch (e: IllegalStateException) {
            assertEquals("connection lost", e.message)
        }
    }

    @Test
    fun `missing exit status is not accepted as success`() = runTest {
        try {
            flowOf(entry(0)).readFiles()
            fail("missing exit status was ignored")
        } catch (e: IllegalStateException) {
            assertEquals("File listing ended without an exit status", e.message)
        }
    }

    @Test
    fun `cleanup errors remain visible after a successful exit`() = runTest {
        val warning = "error while unlocking: B2 request failed"
        val messages = mutableListOf<String>()
        val result = flowOf(entry(0), ResticEvent.Diagnostic(warning), ResticEvent.Finished(0))
            .readFiles { messages += it }
        assertEquals(warning, result.warning)
        assertEquals(1, result.files.size)
        assertTrue(messages.contains(warning))
        assertTrue(messages.contains("listing exited with code 0"))
    }

    @Test
    fun `cancellation propagates`() = runTest {
        val cancelled = CancellationException("test cancellation")
        try {
            flow {
                emit(entry(0))
                throw cancelled
            }.readFiles()
            fail("cancellation was swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }
}

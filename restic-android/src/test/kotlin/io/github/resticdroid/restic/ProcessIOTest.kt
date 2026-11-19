package io.github.resticdroid.restic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class ProcessIOTest {
    @Test
    fun `oversized output is drained and cannot be mistaken for complete JSON`() {
        val reader = StringReader("abcdefgh")
        val output = reader.readBounded(4)
        assertEquals("abcd", output.text)
        assertEquals(-1, reader.read())
        assertTrue(output.truncated)
        assertThrows(IllegalStateException::class.java) { output.complete() }
        assertEquals("abcd", StringReader("abcd").readBounded(4).complete())
    }

    @Test(timeout = 15_000)
    fun `cancellation stops a process before joining blocked readers`() = runBlocking {
        val started = CompletableDeferred<Process>()
        val job = launch(Dispatchers.IO) {
            withProcess({ ProcessBuilder("sh", "-c", "printf ready; exec sleep 60").start() }) { process ->
                val out = async(Dispatchers.IO) {
                    repeat(5) { check(process.inputStream.read() >= 0) }
                    started.complete(process)
                    process.inputStream.bufferedReader().use { it.readBounded() }
                }
                val err = async(Dispatchers.IO) { process.errorStream.bufferedReader().use { it.readBounded() } }
                runInterruptible { process.waitFor() }
                out.await()
                err.await()
            }
        }
        val process = withTimeout(5_000) { started.await() }
        try {
            withTimeout(5_000) { job.cancelAndJoin() }
            assertFalse(process.isAlive)
        } finally {
            process.destroyForcibly()
        }
    }
}

package io.github.resticdroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {
    @Test
    fun `credentials in a repository url are removed`() {
        val line = "Fatal: unable to open repository at rest:https://alice:hunter2@backup.example.com:8000/"
        val safe = Redact.text(line)
        assertFalse("password must not survive into a log file", safe.contains("hunter2"))
        assertFalse(safe.contains("alice"))
        assertTrue("the rest of the message must still be useful", safe.contains("backup.example.com"))
    }

    @Test
    fun `a url without credentials is untouched`() {
        val line = "repository rest:https://backup.example.com:8000/ opened"
        assertEquals(line, Redact.text(line))
    }

    @Test
    fun `ordinary messages are untouched`() {
        val line = "done: snapshot 3fd9a1c2, 41 new files, 210.4 MiB added"
        assertEquals(line, Redact.text(line))
    }

    @Test
    fun `known secrets are removed by literal match`() {
        val safe = Redact.text("error: key K0010abcdefg rejected", listOf("K0010abcdefg"))
        assertFalse(safe.contains("K0010abcdefg"))
    }

    @Test
    fun `very short secrets are not matched, to avoid mangling every message`() {
        val safe = Redact.text("could not read /a/b/c", listOf("a"))
        assertEquals("could not read /a/b/c", safe)
    }
}

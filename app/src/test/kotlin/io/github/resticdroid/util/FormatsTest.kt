package io.github.resticdroid.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatsTest {
    @Test
    fun `bytes are scaled to a readable unit`() {
        assertEquals("0 B", Formats.bytes(0))
        assertEquals("512 B", Formats.bytes(512))
        assertEquals("1.0 KiB", Formats.bytes(1024))
        assertEquals("1.5 KiB", Formats.bytes(1536))
        assertEquals("1.0 MiB", Formats.bytes(1024L * 1024))
        assertEquals("2.5 GiB", Formats.bytes((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `large values drop the decimal, which would be noise`() {
        assertEquals("500 KiB", Formats.bytes(512_000))
    }

    @Test
    fun `an unknown size is a dash, not a zero`() {
        assertEquals("\u2014", Formats.bytes(null))
    }

    @Test
    fun `durations are written in the largest useful unit`() {
        assertEquals("45s", Formats.duration(45))
        assertEquals("2m 5s", Formats.duration(125))
        assertEquals("1h 30m", Formats.duration(5400))
        assertEquals("2d 3h", Formats.duration(183_600))
    }

    @Test
    fun `snapshot timestamps are trimmed to minutes`() {
        assertEquals("2026-08-29 09:00", Formats.snapshotTime("2026-08-29T09:00:00.123456+03:00"))
        assertEquals("2026-08-29", Formats.snapshotTime("2026-08-29"))
    }
}

class NextRunFormatTest {
    private val now = 1_756_460_000_000L

    @org.junit.Test
    fun `a run already due says so rather than showing a negative interval`() {
        org.junit.Assert.assertEquals("due now", Formats.nextRun(now - 1000, now))
        org.junit.Assert.assertEquals("due now", Formats.nextRun(now, now))
    }

    @org.junit.Test
    fun `minutes, hours and days each get their own shape`() {
        org.junit.Assert.assertTrue(Formats.nextRun(now + 30_000, now) == "in under a minute")
        org.junit.Assert.assertTrue(Formats.nextRun(now + 25 * 60_000, now).startsWith("in 25m"))
        org.junit.Assert.assertTrue(Formats.nextRun(now + 4 * 3_600_000, now).startsWith("in 4h"))
        org.junit.Assert.assertTrue(Formats.nextRun(now + 3 * 86_400_000, now).contains(" at "))
        org.junit.Assert.assertFalse(Formats.nextRun(now + 3 * 86_400_000, now).startsWith("in "))
    }

    @org.junit.Test
    fun `a clock time is always included for anything past the next minute`() {
        listOf(25 * 60_000L, 4 * 3_600_000L, 3 * 86_400_000L).forEach {
            org.junit.Assert.assertTrue(Formats.nextRun(now + it, now).contains(":"))
        }
    }
}

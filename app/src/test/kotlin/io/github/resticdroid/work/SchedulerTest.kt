package io.github.resticdroid.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

class SchedulerTest {
    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2026)
        set(Calendar.MONTH, Calendar.AUGUST)
        set(Calendar.DAY_OF_MONTH, 29)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `a time later today is scheduled today`() {
        val delay = Scheduler.delayUntil(hour = 23, minute = 0, now = at(20, 0))
        assertEquals(3, TimeUnit.MILLISECONDS.toHours(delay))
    }

    @Test
    fun `a time already past today rolls over to tomorrow`() {
        val delay = Scheduler.delayUntil(hour = 3, minute = 0, now = at(9, 0))
        assertTrue("delay must not be negative", delay > 0)
        assertEquals(18, TimeUnit.MILLISECONDS.toHours(delay))
    }

    @Test
    fun `the exact scheduled minute counts as already past`() {
        val delay = Scheduler.delayUntil(hour = 3, minute = 0, now = at(3, 0))
        assertEquals(24, TimeUnit.MILLISECONDS.toHours(delay))
    }

    @Test
    fun `work names are stable and namespaced per profile`() {
        assertEquals(Scheduler.workName("photos"), Scheduler.workName("photos"))
        assertTrue(Scheduler.workName("photos") != Scheduler.workName("documents"))
        assertTrue(Scheduler.workName("photos").contains("photos"))
    }
}

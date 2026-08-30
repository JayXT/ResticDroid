package io.github.resticdroid.config

import io.github.resticdroid.restic.ResticBackend
import io.github.resticdroid.restic.RetentionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test
    fun `a profile survives a write and read unchanged`() {
        val original = Profile(
            id = "photos",
            name = "Photos & video",
            enabled = false,
            paths = listOf("/storage/emulated/0/DCIM", "/storage/emulated/0/Pictures"),
            excludes = listOf("**/.thumbnails", "*.tmp"),
            destinationId = "backblaze",
            tags = listOf("phone"),
            schedule = Schedule.Daily(3, 30),
            conditions = Conditions(
                requireCharging = true,
                requireUnmetered = false,
                requireIdle = true,
                minBatteryPercent = 40,
                wifiSsid = listOf("home", "office wifi"),
            ),
            retention = RetentionPolicy(last = 5, daily = 7, monthly = 3),
            excludeCaches = false,
            includeApps = true,
        )

        val restored = Profile.fromIni("photos", Ini.parse(original.toIni()))
        assertEquals(original, restored)
    }

    @Test
    fun `a destination survives a write and read unchanged`() {
        val original = Destination(
            id = "backblaze",
            name = "Backblaze B2",
            backend = ResticBackend.B2,
            location = "my-bucket:pixel",
            options = listOf("--limit-upload", "2000"),
        )
        assertEquals(original, Destination.fromIni("backblaze", Ini.parse(original.toIni())))
    }

    @Test
    fun `a destination file contains no secrets`() {
        val text = Destination(
            id = "b2",
            name = "B2",
            backend = ResticBackend.B2,
            location = "bucket:path",
        ).toIni()

        listOf("password", "B2_ACCOUNT_KEY", "secret", "token").forEach {
            assertFalse("'$it' leaked into the config file", text.contains("$it ="))
        }
    }

    @Test
    fun `settings survive a write and read unchanged`() {
        val original = Settings(requireAuth = false, hostname = "pixel-9", logRetention = 50)
        assertEquals(original, Settings.fromIni(Ini.parse(original.toIni())))
    }

    @Test
    fun `unknown keys are preserved across a rewrite`() {
        val written = Profile(
            id = "p",
            name = "P",
            paths = listOf("/a"),
            destinationId = "d",
            unknown = listOf("future-thing" to "42"),
        ).toIni()

        val reread = Profile.fromIni("p", Ini.parse(written))
        assertEquals(listOf("future-thing" to "42"), reread.unknown)
        assertTrue(reread.toIni().contains("future-thing = 42"))
    }

    @Test
    fun `schedules round-trip through their text form`() {
        listOf(
            Schedule.Manual,
            Schedule.Interval(6),
            Schedule.Daily(3, 0),
            Schedule.Daily(23, 59),
        ).forEach { assertEquals(it, Schedule.parse(it.serialize())) }
    }

    @Test
    fun `an unparseable or out-of-range schedule falls back to manual`() {
        listOf(null, "", "sometimes", "daily 25:00", "daily 3:99", "every 0h x").forEach {
            assertEquals("input: $it", Schedule.Manual, Schedule.parse(it))
        }
    }

    @Test
    fun `an interval of zero is clamped to one hour`() {
        assertEquals(Schedule.Interval(1), Schedule.parse("every 0h"))
    }

    @Test
    fun `validate names what is missing`() {
        assertEquals(
            listOf("no path is configured", "no destination is configured"),
            Profile(id = "p", name = "P").validate(),
        )
        assertTrue(
            Profile(id = "p", name = "P", paths = listOf("/a"), destinationId = "d")
                .validate().isEmpty()
        )
    }

    @Test
    fun `a profile with only include-apps is valid`() {
        assertTrue(
            Profile(id = "p", name = "P", destinationId = "d", includeApps = true)
                .validate().isEmpty()
        )
    }

    @Test
    fun `battery percentage is clamped to a sane range`() {
        assertEquals(100, Profile.fromIni("p", Ini.parse("min-battery = 900")).conditions.minBatteryPercent)
        assertEquals(0, Profile.fromIni("p", Ini.parse("min-battery = -5")).conditions.minBatteryPercent)
    }

    @Test
    fun `an empty retention policy is written as no keep lines at all`() {
        val text = Profile(
            id = "p", name = "P", paths = listOf("/a"), destinationId = "d",
            retention = RetentionPolicy(),
        ).toIni()
        assertFalse(text.contains("keep-last"))
        assertNull(Profile.fromIni("p", Ini.parse(text)).retention.last)
        assertTrue(Profile.fromIni("p", Ini.parse(text)).retention.isEmpty())
    }

    @Test
    fun `an unknown backend degrades to local rather than crashing`() {
        val d = Destination.fromIni("x", Ini.parse("backend = quantum-tape\nlocation = /a"))
        assertEquals(ResticBackend.LOCAL, d.backend)
    }
}

class ProfileExposedFieldsTest {
    @org.junit.Test
    fun `tags and exclude files round-trip`() {
        val original = Profile(
            id = "data", name = "Data", paths = listOf("/a"), destinationId = "b2",
            tags = listOf("data", "manual"),
            excludeFiles = listOf("system-exclude.txt", "/storage/emulated/0/my-excludes.txt"),
            retention = io.github.resticdroid.restic.RetentionPolicy(
                hourly = 6, daily = 14, weekly = 8, monthly = 12, yearly = 3,
            ),
        )
        val restored = Profile.fromIni("data", Ini.parse(original.toIni()))
        org.junit.Assert.assertEquals(original, restored)
        org.junit.Assert.assertEquals(listOf("data", "manual"), restored.tags)
        org.junit.Assert.assertEquals(3, restored.retention.yearly)
        org.junit.Assert.assertEquals(6, restored.retention.hourly)
    }

    @org.junit.Test
    fun `every retention period the model supports survives a write and read`() {
        val full = io.github.resticdroid.restic.RetentionPolicy(
            last = 3, hourly = 24, daily = 14, weekly = 12, monthly = 12, yearly = 5, within = "30d",
        )
        val p = Profile(id = "p", name = "P", paths = listOf("/a"), destinationId = "d", retention = full)
        org.junit.Assert.assertEquals(full, Profile.fromIni("p", Ini.parse(p.toIni())).retention)
    }
}

class ScheduleParseTest {
    @Test
    fun `a number too large for an Int does not throw out of a config load`() {
        assertEquals(Schedule.Manual, Schedule.parse("every 99999999999h"))
        assertEquals(Schedule.Manual, Schedule.parse("daily 99:99"))
    }

    @Test
    fun `ordinary schedules still parse`() {
        assertEquals(Schedule.Interval(6), Schedule.parse("every 6h"))
        assertEquals(Schedule.Daily(3, 30), Schedule.parse("daily 03:30"))
        assertEquals(Schedule.Manual, Schedule.parse(null))
    }
}

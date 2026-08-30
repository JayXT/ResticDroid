package io.github.resticdroid.config

import androidx.test.core.app.ApplicationProvider
import io.github.resticdroid.restic.ResticBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ConfigStoreTest {
    private lateinit var store: ConfigStore

    @Before
    fun setUp() {
        ConfigPaths.root().deleteRecursively()
        store = ConfigStore(ApplicationProvider.getApplicationContext())
        ConfigPaths.ensure()
    }

    @Test
    fun `an empty directory loads as an empty configuration`() {
        val config = store.load()
        assertTrue(config.accessible)
        assertTrue(config.profiles.isEmpty())
        assertTrue(config.destinations.isEmpty())
    }

    @Test
    fun `a saved profile is readable from disk and comes back identical`() {
        val profile = Profile(
            id = "photos",
            name = "Photos",
            paths = listOf("/storage/emulated/0/DCIM"),
            destinationId = "b2",
            schedule = Schedule.Daily(3, 0),
        )
        store.save(profile)

        val file = File(ConfigPaths.profilesDir(), "photos.conf")
        assertTrue("expected the profile file to exist", file.isFile)
        assertTrue(file.readText().contains("path = /storage/emulated/0/DCIM"))

        assertEquals(profile, store.load().profile("photos"))
    }

    @Test
    fun `a profile written by hand is loaded without the app ever having saved it`() {
        File(ConfigPaths.profilesDir(), "handwritten.conf").writeText(
            """
            # written in a text editor over USB
            name = Hand written
            destination = nas
            path = /storage/emulated/0/Documents
            exclude = *.tmp
            schedule = every 12h
            require-charging = yes
            min-battery = 25
            """.trimIndent()
        )

        val profile = store.load().profile("handwritten")
        assertNotNull(profile)
        assertEquals("Hand written", profile!!.name)
        assertEquals(listOf("/storage/emulated/0/Documents"), profile.paths)
        assertEquals(Schedule.Interval(12), profile.schedule)
        assertTrue(profile.conditions.requireCharging)
        assertEquals(25, profile.conditions.minBatteryPercent)
    }

    @Test
    fun `deleting a file removes the profile`() {
        store.save(Profile(id = "gone", name = "Gone", paths = listOf("/a"), destinationId = "d"))
        assertNotNull(store.load().profile("gone"))

        File(ConfigPaths.profilesDir(), "gone.conf").delete()
        assertNull(store.load().profile("gone"))
    }

    @Test
    fun `files that are not dot-conf are ignored`() {
        File(ConfigPaths.profilesDir(), "notes.txt").writeText("name = Not a profile")
        File(ConfigPaths.profilesDir(), "photos.conf.bak").writeText("name = Backup copy")
        assertTrue(store.load().profiles.isEmpty())
    }

    @Test
    fun `destinations and profiles are kept in separate directories`() {
        store.save(
            Destination(id = "b2", name = "B2", backend = ResticBackend.B2, location = "bucket:p")
        )
        store.save(Profile(id = "p", name = "P", paths = listOf("/a"), destinationId = "b2"))

        val config = store.load()
        assertEquals(1, config.destinations.size)
        assertEquals(1, config.profiles.size)
        assertEquals("B2", config.destination("b2")?.name)
    }

    @Test
    fun `initialiseIfEmpty writes a settings file and a README, and does not clobber them`() {
        store.initialiseIfEmpty()
        val settings = ConfigPaths.settingsFile()
        val readme = File(ConfigPaths.root(), "README")
        assertTrue(settings.isFile)
        assertTrue(readme.isFile)

        settings.writeText("require-auth = no\n")
        store.initialiseIfEmpty()
        assertEquals("require-auth = no\n", settings.readText())
    }

    @Test
    fun `an atomic save leaves no temp file behind`() {
        store.save(Profile(id = "p", name = "P", paths = listOf("/a"), destinationId = "d"))
        val strays = ConfigPaths.profilesDir().listFiles { f -> f.name.startsWith(".") }
        assertTrue("temp files were left behind", strays.isNullOrEmpty())
    }

    @Test
    fun `a corrupt file does not stop the other profiles from loading`() {
        File(ConfigPaths.profilesDir(), "broken.conf").writeText(" not  ini at all")
        store.save(Profile(id = "good", name = "Good", paths = listOf("/a"), destinationId = "d"))

        val config = store.load()
        assertEquals(2, config.profiles.size)
        assertNotNull(config.profile("good"))
        assertTrue(config.profile("broken")!!.validate().isNotEmpty())
    }

    @Test
    fun `profiles are sorted by name, not by filename`() {
        store.save(Profile(id = "zzz", name = "Alpha", paths = listOf("/a"), destinationId = "d"))
        store.save(Profile(id = "aaa", name = "Zulu", paths = listOf("/a"), destinationId = "d"))
        assertEquals(listOf("Alpha", "Zulu"), store.load().profiles.map { it.name })
    }
}

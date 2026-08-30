package io.github.resticdroid.config

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ConfigPathsTest {
    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    @Test
    fun `slugify produces a safe filename stem`() {
        assertEquals("my-photos", ConfigPaths.slugify("My Photos!"))
        assertEquals("photos-video", ConfigPaths.slugify("Photos & video"))
        assertEquals("a-b", ConfigPaths.slugify("  a / b  "))
        assertEquals("profile", ConfigPaths.slugify("///"))
        assertEquals("2026-backup", ConfigPaths.slugify("2026 backup"))
    }

    @Test
    fun `slugify caps the length so no filesystem chokes`() {
        assertEquals(48, ConfigPaths.slugify("x".repeat(200)).length)
    }

    @Test
    fun `uniqueId avoids colliding with a file that is already there`() {
        val dir = temp.newFolder("profiles.d")
        assertEquals("photos", ConfigPaths.uniqueId(dir, "Photos"))

        File(dir, "photos.conf").writeText("")
        assertEquals("photos-2", ConfigPaths.uniqueId(dir, "Photos"))

        File(dir, "photos-2.conf").writeText("")
        assertEquals("photos-3", ConfigPaths.uniqueId(dir, "Photos"))
    }
}

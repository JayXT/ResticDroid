package io.github.resticdroid.config

import io.github.resticdroid.engine.profileTag
import io.github.resticdroid.engine.retentionScope
import io.github.resticdroid.engine.snapshotTags
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileTagTest {

    private fun profile(
        name: String = "Data",
        tags: List<String> = emptyList(),
        manual: List<String> = emptyList(),
    ) = Profile(
        id = "data", name = name, paths = listOf("/x"), destinationId = "d",
        tags = tags, manualTags = manual,
    )

    @Test
    fun `the profile name is the tag`() {
        assertEquals("Data", profileTag(profile()))
        assertEquals(listOf("Data"), snapshotTags(profile(), manual = false))
    }

    @Test
    fun `a comma would split into two tags, so it is dropped`() {
        assertEquals("Photos and video", profileTag(profile(name = "Photos, and video")))
    }

    @Test
    fun `a nameless profile falls back to its id`() {
        assertEquals("data", profileTag(profile(name = "   ")))
    }

    @Test
    fun `the manual tag applies only to a run you start`() {
        val p = profile(tags = listOf("phone"), manual = listOf("manual"))
        assertEquals(listOf("phone", "Data", "manual"), snapshotTags(p, manual = true))
        assertEquals(listOf("phone", "Data"), snapshotTags(p, manual = false))
    }

    @Test
    fun `retention is scoped to every scheduled tag, so a shared repository is safe`() {
        val p = profile(tags = listOf("Phone"), manual = listOf("manual"))
        // PC,Data snapshots carry "Data" but not "Phone", so they are out of scope.
        assertEquals("Phone,Data", retentionScope(p))
        // A run started by hand is in scope: it carries both, plus one more.
        assertEquals(listOf("Phone", "Data", "manual"), snapshotTags(p, manual = true))
    }

    @Test
    fun `a profile with no tags of its own is scoped by its name alone`() {
        assertEquals("Data", retentionScope(profile()))
    }

    @Test
    fun `grouping by tags round-trips`() {
        for (grouping in listOf(true, false)) {
            val p = profile().copy(groupByTags = grouping)
            assertEquals(grouping, Profile.fromIni("data", Ini.parse(p.toIni())).groupByTags)
        }
    }

    @Test
    fun `prune days round-trip, including unset and never`() {
        for (days in listOf(null, 0, 1, 30)) {
            val p = profile(tags = listOf("phone"), manual = listOf("manual")).copy(pruneDays = days)
            val back = Profile.fromIni("data", Ini.parse(p.toIni()))
            assertEquals(days, back.pruneDays)
            assertEquals(p.tags, back.tags)
            assertEquals(p.manualTags, back.manualTags)
        }
    }
}

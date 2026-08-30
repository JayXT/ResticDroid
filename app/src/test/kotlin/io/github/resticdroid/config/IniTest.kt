package io.github.resticdroid.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IniTest {
    @Test
    fun `parses key value pairs and ignores comments and blanks`() {
        val ini = Ini.parse(
            """
            # a comment
            ; also a comment

            name = Photos
            enabled = yes
            """.trimIndent()
        )
        assertEquals("Photos", ini.get("name"))
        assertTrue(ini.bool("enabled"))
    }

    @Test
    fun `keys are case insensitive`() {
        val ini = Ini.parse("Min-Battery = 30")
        assertEquals(30, ini.int("min-battery", 0))
        assertEquals(30, ini.int("MIN-BATTERY", 0))
    }

    @Test
    fun `a repeated key builds a list, in file order`() {
        val ini = Ini.parse(
            """
            path = /a
            path = /b
            path = /c
            """.trimIndent()
        )
        assertEquals(listOf("/a", "/b", "/c"), ini.all("path"))
    }

    @Test
    fun `a single-valued read of a repeated key takes the last, as Unix config does`() {
        assertEquals("/b", Ini.parse("path = /a\npath = /b").get("path"))
    }

    @Test
    fun `a hash inside a value is part of the value`() {
        val ini = Ini.parse("exclude = **/#recycle\nname = C# projects")
        assertEquals("**/#recycle", ini.get("exclude"))
        assertEquals("C# projects", ini.get("name"))
    }

    @Test
    fun `an equals sign inside a value survives`() {
        assertEquals("a=b=c", Ini.parse("option = a=b=c").get("option"))
    }

    @Test
    fun `quotes preserve leading and trailing space`() {
        assertEquals("  padded  ", Ini.parse("""name = "  padded  """" ).get("name"))
    }

    @Test
    fun `boolean spellings`() {
        val ini = Ini.parse("a = yes\nb = true\nc = on\nd = 1\ne = no\nf = off\ng = nonsense")
        listOf("a", "b", "c", "d").forEach { assertTrue(it, ini.bool(it)) }
        listOf("e", "f").forEach { assertFalse(it, ini.bool(it, default = true)) }
        assertTrue("unknown values fall back to the default", ini.bool("g", default = true))
    }

    @Test
    fun `malformed lines are skipped rather than aborting the file`() {
        val ini = Ini.parse("name = Photos\nthis line has no equals sign\npath = /a")
        assertEquals("Photos", ini.get("name"))
        assertEquals(listOf("/a"), ini.all("path"))
    }

    @Test
    fun `a missing key reads as null or the default`() {
        val ini = Ini.parse("")
        assertNull(ini.get("nope"))
        assertEquals("fallback", ini.string("nope", "fallback"))
        assertEquals(7, ini.int("nope", 7))
        assertNull(ini.intOrNull("nope"))
    }

    @Test
    fun `keysExcept reports what this version does not understand`() {
        val ini = Ini.parse("name = x\nfuture-option = 1")
        assertEquals(listOf("future-option" to "1"), ini.keysExcept(setOf("name")))
    }

    @Test
    fun `writer emits repeated keys and skips empty values`() {
        val text = IniWriter()
            .comment("header")
            .put("name", "Photos")
            .put("blank", "")
            .put("nothing", null as String?)
            .putAll("path", listOf("/a", "", "/b"))
            .put("enabled", true)
            .put("count", 3)
            .build()

        val ini = Ini.parse(text)
        assertEquals("Photos", ini.get("name"))
        assertNull(ini.get("blank"))
        assertNull(ini.get("nothing"))
        assertEquals(listOf("/a", "/b"), ini.all("path"))
        assertTrue(ini.bool("enabled"))
        assertEquals(3, ini.int("count", 0))
        assertTrue(text.startsWith("# header"))
    }
}

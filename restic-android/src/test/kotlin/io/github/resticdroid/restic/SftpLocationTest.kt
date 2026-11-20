package io.github.resticdroid.restic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SftpLocationTest {
    @Test fun pathsRoundTrip() {
        for (path in listOf("phone", "/home/phone", ".", "/", "日本語/phone #1", "100%")) {
            val endpoint = SftpLocation("2001:db8::1", 23, "user@example", path)
            assertEquals(endpoint, SftpLocation.parse(endpoint.uri()))
            assertEquals("[2001:db8::1]:23", endpoint.address)
        }
        assertEquals("sftp://u@host:22//home", SftpLocation("host", 22, "u", "/home").uri())
    }

    @Test fun pinBelongsToHostAndPort() {
        val endpoint = SftpLocation.parse("sftp://u@host/phone")
        val fingerprint = "SHA256:" + "a".repeat(43)
        assertTrue(endpoint.accepts("host:22 $fingerprint"))
        assertFalse(endpoint.accepts("host:22 SHA256:abc"))
        assertFalse(endpoint.accepts("host:23 $fingerprint"))
        assertFalse(endpoint.accepts("other:22 $fingerprint"))
        assertFalse(endpoint.accepts(null))
    }
}

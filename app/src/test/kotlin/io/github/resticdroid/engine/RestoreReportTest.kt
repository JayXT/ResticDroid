package io.github.resticdroid.engine

import io.github.resticdroid.restic.ResticItemError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

class RestoreReportTest {
    private fun error(item: String, message: String) = ResticItemError(item, message)

    @Test
    fun `a restore with nothing to report says so plainly`() {
        assertEquals("Restored in place", RestoreReport.summarise("/", emptyList()))
        assertEquals("Restored into /sdcard/x", RestoreReport.summarise("/sdcard/x", emptyList()))
    }

    @Test
    fun `ownership on a synthetic root is not worth mentioning`() {
        val problems = listOf(
            error("/storage/emulated", "lchown /storage/emulated: no such file or directory"),
            error("/storage", "chmod /storage: operation not permitted"),
        )
        assertTrue(problems.all(RestoreReport::isBenign))
        assertEquals("Restored in place", RestoreReport.summarise("/", problems))
    }

    @Test
    fun `a file that could not be written is reported`() {
        val problems = listOf(
            error("/storage/emulated", "lchown /storage/emulated: no such file or directory"),
            error("/sdcard/Photos/a.jpg", "open /sdcard/Photos/a.jpg: permission denied"),
        )
        val summary = RestoreReport.summarise("/", problems)
        assertEquals(
            "Restored in place, but 1 item could not be written: " +
                "/sdcard/Photos/a.jpg: open /sdcard/Photos/a.jpg: permission denied",
            summary,
        )
    }

    @Test
    fun `losing metadata on a real file still counts`() {
        assertFalse(RestoreReport.isBenign(error("/sdcard/a.txt", "chmod /sdcard/a.txt: read-only file system")))
    }

    @Test
    fun `long lists are cut short`() {
        val problems = (1..5).map { error("/sdcard/$it", "open /sdcard/$it: permission denied") }
        assertTrue(RestoreReport.summarise("/", problems).startsWith("Restored in place, but 5 items"))
        assertTrue(RestoreReport.summarise("/", problems).endsWith(" …"))
    }
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
class ProviderErrorTest {
    private fun destination(backend: io.github.resticdroid.restic.ResticBackend) =
        io.github.resticdroid.config.Destination(
            id = "d", name = "TestBucket", backend = backend, location = "bucket:path",
        )

    @Test
    fun `every backend that authenticates explains a refusal the same way`() {
        val authenticating = io.github.resticdroid.restic.ResticBackend.entries
            .filter { it.credentials.isNotEmpty() }
        assertTrue("expected more than one authenticating backend", authenticating.size > 1)

        authenticating.forEach { backend ->
            val said = ProviderError.explain(
                destination(backend),
                "unable to open repository at b2:bucket: b2.NewClient: b2_authorize_account: 401: ",
            )
            assertEquals(
                "${backend.displayName} rejected the account credentials for 'TestBucket'.",
                said,
            )
        }
    }

    @Test
    fun `a local folder has no credentials to refuse, so restic keeps the word`() {
        val message = "Fatal: unable to open config file: 401 whatever"
        assertEquals(
            message,
            ProviderError.explain(destination(io.github.resticdroid.restic.ResticBackend.LOCAL), message),
        )
    }

    @Test
    fun `anything that is not a refusal is passed through untouched`() {
        val d = destination(io.github.resticdroid.restic.ResticBackend.B2)
        listOf(
            "Fatal: wrong password or no key found",
            "repository does not exist",
            "read /storage/emulated/0/DCIM/4013.jpg: permission denied",
            "uploaded 14015 packs",
        ).forEach { assertEquals(it, ProviderError.explain(d, it)) }
    }
}

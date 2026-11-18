package io.github.resticdroid.work

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31])
class ForegroundTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `successful promotion permits execution`() = runTest {
        assertTrue(startForeground(context, RunLog.open("test")) { })
    }

    @Test
    fun `a platform restriction does not permit execution`() = runTest {
        Notifications.ensureChannels(context)
        assertFalse(startForeground(context, RunLog.open("test")) {
            throw ForegroundServiceStartNotAllowedException("test restriction")
        })
    }

    @Test
    fun `cancellation escapes unchanged`() = runTest {
        val expected = CancellationException("cancelled")
        try {
            startForeground(context, RunLog.open("test")) { throw expected }
            fail("cancellation was swallowed")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }

    @Test
    fun `configuration errors are not treated as transient restrictions`() = runTest {
        val expected = SecurityException("missing service permission")
        try {
            startForeground(context, RunLog.open("test")) { throw expected }
            fail("configuration error was swallowed")
        } catch (actual: SecurityException) {
            assertSame(expected, actual)
        }
    }
}

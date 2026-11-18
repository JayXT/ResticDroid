package io.github.resticdroid.work

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.resticdroid.config.Conditions
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class DeviceConditionsTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun denyLocation() {
        shadowOf(app).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
    }

    @Test
    fun `profiles without an SSID restriction need no location access`() {
        assertEquals(DeviceConditions.Verdict.Satisfied, DeviceConditions.check(app, Conditions()))
    }

    @Test
    fun `missing precise location is not reported as disconnected Wi-Fi`() {
        val verdict = DeviceConditions.check(app, Conditions(wifiSsid = listOf("Home")))
        assertEquals(
            DeviceConditions.Verdict.Unsatisfied("Wi-Fi name requires precise location permission"),
            verdict,
        )
    }

    @Test
    fun `foreground location alone cannot enable a scheduled SSID check`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val verdict = DeviceConditions.check(app, Conditions(wifiSsid = listOf("Home")))
        assertEquals(
            DeviceConditions.Verdict.Unsatisfied("Wi-Fi name requires location permission set to Allow all the time"),
            verdict,
        )
    }

    @Test
    @Config(sdk = [28])
    fun `Android 9 does not require background location permission`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val verdict = DeviceConditions.check(app, Conditions(wifiSsid = listOf("Home")))
        assertFalse((verdict as? DeviceConditions.Verdict.Unsatisfied)?.reason.orEmpty().contains("Allow all the time"))
    }
}

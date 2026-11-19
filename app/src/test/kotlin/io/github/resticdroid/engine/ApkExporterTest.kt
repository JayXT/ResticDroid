package io.github.resticdroid.engine

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class ApkExporterTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `a missing APK cannot silently count as an exported app`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = ApplicationInfo().apply {
            packageName = "test.missing.apk"
            sourceDir = File(temp.root, "missing.apk").absolutePath
        }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = info.packageName
            applicationInfo = info
        })
        try {
            ApkExporter(app).export(File(temp.root, "staging"))
            fail("missing APK was ignored")
        } catch (_: IOException) {
            // Propagated to the worker; BackupEngine.onCompletion clears staging.
        }
    }
}

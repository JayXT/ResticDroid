package io.github.resticdroid.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ApkExporter(private val context: Context) {
    data class Exported(val directory: File, val count: Int)

    suspend fun export(into: File): Exported {
        check(into.deleteRecursively()) { "Cannot clear APK staging directory" }
        check(into.mkdirs()) { "Cannot create APK staging directory" }

        var count = 0

        for (app in userApplications()) {
            currentCoroutineContext().ensureActive()
            val sources = buildList {
                app.sourceDir?.let { add(File(it)) }
                app.splitSourceDirs?.forEach { add(File(it)) }
            }
            check(sources.isNotEmpty()) { "No APK found for ${app.packageName}" }

            val label = runCatching {
                context.packageManager.getApplicationLabel(app).toString()
            }.getOrDefault(app.packageName)

            val target = File(into, sanitise(app.packageName)).apply { mkdirs() }
            File(target, "app.txt").writeText(
                buildString {
                    appendLine("package = ${app.packageName}")
                    appendLine("label = $label")
                    appendLine("version = ${versionOf(app.packageName)}")
                }
            )

            sources.forEachIndexed { index, source ->
                val name = if (index == 0) "base.apk" else sanitise(source.name)
                val destination = File(target, name)
                currentCoroutineContext().ensureActive()
                source.copyTo(destination, overwrite = true)
            }
            count++
        }
        return Exported(into, count)
    }

    private fun userApplications(): List<ApplicationInfo> =
        context.packageManager.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .filter { it.packageName != context.packageName }
            .sortedBy { it.packageName }

    private fun versionOf(packageName: String): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun sanitise(name: String) = name.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }
        .joinToString("")
}

package io.github.resticdroid.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File

class ApkExporter(private val context: Context) {
    data class Exported(val directory: File, val count: Int, val bytes: Long)

    fun export(into: File): Exported {
        into.mkdirs()
        into.listFiles()?.forEach { it.deleteRecursively() }

        var count = 0
        var bytes = 0L

        for (app in userApplications()) {
            val sources = buildList {
                app.sourceDir?.let { add(File(it)) }
                app.splitSourceDirs?.forEach { add(File(it)) }
            }.filter { it.isFile && it.canRead() }
            if (sources.isEmpty()) continue

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
                runCatching { source.copyTo(destination, overwrite = true) }
                    .onSuccess { bytes += it.length() }
            }
            count++
        }
        return Exported(into, count, bytes)
    }

    private fun userApplications(): List<ApplicationInfo> = runCatching {
        context.packageManager.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .filter { it.packageName != context.packageName }
            .sortedBy { it.packageName }
    }.getOrDefault(emptyList())

    private fun versionOf(packageName: String): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun sanitise(name: String) = name.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }
        .joinToString("")
}

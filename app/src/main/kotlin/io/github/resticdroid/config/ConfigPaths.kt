package io.github.resticdroid.config

import android.content.Context
import android.os.Environment
import java.io.File

object ConfigPaths {
    private const val DIRECTORY_NAME: String = "ResticDroid"
    private const val SETTINGS_FILE: String = "resticdroid.conf"
    const val CONFIG_SUFFIX: String = ".conf"

    fun root(): File = File(Environment.getExternalStorageDirectory(), DIRECTORY_NAME)

    fun settingsFile(): File = File(root(), SETTINGS_FILE)
    fun destinationsDir(): File = File(root(), "destinations.d")
    fun profilesDir(): File = File(root(), "profiles.d")
    fun excludesDir(): File = File(root(), "exclude.d")
    fun logDir(): File = File(root(), "log")

    fun stagingDir(context: Context): File = File(context.filesDir, "staging")

    fun credentialDir(context: Context): File = File(context.filesDir, "credentials")

    fun stagingDir(context: Context, profileId: String): File =
        File(stagingDir(context), slugify(profileId, "run"))

    fun ensure() {
        listOf(root(), destinationsDir(), profilesDir(), excludesDir(), logDir())
            .forEach { it.mkdirs() }
    }

    fun slugify(name: String, fallback: String = "profile"): String {
        val slug = name.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .take(48)
        return slug.ifEmpty { fallback }
    }

    fun uniqueId(directory: File, name: String, fallback: String = "profile"): String {
        val base = slugify(name, fallback)
        if (!File(directory, base + CONFIG_SUFFIX).exists()) return base
        var n = 2
        while (File(directory, "$base-$n$CONFIG_SUFFIX").exists()) n++
        return "$base-$n"
    }
}

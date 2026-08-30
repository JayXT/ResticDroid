package io.github.resticdroid.engine

import android.os.Environment
import java.io.File

data class DirectoryScheme(
    val name: String,
    val paths: List<String>,
    val excludes: List<String> = emptyList(),
    val includeApps: Boolean = false,
) {
    companion object {
        private val shared: String get() = Environment.getExternalStorageDirectory().absolutePath

        private fun shared(vararg names: String) = names.map { "$shared/$it" }

        val CommonNoise: List<String> = listOf(
            "**/.thumbnails",
            "**/.trashed-*",
            "**/*.tmp",
            "**/*.part",
            "**/cache",
            "**/Cache",
            "**/.cache",
            "**/lost+found",
        )

        fun all(): List<DirectoryScheme> = listOf(media(), documents(), apps(), everything())

        fun media(): DirectoryScheme = DirectoryScheme(
            name = "Photos and media",
            paths = shared("DCIM", "Pictures", "Movies", "Music"),
            excludes = CommonNoise,
        )

        fun documents(): DirectoryScheme = DirectoryScheme(
            name = "Documents and downloads",
            paths = shared("Documents", "Download", "Books"),
            excludes = CommonNoise,
        )

        fun apps(): DirectoryScheme = DirectoryScheme(
            name = "Installed apps",
            paths = emptyList(),
            includeApps = true,
        )

        fun everything(): DirectoryScheme = DirectoryScheme(
            name = "All shared storage",
            paths = listOf(shared),
            excludes = CommonNoise + listOf(
                "$shared/Android/data",
                "$shared/Android/obb",
            ),
        )

        fun existingPaths(scheme: DirectoryScheme): List<String> =
            scheme.paths.filter { File(it).isDirectory }
    }
}

package io.github.resticdroid.engine

import io.github.resticdroid.config.Config
import io.github.resticdroid.config.ConfigPaths
import io.github.resticdroid.config.Destination
import io.github.resticdroid.restic.ResticBackend
import java.io.File

object ExclusionPolicy {
    // Every local repository is excluded, not only the one being written to:
    // otherwise a profile writing to repo A would swallow repo B.
    fun implicitExcludes(config: Config, stagingDir: File?): List<String> {
        val excludes = LinkedHashSet<String>()

        config.destinations
            .filter { it.backend == ResticBackend.LOCAL }
            .mapNotNull { normalisedRepositoryPath(it) }
            .forEach { excludes += it }

        excludes += ConfigPaths.logDir().absolutePath

        stagingDir?.let { excludes += it.absolutePath }

        return excludes.toList()
    }

    /** Canonical paths of every local repository, whether or not it is in use. */
    fun repositoryPaths(config: Config): List<String> =
        config.destinations
            .filter { it.backend == ResticBackend.LOCAL }
            .mapNotNull { normalisedRepositoryPath(it) }

    private fun normalisedRepositoryPath(destination: Destination): String? {
        val raw = destination.location.trim()
        if (raw.isEmpty()) return null
        val file = File(raw)
        if (!file.isAbsolute) return null
        return runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    }

    fun isRecursive(path: String, repositoryPath: String): Boolean {
        val backup = canonical(path)
        val repository = canonical(repositoryPath)
        return repository == backup || repository.startsWith("$backup/")
    }

    private fun canonical(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(File(path).absolutePath).trimEnd('/')
}

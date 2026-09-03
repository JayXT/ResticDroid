package io.github.resticdroid.engine

import android.content.Context
import android.os.Build
import io.github.resticdroid.config.Config
import io.github.resticdroid.config.ConfigPaths
import io.github.resticdroid.config.Profile
import io.github.resticdroid.restic.Restic
import io.github.resticdroid.restic.ResticCommand
import io.github.resticdroid.restic.ResticEvent
import io.github.resticdroid.restic.ResticExit
import io.github.resticdroid.restic.ResticRepository
import io.github.resticdroid.secret.SecretStore
import io.github.resticdroid.util.Redact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.Dispatchers
import java.io.File

sealed interface RunEvent {
    data class Started(val profileId: String, val paths: List<String>) : RunEvent
    data class Preparing(val message: String) : RunEvent
    data class Progress(
        val fraction: Float,
        val filesDone: Long?,
        val totalFiles: Long?,
        val bytesDone: Long?,
        val totalBytes: Long?,
        val secondsRemaining: Long?,
        val currentFile: String?,
    ) : RunEvent
    data class Warning(val message: String) : RunEvent
    data class Finished(
        val exitCode: Int,
        val snapshotId: String?,
        val filesNew: Long,
        val bytesAdded: Long,
        val warnings: Int,
    ) : RunEvent
    data class Failed(val message: String) : RunEvent
}

/**
 * The tag every snapshot of a profile carries, and what `forget` scopes to.
 *
 * The profile's name, so `restic snapshots --tag Photos` works from a desktop
 * without anyone having to learn an internal id. restic splits a tag value on
 * commas, so a name containing one would silently become two tags; the comma
 * is dropped rather than the name mangled. Renaming a profile changes the tag,
 * which means retention stops recognising its older snapshots - the price of a
 * tag that reads like something a person chose.
 */
internal fun profileTag(profile: Profile): String =
    profile.name.replace(",", "").trim().ifEmpty { profile.id }

internal fun snapshotTags(profile: Profile, manual: Boolean): List<String> =
    (profile.tags + profileTag(profile) + if (manual) profile.manualTags else emptyList())
        .map { it.replace(",", "").trim() }
        .filter { it.isNotEmpty() }
        .distinct()

class BackupEngine(
    private val context: Context,
    private val restic: Restic,
    private val secrets: SecretStore,
) {
    fun run(config: Config, profile: Profile, manual: Boolean = false): Flow<RunEvent> = flow {
        val problems = profile.validate()
        if (problems.isNotEmpty()) {
            emit(RunEvent.Failed(problems.joinToString("; ")))
            return@flow
        }

        val destination = config.destination(profile.destinationId)
        if (destination == null) {
            emit(RunEvent.Failed("destination '${profile.destinationId}' does not exist"))
            return@flow
        }

        val opened = Repositories.openOrFail(
            destination, secrets, ConfigPaths.credentialDir(context),
        )
        val repository = opened.getOrElse {
            emit(RunEvent.Failed(it.message ?: "could not open '${destination.name}'"))
            return@flow
        }

        // restic 0.19 stopped honouring --exclude for a path passed to backup
        // explicitly (upstream change #5767), so a profile pointed straight at a
        // local repository would copy it into itself. Excludes still cover a
        // repository nested inside a backed-up directory; this covers the rest.
        val repositories = ExclusionPolicy.repositoryPaths(config)
        val selfReferential = profile.paths.filter { path ->
            repositories.any { ExclusionPolicy.isRecursive(path, it) && ExclusionPolicy.isRecursive(it, path) }
        }
        selfReferential.forEach { emit(RunEvent.Warning("skipped $it: it is a repository")) }

        val paths = profile.paths
            .filter { it !in selfReferential }
            .filter { File(it).exists() }
            .toMutableList()
        val staging = ConfigPaths.stagingDir(context, profile.id)
        if (profile.includeApps) {
            emit(RunEvent.Preparing("Exporting installed apps"))
            val staged = ApkExporter(context).export(File(staging, "apps"))
            if (staged.count > 0) paths += staged.directory.absolutePath
        }
        if (paths.isEmpty()) {
            emit(RunEvent.Failed("none of the configured paths exist on this device"))
            return@flow
        }

        val excludes = (
            profile.excludes +
                ExclusionPolicy.implicitExcludes(
                    config,
                    ConfigPaths.stagingDir(context).takeIf { !profile.includeApps },
                )
            ).distinct()

        emit(RunEvent.Started(profile.id, paths))

        val excludeFiles = profile.excludeFiles.mapNotNull { entry ->
            val file = if (entry.startsWith("/")) File(entry) else File(ConfigPaths.excludesDir(), entry)
            file.takeIf { it.isFile }?.absolutePath
        }

        val command = ResticCommand.backup(
            paths = paths,
            excludes = excludes,
            excludeFiles = excludeFiles,
            excludeCaches = profile.excludeCaches,
            tags = snapshotTags(profile, manual),
            host = hostname(config),
        )

        val secrets = buildList {
            add(repository.password)
            addAll(repository.env.values)
        }
        fun safe(text: String) = Redact.text(text, secrets)

        var warnings = 0
        var snapshotId: String? = null
        var filesNew = 0L
        var bytesAdded = 0L
        var lastDiagnostic: String? = null

        restic.stream(repository, command).collect { event ->
            when (event) {
                is ResticEvent.Progress -> emit(
                    RunEvent.Progress(
                        fraction = event.percentDone.toFloat().coerceIn(0f, 1f),
                        filesDone = event.filesDone,
                        totalFiles = event.totalFiles,
                        bytesDone = event.bytesDone,
                        totalBytes = event.totalBytes,
                        secondsRemaining = event.secondsRemaining,
                        currentFile = event.currentFiles.firstOrNull(),
                    )
                )
                is ResticEvent.Summary -> {
                    snapshotId = event.snapshotId
                    filesNew = event.filesNew
                    bytesAdded = event.dataAdded
                }
                is ResticEvent.ItemError -> {
                    warnings++
                    emit(RunEvent.Warning(safe(listOfNotNull(event.item, event.message).joinToString(": "))))
                }
                is ResticEvent.Diagnostic -> lastDiagnostic = safe(event.line)
                is ResticEvent.Finished -> {
                    if (ResticExit.isSuccess(event.exitCode)) {
                        if (!profile.retention.isEmpty()) {
                            emit(RunEvent.Preparing("Applying retention policy"))
                            forget(repository, profile)
                        }
                        emit(
                            RunEvent.Finished(
                                exitCode = event.exitCode,
                                snapshotId = snapshotId,
                                filesNew = filesNew,
                                bytesAdded = bytesAdded,
                                warnings = warnings,
                            )
                        )
                    } else {
                        emit(
                            RunEvent.Failed(
                                ProviderError.explain(
                                    destination,
                                    lastDiagnostic?.takeIf { it.isNotBlank() }
                                        ?: ResticExit.describe(event.exitCode),
                                )
                            )
                        )
                    }
                }
                is ResticEvent.Output, is ResticEvent.Json -> Unit
            }
        }
    }
        // Exported APKs are copies, and a phone's worth of them is gigabytes.
        // They exist for the length of one run and no longer.
        .onCompletion { ConfigPaths.stagingDir(context, profile.id).deleteRecursively() }
        .flowOn(Dispatchers.IO)

    // A failed forget is not a failed backup - the snapshot is safely written -
    // but it must be said out loud, and it must not stamp the marker: a silent
    // failure would suppress the next prune for another whole period.
    private suspend fun FlowCollector<RunEvent>.forget(repository: ResticRepository, profile: Profile) {
        val prune = pruneDue(profile)
        val result = runCatching {
            restic.execute(
                repository,
                ResticCommand.forget(
                    policy = profile.retention,
                    tags = listOf(profileTag(profile)),
                    prune = prune,
                ),
            )
        }
        val outcome = result.getOrNull()
        when {
            outcome == null -> emit(RunEvent.Warning("retention failed: ${result.exceptionOrNull()?.message}"))
            !outcome.isSuccess -> emit(RunEvent.Warning("retention failed: ${outcome.humanError()}"))
            prune -> markPruned(profile)
        }
    }

    // Retention is applied after every backup; pruning - the part that rewrites
    // pack files - only when the profile says it is due. The marker file's
    // mtime is the record: no schema, and it disappears with the app's data.
    private fun pruneDue(profile: Profile): Boolean {
        val days = profile.pruneDays ?: return true
        if (days == 0) return false
        val last = pruneMarker(profile).takeIf { it.exists() }?.lastModified() ?: 0L
        return System.currentTimeMillis() - last >= days * 86_400_000L
    }

    private fun markPruned(profile: Profile) {
        val marker = pruneMarker(profile)
        marker.parentFile?.mkdirs()
        if (!marker.exists()) marker.createNewFile()
        marker.setLastModified(System.currentTimeMillis())
    }

    private fun pruneMarker(profile: Profile): File =
        File(File(context.filesDir, "prune"), ConfigPaths.slugify(profile.id))

    private fun hostname(config: Config): String =
        config.settings.hostname.ifBlank { Build.MODEL.replace(' ', '-') }
}

package io.github.resticdroid.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.resticdroid.R
import io.github.resticdroid.config.ConfigStore
import io.github.resticdroid.engine.BackupEngine
import io.github.resticdroid.engine.RunEvent
import io.github.resticdroid.restic.Restic
import io.github.resticdroid.secret.SecretStore
import io.github.resticdroid.util.Formats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class BackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    // Expedited work asks for this before doWork runs, and CoroutineWorker's
    // default throws. Below Android 12 that kills the request outright.
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(
            inputData.getString(KEY_PROFILE_ID).orEmpty(),
            applicationContext.getString(R.string.status_starting),
            null,
        )

    override suspend fun doWork(): Result {
        val profileId = inputData.getString(KEY_PROFILE_ID) ?: return Result.failure()
        val manual = inputData.getBoolean(KEY_MANUAL, false)

        val store = ConfigStore(applicationContext)
        val config = store.load()
        if (!config.accessible) {
            if (!manual) return Result.retry()
            Notifications.ensureChannels(applicationContext)
            Notifications.result(
                applicationContext,
                applicationContext.getString(R.string.notification_failed, profileId),
                "Storage is not accessible. Grant All files access to ResticDroid and try again.",
                failed = true,
            )
            return Result.failure()
        }

        val profile = config.profile(profileId) ?: return Result.failure()
        if (!profile.enabled && !manual) return Result.success()

        if (!manual) {
            when (val verdict = DeviceConditions.check(applicationContext, profile.conditions)) {
                is DeviceConditions.Verdict.Unsatisfied -> {
                    RunLog.open(profileId).line("skipped: ${verdict.reason}")
                    return Result.retry()
                }
                DeviceConditions.Verdict.Satisfied -> Unit
            }
        }

        Notifications.ensureChannels(applicationContext)
        val log = RunLog.open(profileId)
        log.line("starting profile '${profile.name}' (${if (manual) "manual" else "scheduled"})")

        setForeground(foregroundInfo(profile.name, applicationContext.getString(R.string.status_starting), null))

        val engine = BackupEngine(
            context = applicationContext,
            restic = Restic.from(applicationContext),
            secrets = SecretStore(applicationContext),
        )

        var outcome: Result = Result.failure()
        var warnings = 0

        try {
            engine.run(config, profile, manual).collect { event ->
            when (event) {
                is RunEvent.Started -> {
                    log.line("backing up: ${event.paths.joinToString(", ")}")
                    report(profileId, Progress(0f, applicationContext.getString(R.string.status_scanning)))
                }

                is RunEvent.Preparing -> {
                    log.line(event.message)
                    report(profileId, Progress(null, event.message))
                    setForeground(foregroundInfo(profile.name, event.message, null))
                }

                is RunEvent.Progress -> {
                    val percent = (event.fraction * 100).toInt()
                    val text = buildString {
                        if (event.filesDone != null && event.totalFiles != null) {
                            append("${event.filesDone} / ${event.totalFiles} files")
                        }
                        if (event.bytesDone != null && event.totalBytes != null) {
                            if (isNotEmpty()) append(" · ")
                            append(Formats.bytes(event.bytesDone))
                            append(" / ")
                            append(Formats.bytes(event.totalBytes))
                        }
                        event.secondsRemaining?.takeIf { it > 0 }?.let {
                            if (isNotEmpty()) append(" · ")
                            append(Formats.duration(it))
                            append(" left")
                        }
                    }.ifEmpty { applicationContext.getString(R.string.status_working) }

                    report(profileId, Progress(event.fraction, text))
                    setForeground(foregroundInfo(profile.name, text, percent))
                }

                is RunEvent.Warning -> {
                    warnings++
                    log.line("warning: ${event.message}")
                }

                is RunEvent.Finished -> {
                    log.line(
                        "done: snapshot ${event.snapshotId ?: "?"}, " +
                            "${event.filesNew} new files, ${Formats.bytes(event.bytesAdded)} added" +
                            if (event.warnings > 0) ", ${event.warnings} warnings" else ""
                    )
                    Notifications.result(
                        applicationContext,
                        applicationContext.getString(R.string.notification_done, profile.name),
                        buildString {
                            append(Formats.bytes(event.bytesAdded))
                            append(" added")
                            event.snapshotId?.let { append(" · snapshot ${it.take(8)}") }
                            if (event.warnings > 0) {
                                append("\n${event.warnings} files could not be read")
                            }
                        },
                        failed = false,
                    )
                    Runs.changed(profile.destinationId)
                    outcome = Result.success()
                }

                is RunEvent.Failed -> {
                    log.line("failed: ${event.message}")
                    Notifications.result(
                        applicationContext,
                        applicationContext.getString(R.string.notification_failed, profile.name),
                        event.message,
                        failed = true,
                    )
                    outcome = if (manual) Result.failure() else Result.retry()
                }
            }
        }

        } finally {
            progress.update { it - profileId }
        }

        RunLog.prune(config.settings.logRetention)
        log.line("log kept at ${log.path()}")
        return outcome
    }

    private fun foregroundInfo(profileName: String, text: String, percent: Int?): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val notification = Notifications.progress(applicationContext, profileName, text, percent, cancel)

        val notificationId = Notifications.progressId(id.toString())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun report(profileId: String, value: Progress) {
        progress.update { it + (profileId to value) }
    }

    data class Progress(val fraction: Float?, val text: String)

    companion object {
        const val KEY_PROFILE_ID: String = "profile"
        const val KEY_MANUAL: String = "manual"

        // Keyed by profile: WorkManager runs several backups at once, and a
        // single slot would show the last one to speak on every card.
        val progress: MutableStateFlow<Map<String, Progress>> = MutableStateFlow(emptyMap())
    }
}

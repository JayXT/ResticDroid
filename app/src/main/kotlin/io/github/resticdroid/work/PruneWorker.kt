package io.github.resticdroid.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.resticdroid.R
import io.github.resticdroid.config.ConfigPaths
import io.github.resticdroid.config.ConfigStore
import io.github.resticdroid.engine.ProviderError
import io.github.resticdroid.engine.RepositoryWork
import io.github.resticdroid.engine.Repositories
import io.github.resticdroid.restic.Restic
import io.github.resticdroid.restic.ResticCommand
import io.github.resticdroid.restic.lockCleanupWarning
import io.github.resticdroid.secret.SecretStore
import io.github.resticdroid.util.Redact
import kotlinx.coroutines.CancellationException

/**
 * `restic prune`, on demand.
 *
 * A worker rather than a coroutine in the UI because pruning rewrites pack
 * files and can run for many minutes: it has to survive the screen going off
 * and the app being swiped away, which is exactly what a foreground worker is.
 */
class PruneWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(pendingName())

    override suspend fun doWork(): Result {
        Notifications.ensureChannels(applicationContext)
        val destinationId = inputData.getString(KEY_DESTINATION_ID) ?: return Result.failure()
        val config = ConfigStore(applicationContext).load()

        if (!config.accessible) {
            Notifications.result(
                applicationContext,
                applicationContext.getString(R.string.notification_failed, destinationId),
                "Storage is not accessible. Grant All files access to ResticDroid and try again.",
                failed = true,
            )
            return Result.failure()
        }

        val destination = config.destination(destinationId) ?: run {
            Notifications.result(
                applicationContext,
                applicationContext.getString(R.string.notification_failed, destinationId),
                "That repository is no longer configured.",
                failed = true,
            )
            return Result.failure()
        }

        val log = RunLog.open("prune-$destinationId")
        return try {
            val promoted = startForeground(applicationContext, log) {
                setForeground(foregroundInfo(destination.name))
            }
            if (!promoted) return Result.failure()
            log.line("pruning '${destination.name}'")

            RepositoryWork.run {
                val opened = Repositories.openOrFail(
                    destination, SecretStore(applicationContext), ConfigPaths.credentialDir(applicationContext),
                )
                val repository = opened.getOrElse {
                    log.line("failed: ${it.message}")
                    Notifications.result(applicationContext, destination.name, it.message ?: "Failed", failed = true)
                    return@run Result.failure()
                }

                val result = Restic.from(applicationContext).execute(repository, ResticCommand.prune())
                val warning = lockCleanupWarning(result.stderr)?.let {
                    Redact.text(it, repository.env.values + repository.password)
                }
                warning?.let { log.line("warning: $it") }

                Runs.changed(destinationId)
                if (result.isSuccess) {
                    log.line("done")
                    Notifications.result(
                        applicationContext, destination.name,
                        warning?.let { "Prune finished with a warning: $it" } ?: "Prune finished",
                        failed = false,
                    )
                    Result.success()
                } else {
                    val message = ProviderError.explain(destination, result.humanError())
                    log.line("failed: $message")
                    Notifications.result(applicationContext, destination.name, message, failed = true)
                    Result.failure()
                }
            }
        } catch (e: CancellationException) {
            log.line("cancelled")
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Prune failed"
            log.line("failed: $message")
            Notifications.result(applicationContext, destination.name, message, failed = true)
            Result.failure()
        } finally {
            RunLog.prune(config.settings.logRetention)
        }
    }

    private fun pendingName(): String =
        inputData.getString(KEY_DESTINATION_ID).orEmpty()

    private fun foregroundInfo(name: String): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = Notifications.progress(
            context = applicationContext,
            profileName = name,
            text = "Reclaiming space",
            percent = null,
            cancelIntent = cancel,
            title = applicationContext.getString(R.string.notification_pruning, name),
        )
        val notificationId = Notifications.progressId(id.toString())
        // DATA_SYNC service type available starting with Android 10 (API 29 / Q)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_DESTINATION_ID: String = "destination"
    }
}

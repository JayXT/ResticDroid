package io.github.resticdroid.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.resticdroid.config.Conditions
import io.github.resticdroid.config.Config
import io.github.resticdroid.config.Profile
import io.github.resticdroid.config.Schedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val PERIODIC_PREFIX = "backup-periodic:"
    private const val ONESHOT_PREFIX = "backup-now:"
    const val TAG_ALL: String = "resticdroid-backup"

    private const val MANUAL_TAG_PREFIX = "manual:"
    private const val PERIODIC_TAG_PREFIX = "periodic:"

    private fun manualTag(profileId: String) = MANUAL_TAG_PREFIX + profileId
    private fun periodicTag(profileId: String) = PERIODIC_TAG_PREFIX + profileId

    private const val SCHEDULE_TAG_PREFIX = "schedule:"

    fun workName(profileId: String): String = PERIODIC_PREFIX + profileId

    fun sync(context: Context, config: Config) {
        val manager = WorkManager.getInstance(context)
        val wanted = config.profiles.filter { it.enabled && it.schedule != Schedule.Manual }

        config.profiles.filterNot { it in wanted }.forEach {
            manager.cancelUniqueWork(workName(it.id))
        }
        wanted.forEach { schedule(manager, it) }
    }

    // Constraint edits must not cancel a running backup. Only an explicit
    // schedule change resets its timing. Old requests without a tag migrate
    // through UPDATE, preserving their current run and enqueue time.
    internal fun updatePolicy(tags: Set<String>, schedule: Schedule): ExistingPeriodicWorkPolicy {
        val previous = tags.firstOrNull { it.startsWith(SCHEDULE_TAG_PREFIX) }
        return if (previous != null && previous != SCHEDULE_TAG_PREFIX + schedule.serialize()) {
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
        } else {
            ExistingPeriodicWorkPolicy.UPDATE
        }
    }

    private fun schedule(manager: WorkManager, profile: Profile) {
        val interval = when (val s = profile.schedule) {
            is Schedule.Interval -> s.hours.toLong()
            is Schedule.Daily -> 24L
            Schedule.Manual -> return
        }

        val existing = manager.getWorkInfosForUniqueWork(workName(profile.id)).get()
            .firstOrNull { !it.state.isFinished }
        val policy = updatePolicy(existing?.tags.orEmpty(), profile.schedule)

        val request = PeriodicWorkRequestBuilder<BackupWorker>(interval, TimeUnit.HOURS)
            .setConstraints(constraints(profile.conditions))
            .setInputData(workDataOf(BackupWorker.KEY_PROFILE_ID to profile.id))
            .addTag(TAG_ALL)
            .addTag(periodicTag(profile.id))
            .addTag(SCHEDULE_TAG_PREFIX + profile.schedule.serialize())
            .apply {
                (profile.schedule as? Schedule.Daily)?.let {
                    // UPDATE keeps the old enqueue time, so keep its delay too.
                    val delay = if (existing != null && policy == ExistingPeriodicWorkPolicy.UPDATE) {
                        existing.initialDelayMillis
                    } else {
                        delayUntil(it.hour, it.minute)
                    }
                    setInitialDelay(delay, TimeUnit.MILLISECONDS)
                }
                setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            }
            .build()

        manager.enqueueUniquePeriodicWork(
            workName(profile.id),
            policy,
            request,
        )
    }

    fun runNow(context: Context, profileId: String) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(
                workDataOf(
                    BackupWorker.KEY_PROFILE_ID to profileId,
                    BackupWorker.KEY_MANUAL to true,
                )
            )
            .addTag(TAG_ALL)
            .addTag(manualTag(profileId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONESHOT_PREFIX + profileId, ExistingWorkPolicy.KEEP, request)
    }

    fun pruneNow(context: Context, destinationId: String) {
        val request = OneTimeWorkRequestBuilder<PruneWorker>()
            .setInputData(workDataOf(PruneWorker.KEY_DESTINATION_ID to destinationId))
            .addTag(TAG_ALL)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("prune-" + destinationId, ExistingWorkPolicy.KEEP, request)
    }

    fun nextRuns(context: Context): Flow<Map<String, Long>> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG_ALL).map { infos ->
            buildMap {
                infos.forEach { info ->
                    if (info.state.isFinished) return@forEach
                    val profileId = info.tags
                        .firstOrNull { it.startsWith(PERIODIC_TAG_PREFIX) }
                        ?.removePrefix(PERIODIC_TAG_PREFIX)
                        ?: return@forEach
                    val next = info.nextScheduleTimeMillis
                    if (next != Long.MAX_VALUE) {
                        merge(profileId, next, ::minOf)
                    }
                }
            }
        }

    fun stopRun(context: Context, profileId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(manualTag(profileId))
    }

    fun cancel(context: Context, profileId: String) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(workName(profileId))
        manager.cancelAllWorkByTag(periodicTag(profileId))
        manager.cancelAllWorkByTag(manualTag(profileId))
    }

    private fun constraints(conditions: Conditions): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (conditions.requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED
        )
        .setRequiresCharging(conditions.requireCharging)
        .setRequiresDeviceIdle(conditions.requireIdle)
        .setRequiresBatteryNotLow(conditions.minBatteryPercent > 0)
        .setRequiresStorageNotLow(true)
        .build()

    internal fun delayUntil(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val target = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now
    }
}

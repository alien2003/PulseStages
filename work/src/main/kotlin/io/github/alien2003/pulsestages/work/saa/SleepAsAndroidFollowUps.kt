package io.github.alien2003.pulsestages.work.saa

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.work.schedule.WorkRequests
import javax.inject.Inject

/**
 * The work that asks Sleep as Android to sync once the recent nights are complete (SleepAsAndroidSyncWorker): the
 * owed request, its follow-up and every later check of a request that waits. Kept apart from WorkScheduler on purpose:
 * WorkManagerWorkScheduler depends on BackfillRunner, which depends on [SleepAsAndroidSyncRequests], which uses this.
 */
interface SleepAsAndroidFollowUps {
    /**
     * Schedules the work [delayMs] from now with the constraints of [schedule], replacing a pending one. Not for the
     * work itself: replacing cancels a running work too ([scheduleFromWork]).
     */
    fun schedule(
        delayMs: Long,
        schedule: ScheduleSettings,
    )

    /**
     * Schedules the work [delayMs] from now with the constraints of [schedule] from inside the running work: it is
     * appended to the running one, which goes on undisturbed, and starts its delay when that one has finished.
     */
    fun scheduleFromWork(
        delayMs: Long,
        schedule: ScheduleSettings,
    )

    /** Cancels a pending work (nothing happens when none is pending). */
    fun cancel()
}

/**
 * [SleepAsAndroidFollowUps] over WorkManager: the one-time unique work [WorkRequests.Names.SLEEP_AS_ANDROID_FOLLOW_UP],
 * enqueued with [ExistingWorkPolicy.REPLACE] by a run, so at most one is pending, and with
 * [ExistingWorkPolicy.APPEND_OR_REPLACE] by the work itself (REPLACE would cancel it while it still stores its state).
 * WorkManager is looked up lazily, like in WorkManagerWorkScheduler.
 */
class WorkManagerSleepAsAndroidFollowUps
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : SleepAsAndroidFollowUps {
        private val workManager: WorkManager get() = WorkManager.getInstance(context)

        override fun schedule(
            delayMs: Long,
            schedule: ScheduleSettings,
        ) {
            enqueue(ExistingWorkPolicy.REPLACE, delayMs, schedule)
        }

        override fun scheduleFromWork(
            delayMs: Long,
            schedule: ScheduleSettings,
        ) {
            enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE, delayMs, schedule)
        }

        override fun cancel() {
            workManager.cancelUniqueWork(WorkRequests.Names.SLEEP_AS_ANDROID_FOLLOW_UP)
        }

        private fun enqueue(
            policy: ExistingWorkPolicy,
            delayMs: Long,
            schedule: ScheduleSettings,
        ) {
            workManager.enqueueUniqueWork(
                WorkRequests.Names.SLEEP_AS_ANDROID_FOLLOW_UP,
                policy,
                WorkRequests.sleepAsAndroidFollowUp(schedule, delayMs),
            )
        }
    }

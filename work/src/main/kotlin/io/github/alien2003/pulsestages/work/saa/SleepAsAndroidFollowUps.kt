package io.github.alien2003.pulsestages.work.saa

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.work.schedule.WorkRequests
import javax.inject.Inject

/**
 * The delayed follow-up sync request to Sleep as Android as a work (SleepAsAndroidSyncWorker). Kept apart from
 * WorkScheduler on purpose: WorkManagerWorkScheduler depends on BackfillRunner, which depends on
 * [SleepAsAndroidSyncRequests], which uses this.
 */
interface SleepAsAndroidFollowUps {
    /** Schedules the follow-up [delayMs] from now with the constraints of [schedule], replacing a pending one. */
    fun schedule(
        delayMs: Long,
        schedule: ScheduleSettings,
    )

    /** Cancels a pending follow-up (nothing happens when none is pending). */
    fun cancel()
}

/**
 * [SleepAsAndroidFollowUps] over WorkManager: the one-time unique work [WorkRequests.Names.SLEEP_AS_ANDROID_FOLLOW_UP],
 * enqueued with [ExistingWorkPolicy.REPLACE], so at most one is pending. WorkManager is looked up lazily, like in
 * WorkManagerWorkScheduler.
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
            workManager.enqueueUniqueWork(
                WorkRequests.Names.SLEEP_AS_ANDROID_FOLLOW_UP,
                ExistingWorkPolicy.REPLACE,
                WorkRequests.sleepAsAndroidFollowUp(schedule, delayMs),
            )
        }

        override fun cancel() {
            workManager.cancelUniqueWork(WorkRequests.Names.SLEEP_AS_ANDROID_FOLLOW_UP)
        }
    }

package io.github.alien2003.pulsestages.work.saa

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.work.schedule.WorkRequests
import io.github.alien2003.pulsestages.work.worker.SleepAsAndroidSyncWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [WorkManagerSleepAsAndroidFollowUps] against work-testing's test WorkManager (Robolectric): one delayed unique work
 * with the schedule's constraints, replaced by a later follow-up and removed by a cancel. Its worker never runs here
 * (the delay holds it; the factory would refuse it anyway).
 */
@RunWith(RobolectricTestRunner::class)
class WorkManagerSleepAsAndroidFollowUpsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private val followUps = WorkManagerSleepAsAndroidFollowUps(context)

    @Before
    fun initWorkManager() {
        val refusing =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = throw AssertionError("$workerClassName must not run before its delay")
            }
        val configuration =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(refusing)
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    @After
    fun stopWork() {
        workManager.cancelAllWork()
    }

    private fun infos(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(WorkRequests.Names.SLEEP_AS_ANDROID_FOLLOW_UP).get()

    @Test
    fun aFollowUpIsOneDelayedUniqueWorkWithTheScheduleConstraints() {
        followUps.schedule(SleepAsAndroidSyncRequests.FOLLOW_UP_DELAY_MS, ScheduleSettings())

        val info = infos().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(600_000L, info.initialDelayMillis)
        assertTrue(info.constraints.requiresBatteryNotLow())
        assertTrue(info.tags.toString(), WorkRequests.TAG_ALL in info.tags)
        assertTrue(info.tags.toString(), WorkRequests.Tags.SLEEP_AS_ANDROID in info.tags)
        assertTrue(info.tags.toString(), SleepAsAndroidSyncWorker::class.java.name in info.tags)
    }

    @Test
    fun aLaterFollowUpReplacesThePendingOneAndACancelRemovesIt() {
        followUps.schedule(SleepAsAndroidSyncRequests.FOLLOW_UP_DELAY_MS, ScheduleSettings())
        val first = infos().single().id

        followUps.schedule(SleepAsAndroidSyncRequests.FOLLOW_UP_DELAY_MS, ScheduleSettings(runOnlyWhenNotLowBattery = false))

        val pending = infos().filter { !it.state.isFinished }
        assertEquals("at most one pending", 1, pending.size)
        assertTrue("replaced, not kept", pending.single().id != first)
        assertFalse(pending.single().constraints.requiresBatteryNotLow())

        followUps.cancel()

        assertTrue(infos().all { it.state == WorkInfo.State.CANCELLED })
        followUps.cancel()
    }
}

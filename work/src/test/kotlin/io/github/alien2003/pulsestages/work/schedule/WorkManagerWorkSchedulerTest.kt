package io.github.alien2003.pulsestages.work.schedule

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.pipeline.BackfillOutcome
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.local.db.BackfillStateEntity
import io.github.alien2003.pulsestages.data.local.db.BackfillStatus
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.work.run.BackfillRunner
import io.github.alien2003.pulsestages.work.run.OutcomeKind
import io.github.alien2003.pulsestages.work.run.RetryLimits
import io.github.alien2003.pulsestages.work.run.RunSummary
import io.github.alien2003.pulsestages.work.run.WorkOutcome
import io.github.alien2003.pulsestages.work.run.WorkResults
import io.github.alien2003.pulsestages.work.saa.sleepAsAndroidRequests
import io.github.alien2003.pulsestages.work.worker.EstimateNewNightsWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * [WorkManagerWorkScheduler] against work-testing's test WorkManager (Robolectric): unique works, schedule updates
 * in place, the daily pinning, cancellation of the backfill and the status flows. The workers themselves are replaced
 * by stand-ins whose behaviour each test chooses.
 */
@RunWith(RobolectricTestRunner::class)
class WorkManagerWorkSchedulerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    /** What the stand-in workers do when WorkManager runs them. */
    private enum class Mode { PARK, RETRY, SUCCEED }

    @Volatile
    private var mode = Mode.PARK

    private val summary = RunSummary(found = 2, estimated = 2)

    /**
     * What parked stand-ins wait for. Held by the test so that a parked worker stays reachable: a coroutine suspended
     * in `awaitCancellation()` is referenced by nobody, and once it is garbage collected WorkManager fails the work
     * (`FutureGarbageCollectedException`).
     */
    private val parked = CompletableDeferred<Unit>()

    private inner class StandIn(
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result =
            when (mode) {
                Mode.PARK -> {
                    parked.await()
                    Result.success()
                }

                Mode.RETRY -> {
                    Result.retry()
                }

                Mode.SUCCEED -> {
                    WorkResults.toResult(WorkOutcome.Completed(summary), runAttemptCount, RetryLimits())
                }
            }
    }

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    @Before
    fun initWorkManager() {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = StandIn(appContext, workerParameters)
            }
        val configuration =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(factory)
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    @After
    fun stopWork() {
        workManager.cancelAllWork()
    }

    private class World(
        val harness: PipelineHarness,
        val scheduler: WorkManagerWorkScheduler,
    )

    private fun TestScope.world(): World {
        val harness =
            PipelineHarness(
                backgroundScope,
                folder.newFolder(),
                StandardTestDispatcher(testScheduler),
                startMs = System.currentTimeMillis(),
            )
        val scheduler =
            WorkManagerWorkScheduler(
                context,
                harness.settings,
                BackfillRunner(harness.backfill, harness.sleepAsAndroidRequests(), harness.logger),
                harness.clock,
                { zone },
                harness.logger,
            )
        return World(harness, scheduler)
    }

    private suspend fun info(name: String): WorkInfo = workManager.getWorkInfosForUniqueWorkFlow(name).first().single()

    @Test
    fun ensureScheduledEnqueuesThePeriodicCheckAndTheDailyRun() =
        runTest {
            val world = world()
            val schedule = ScheduleSettings()

            world.scheduler.ensureScheduled(schedule)

            val periodic = info(WorkRequests.Names.PERIODIC)
            assertEquals(WorkInfo.State.ENQUEUED, periodic.state)
            assertEquals(TimeUnit.HOURS.toMillis(1), periodic.periodicityInfo?.repeatIntervalMillis)
            assertTrue(periodic.constraints.requiresBatteryNotLow())
            assertTrue(WorkRequests.periodicTag(schedule) in periodic.tags)
            val daily = info(WorkRequests.Names.DAILY)
            assertEquals(TimeUnit.HOURS.toMillis(24), daily.periodicityInfo?.repeatIntervalMillis)
            val nextDaily = DailySchedule.nextRunMs(world.harness.clock.millis(), schedule.dailyTimeMinutes, zone)
            assertEquals(nextDaily, daily.nextScheduleTimeMillis)
            assertEquals(
                nextDaily,
                world.scheduler
                    .status(WorkJob.ESTIMATE_DAILY)
                    .first()
                    .nextScheduleTimeMillis,
            )
            assertEquals(
                JobState.ENQUEUED,
                world.scheduler
                    .status(WorkJob.ESTIMATE_PERIODIC)
                    .first()
                    .state,
            )
        }

    @Test
    fun unchangedSettingsLeaveTheWorksAloneAndChangedSettingsUpdateThemInPlace() =
        runTest {
            val world = world()
            val schedule = ScheduleSettings()
            world.scheduler.ensureScheduled(schedule)
            val periodic = info(WorkRequests.Names.PERIODIC)
            val daily = info(WorkRequests.Names.DAILY)

            world.scheduler.ensureScheduled(schedule)
            assertEquals(periodic.generation, info(WorkRequests.Names.PERIODIC).generation)
            assertEquals(daily.generation, info(WorkRequests.Names.DAILY).generation)

            world.scheduler.ensureScheduled(schedule.copy(periodicMinutes = 180, runOnlyWhenNotLowBattery = false))
            val updated = info(WorkRequests.Names.PERIODIC)
            assertEquals(periodic.id, updated.id)
            assertEquals(periodic.generation + 1, updated.generation)
            assertEquals(TimeUnit.HOURS.toMillis(3), updated.periodicityInfo?.repeatIntervalMillis)
            assertFalse(updated.constraints.requiresBatteryNotLow())

            world.scheduler.ensureScheduled(
                schedule.copy(periodicMinutes = 180, runOnlyWhenNotLowBattery = false, dailyTimeMinutes = 21 * 60),
            )
            val evening = info(WorkRequests.Names.DAILY)
            assertEquals(daily.id, evening.id)
            assertEquals(DailySchedule.nextRunMs(world.harness.clock.millis(), 21 * 60, zone), evening.nextScheduleTimeMillis)
        }

    @Test
    fun aDriftedDailyRunIsPinnedToTheConfiguredTimeAgain() =
        runTest {
            val world = world()
            val schedule = ScheduleSettings()
            val now = world.harness.clock.millis()
            // An enqueued daily run with the right schedule tag but two hours late.
            workManager.enqueueUniquePeriodicWork(
                WorkRequests.Names.DAILY,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<EstimateNewNightsWorker>(24, TimeUnit.HOURS)
                    .setNextScheduleTimeOverride(DailySchedule.nextRunMs(now, schedule.dailyTimeMinutes, zone) + TimeUnit.HOURS.toMillis(2))
                    .addTag(WorkRequests.dailyTag(schedule, zone))
                    .build(),
            )

            world.scheduler.ensureScheduled(schedule)

            assertEquals(
                DailySchedule.nextRunMs(now, schedule.dailyTimeMinutes, zone),
                info(WorkRequests.Names.DAILY).nextScheduleTimeMillis,
            )
        }

    @Test
    fun oneTimeWorksAreUniqueAndReportTheirOutput() =
        runTest {
            val world = world()
            world.scheduler.estimateNow()
            val first = info(WorkRequests.Names.NOW)
            assertEquals(WorkInfo.State.RUNNING, first.state)
            world.scheduler.estimateNow()
            assertEquals("KEEP: no second run while one is running", first.id, info(WorkRequests.Names.NOW).id)
            workManager.cancelAllWork()

            mode = Mode.SUCCEED
            world.scheduler.recomputeAll()
            world.scheduler.recomputeNight("ps:night")

            val all = world.scheduler.status(WorkJob.RECOMPUTE_ALL).first { it.state == JobState.SUCCEEDED }
            assertEquals(OutcomeKind.COMPLETED, all.lastOutput?.kind)
            assertEquals(summary, all.lastOutput?.summary)
            assertEquals(
                OutcomeKind.COMPLETED,
                world.scheduler
                    .nightRecomputeStatus("ps:night")
                    .first {
                        it.state == JobState.SUCCEEDED
                    }.lastOutput
                    ?.kind,
            )
            assertEquals(
                JobState.NOT_SCHEDULED,
                world.scheduler
                    .nightRecomputeStatus("ps:other")
                    .first()
                    .state,
            )
        }

    @Test
    fun cancellingAWaitingBackfillRemovesItAndPersistsCancelled() =
        runTest {
            val world = world()
            val now = world.harness.clock.millis()
            world.harness.ledger.backfillDao.save(
                BackfillStateEntity(
                    status = BackfillStatus.WAITING_RETRY,
                    rangeStartMs = 0,
                    rangeEndMs = now,
                    cursorMs = 0,
                    startedAt = now,
                    updatedAt = now,
                ),
            )
            mode = Mode.RETRY
            world.scheduler.startBackfill()
            assertEquals(WorkInfo.State.ENQUEUED, info(WorkRequests.Names.BACKFILL).state)
            assertEquals("backing off after a retry", 1, info(WorkRequests.Names.BACKFILL).runAttemptCount)

            world.scheduler.cancelBackfill()

            assertEquals(WorkInfo.State.CANCELLED, info(WorkRequests.Names.BACKFILL).state)
            assertEquals(
                BackfillStatus.CANCELLED,
                world.harness.ledger.backfillDao
                    .get()
                    ?.status,
            )
        }

    @Test
    fun cancellingARunningBackfillWhoseWorkerHasNotReachedTheEngineRemovesIt() =
        runTest {
            val world = world()
            val now = world.harness.clock.millis()
            world.harness.ledger.backfillDao.save(
                BackfillStateEntity(
                    status = BackfillStatus.RUNNING,
                    rangeStartMs = 0,
                    rangeEndMs = now,
                    cursorMs = 0,
                    startedAt = now,
                    updatedAt = now,
                ),
            )
            world.scheduler.startBackfill()
            // WorkManager runs the work, but its worker is still starting (e.g. going foreground).
            assertEquals(WorkInfo.State.RUNNING, info(WorkRequests.Names.BACKFILL).state)

            world.scheduler.cancelBackfill()

            assertEquals(
                BackfillStatus.CANCELLED,
                world.harness.ledger.backfillDao
                    .get()
                    ?.status,
            )
            assertEquals("no night is in progress: removed", WorkInfo.State.CANCELLED, info(WorkRequests.Names.BACKFILL).state)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingABackfillInsideItsWindowLoopLetsItStopBetweenNights() =
        runTest {
            val world = world()
            val harness = world.harness
            (3 downTo 1).forEach { harness.repository.addNight(OHEALTH, harness.clock.millis() - it * TimeUnit.DAYS.toMillis(1)) }
            val release = CompletableDeferred<Unit>()
            harness.estimator.beforeEstimate = { release.await() }
            world.scheduler.startBackfill()
            val engine = async { harness.backfill.run(inBackground = false) }
            runCurrent()
            assertTrue("the engine is inside a night", harness.backfill.isRunning)

            world.scheduler.cancelBackfill()

            assertEquals("not interrupted in the middle of a night", WorkInfo.State.RUNNING, info(WorkRequests.Names.BACKFILL).state)
            release.complete(Unit)
            assertEquals(BackfillOutcome.CANCELLED, engine.await().outcome)
            assertEquals("the night in progress is finished", 1, harness.repository.ownSessions().size)
        }

    @Test
    fun estimateNowAndRecomputeNightReplaceAPendingRetryButKeepARunningRun() =
        runTest {
            val world = world()
            mode = Mode.RETRY
            world.scheduler.estimateNow()
            world.scheduler.recomputeNight("ps:night")
            val waiting = info(WorkRequests.Names.NOW)
            val waitingNight = info(WorkRequests.Names.recomputeNight("ps:night"))
            assertEquals(WorkInfo.State.ENQUEUED, waiting.state)
            assertEquals("in the backoff of a retry", 1, waiting.runAttemptCount)

            mode = Mode.PARK
            world.scheduler.estimateNow()
            world.scheduler.recomputeNight("ps:night")

            val replaced = info(WorkRequests.Names.NOW)
            assertNotEquals("a new request runs now", waiting.id, replaced.id)
            assertEquals(WorkInfo.State.RUNNING, replaced.state)
            assertNotEquals(waitingNight.id, info(WorkRequests.Names.recomputeNight("ps:night")).id)
            world.scheduler.estimateNow()
            assertEquals("a running request is kept", replaced.id, info(WorkRequests.Names.NOW).id)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun keepScheduledFollowsTheScheduleSettings() =
        runTest {
            val world = world()
            val job = backgroundScope.launch { world.scheduler.keepScheduled() }
            runCurrent()
            assertEquals(TimeUnit.HOURS.toMillis(1), info(WorkRequests.Names.PERIODIC).periodicityInfo?.repeatIntervalMillis)

            world.harness.settings.set { it.copy(schedule = it.schedule.copy(periodicMinutes = 360)) }
            runCurrent()

            assertEquals(TimeUnit.HOURS.toMillis(6), info(WorkRequests.Names.PERIODIC).periodicityInfo?.repeatIntervalMillis)

            world.harness.settings.set { it.copy(schedule = it.schedule.copy(periodicMinutes = 15)) }
            runCurrent()

            assertEquals(TimeUnit.MINUTES.toMillis(15), info(WorkRequests.Names.PERIODIC).periodicityInfo?.repeatIntervalMillis)
            job.cancel()
        }

    @Test
    fun aPeriodicWorkWithTheHourBasedTagOfAnOlderVersionIsUpdatedInPlaceOnce() =
        runTest {
            val world = world()
            val schedule = ScheduleSettings()
            // As enqueued by v0.3.0: the same hourly period, under the tag of the hour-based setting.
            workManager.enqueueUniquePeriodicWork(
                WorkRequests.Names.PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<EstimateNewNightsWorker>(1, TimeUnit.HOURS)
                    .addTag("schedule:periodic:1h:batteryNotLow=true")
                    .build(),
            )
            val old = info(WorkRequests.Names.PERIODIC)

            world.scheduler.ensureScheduled(schedule)

            val updated = info(WorkRequests.Names.PERIODIC)
            assertEquals(old.id, updated.id)
            assertEquals(old.generation + 1, updated.generation)
            assertTrue(WorkRequests.periodicTag(schedule) in updated.tags)
            assertEquals(TimeUnit.HOURS.toMillis(1), updated.periodicityInfo?.repeatIntervalMillis)
            world.scheduler.ensureScheduled(schedule)
            assertEquals("matching works are left alone afterwards", updated.generation, info(WorkRequests.Names.PERIODIC).generation)
        }
}

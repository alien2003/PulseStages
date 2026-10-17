package io.github.alien2003.pulsestages.work.worker

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.util.concurrent.ListenableFuture
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeScope
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.BackfillStatus
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.work.notify.WorkNotifications
import io.github.alien2003.pulsestages.work.run.BackfillRunner
import io.github.alien2003.pulsestages.work.run.EstimateNewNightsRunner
import io.github.alien2003.pulsestages.work.run.OutcomeKind
import io.github.alien2003.pulsestages.work.run.RecomputeRequest
import io.github.alien2003.pulsestages.work.run.RecomputeRunner
import io.github.alien2003.pulsestages.work.run.RetryLimits
import io.github.alien2003.pulsestages.work.run.RunReport
import io.github.alien2003.pulsestages.work.run.WorkResults
import io.github.alien2003.pulsestages.work.saa.RecordingFollowUps
import io.github.alien2003.pulsestages.work.saa.RecordingSyncRequestSender
import io.github.alien2003.pulsestages.work.saa.requestSleepAsAndroidSync
import io.github.alien2003.pulsestages.work.saa.sleepAsAndroidRequests
import io.github.alien2003.pulsestages.work.schedule.WorkJob
import io.github.alien2003.pulsestages.work.schedule.WorkScheduler
import io.github.alien2003.pulsestages.work.schedule.WorkStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The CoroutineWorker adapters built with work-testing's [TestListenableWorkerBuilder] (Robolectric, JUnit 4 via the
 * Vintage engine) over the data-hc fixtures: the ListenableWorker.Result of every outcome, progress and foreground
 * notifications, and that doWork never throws.
 */
@RunWith(RobolectricTestRunner::class)
class WorkerAdaptersTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = RuntimeEnvironment.getApplication()

    /** Records the scheduler calls a worker makes. */
    private class RecordingScheduler : WorkScheduler {
        var realigned = 0
        var failRealign = false

        override suspend fun ensureScheduled(schedule: ScheduleSettings) = Unit

        override suspend fun keepScheduled() = Unit

        override suspend fun realignDaily() {
            realigned++
            check(!failRealign) { "WorkManager is gone" }
        }

        override suspend fun estimateNow() = Unit

        override fun startBackfill() = Unit

        override suspend fun cancelBackfill() = Unit

        override fun recomputeAll(scope: RecomputeScope) = Unit

        override suspend fun recomputeNight(clientRecordId: String) = Unit

        override fun status(job: WorkJob): Flow<WorkStatus> = emptyFlow()

        override fun nightRecomputeStatus(clientRecordId: String): Flow<WorkStatus> = emptyFlow()
    }

    /** One environment: the pipeline fixtures plus recorders of what the workers report. */
    private inner class World(
        scope: TestScope,
    ) {
        val harness =
            PipelineHarness(scope.backgroundScope, folder.newFolder(), StandardTestDispatcher(scope.testScheduler), startMs = NOON)
        val scheduler = RecordingScheduler()
        val progress = mutableListOf<Data>()
        val foreground = mutableListOf<ForegroundInfo>()
        var foregroundAllowed = true
        val notifications = WorkNotifications(context)

        /** The app is in the foreground for Health Connect unless the fake says otherwise (as in the harness). */
        val support = WorkerSupport(harness.foregroundState, RetryLimits(), notifications, harness.logger)

        /** What Sleep as Android is sent, through the requests every runner and the follow-up worker share. */
        val sleepAsAndroid = RecordingSyncRequestSender()
        val followUps = RecordingFollowUps()
        val requests = harness.sleepAsAndroidRequests(sleepAsAndroid, followUps)

        private val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker? {
                    val h = harness
                    return when (workerClassName) {
                        EstimateNewNightsWorker::class.java.name -> {
                            EstimateNewNightsWorker(
                                appContext,
                                workerParameters,
                                EstimateNewNightsRunner(h.sync, h.personalization, requests, h.logger),
                                support,
                                scheduler,
                            )
                        }

                        BackfillWorker::class.java.name -> {
                            BackfillWorker(appContext, workerParameters, BackfillRunner(h.backfill, requests, h.logger), support)
                        }

                        RecomputeWorker::class.java.name -> {
                            RecomputeWorker(
                                appContext,
                                workerParameters,
                                RecomputeRunner(h.recomputer, h.health, requests, h.logger),
                                support,
                            )
                        }

                        SleepAsAndroidSyncWorker::class.java.name -> {
                            SleepAsAndroidSyncWorker(appContext, workerParameters, requests, h.logger)
                        }

                        else -> {
                            null
                        }
                    }
                }
            }

        private fun done(): ListenableFuture<Void> = SettableFuture.create<Void>().apply { set(null) }

        private val progressUpdater =
            ProgressUpdater { _, _, data ->
                progress += data
                done()
            }

        private val foregroundUpdater =
            ForegroundUpdater { _, _, info ->
                if (foregroundAllowed) {
                    foreground += info
                    // A foreground service puts the app in the foreground for Health Connect.
                    harness.repository.inBackground = false
                    done()
                } else {
                    SettableFuture.create<Void>().apply { setException(IllegalStateException("ForegroundServiceStartNotAllowed")) }
                }
            }

        inline fun <reified W : ListenableWorker> worker(
            input: Data = Data.EMPTY,
            attempt: Int = 0,
        ): W = builder<W>(input, attempt).build()

        fun <W : ListenableWorker> configure(
            builder: TestListenableWorkerBuilder<W>,
            input: Data,
            attempt: Int,
        ): TestListenableWorkerBuilder<W> =
            builder
                .setWorkerFactory(factory)
                .setInputData(input)
                .setRunAttemptCount(attempt)
                .setProgressUpdater(progressUpdater)
                .setForegroundUpdater(foregroundUpdater)

        inline fun <reified W : ListenableWorker> builder(
            input: Data,
            attempt: Int,
        ): TestListenableWorkerBuilder<W> = configure(TestListenableWorkerBuilder<W>(context), input, attempt)
    }

    private fun report(result: ListenableWorker.Result): RunReport = checkNotNull(WorkResults.parse(result.outputData))

    private fun periodic(): Data = WorkerInputs.estimate(EstimateTrigger.PERIODIC)

    private fun now(): Data = WorkerInputs.estimate(EstimateTrigger.NOW)

    @Test
    fun estimateWorkerSucceedsWithTheCountersOfTheRun() =
        runTest {
            val world = World(this)
            world.harness.repository.addNight(OHEALTH, nightStart(1))

            val result = world.worker<EstimateNewNightsWorker>(periodic()).doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(1, report(result).summary?.estimated)
            assertEquals(0, world.scheduler.realigned)
        }

    @Test
    fun onlyEstimateNowReChecksARecentNightCheckedLessThanAnHourAgo() =
        runTest {
            val world = World(this)
            val h = world.harness
            h.repository.addNight(OHEALTH, nightStart(0))
            world.worker<EstimateNewNightsWorker>(periodic()).doWork()
            h.clock.advanceBy(15 * MINUTE_MS)
            h.repository.clearCalls()

            world.worker<EstimateNewNightsWorker>(periodic()).doWork()
            world.worker<EstimateNewNightsWorker>(WorkerInputs.estimate(EstimateTrigger.DAILY)).doWork()
            assertEquals("scheduled runs keep the hourly cadence", 0, h.repository.callCount(Operation.READ_HEART_RATE))

            val result = world.worker<EstimateNewNightsWorker>(now()).doWork()

            assertEquals(OutcomeKind.COMPLETED, report(result).kind)
            assertEquals("\"estimate now\" checks it again at once", 1, h.repository.callCount(Operation.READ_HEART_RATE))
        }

    @Test
    fun unavailableHealthConnectIsABlockedSuccessNotACrash() =
        runTest {
            val world = World(this)
            world.harness.repository.availability = HcAvailability.NOT_SUPPORTED

            val result = world.worker<EstimateNewNightsWorker>(periodic()).doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(OutcomeKind.BLOCKED, report(result).kind)
            assertEquals(BlockedReason.HEALTH_CONNECT_UNAVAILABLE, report(result).blockedReason)
        }

    @Test
    fun revokedPermissionsAreABlockedSuccess() =
        runTest {
            val world = World(this)
            world.harness.repository.addNight(OHEALTH, nightStart(1))
            world.harness.repository.revoke(HealthPermissions.WRITE_SLEEP)

            val result = world.worker<EstimateNewNightsWorker>(periodic()).doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(BlockedReason.PERMISSION_MISSING, report(result).blockedReason)
        }

    @Test
    fun aBackgroundRunWithoutTheBackgroundPermissionIsBlockedUnlessTheAppIsInTheForeground() =
        runTest {
            val world = World(this)
            world.harness.repository.addNight(OHEALTH, nightStart(1))
            world.harness.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            world.harness.repository.inBackground = true

            val background = world.worker<EstimateNewNightsWorker>(periodic()).doWork()
            assertEquals(BlockedReason.BACKGROUND_PERMISSION_MISSING, report(background).blockedReason)
            assertTrue("a scheduled run does not go foreground", world.foreground.isEmpty())

            world.harness.repository.inBackground = false
            val visible = world.worker<EstimateNewNightsWorker>(periodic()).doWork()
            assertEquals(OutcomeKind.COMPLETED, report(visible).kind)
        }

    @Test
    fun estimateNowGoesForegroundAndARetryThatCannotIsABackgroundRunThatDeletesNothing() =
        runTest {
            val world = World(this)
            val repository = world.harness.repository
            (3 downTo 1).forEach { repository.addNight(OHEALTH, nightStart(it)) }
            assertEquals(OutcomeKind.COMPLETED, report(world.worker<EstimateNewNightsWorker>(now()).doWork()).kind)
            assertEquals(WorkNotifications.ESTIMATE_ID, world.foreground.single().notificationId)
            // Later: the source set changed (a re-read is due) and a retry of "estimate now" runs in the background,
            // not expedited, where the platform refuses a foreground service and Health Connect reads silently.
            world.harness.settings.set { it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH, OTHER_APP))) }
            repository.backgroundRule = FakeHealthRepository.BackgroundRule.SELF_ONLY
            repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            repository.inBackground = true
            world.foregroundAllowed = false

            val retry = world.worker<EstimateNewNightsWorker>(now(), attempt = 1).doWork()

            assertEquals(BlockedReason.BACKGROUND_PERMISSION_MISSING, report(retry).blockedReason)
            assertEquals("nothing is taken for deleted", 3, repository.ownSessions().size)
            assertTrue(
                world.harness.ledger
                    .allNights()
                    .all { it.status == NightStatus.WRITTEN },
            )

            world.foregroundAllowed = true
            val promoted = world.worker<EstimateNewNightsWorker>(now(), attempt = 2).doWork()
            assertEquals("as a foreground service it reads in the foreground", OutcomeKind.COMPLETED, report(promoted).kind)
            assertEquals(3, repository.ownSessions().size)
        }

    @Test
    fun aQuotaErrorAsksForARetry() =
        runTest {
            val world = World(this)
            world.harness.repository.addNight(OHEALTH, nightStart(1))
            world.harness.repository.quotaAfter(calls = 1)

            assertTrue(world.worker<EstimateNewNightsWorker>(periodic()).doWork() is ListenableWorker.Result.Retry)
        }

    @Test
    fun anUnexpectedErrorIsRetriedThenFailsButNeverThrows() =
        runTest {
            val world = World(this)
            world.harness.repository.addNight(OHEALTH, nightStart(1))
            world.harness.estimator.failWhen = { OutOfMemoryError("simulated") }

            val first = world.worker<EstimateNewNightsWorker>(periodic(), attempt = 0).doWork()
            val last = world.worker<EstimateNewNightsWorker>(periodic(), attempt = RetryLimits().maxUnexpectedRetries).doWork()

            assertTrue(first is ListenableWorker.Result.Retry)
            assertTrue(last is ListenableWorker.Result.Failure)
            assertEquals(OutcomeKind.FAILED, report(last).kind)
            assertTrue(checkNotNull(report(last).detail).contains("OutOfMemoryError"))
        }

    @Test
    fun aDailyRunPinsTheNextDailyRunAgainEvenWhenThatFails() =
        runTest {
            val world = World(this)
            val daily = WorkerInputs.estimate(EstimateTrigger.DAILY)

            assertTrue(world.worker<EstimateNewNightsWorker>(daily).doWork() is ListenableWorker.Result.Success)
            world.scheduler.failRealign = true
            assertTrue(world.worker<EstimateNewNightsWorker>(daily).doWork() is ListenableWorker.Result.Success)
            assertEquals(2, world.scheduler.realigned)

            world.harness.repository.quotaAfter(calls = 0)
            assertTrue(world.worker<EstimateNewNightsWorker>(daily).doWork() is ListenableWorker.Result.Retry)
            assertEquals("a retry keeps its backoff", 2, world.scheduler.realigned)
        }

    @Test
    fun backfillRunsInTheForegroundAndPublishesProgress() =
        runTest {
            val world = World(this)
            (20 downTo 1).forEach { world.harness.repository.addNight(OHEALTH, nightStart(it)) }

            val result = world.worker<BackfillWorker>().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(20, report(result).summary?.estimated)
            val progress = world.progress.mapNotNull { WorkerProgress.parse(it) }
            assertEquals(listOf(0, 1, 2, 3, 4, 4), progress.map { it.done })
            assertEquals(4, progress.last().total)
            assertEquals(20, progress.last().nightsEstimated)
            assertTrue("the notification follows the progress", world.foreground.size > 1)
            assertTrue(world.foreground.all { it.foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC })
            assertTrue(world.foreground.all { it.notificationId == WorkNotifications.BACKFILL_ID })
        }

    @Test
    fun backfillContinuesWhenTheForegroundServiceIsNotAllowed() =
        runTest {
            val world = World(this)
            (3 downTo 1).forEach { world.harness.repository.addNight(OHEALTH, nightStart(it)) }
            world.foregroundAllowed = false

            val result = world.worker<BackfillWorker>().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(3, report(result).summary?.estimated)
            assertTrue(world.foreground.isEmpty())
        }

    @Test
    fun aBackfillResumedInTheBackgroundIsBlockedAndNeverDone() =
        runTest {
            val world = World(this)
            val repository = world.harness.repository
            (20 downTo 1).forEach { repository.addNight(OHEALTH, nightStart(it)) }
            repository.failOn(Operation.READ_SLEEP_SESSIONS, HealthDataException.QuotaExceeded("quota"), afterCalls = 2)
            assertTrue(world.worker<BackfillWorker>().doWork() is ListenableWorker.Result.Retry)
            repository.clearFailures()
            repository.backgroundRule = FakeHealthRepository.BackgroundRule.SELF_ONLY
            repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            repository.inBackground = true
            world.foregroundAllowed = false

            val resumed = world.worker<BackfillWorker>(attempt = 1).doWork()

            assertEquals(BlockedReason.BACKGROUND_PERMISSION_MISSING, report(resumed).blockedReason)
            val state =
                checkNotNull(
                    world.harness.ledger.backfillDao
                        .get(),
                )
            assertEquals(BackfillStatus.WAITING_RETRY, state.status)
            assertEquals(1, state.windowsDone)
        }

    @Test
    fun aCancelBeforeTheBackfillWorkerReachesTheEngineIsNotLost() =
        runTest {
            val world = World(this)
            (20 downTo 1).forEach { world.harness.repository.addNight(OHEALTH, nightStart(it)) }
            val input = WorkerInputs.backfill(requestedAtMs = world.harness.clock.millis())
            world.harness.clock.advanceBy(1_000)
            world.harness.backfill.cancel()

            val result = world.worker<BackfillWorker>(input).doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(true, report(result).summary?.cancelled)
            assertTrue(
                world.harness.repository
                    .ownSessions()
                    .isEmpty(),
            )
        }

    @Test
    fun recomputeWorkerRecomputesAndRejectsMalformedRequests() =
        runTest {
            val world = World(this)
            world.harness.repository.addNight(OHEALTH, nightStart(1))
            world.harness.sync.run(inBackground = false)
            val id =
                world.harness.ledger
                    .allNights()
                    .single()
                    .clientRecordId

            val night = world.worker<RecomputeWorker>(WorkerInputs.recompute(RecomputeRequest.Nights(listOf(id)))).doWork()
            assertEquals(1, report(night).summary?.estimated)
            val all =
                world
                    .worker<RecomputeWorker>(
                        WorkerInputs.recompute(RecomputeRequest.All(RecomputeScope.ALL, Long.MAX_VALUE)),
                    ).doWork()
            assertEquals(1, report(all).summary?.estimated)

            assertTrue(world.worker<RecomputeWorker>(Data.EMPTY, attempt = 0).doWork() is ListenableWorker.Result.Retry)
            assertTrue(world.worker<RecomputeWorker>(Data.EMPTY, attempt = 3).doWork() is ListenableWorker.Result.Failure)
        }

    @Test
    fun theFollowUpWorkerSendsThePendingRequestOnceAndAlwaysSucceeds() =
        runTest {
            val world = World(this)
            val h = world.harness
            h.requestSleepAsAndroidSync()
            h.repository.addNight(OHEALTH, nightStart(1))
            val run = world.worker<EstimateNewNightsWorker>(now()).doWork()
            assertEquals(true, report(run).summary?.sleepAsAndroidSyncRequested)
            assertEquals(1, world.sleepAsAndroid.sent)
            assertEquals(1, world.followUps.scheduled.size)
            h.clock.advanceBy(10 * MINUTE_MS)
            h.repository.clearCalls()

            val followUp = world.worker<SleepAsAndroidSyncWorker>().doWork()

            assertTrue(followUp is ListenableWorker.Result.Success)
            assertEquals(2, world.sleepAsAndroid.sent)
            assertNull(
                h.syncState.state
                    .first()
                    .sleepAsAndroidFollowUpDueAtMs,
            )
            assertTrue(
                "a re-run sends nothing",
                world.worker<SleepAsAndroidSyncWorker>(attempt = 1).doWork() is ListenableWorker.Result.Success,
            )
            assertEquals(2, world.sleepAsAndroid.sent)
            assertTrue("it reads nothing from Health Connect: ${h.repository.calls}", h.repository.calls.isEmpty())
        }

    @Test
    fun theFollowUpWorkerSucceedsWhenTheRequestFails() =
        runTest {
            val world = World(this)
            val h = world.harness
            h.requestSleepAsAndroidSync()
            world.requests.afterRun(written = 1, rewritten = 0)
            world.sleepAsAndroid.failure = SecurityException("not allowed")
            h.clock.advanceBy(10 * MINUTE_MS)
            h.repository.clearCalls()

            val result = world.worker<SleepAsAndroidSyncWorker>().doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals("the follow-up was attempted", 2, world.sleepAsAndroid.attempts)
            assertEquals("only the first request", 1, world.sleepAsAndroid.sent)
            assertTrue("it reads nothing from Health Connect: ${h.repository.calls}", h.repository.calls.isEmpty())
        }

    @Test
    fun foregroundInfoUsesTheDataSyncTypeAndTheWorkChannel() =
        runTest {
            val world = World(this)

            val infos =
                listOf(
                    world.worker<EstimateNewNightsWorker>(periodic()).getForegroundInfo(),
                    world.worker<BackfillWorker>().getForegroundInfo(),
                    world.worker<RecomputeWorker>().getForegroundInfo(),
                )

            assertTrue(infos.all { it.foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC })
            assertEquals(
                listOf(WorkNotifications.ESTIMATE_ID, WorkNotifications.BACKFILL_ID, WorkNotifications.RECOMPUTE_ID),
                infos.map {
                    it.notificationId
                },
            )
            val manager = context.getSystemService(NotificationManager::class.java)
            assertNotNull(manager.getNotificationChannel(WorkNotifications.CHANNEL_ID))
            val text =
                world.notifications
                    .backfillInfo(
                        JobProgress(2, 5, nightsEstimated = 1),
                    ).notification.extras
                    .getCharSequence(Notification.EXTRA_TEXT)
                    ?.toString()
            assertEquals("Week 2 of 5 · 1 night estimated", text)
        }
}

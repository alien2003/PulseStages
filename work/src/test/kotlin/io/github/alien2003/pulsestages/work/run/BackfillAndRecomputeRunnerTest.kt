package io.github.alien2003.pulsestages.work.run

import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeProgress
import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeScope
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.BackfillStateEntity
import io.github.alien2003.pulsestages.data.local.db.BackfillStatus
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.data.local.sync.SleepAsAndroidRequestState
import io.github.alien2003.pulsestages.work.saa.RecordingFollowUps
import io.github.alien2003.pulsestages.work.saa.RecordingSyncRequestSender
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidRequest
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidSyncRequests
import io.github.alien2003.pulsestages.work.saa.requestSleepAsAndroidSync
import io.github.alien2003.pulsestages.work.saa.sleepAsAndroidRequests
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The runners of BackfillWorker and RecomputeWorker on the data-hc fixtures. */
class BackfillAndRecomputeRunnerTest {
    @TempDir
    lateinit var dir: File

    private val limits = RetryLimits()

    private fun PipelineHarness.backfillRunner(requests: SleepAsAndroidSyncRequests = sleepAsAndroidRequests()): BackfillRunner =
        BackfillRunner(backfill, requests, logger)

    private fun PipelineHarness.recomputeRunner(requests: SleepAsAndroidSyncRequests = sleepAsAndroidRequests()): RecomputeRunner =
        RecomputeRunner(recomputer, health, requests, logger)

    private suspend fun PipelineHarness.request(): SleepAsAndroidRequestState = syncState.state.first().sleepAsAndroidRequest

    private fun PipelineHarness.addNights(daysAgo: Iterable<Int>) = daysAgo.forEach { repository.addNight(OHEALTH, nightStart(it)) }

    @Test
    fun `the backfill reports progress after every window and completes`() =
        harnessTest(dir) { h ->
            h.addNights(20 downTo 1)
            val progress = mutableListOf<BackfillStateEntity>()

            val outcome =
                assertInstanceOf(WorkOutcome.Completed::class.java, h.backfillRunner().run(inBackground = false) { progress += it })

            assertEquals(20, outcome.summary.estimated)
            assertEquals(listOf(0, 1, 2, 3, 4, 4), progress.map { it.windowsDone })
            assertEquals(ResultKind.SUCCESS, WorkResults.kindOf(outcome, 0, limits))
            assertTrue(outcome.summary.sleepAsAndroidSyncDeferred, "on by default, once the nights are complete")
        }

    @Test
    fun `with the option on, a backfill that wrote nights asks Sleep as Android once, one over current nights does not`() =
        harnessTest(dir) { h ->
            h.requestSleepAsAndroidSync()
            h.addNights(20 downTo 1)
            val sender = RecordingSyncRequestSender()
            val requests = h.sleepAsAndroidRequests(sender)

            val outcome = assertInstanceOf(WorkOutcome.Completed::class.java, h.backfillRunner(requests).run(inBackground = false))

            assertTrue(outcome.summary.sleepAsAndroidSyncDeferred)
            assertFalse(outcome.summary.sleepAsAndroidSyncRequested)
            h.clock.advanceBy(SleepAsAndroidSyncRequests.CHECK_WAIT_MS)
            assertEquals(SleepAsAndroidRequest.SENT, requests.onWork(), "without a run's late-data pass, an hour later")
            assertEquals(1, sender.sent, "one request for the whole backfill, not one per window")

            h.clock.advanceBy(MINUTE_MS)
            val again = assertInstanceOf(WorkOutcome.Completed::class.java, h.backfillRunner(requests).run(inBackground = false))

            assertEquals(20, again.summary.estimated, "every night is up to date")
            assertFalse(again.summary.sleepAsAndroidSyncRequested)
            assertFalse(again.summary.sleepAsAndroidSyncDeferred)
            assertEquals(1, sender.sent, "nothing was written")
        }

    @Test
    fun `with the option off, a backfill that wrote nights asks Sleep as Android nothing`() =
        harnessTest(dir) { h ->
            h.requestSleepAsAndroidSync(on = false)
            h.addNights(20 downTo 1)
            val sender = RecordingSyncRequestSender()

            val outcome =
                assertInstanceOf(
                    WorkOutcome.Completed::class.java,
                    h.backfillRunner(h.sleepAsAndroidRequests(sender)).run(inBackground = false),
                )

            assertEquals(20, h.repository.ownSessions().size)
            assertFalse(outcome.summary.sleepAsAndroidSyncRequested)
            assertFalse(outcome.summary.sleepAsAndroidSyncDeferred)
            assertEquals(0, sender.lookups, "not even looked up")
        }

    @Test
    fun `a backfill that wrote nights before a quota stop still asks Sleep as Android once every night is processed`() =
        harnessTest(dir) { h ->
            h.requestSleepAsAndroidSync()
            h.addNights(20 downTo 1)
            h.repository.failOn(Operation.READ_HEART_RATE, HealthDataException.QuotaExceeded("quota"), afterCalls = 10)
            val sender = RecordingSyncRequestSender()
            val requests = h.sleepAsAndroidRequests(sender)

            assertInstanceOf(WorkOutcome.Retry::class.java, h.backfillRunner(requests).run(inBackground = false))

            assertTrue(h.repository.ownSessions().isNotEmpty())
            assertEquals(NOON, h.request().dueAtMs, "owed for the nights written before the stop")
            assertEquals(0, sender.attempts)
            h.clock.advanceBy(SleepAsAndroidSyncRequests.CHECK_WAIT_MS)
            assertEquals(SleepAsAndroidRequest.DEFERRED, requests.onWork(), "the nights the backfill has not reached are not processed")

            assertInstanceOf(WorkOutcome.Completed::class.java, h.backfillRunner(requests).run(inBackground = false))
            h.clock.advanceBy(SleepAsAndroidSyncRequests.CHECK_WAIT_MS)
            assertEquals(SleepAsAndroidRequest.SENT, requests.onWork())
            assertEquals(1, sender.sent)
        }

    @Test
    fun `cancelling the backfill stops it between nights and persists CANCELLED`() =
        harnessTest(dir) { h ->
            h.addNights(20 downTo 1)
            val runner = h.backfillRunner()
            h.estimator.beforeEstimate = { if (h.estimator.inputs.size == 3) runner.cancel() }

            val outcome = assertInstanceOf(WorkOutcome.Completed::class.java, runner.run(inBackground = false))

            assertTrue(outcome.summary.cancelled)
            assertEquals(3, h.estimator.inputs.size, "the night in progress finished, no further night started")
            assertEquals(3, h.repository.ownSessions().size)
            assertEquals(
                BackfillStatus.CANCELLED,
                h.ledger.backfillDao
                    .get()
                    ?.status,
            )
            assertEquals(ResultKind.SUCCESS, WorkResults.kindOf(outcome, 0, limits))
        }

    @Test
    fun `a quota error mid-backfill is retried and the next run resumes from the persisted cursor`() =
        harnessTest(dir) { h ->
            h.addNights(20 downTo 1)
            h.repository.failOn(Operation.READ_HEART_RATE, HealthDataException.QuotaExceeded("quota"), afterCalls = 10)

            val first = assertInstanceOf(WorkOutcome.Retry::class.java, h.backfillRunner().run(inBackground = false))

            assertEquals(RetryCause.QUOTA, first.cause)
            assertEquals(ResultKind.RETRY, WorkResults.kindOf(first, 0, limits))
            val paused = checkNotNull(h.ledger.backfillDao.get())
            assertEquals(BackfillStatus.WAITING_RETRY, paused.status)
            assertTrue(paused.windowsDone in 1 until paused.windowsTotal, "some windows done: ${paused.windowsDone}")
            val estimatedBefore =
                h.estimator.inputs
                    .map { it.sessionStartMs }
                    .toSet()
            h.estimator.inputs.clear()

            val second = assertInstanceOf(WorkOutcome.Completed::class.java, h.backfillRunner().run(inBackground = false))

            assertEquals(
                BackfillStatus.DONE,
                h.ledger.backfillDao
                    .get()
                    ?.status,
            )
            assertTrue(h.estimator.inputs.all { it.sessionStartMs >= paused.cursorMs }, "nothing before the cursor is read again")
            assertEquals(20, h.repository.ownSessions().size)
            assertEquals(20, (estimatedBefore + h.estimator.inputs.map { it.sessionStartMs }).size)
            assertEquals(20, second.summary.estimated, "nights of resumed windows count as estimated")
        }

    @Test
    fun `a backfill that stored FAILED fails the work at once instead of being retried from the start`() =
        harnessTest(dir) { h ->
            h.addNights(20 downTo 1)
            h.repository.failOn(Operation.READ_SLEEP_SESSIONS, HealthDataException.InvalidData("bad record"), afterCalls = 2)

            val outcome = assertInstanceOf(WorkOutcome.Failed::class.java, h.backfillRunner().run(inBackground = false))

            val failed = checkNotNull(h.ledger.backfillDao.get())
            assertEquals(BackfillStatus.FAILED, failed.status)
            assertEquals(1, failed.windowsDone)
            assertTrue(outcome.detail.contains("bad record"))
            assertEquals(ResultKind.FAILURE, WorkResults.kindOf(outcome, runAttemptCount = 0, limits), "no retry")
            assertEquals(OutcomeKind.FAILED, WorkResults.parse(WorkResults.output(outcome))?.kind)
        }

    @Test
    fun `a cancel made after the backfill was requested stops its retry before it begins`() =
        harnessTest(dir) { h ->
            h.addNights(20 downTo 1)
            val runner = h.backfillRunner()
            val requestedAt = h.clock.millis()
            h.repository.failOn(Operation.READ_SLEEP_SESSIONS, HealthDataException.QuotaExceeded("quota"), afterCalls = 2)
            assertInstanceOf(WorkOutcome.Retry::class.java, runner.run(inBackground = false, requestedAtMs = requestedAt))
            val written = h.repository.ownSessions().size
            h.repository.clearFailures()

            h.clock.advanceBy(MINUTE_MS)
            runner.cancel()
            val retry = assertInstanceOf(WorkOutcome.Completed::class.java, runner.run(inBackground = false, requestedAtMs = requestedAt))

            assertTrue(retry.summary.cancelled)
            assertEquals(written, h.repository.ownSessions().size, "the retry processed nothing")
            assertEquals(
                BackfillStatus.CANCELLED,
                h.ledger.backfillDao
                    .get()
                    ?.status,
            )
        }

    @Test
    fun `a backfill stopped by WorkManager stays resumable`() =
        harnessTest(dir) { h ->
            h.addNights(20 downTo 1)
            val entered = CompletableDeferred<Unit>()
            h.estimator.beforeEstimate = {
                if (h.estimator.inputs.size == 10) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val job = launch { h.backfillRunner().run(inBackground = false) }
            entered.await()

            job.cancel()
            job.join()

            assertTrue(job.isCancelled)
            assertEquals(
                BackfillStatus.RUNNING,
                h.ledger.backfillDao
                    .get()
                    ?.status,
                "resumed by the next run",
            )
            h.estimator.beforeEstimate = {}
            assertInstanceOf(WorkOutcome.Completed::class.java, h.backfillRunner().run(inBackground = false))
            assertEquals(20, h.repository.ownSessions().size)
        }

    @Test
    fun `a backfill stopped by WorkManager after it wrote nights leaves a request owed for them, checked by the work`() =
        harnessTest(dir) { h ->
            h.requestSleepAsAndroidSync()
            h.addNights(20 downTo 1)
            val entered = CompletableDeferred<Unit>()
            h.estimator.beforeEstimate = {
                if (h.estimator.inputs.size == 10) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val sender = RecordingSyncRequestSender()
            val followUps = RecordingFollowUps()
            val job = launch { h.backfillRunner(h.sleepAsAndroidRequests(sender, followUps)).run(inBackground = false) }
            entered.await()

            job.cancel()
            job.join()

            assertTrue(job.isCancelled)
            assertEquals(9, h.repository.ownSessions().size)
            assertEquals(NOON, h.request().dueAtMs, "the nights written before the stop are in Health Connect")
            assertEquals(
                listOf(RecordingFollowUps.Scheduled(SleepAsAndroidSyncRequests.RECHECK_MS, ScheduleSettings())),
                followUps.scheduled,
            )
            assertEquals(0, sender.sent)
        }

    @Test
    fun `an unavailable Health Connect or a missing permission blocks the backfill without a crash`() =
        harnessTest(dir) { h ->
            h.addNights(3 downTo 1)
            h.repository.availability = HcAvailability.NOT_SUPPORTED
            assertEquals(
                BlockedReason.HEALTH_CONNECT_UNAVAILABLE,
                assertInstanceOf(WorkOutcome.Blocked::class.java, h.backfillRunner().run(inBackground = false)).reason,
            )

            h.repository.availability = HcAvailability.AVAILABLE
            h.estimator.beforeEstimate = { h.repository.revoke(HealthPermissions.READ_STEPS) }
            val revoked = assertInstanceOf(WorkOutcome.Blocked::class.java, h.backfillRunner().run(inBackground = false))
            assertEquals(BlockedReason.PERMISSION_MISSING, revoked.reason)
            assertEquals(
                BackfillStatus.WAITING_RETRY,
                h.ledger.backfillDao
                    .get()
                    ?.status,
            )
            assertEquals(ResultKind.SUCCESS, WorkResults.kindOf(revoked, 0, limits))
        }

    @Test
    fun `a recompute that is really in the background is blocked, not completed with nothing recomputed`() =
        harnessTest(dir) { h ->
            h.addNights(3 downTo 1)
            h.sync.run(inBackground = false)
            h.clock.advanceBy(MINUTE_MS)
            // A retry long after the user asked, on a Health Connect that reads silently in the background.
            h.repository.backgroundRule = FakeHealthRepository.BackgroundRule.SELF_ONLY
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            h.repository.inBackground = true

            val outcome =
                h.recomputeRunner().run(RecomputeRequest.All(RecomputeScope.ALL, h.clock.millis()), inBackground = false)

            assertEquals(BlockedReason.BACKGROUND_PERMISSION_MISSING, assertInstanceOf(WorkOutcome.Blocked::class.java, outcome).reason)
            assertTrue(h.ledger.allNights().all { it.recomputeCount == 0 })
            assertEquals(3, h.repository.ownSessions().size)
        }

    @Test
    fun `recompute all rewrites every night and reports progress`() =
        harnessTest(dir) { h ->
            h.addNights(listOf(20, 10, 1))
            h.backfill.run(inBackground = false)
            h.clock.advanceBy(MINUTE_MS)
            val progress = mutableListOf<RecomputeProgress>()

            val outcome =
                assertInstanceOf(
                    WorkOutcome.Completed::class.java,
                    h.recomputeRunner().run(RecomputeRequest.All(RecomputeScope.ALL, h.clock.millis()), inBackground = false) {
                        progress +=
                            it
                    },
                )

            assertEquals(3, outcome.summary.estimated)
            assertTrue(outcome.summary.sleepAsAndroidSyncDeferred, "on by default, once the nights are complete")
            assertEquals(3, progress.last().done)
            assertEquals(
                listOf(1),
                h.ledger
                    .allNights()
                    .map { it.recomputeCount }
                    .distinct(),
            )
        }

    @Test
    fun `with the option on, a recompute that rewrote nights asks Sleep as Android once`() =
        harnessTest(dir) { h ->
            h.addNights(listOf(20, 10, 1))
            h.backfill.run(inBackground = false)
            h.clock.advanceBy(MINUTE_MS)
            h.requestSleepAsAndroidSync()
            val sender = RecordingSyncRequestSender()
            val requests = h.sleepAsAndroidRequests(sender)

            val outcome =
                assertInstanceOf(
                    WorkOutcome.Completed::class.java,
                    h.recomputeRunner(requests).run(RecomputeRequest.All(RecomputeScope.ALL, h.clock.millis()), inBackground = false),
                )

            assertEquals(3, outcome.summary.estimated)
            assertTrue(outcome.summary.sleepAsAndroidSyncDeferred)
            h.clock.advanceBy(SleepAsAndroidSyncRequests.CHECK_WAIT_MS)
            assertEquals(SleepAsAndroidRequest.SENT, requests.onWork())
            assertEquals(1, sender.sent)
        }

    /**
     * A request owed since an earlier write whose nights have looked settled for an hour (the work would send it now),
     * and [requests] over [sender]; the work's check runs during the first estimate of the next run ([duringRun]).
     */
    private suspend fun PipelineHarness.confirmedRequest(
        requests: SleepAsAndroidSyncRequests,
        duringRun: MutableList<SleepAsAndroidRequest>,
    ) {
        requests.recordRun(written = 1, rewritten = 0)
        assertEquals(SleepAsAndroidRequest.DEFERRED, requests.trySend())
        clock.advanceBy(SleepAsAndroidSyncRequests.CHECK_WAIT_MS)
        estimator.beforeEstimate = { if (duringRun.isEmpty()) duringRun += requests.onWork() }
    }

    @Test
    fun `the work sends no request in the middle of a recompute, which may still rewrite the nights it would import`() =
        harnessTest(dir) { h ->
            h.addNights(listOf(20, 10, 1))
            h.backfill.run(inBackground = false)
            val sender = RecordingSyncRequestSender()
            val requests = h.sleepAsAndroidRequests(sender)
            val duringRun = mutableListOf<SleepAsAndroidRequest>()
            h.confirmedRequest(requests, duringRun)

            val outcome =
                assertInstanceOf(
                    WorkOutcome.Completed::class.java,
                    h.recomputeRunner(requests).run(RecomputeRequest.All(RecomputeScope.ALL, h.clock.millis()), inBackground = false),
                )

            assertEquals(listOf(SleepAsAndroidRequest.DEFERRED), duringRun, "the nights are confirmed again first")
            assertEquals(0, sender.sent)
            assertTrue(outcome.summary.sleepAsAndroidSyncDeferred)
        }

    @Test
    fun `the work sends no request in the middle of a backfill`() =
        harnessTest(dir) { h ->
            // Written to Health Connect over 12 hours ago: out of the scope of the check, which finds the nights settled.
            h.clock.nowMs = NOON - 13 * HOUR_MS
            h.addNights(20 downTo 1)
            h.clock.nowMs = NOON
            val sender = RecordingSyncRequestSender()
            val requests = h.sleepAsAndroidRequests(sender)
            val duringRun = mutableListOf<SleepAsAndroidRequest>()
            h.confirmedRequest(requests, duringRun)

            assertInstanceOf(WorkOutcome.Completed::class.java, h.backfillRunner(requests).run(inBackground = false))

            assertEquals(listOf(SleepAsAndroidRequest.DEFERRED), duringRun)
            assertEquals(0, sender.sent)
        }

    @Test
    fun `a recompute that a Health Connect error ends after its first group of nights still leaves a request owed`() =
        harnessTest(dir) { h ->
            h.addNights(listOf(20, 10, 1))
            h.backfill.run(inBackground = false)
            h.clock.advanceBy(MINUTE_MS)
            h.requestSleepAsAndroidSync()
            // Three groups of nights (more than 7 days apart): the read of the second one fails.
            h.repository.failOn(Operation.READ_SLEEP_SESSIONS, HealthDataException.QuotaExceeded("quota"), afterCalls = 1)
            val sender = RecordingSyncRequestSender()
            val requests = h.sleepAsAndroidRequests(sender)

            val outcome = h.recomputeRunner(requests).run(RecomputeRequest.All(RecomputeScope.ALL, h.clock.millis()), inBackground = false)

            assertEquals(RetryCause.QUOTA, assertInstanceOf(WorkOutcome.Retry::class.java, outcome).cause)
            assertEquals(
                listOf(1, 0, 0),
                h.ledger
                    .allNights()
                    .sortedBy { it.sessionStartMs }
                    .map { it.recomputeCount },
            )
            assertEquals(h.clock.millis(), h.request().dueAtMs, "the night rewritten before the error is in Health Connect")
            assertEquals(0, sender.attempts)
            h.repository.clearFailures()
            h.clock.advanceBy(MINUTE_MS)
            assertEquals(SleepAsAndroidRequest.DEFERRED, requests.onWork(), "checked by the work: the nights look complete from now on")
            h.clock.advanceBy(SleepAsAndroidSyncRequests.CHECK_WAIT_MS)
            assertEquals(SleepAsAndroidRequest.SENT, requests.onWork())
            assertEquals(1, sender.sent)
        }

    @Test
    fun `recompute of one night, and its failures as outcomes`() =
        harnessTest(dir) { h ->
            h.addNights(2 downTo 1)
            h.sync.run(inBackground = false)
            val night = h.ledger.allNights().first()

            val done =
                assertInstanceOf(
                    WorkOutcome.Completed::class.java,
                    h.recomputeRunner().run(RecomputeRequest.Nights(listOf(night.clientRecordId)), false),
                )
            assertEquals(1, done.summary.estimated)

            h.repository.failOn(Operation.READ_SLEEP_SESSIONS, HealthDataException.QuotaExceeded("quota"))
            val quota =
                assertInstanceOf(
                    WorkOutcome.Retry::class.java,
                    h.recomputeRunner().run(RecomputeRequest.Nights(listOf(night.clientRecordId)), false),
                )
            assertEquals(RetryCause.QUOTA, quota.cause)

            h.repository.failOn(Operation.READ_SLEEP_SESSIONS, HealthDataException.PermissionDenied("revoked"))
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            val denied =
                assertInstanceOf(
                    WorkOutcome.Blocked::class.java,
                    h.recomputeRunner().run(RecomputeRequest.Nights(listOf(night.clientRecordId)), false),
                )
            assertEquals(BlockedReason.BACKGROUND_PERMISSION_MISSING, denied.reason, "classified like the pipeline does")

            h.repository.revoke(HealthPermissions.WRITE_SLEEP)
            val blocked =
                assertInstanceOf(
                    WorkOutcome.Blocked::class.java,
                    h.recomputeRunner().run(RecomputeRequest.Nights(listOf(night.clientRecordId)), false),
                )
            assertEquals(BlockedReason.PERMISSION_MISSING, blocked.reason)
        }
}

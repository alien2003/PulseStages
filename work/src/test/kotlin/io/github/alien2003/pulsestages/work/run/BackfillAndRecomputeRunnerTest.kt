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
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.BackfillStateEntity
import io.github.alien2003.pulsestages.data.local.db.BackfillStatus
import io.github.alien2003.pulsestages.work.saa.RecordingSyncRequestSender
import io.github.alien2003.pulsestages.work.saa.SyncRequestSender
import io.github.alien2003.pulsestages.work.saa.requestSleepAsAndroidSync
import io.github.alien2003.pulsestages.work.saa.sleepAsAndroidRequests
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
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

    private fun PipelineHarness.backfillRunner(sender: SyncRequestSender = RecordingSyncRequestSender()): BackfillRunner =
        BackfillRunner(backfill, sleepAsAndroidRequests(sender), logger)

    private fun PipelineHarness.recomputeRunner(sender: SyncRequestSender = RecordingSyncRequestSender()): RecomputeRunner =
        RecomputeRunner(recomputer, health, sleepAsAndroidRequests(sender), logger)

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
            assertFalse(outcome.summary.sleepAsAndroidSyncRequested, "off by default")
        }

    @Test
    fun `with the option on, a backfill that wrote nights asks Sleep as Android once, one over current nights does not`() =
        harnessTest(dir) { h ->
            h.requestSleepAsAndroidSync()
            h.addNights(20 downTo 1)
            val sender = RecordingSyncRequestSender()

            val outcome = assertInstanceOf(WorkOutcome.Completed::class.java, h.backfillRunner(sender).run(inBackground = false))

            assertTrue(outcome.summary.sleepAsAndroidSyncRequested)
            assertEquals(1, sender.sent, "one request for the whole backfill, not one per window")

            h.clock.advanceBy(MINUTE_MS)
            val again = assertInstanceOf(WorkOutcome.Completed::class.java, h.backfillRunner(sender).run(inBackground = false))

            assertEquals(20, again.summary.estimated, "every night is up to date")
            assertFalse(again.summary.sleepAsAndroidSyncRequested)
            assertEquals(1, sender.sent, "nothing was written")
        }

    @Test
    fun `a backfill that wrote nights before a quota stop still asks Sleep as Android`() =
        harnessTest(dir) { h ->
            h.requestSleepAsAndroidSync()
            h.addNights(20 downTo 1)
            h.repository.failOn(Operation.READ_HEART_RATE, HealthDataException.QuotaExceeded("quota"), afterCalls = 10)
            val sender = RecordingSyncRequestSender()

            assertInstanceOf(WorkOutcome.Retry::class.java, h.backfillRunner(sender).run(inBackground = false))

            assertTrue(h.repository.ownSessions().isNotEmpty())
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
            assertFalse(outcome.summary.sleepAsAndroidSyncRequested, "off by default")
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

            val outcome =
                assertInstanceOf(
                    WorkOutcome.Completed::class.java,
                    h.recomputeRunner(sender).run(RecomputeRequest.All(RecomputeScope.ALL, h.clock.millis()), inBackground = false),
                )

            assertEquals(3, outcome.summary.estimated)
            assertTrue(outcome.summary.sleepAsAndroidSyncRequested)
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

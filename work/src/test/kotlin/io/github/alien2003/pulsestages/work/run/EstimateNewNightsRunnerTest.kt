package io.github.alien2003.pulsestages.work.run

import io.github.alien2003.pulsestages.algo.synthetic.SyntheticPerson
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.hc.pipeline.NewNightsSync
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.addSyntheticNight
import io.github.alien2003.pulsestages.data.hc.testutil.cyclingBpm
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.PersonalModelKind
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import io.github.alien2003.pulsestages.work.saa.RecordingFollowUps
import io.github.alien2003.pulsestages.work.saa.RecordingSyncRequestSender
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidFollowUps
import io.github.alien2003.pulsestages.work.saa.SyncRequestSender
import io.github.alien2003.pulsestages.work.saa.requestSleepAsAndroidSync
import io.github.alien2003.pulsestages.work.saa.sleepAsAndroidRequests
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The runner of EstimateNewNightsWorker on the data-hc fixtures (fake Health Connect, in-memory ledger, fake
 * estimator): it never crashes, and every failure becomes an outcome that maps to the right WorkManager result.
 */
class EstimateNewNightsRunnerTest {
    @TempDir
    lateinit var dir: File

    private val limits = RetryLimits()

    private fun PipelineHarness.runner(
        sender: SyncRequestSender = RecordingSyncRequestSender(),
        followUps: SleepAsAndroidFollowUps = RecordingFollowUps(),
    ): EstimateNewNightsRunner = EstimateNewNightsRunner(sync, personalization, sleepAsAndroidRequests(sender, followUps), logger)

    private suspend fun PipelineHarness.pendingFollowUp(): Long? = syncState.state.first().sleepAsAndroidFollowUpDueAtMs

    private fun kinds(outcome: WorkOutcome): List<ResultKind> = (0..limits.maxRetries).map { WorkResults.kindOf(outcome, it, limits) }

    @Test
    fun `a normal run estimates the new nights`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))

            val sender = RecordingSyncRequestSender()

            val outcome = assertInstanceOf(WorkOutcome.Completed::class.java, h.runner(sender).run(inBackground = true))

            assertEquals(2, outcome.summary.estimated)
            assertEquals(2, h.repository.ownSessions().size)
            assertEquals(ResultKind.SUCCESS, WorkResults.kindOf(outcome, 0, limits))
            assertFalse(outcome.summary.sleepAsAndroidSyncRequested)
            assertEquals(0, sender.attempts, "Sleep as Android is not asked by default")
        }

    @Test
    fun `with the option on, a sync that writes two nights asks Sleep as Android once and an idle run does not`() =
        harnessTest(dir) { h ->
            h.requestSleepAsAndroidSync()
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            val sender = RecordingSyncRequestSender()
            val runner = h.runner(sender)

            val outcome = assertInstanceOf(WorkOutcome.Completed::class.java, runner.run(inBackground = true))

            assertEquals(2, outcome.summary.estimated)
            assertTrue(outcome.summary.sleepAsAndroidSyncRequested)
            assertEquals(1, sender.sent)
            assertNotNull(
                h.syncState.state
                    .first()
                    .sleepAsAndroidFollowUpDueAtMs,
                "the follow-up for the new nights",
            )
            assertTrue(WorkResults.parse(WorkResults.output(outcome))?.summary?.sleepAsAndroidSyncRequested == true)

            h.clock.advanceBy(15 * MINUTE_MS)
            val idle = assertInstanceOf(WorkOutcome.Completed::class.java, runner.run(inBackground = true))

            assertFalse(idle.summary.sleepAsAndroidSyncRequested)
            assertEquals(1, sender.sent, "nothing written, nothing asked")
        }

    @Test
    fun `Health Connect unavailable does not crash the worker and is not retried`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.availability = HcAvailability.PROVIDER_UPDATE_REQUIRED

            val outcome = assertInstanceOf(WorkOutcome.Blocked::class.java, h.runner().run(inBackground = true))

            assertEquals(BlockedReason.HEALTH_CONNECT_UNAVAILABLE, outcome.reason)
            assertTrue(kinds(outcome).all { it == ResultKind.SUCCESS }, "no retry storm")
            assertEquals(
                SyncOutcome.HEALTH_CONNECT_UNAVAILABLE,
                h.syncState.state
                    .first()
                    .lastRunOutcome,
            )
        }

    @Test
    fun `a revoked required permission blocks the run before anything is read`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.revoke(HealthPermissions.READ_HEART_RATE)
            h.repository.clearCalls()

            val outcome = assertInstanceOf(WorkOutcome.Blocked::class.java, h.runner().run(inBackground = false))

            assertEquals(BlockedReason.PERMISSION_MISSING, outcome.reason)
            assertEquals(listOf(Operation.GRANTED_PERMISSIONS), h.repository.calls.distinct())
            assertEquals(ResultKind.SUCCESS, WorkResults.kindOf(outcome, 0, limits))
        }

    @Test
    fun `a permission revoked in the middle of the run stops it without a crash and keeps the nights done`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            h.estimator.beforeEstimate = { h.repository.revoke(HealthPermissions.READ_HEART_RATE) }

            val outcome = assertInstanceOf(WorkOutcome.Blocked::class.java, h.runner().run(inBackground = false))

            assertEquals(BlockedReason.PERMISSION_MISSING, outcome.reason)
            assertEquals(1, h.repository.ownSessions().size, "the first night was written before the revocation")
            assertEquals(listOf(NightStatus.WRITTEN), h.ledger.allNights().map { it.status })
        }

    @Test
    fun `a run that wrote a night and was then blocked still asks Sleep as Android once`() =
        harnessTest(dir) { h ->
            h.requestSleepAsAndroidSync()
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            h.estimator.beforeEstimate = { h.repository.revoke(HealthPermissions.READ_HEART_RATE) }
            val sender = RecordingSyncRequestSender()

            assertInstanceOf(WorkOutcome.Blocked::class.java, h.runner(sender).run(inBackground = false))

            assertEquals(1, h.repository.ownSessions().size)
            assertEquals(1, sender.sent, "the night written before the stop is in Health Connect")
        }

    @Test
    fun `a night written before a Health Connect error that ends the sync still gets its request`() =
        harnessTest(dir) { h ->
            h.requestSleepAsAndroidSync()
            // An older night whose heart rate has not arrived: retried by the next late-data pass.
            h.repository.addNight(OHEALTH, nightStart(1), withHeartRate = false)
            val sender = RecordingSyncRequestSender()
            val followUps = RecordingFollowUps()
            val runner = h.runner(sender, followUps)
            assertInstanceOf(WorkOutcome.Completed::class.java, runner.run(inBackground = false))
            assertEquals(0, sender.attempts, "nothing written yet")
            h.clock.advanceBy(HOUR_MS)
            h.repository.addNight(OHEALTH, nightStart(0))
            // The first sleep-session read is the Changes page's window, the second the retry's: it ends the sync.
            h.repository.failOn(Operation.READ_SLEEP_SESSIONS, HealthDataException.QuotaExceeded("quota"), afterCalls = 1)

            val outcome = assertInstanceOf(WorkOutcome.Retry::class.java, runner.run(inBackground = false))

            assertEquals(RetryCause.QUOTA, outcome.cause)
            assertEquals(1, h.repository.ownSessions().size, "the new night was written before the error")
            assertEquals(1, sender.sent, "and Sleep as Android is asked for it")
            assertEquals(1, followUps.scheduled.size)
            assertEquals(
                NOON,
                h.syncState.state
                    .first()
                    .lateDataPassAtMs,
                "the pass that the error ended is tried again",
            )
        }

    @Test
    fun `a run cancelled after it wrote a night still asks Sleep as Android, then the cancellation goes on`() =
        harnessTest(dir) { h ->
            h.requestSleepAsAndroidSync()
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            val entered = CompletableDeferred<Unit>()
            h.estimator.beforeEstimate = { input ->
                if (input.sessionStartMs == nightStart(1)) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val sender = RecordingSyncRequestSender()
            val followUps = RecordingFollowUps()
            var finished = false
            val job =
                launch {
                    h.runner(sender, followUps).run(inBackground = false)
                    finished = true
                }
            entered.await()

            job.cancel()
            job.join()

            assertTrue(job.isCancelled)
            assertFalse(finished, "the runner rethrew the cancellation")
            assertEquals(1, h.repository.ownSessions().size, "the first night was written before WorkManager stopped the run")
            assertEquals(1, sender.sent)
            assertEquals(1, followUps.scheduled.size)
            assertNotNull(h.pendingFollowUp())
            assertEquals(
                SyncOutcome.CANCELLED,
                h.syncState.state
                    .first()
                    .lastRunOutcome,
            )
        }

    @Test
    fun `with the option on, checks of an unchanged night ask nothing and a rewrite after late heart rate asks once`() =
        harnessTest(dir) { h ->
            h.requestSleepAsAndroidSync()
            // A recent night whose heart rate has only partly reached Health Connect (until 307 min after the start).
            val session = h.repository.addSleepSession(OHEALTH, nightStart(0), nightStart(0) + 8 * HOUR_MS)
            val bpm = cyclingBpm(session.startMs)
            h.repository.addHeartRate(OHEALTH, session.startMs - 30 * MINUTE_MS, session.startMs + 307 * MINUTE_MS, bpm = bpm)
            val sender = RecordingSyncRequestSender()
            val followUps = RecordingFollowUps()
            val runner = h.runner(sender, followUps)
            assertInstanceOf(WorkOutcome.Completed::class.java, runner.run(inBackground = true))
            assertEquals(1, sender.sent)
            assertEquals(1, followUps.scheduled.size)
            val due = checkNotNull(h.pendingFollowUp())

            val checkedAtMin = mutableListOf<Int>()
            (1..8).forEach { run ->
                h.clock.advanceBy(15 * MINUTE_MS)
                val reads = h.repository.callCount(Operation.READ_HEART_RATE)
                val outcome = assertInstanceOf(WorkOutcome.Completed::class.java, runner.run(inBackground = true))
                if (h.repository.callCount(Operation.READ_HEART_RATE) > reads) checkedAtMin += run * 15
                assertFalse(outcome.summary.sleepAsAndroidSyncRequested)
                assertEquals(0, outcome.summary.refreshed)
            }

            assertEquals(listOf(60, 120), checkedAtMin, "the hourly checks")
            assertEquals(1, sender.sent, "a check that finds nothing new asks nothing")
            assertEquals(due, h.pendingFollowUp(), "the follow-up is left to its own work")

            h.repository.addHeartRate(OHEALTH, session.startMs + 308 * MINUTE_MS, session.endMs + 30 * MINUTE_MS, bpm = bpm)
            h.clock.advanceBy(NewNightsSync.MIN_RECHECK_GAP_MS)
            val rewrite = assertInstanceOf(WorkOutcome.Completed::class.java, runner.run(inBackground = true))

            assertEquals(1, rewrite.summary.refreshed)
            assertEquals(0, rewrite.summary.estimated)
            assertTrue(rewrite.summary.sleepAsAndroidSyncRequested)
            assertEquals(2, sender.sent, "exactly one request for the rewrite")
            assertEquals(1, followUps.scheduled.size, "no new follow-up for a rewrite")
            assertEquals(1, followUps.cancelled, "the follow-up that was due is taken by this request")
            assertNull(h.pendingFollowUp())
        }

    @Test
    fun `a background run without the background permission is blocked, reads nothing and is not retried`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            h.repository.inBackground = true
            h.repository.clearCalls()

            val outcome = assertInstanceOf(WorkOutcome.Blocked::class.java, h.runner().run(inBackground = true))

            assertEquals(BlockedReason.BACKGROUND_PERMISSION_MISSING, outcome.reason)
            assertEquals(listOf(Operation.GRANTED_PERMISSIONS), h.repository.calls.distinct(), "no read is even attempted")
            assertTrue(kinds(outcome).all { it == ResultKind.SUCCESS })
            assertEquals(
                SyncOutcome.BACKGROUND_READ_BLOCKED,
                h.syncState.state
                    .first()
                    .lastRunOutcome,
            )
        }

    @Test
    fun `losing the foreground mid-run without the background permission is reported as such`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            h.estimator.beforeEstimate = { h.repository.inBackground = true }

            val outcome = assertInstanceOf(WorkOutcome.Blocked::class.java, h.runner().run(inBackground = false))

            assertEquals(BlockedReason.BACKGROUND_PERMISSION_MISSING, outcome.reason)
        }

    @Test
    fun `a quota error is retried with backoff`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.quotaAfter(calls = 1)

            val outcome = assertInstanceOf(WorkOutcome.Retry::class.java, h.runner().run(inBackground = true))

            assertEquals(RetryCause.QUOTA, outcome.cause)
            assertEquals(ResultKind.RETRY, WorkResults.kindOf(outcome, 0, limits))
            assertEquals(ResultKind.RETRY, WorkResults.kindOf(outcome, limits.maxRetries - 1, limits))
            assertEquals(ResultKind.FAILURE, WorkResults.kindOf(outcome, limits.maxRetries, limits), "a safety net after many retries")
            assertEquals(
                SyncOutcome.QUOTA_EXCEEDED,
                h.syncState.state
                    .first()
                    .lastRunOutcome,
            )
        }

    @Test
    fun `a transient error is retried`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.failOn(Operation.CHANGES_TOKEN, HealthDataException.Transient("binder died"))

            val outcome = assertInstanceOf(WorkOutcome.Retry::class.java, h.runner().run(inBackground = true))

            assertEquals(RetryCause.TRANSIENT, outcome.cause)
            assertEquals(ResultKind.RETRY, WorkResults.kindOf(outcome, 0, limits))
        }

    @Test
    fun `an unexpected error is never thrown, it is retried a few times, then the work fails`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.estimator.failWhen = { AssertionError("a bug that escapes the per-night isolation") }

            val outcome = assertInstanceOf(WorkOutcome.Unexpected::class.java, h.runner().run(inBackground = true))

            assertTrue(outcome.detail.contains("AssertionError"))
            val unexpectedKinds = (0..limits.maxUnexpectedRetries).map { WorkResults.kindOf(outcome, it, limits) }
            assertEquals(List(limits.maxUnexpectedRetries) { ResultKind.RETRY } + ResultKind.FAILURE, unexpectedKinds)
        }

    @Test
    fun `invalid data from Health Connect is an unexpected error`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.failOn(Operation.READ_SLEEP_SESSIONS, HealthDataException.InvalidData("bad request"))

            assertInstanceOf(WorkOutcome.Unexpected::class.java, h.runner().run(inBackground = true))
        }

    @Test
    fun `a failing night is logged and the run still completes`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            h.estimator.failWhen = { input -> IllegalStateException("bad night").takeIf { input.sessionStartMs == nightStart(2) } }

            val outcome = assertInstanceOf(WorkOutcome.Completed::class.java, h.runner().run(inBackground = true))

            assertEquals(1, outcome.summary.failed)
            assertEquals(1, outcome.summary.estimated)
        }

    @Test
    fun `cancellation is propagated, not swallowed`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            val entered = CompletableDeferred<Unit>()
            h.estimator.beforeEstimate = {
                entered.complete(Unit)
                awaitCancellation()
            }
            var finished = false
            val job =
                launch {
                    h.runner().run(inBackground = true)
                    finished = true
                }
            entered.await()

            job.cancel()
            job.join()

            assertTrue(job.isCancelled)
            assertTrue(!finished, "the runner rethrew the cancellation")
            assertEquals(
                SyncOutcome.CANCELLED,
                h.syncState.state
                    .first()
                    .lastRunOutcome,
            )
        }

    @Test
    fun `a completed run adapts the personal model when population EM is due`() =
        harnessTest(dir) { h ->
            h.settings.set { it.copy(algo = it.algo.copy(personal = it.algo.personal.copy(emEveryNights = 2))) }
            val person = SyntheticPerson(baselineBpm = 60.0, remOffsetBpm = 11.0, awakeOffsetBpm = 20.0)
            h.repository.addSyntheticNight(OHEALTH, nightStart(2), seed = 71, person = person, outlierRate = 0.0)
            h.repository.addSyntheticNight(OHEALTH, nightStart(1), seed = 72, person = person, outlierRate = 0.0)

            val outcome = assertInstanceOf(WorkOutcome.Completed::class.java, h.runner().run(inBackground = true))

            assertTrue(outcome.summary.personalModelUpdated)
            assertEquals(
                PersonalModelKind.EM,
                h.ledger.personalModelDao
                    .getActive()
                    ?.kind,
            )

            val again = assertInstanceOf(WorkOutcome.Completed::class.java, h.runner().run(inBackground = true))
            assertTrue(!again.summary.personalModelUpdated, "not due again")
        }

    @Test
    fun `an idle background run, population EM included, makes only the permission checks and one change-log read`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(0))
            h.runner().run(inBackground = false)
            h.clock.advanceBy(15 * MINUTE_MS)
            h.repository.inBackground = true
            h.repository.clearCalls()

            val outcome = assertInstanceOf(WorkOutcome.Completed::class.java, h.runner().run(inBackground = true))

            assertEquals(RunSummary(), outcome.summary)
            assertEquals(listOf(Operation.GRANTED_PERMISSIONS, Operation.GRANTED_PERMISSIONS, Operation.CHANGES), h.repository.calls)
        }

    @Test
    fun `a run the user asked for re-checks a recent night that a scheduled run 15 minutes after its check leaves alone`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(0))
            val runner = h.runner()
            runner.run(inBackground = false)
            h.clock.advanceBy(15 * MINUTE_MS)
            h.repository.clearCalls()

            assertInstanceOf(WorkOutcome.Completed::class.java, runner.run(inBackground = false))
            assertEquals(0, h.repository.callCount(Operation.READ_HEART_RATE), "a scheduled run keeps the hourly cadence")

            assertInstanceOf(WorkOutcome.Completed::class.java, runner.run(inBackground = false, userRequested = true))
            assertEquals(1, h.repository.callCount(Operation.READ_HEART_RATE), "checked again at once")
        }
}

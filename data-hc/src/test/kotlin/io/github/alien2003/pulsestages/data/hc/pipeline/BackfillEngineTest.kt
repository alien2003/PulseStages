package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MIDNIGHT
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.BackfillStateEntity
import io.github.alien2003.pulsestages.data.local.db.BackfillStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BackfillEngineTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `the whole history is processed forward in 7-day windows with progress`() =
        harnessTest(dir) { h ->
            (20 downTo 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            val progress = mutableListOf<BackfillStateEntity>()

            val report = h.backfill.run(inBackground = false) { progress += it }

            assertEquals(BackfillOutcome.DONE, report.outcome)
            val state = checkNotNull(report.state)
            assertEquals(BackfillStatus.DONE, state.status)
            assertEquals(MIDNIGHT - 21 * DAY_MS, state.rangeStartMs, "the range starts at the day of the oldest session")
            assertEquals(NOON, state.rangeEndMs)
            assertEquals(4, state.windowsTotal)
            assertEquals(4, state.windowsDone)
            assertEquals(20, state.nightsFound)
            assertEquals(20, state.nightsEstimated)
            assertEquals(0, state.nightsFailed)
            assertFalse(state.historyLimited)
            assertEquals(listOf(0, 1, 2, 3, 4, 4), progress.map { it.windowsDone })

            val starts = h.estimator.inputs.map { it.sessionStartMs }
            assertEquals(starts.sorted(), starts, "oldest first")
            assertEquals(20, starts.distinct().size, "every night is estimated exactly once across windows")
            assertEquals(20, h.repository.ownSessions().size)
            assertTrue(
                h.estimator.inputs
                    .drop(3)
                    .all { it.baselineNadirBpm != null },
                "later nights have a baseline",
            )
        }

    @Test
    fun `a night crossing a window boundary is merged and written once by the window of its first fragment`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(20))
            val boundary = MIDNIGHT - 21 * DAY_MS + 7 * DAY_MS
            val first = h.repository.addNight(OHEALTH, boundary - HOUR_MS, durationMs = 3 * HOUR_MS)
            val second = h.repository.addNight(OHEALTH, boundary + 2 * HOUR_MS + 15 * MINUTE_MS, durationMs = 4 * HOUR_MS + 45 * MINUTE_MS)

            val state = checkNotNull(h.backfill.run(inBackground = false).state)

            assertEquals(MIDNIGHT - 21 * DAY_MS, state.rangeStartMs)
            val merged = h.repository.ownSessions().single { it.startMs > nightStart(20) }
            assertEquals(NightKeys.clientRecordId(listOf(first.uuid, second.uuid)), merged.clientRecordId)
            assertEquals(first.startMs, merged.startMs)
            assertEquals(second.endMs, merged.endMs)
            assertEquals(1, merged.stages.single { it.startMs <= first.endMs && it.endMs > first.endMs }.type, "the gap is AWAKE")
            assertEquals(2, h.repository.ownSessions().size)
        }

    @Test
    fun `a backfill whose app leaves the foreground without the background permission is blocked for that reason`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            h.repository.inBackground = true

            val report = h.backfill.run(inBackground = false)

            assertEquals(BackfillOutcome.BLOCKED, report.outcome)
            assertEquals(BlockedReason.BACKGROUND_PERMISSION_MISSING, report.blockedReason)
        }

    @Test
    fun `backfillDays limits the range`() =
        harnessTest(dir) { h ->
            (10 downTo 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            h.settings.set { it.copy(sources = it.sources.copy(backfillDays = 5)) }

            val state = checkNotNull(h.backfill.run(inBackground = false).state)

            assertEquals(NOON - 5 * DAY_MS, state.rangeStartMs)
            assertEquals(1, state.windowsTotal)
            assertEquals(4, h.repository.ownSessions().size)
        }

    @Test
    fun `without READ_HEALTH_DATA_HISTORY only the visible history is processed and the state says so`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(40))
            val visible = h.repository.addNight(OHEALTH, nightStart(10))
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_HISTORY)
            h.repository.historyStartMs = NOON - 30 * DAY_MS

            val state = checkNotNull(h.backfill.run(inBackground = false).state)

            assertTrue(state.historyLimited)
            assertEquals(listOf(visible.startMs), h.repository.ownSessions().map { it.startMs })
        }

    @Test
    fun `a quota error leaves a resumable state and the next run continues from the cursor`() =
        harnessTest(dir) { h ->
            (20 downTo 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            h.repository.quotaAfter(70)

            val first = h.backfill.run(inBackground = false)

            assertEquals(BackfillOutcome.QUOTA_EXCEEDED, first.outcome)
            val interrupted = checkNotNull(first.state)
            assertEquals(BackfillStatus.WAITING_RETRY, interrupted.status)
            assertTrue(interrupted.windowsDone in 1 until interrupted.windowsTotal, "stopped mid-way: $interrupted")
            assertTrue(interrupted.cursorMs > interrupted.rangeStartMs)

            h.repository.clearFailures()
            h.repository.clearCalls()
            val second = h.backfill.run(inBackground = false)

            assertEquals(BackfillOutcome.DONE, second.outcome)
            val done = checkNotNull(second.state)
            assertEquals(interrupted.startedAt, done.startedAt, "resumed, not restarted")
            assertEquals(interrupted.rangeStartMs, done.rangeStartMs)
            assertEquals(done.windowsTotal - interrupted.windowsDone, h.repository.callCount(Operation.READ_SLEEP_SESSIONS))
            assertEquals(20, done.nightsFound)
            assertEquals(20, done.nightsEstimated, "nights written before the interruption still count")
            assertEquals(20, h.repository.ownSessions().size)
        }

    @Test
    fun `after process death a new engine resumes the RUNNING state`() =
        harnessTest(dir) { h ->
            (20 downTo 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            var job: Job? = null
            h.estimator.beforeEstimate = {
                if (h.estimator.inputs.size == 10) {
                    job?.cancel()
                    yield()
                }
            }
            job = launch { h.backfill.run(inBackground = false) }
            job.join()

            val killed = checkNotNull(h.ledger.backfillDao.get())
            assertEquals(BackfillStatus.RUNNING, killed.status, "coroutine cancellation keeps the backfill resumable")
            assertTrue(killed.windowsDone >= 1)

            h.estimator.beforeEstimate = {}
            val restarted =
                BackfillEngine(h.repository, h.settings, h.ledger.backfillDao, h.windowReader, h.pipeline, h.logger, h.clock)
            val report = restarted.run(inBackground = false)

            assertEquals(BackfillOutcome.DONE, report.outcome)
            assertEquals(killed.startedAt, report.state?.startedAt)
            assertEquals(20, report.state?.nightsEstimated)
            assertEquals(20, h.repository.ownSessions().size)
        }

    @Test
    fun `cancel stops the backfill between nights and the next run starts over`() =
        harnessTest(dir) { h ->
            (20 downTo 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            h.estimator.beforeEstimate = { if (h.estimator.inputs.size == 3) h.backfill.cancel() }

            val cancelled = h.backfill.run(inBackground = false)

            assertEquals(BackfillOutcome.CANCELLED, cancelled.outcome)
            assertEquals(
                BackfillStatus.CANCELLED,
                h.ledger.backfillDao
                    .get()
                    ?.status,
            )
            assertEquals(3, h.repository.ownSessions().size, "the night in progress finishes, no further night starts")

            h.estimator.beforeEstimate = {}
            h.clock.advanceBy(60_000)
            val again = h.backfill.run(inBackground = false)

            assertEquals(BackfillOutcome.DONE, again.outcome)
            assertNotEquals(cancelled.state?.startedAt, again.state?.startedAt, "a cancelled backfill starts over")
            assertEquals(20, h.repository.ownSessions().size)
            assertEquals(20, again.state?.nightsEstimated)
        }

    @Test
    fun `a second concurrent run is refused`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.estimator.beforeEstimate = { delay(10_000) }
            val running = launch { h.backfill.run(inBackground = false) }
            delay(1_000)

            assertEquals(BackfillOutcome.ALREADY_RUNNING, h.backfill.run(inBackground = false).outcome)

            running.join()
            assertEquals(
                BackfillStatus.DONE,
                h.ledger.backfillDao
                    .get()
                    ?.status,
            )
        }

    @Test
    fun `a background run without the background permission reads nothing`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            h.repository.inBackground = true

            val report = h.backfill.run(inBackground = true)

            assertEquals(BackfillOutcome.BLOCKED, report.outcome)
            assertEquals(BlockedReason.BACKGROUND_PERMISSION_MISSING, report.blockedReason)
            assertEquals(listOf(Operation.GRANTED_PERMISSIONS), h.repository.calls)
        }

    @Test
    fun `a transient error waits for a retry and resumes`() =
        harnessTest(dir) { h ->
            (20 downTo 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            h.repository.failOn(Operation.READ_SLEEP_SESSIONS, HealthDataException.Transient("binder died"), afterCalls = 2)

            val first = h.backfill.run(inBackground = false)

            assertEquals(BackfillOutcome.RETRY_LATER, first.outcome)
            assertEquals(BackfillStatus.WAITING_RETRY, first.state?.status)
            assertNotNull(first.state?.lastError)

            val second = h.backfill.run(inBackground = false)
            assertEquals(BackfillOutcome.DONE, second.outcome)
            assertEquals(20, h.repository.ownSessions().size)
        }
}

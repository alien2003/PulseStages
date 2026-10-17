package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * Check intervals shorter than an hour ([NewNightsSync.MIN_RECHECK_GAP_MS]): every run follows the Changes API, but the
 * late-data pass (the retry and the refresh of recent nights) keeps its hourly cadence, whatever its attempts record, so
 * a 15- or 30-minute interval adds only one change-log read per run; a run the user asked for runs the pass at once. The
 * Health Connect calls are those [io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository] logs behind the
 * production background rule: one entry per repository call (getSdkStatus is not logged).
 */
class ShortCheckIntervalTest {
    @TempDir
    lateinit var dir: File

    /**
     * Runs [runs] syncs [intervalMin] minutes apart (the clock moves before each) and returns the minutes after [fromMs]
     * (the start by default) at which a sync called [operation]: with the default, read heart rate, i.e. checked a night.
     */
    private suspend fun PipelineHarness.syncEvery(
        intervalMin: Int,
        runs: Int,
        operation: Operation = Operation.READ_HEART_RATE,
        fromMs: Long = clock.millis(),
    ): List<Int> {
        val calledAtMin = mutableListOf<Int>()
        repeat(runs) {
            clock.advanceBy(intervalMin * MINUTE_MS)
            val calls = repository.callCount(operation)
            assertEquals(SyncOutcome.SUCCESS, sync.run(inBackground = false).outcome)
            if (repository.callCount(operation) > calls) calledAtMin += ((clock.millis() - fromMs) / MINUTE_MS).toInt()
        }
        return calledAtMin
    }

    /** Start of the run whose late-data pass last completed. */
    private suspend fun PipelineHarness.lateDataPassAt(): Long? = syncState.state.first().lateDataPassAtMs

    @ParameterizedTest
    @ValueSource(ints = [15, 30])
    fun `with 15 or 30 minute checks a recent written night is still checked once an hour`(intervalMin: Int) =
        harnessTest(dir) { h ->
            // Ended 5 h before the first sync: recent for the whole day below.
            h.repository.addNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            h.repository.clearCalls()
            val runs = 24 * 60 / intervalMin

            val checkedAtMin = h.syncEvery(intervalMin, runs)

            assertEquals((1..24).map { it * 60 }, checkedAtMin, "every 60 min, never at the runs in between")
            assertEquals(runs, h.repository.callCount(Operation.CHANGES), "every run follows the Changes API")
            assertEquals(runs, h.repository.callCount(Operation.GRANTED_PERMISSIONS))
            assertEquals(24, h.repository.callCount(Operation.READ_HEART_RATE))
            assertEquals(24, h.repository.callCount(Operation.READ_SLEEP_SESSIONS), "one window read per refresh")
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertEquals(NOON + 24 * HOUR_MS, h.singleNight().inputCheckedAt)
        }

    @Test
    fun `96 checks a day at 15 minutes cost 96 change-log reads and the 24 hourly refreshes`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            h.repository.clearCalls()

            h.syncEvery(intervalMin = 15, runs = 96)

            assertEquals(96, h.repository.callCount(Operation.CHANGES))
            assertEquals(24, h.repository.callCount(Operation.READ_HEART_RATE))
            assertEquals(24, h.repository.callCount(Operation.READ_STEPS))
            assertEquals(0, h.repository.callCount(Operation.CHANGES_TOKEN), "the token stays valid")
        }

    @ParameterizedTest
    @ValueSource(ints = [15, 30])
    fun `with 15 or 30 minute checks a late night is checked 1, 2, 4, 8, 16 and 32 hours after it was first seen`(intervalMin: Int) =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            h.repository.addNight(OHEALTH, nightStart(3))
            h.sync.run(inBackground = false)
            assertEquals(1, h.repository.ownSessions().size)
            h.repository.clearCalls()

            val checkedAtMin = h.syncEvery(intervalMin, runs = 40 * 60 / intervalMin)

            assertEquals(listOf(1, 2, 4, 8, 16, 32).map { it * 60 }, checkedAtMin)
            assertEquals(0, h.repository.callCount(Operation.WRITE))
        }

    @Test
    fun `an idle run between the hourly checks makes exactly one change-log read and the permission checks`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            assertEquals(1, h.repository.ownSessions().size)

            // Foreground: HealthAccessGuard checks the permissions, then the Changes API (an empty page).
            h.clock.advanceBy(15 * MINUTE_MS)
            h.repository.clearCalls()
            val foreground = h.sync.run(inBackground = false)
            assertEquals(listOf(Operation.GRANTED_PERMISSIONS, Operation.CHANGES), h.repository.calls)

            // Background: the background rule checks READ_HEALTH_DATA_IN_BACKGROUND once more before the change log.
            h.clock.advanceBy(15 * MINUTE_MS)
            h.repository.inBackground = true
            h.repository.clearCalls()
            val background = h.sync.run(inBackground = true)
            assertEquals(listOf(Operation.GRANTED_PERMISSIONS, Operation.GRANTED_PERMISSIONS, Operation.CHANGES), h.repository.calls)

            listOf(foreground, background).forEach { report ->
                assertEquals(SyncOutcome.SUCCESS, report.outcome)
                assertEquals(PipelineReport(), report.pipeline, "nothing found, retried or refreshed")
                assertEquals(1, report.pages)
            }
            // The same night, 60 min after its last check, is refreshed again.
            h.clock.advanceBy(30 * MINUTE_MS)
            h.repository.clearCalls()
            val due = h.sync.run(inBackground = true)
            assertEquals(1, due.pipeline.inputChecks)
            assertEquals(1, h.repository.callCount(Operation.READ_HEART_RATE))
        }

    @Test
    fun `a run the user asked for re-checks a night checked 15 minutes earlier and retries one without an estimate`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(0))
            val unfinished = h.repository.addNight(OHEALTH, nightStart(1), withHeartRate = false)
            h.sync.run(inBackground = false)
            val statuses =
                h.ledger
                    .allNights()
                    .sortedBy { it.sessionStartMs }
                    .map { it.status }
            assertEquals(listOf(NightStatus.NOT_ESTIMATED, NightStatus.WRITTEN), statuses)

            h.clock.advanceBy(15 * MINUTE_MS)
            h.repository.addHeartRate(OHEALTH, unfinished.startMs, unfinished.endMs)
            h.repository.clearCalls()
            val periodic = h.sync.run(inBackground = false)
            assertEquals(listOf(Operation.GRANTED_PERMISSIONS, Operation.CHANGES), h.repository.calls, "nothing due yet")
            assertEquals(0, periodic.pipeline.estimated)

            val requested = h.sync.run(inBackground = false, userRequested = true)

            assertEquals(SyncOutcome.SUCCESS, requested.outcome)
            assertEquals(1, requested.pipeline.estimated, "the heart rate that arrived late is used at once")
            assertEquals(1, requested.pipeline.inputChecks, "the written night is checked again")
            assertEquals(2, h.repository.callCount(Operation.READ_HEART_RATE))
            assertTrue(h.ledger.allNights().all { it.status == NightStatus.WRITTEN })
            assertEquals(2, h.repository.ownSessions().size)
        }

    @Test
    fun `a night without an estimate of a source that is no longer enabled is not retried`() =
        harnessTest(dir) { h ->
            h.settings.set {
                it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OHEALTH, OTHER_APP)))
            }
            h.repository.addNight(OTHER_APP, nightStart(0), withHeartRate = false)
            h.sync.run(inBackground = false)
            val unfinished = h.singleNight()
            assertEquals(NightStatus.NOT_ESTIMATED, unfinished.status)
            assertEquals(OTHER_APP, unfinished.sourcePackage)
            h.settings.set { it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH))) }
            // A new package set: this run takes a new token and re-reads the last days by time range.
            h.clock.advanceBy(HOUR_MS)
            h.sync.run(inBackground = false)

            h.clock.advanceBy(HOUR_MS)
            h.repository.clearCalls()
            val report = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.SUCCESS, report.outcome)
            assertEquals(listOf(Operation.GRANTED_PERMISSIONS, Operation.CHANGES), h.repository.calls, "no sleep-session read for it")
            assertEquals(unfinished, h.singleNight(), "left as it is")
        }

    @Test
    fun `the late-data pass runs at most every 55 minutes, whenever the checks come`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            assertEquals(NOON, h.lateDataPassAt(), "the first run's pass")
            val checkedAtMin = mutableListOf<Int>()

            listOf(14, 28, 42, 54, 55, 70, 109, 110, 125, 166).forEach { minute ->
                h.clock.nowMs = NOON + minute * MINUTE_MS
                val reads = h.repository.callCount(Operation.READ_HEART_RATE)
                assertEquals(SyncOutcome.SUCCESS, h.sync.run(inBackground = false).outcome)
                if (h.repository.callCount(Operation.READ_HEART_RATE) > reads) checkedAtMin += minute
            }

            assertEquals(listOf(55, 110, 166), checkedAtMin, "an hourly run up to 5 min early still checks")
            assertEquals(NOON + 166 * MINUTE_MS, h.lateDataPassAt())
        }

    @Test
    fun `a night without an estimate whose retry records nothing is read once per pass, not by every run`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(0), withHeartRate = false)
            h.sync.run(inBackground = false)
            val unfinished = h.singleNight()
            assertEquals(NightStatus.NOT_ESTIMATED, unfinished.status)
            // The source app adds its own stages: the window read skips the session (HAS_STAGES) and the row stays as it is.
            h.repository.updateSleepSession(source.uuid) { it.copy(stages = listOf(HcStage(it.startMs, it.endMs, 4))) }
            h.clock.advanceBy(15 * MINUTE_MS)
            h.sync.run(inBackground = false)
            h.repository.clearCalls()

            val readAtMin = h.syncEvery(intervalMin = 15, runs = 16, operation = Operation.READ_SLEEP_SESSIONS, fromMs = NOON)

            assertEquals(listOf(60, 120, 180, 240), readAtMin, "the retry's window read: 4 of 16 runs")
            assertEquals(16, h.repository.callCount(Operation.CHANGES))
            assertEquals(unfinished, h.singleNight(), "no attempt recorded anything")
        }

    @Test
    fun `a written night that can no longer be estimated is read once per pass, not by every run`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            // The session now starts earlier and its night can no longer be estimated: the old output is kept, and
            // neither its write time nor its input check moves, so the changed sources make it due on every pass.
            h.repository.updateSleepSession(source.uuid) { it.copy(startMs = it.startMs - 30 * MINUTE_MS) }
            h.estimator.declineWhen = { EstimationOutcome.NotEstimated(NotEstimatedReason.LOW_COVERAGE, 0.3, "declined by the test") }
            h.clock.advanceBy(15 * MINUTE_MS)
            h.sync.run(inBackground = false)
            h.repository.clearCalls()

            val checkedAtMin = h.syncEvery(intervalMin = 15, runs = 16, fromMs = NOON)

            assertEquals(listOf(60, 120, 180, 240), checkedAtMin, "4 of 16 runs read its input")
            assertEquals(4, h.repository.callCount(Operation.READ_HEART_RATE))
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            val night = h.singleNight()
            assertEquals(NightStatus.WRITTEN, night.status)
            assertEquals(written.updatedAt, night.updatedAt)
            assertEquals(written.inputCheckedAt, night.inputCheckedAt)
        }

    @Test
    fun `more than 8 due nights after a backfill are worked off one pass at a time`() =
        harnessTest(dir) { h ->
            (12 downTo 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            h.backfill.run(inBackground = false)
            h.sync.run(inBackground = false)
            assertEquals(NOON, h.lateDataPassAt())
            h.repository.clearCalls()
            val checks = mutableMapOf<Int, Int>()

            repeat(12) { run ->
                h.clock.advanceBy(15 * MINUTE_MS)
                val report = h.sync.run(inBackground = false)
                assertEquals(SyncOutcome.SUCCESS, report.outcome)
                if (report.pipeline.inputChecks > 0) checks[(run + 1) * 15] = report.pipeline.inputChecks
            }

            assertEquals(listOf(60, 120, 180), checks.keys.toList(), "never two passes less than 55 min apart: $checks")
            assertEquals(NewNightsSync.MAX_REFRESH_NIGHTS_PER_RUN, checks.getValue(60), "8 of the 12 due nights")
            assertEquals(NewNightsSync.MAX_REFRESH_NIGHTS_PER_RUN, checks.getValue(120), "the other 4 first, then 4 more")
            assertTrue(h.logger.lines.any { "4 more written night(s) are due for a refresh" in it.message })
        }

    @Test
    fun `a run the user asked for runs the pass at once and the next scheduled pass waits 55 minutes from it`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            h.clock.advanceBy(30 * MINUTE_MS)
            h.repository.clearCalls()

            h.sync.run(inBackground = false, userRequested = true)

            assertEquals(1, h.repository.callCount(Operation.READ_HEART_RATE), "checked at once")
            assertEquals(NOON + 30 * MINUTE_MS, h.lateDataPassAt())
            val checkedAtMin = h.syncEvery(intervalMin = 15, runs = 6, fromMs = NOON)
            assertEquals(listOf(90), checkedAtMin, "not at 60 (30 min after the requested pass), but 60 min after it")
        }

    @Test
    fun `a pass that stopped early is not stored as complete and the next run tries it again`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            h.clock.advanceBy(HOUR_MS)
            h.repository.failOn(Operation.READ_HEART_RATE, HealthDataException.QuotaExceeded("quota"))

            val stopped = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.QUOTA_EXCEEDED, stopped.outcome)
            assertEquals(NOON, h.lateDataPassAt(), "the refresh stopped on the quota")
            h.clock.advanceBy(MINUTE_MS)
            h.repository.clearCalls()
            val retried = h.sync.run(inBackground = false)
            assertEquals(SyncOutcome.SUCCESS, retried.outcome)
            assertEquals(1, retried.pipeline.inputChecks, "the retry 1 min later runs the pass")
            assertEquals(NOON + 61 * MINUTE_MS, h.lateDataPassAt())
        }

    @Test
    fun `a pass time after the run's start, after the clock was set back, does not hold off the pass`() =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            assertEquals(NOON, h.lateDataPassAt())
            h.clock.nowMs = NOON - 2 * HOUR_MS

            h.sync.run(inBackground = false)

            assertEquals(NOON - 2 * HOUR_MS, h.lateDataPassAt())
            h.clock.advanceBy(15 * MINUTE_MS)
            h.sync.run(inBackground = false)
            assertEquals(NOON - 2 * HOUR_MS, h.lateDataPassAt(), "from then on the gap applies again")
        }
}

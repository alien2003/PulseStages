package io.github.alien2003.pulsestages.data.hc.pipeline

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * Check intervals shorter than an hour ([NewNightsSync.MIN_RECHECK_GAP_MS]): every run follows the Changes API, but the
 * retry and the refresh of a recent night keep their hourly cadence, so a 15- or 30-minute interval adds only one
 * change-log read per run; a run the user asked for re-checks at once. The Health Connect calls are those
 * [io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository] logs behind the production background rule:
 * one entry per repository call (getSdkStatus is not logged).
 */
class ShortCheckIntervalTest {
    @TempDir
    lateinit var dir: File

    /**
     * Runs [runs] syncs [intervalMin] minutes apart (the clock moves before each) and returns the minutes after the
     * start at which a sync read heart rate, i.e. checked a night.
     */
    private suspend fun PipelineHarness.syncEvery(
        intervalMin: Int,
        runs: Int,
    ): List<Int> {
        val checkedAtMin = mutableListOf<Int>()
        (1..runs).forEach { run ->
            clock.advanceBy(intervalMin * MINUTE_MS)
            val reads = repository.callCount(Operation.READ_HEART_RATE)
            assertEquals(SyncOutcome.SUCCESS, sync.run(inBackground = false).outcome)
            if (repository.callCount(Operation.READ_HEART_RATE) > reads) checkedAtMin += run * intervalMin
        }
        return checkedAtMin
    }

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
}

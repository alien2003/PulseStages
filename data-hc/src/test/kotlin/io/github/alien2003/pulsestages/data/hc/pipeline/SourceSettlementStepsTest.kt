package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * OHealth publishes last night (23:00–07:00) at 07:05, a sync writes it then, and at 08:15 its heart rate reaches 08:14:
 * past the threshold, 08:00.
 */
private suspend fun PipelineHarness.heartRatePastThreshold(): HcSleepSession {
    clock.nowMs = time(7, 5)
    val night = repository.addNight(OHEALTH, nightStart(0))
    syncAt(time(7, 5))
    clock.nowMs = time(8, 15)
    repository.addHeartRate(OHEALTH, time(7, 30), time(8, 15))
    return night
}

/**
 * [SourceSettlement]'s rule for steps: a source that writes fine-grained steps settles a recent session with heart rate of
 * its own in it only once one of its step records shows a walk from the time its heart rate must reach. The helpers are
 * those of [SourceSettlementTest].
 */
class SourceSettlementStepsTest {
    @TempDir
    lateinit var dir: File

    /**
     * A sleep-in: OHealth publishes the night cut at 07:29 while the user still sleeps, then uploads heart rate alone (the
     * watch's heart-rate-only report) past the end + margin without extending the session. Heart rate would settle it;
     * OHealth writes one-minute steps, and none show a walk since the threshold.
     */
    @Test
    fun `a heart-rate-only upload past the end of a provisional night does not settle it without a walk`() =
        harnessTest(dir, now = time(7, 30)) { h ->
            h.eveningWalk()
            val provisional = h.repository.addSleepSession(OHEALTH, time(0, 10), time(7, 29))
            h.repository.addHeartRate(OHEALTH, time(-1, 40), time(7, 29))
            h.syncAt(time(7, 34))
            h.repository.clearCalls()
            assertFalse(h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA).awaitingSteps)
            assertEquals(0, h.repository.callCount(Operation.READ_NEWEST_STEPS), "a check that heart rate holds reads no steps")
            assertEquals(0, h.repository.callCount(Operation.READ_STEPS))

            h.clock.nowMs = time(8, 45)
            h.repository.addHeartRate(OHEALTH, time(7, 29), time(8, 45))
            h.repository.clearCalls()
            val waiting = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
            assertTrue(waiting.awaitingSteps)
            assertTrue(waiting.ownHeartRate)
            assertEquals(SourceVersion.of(provisional), waiting.version)
            assertEquals(h.singleNight().clientRecordId, waiting.nightClientRecordId)
            assertEquals(time(8, 29), waiting.thresholdMs)
            assertEquals(time(8, 43), waiting.latestHeartRateMs)
            assertEquals(1, h.repository.callCount(Operation.READ_NEWEST_STEPS))
            assertEquals(1, h.repository.callCount(Operation.READ_STEPS))

            // Turning over in bed: a few steps in a minute are no walk, however many such minutes.
            h.clock.nowMs = time(9, 50)
            h.repository.addHeartRate(OHEALTH, time(8, 45), time(9, 50))
            (1L..4L).forEach { steps -> h.repository.addSteps(OHEALTH, time(9, steps.toInt()), time(9, steps.toInt() + 1), steps) }
            assertTrue(h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA).awaitingSteps)
        }

    @Test
    fun `a minute of 5 steps or more from the threshold on settles a night, fewer per minute do not`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.eveningWalk()
            h.heartRatePastThreshold()
            (1L..4L).forEach { steps -> h.repository.addSteps(OHEALTH, time(8, steps.toInt()), time(8, steps.toInt() + 1), steps) }
            h.repository.addSteps(OHEALTH, time(8, 5), time(8, 7), 9)
            val waiting = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
            assertTrue(waiting.awaitingSteps, "1-4 steps a minute, and 9 steps in 2 minutes")
            assertEquals(time(8, 0), waiting.thresholdMs)

            h.repository.addSteps(OHEALTH, time(8, 0), time(8, 1), 5)
            assertEquals(mapOf(OHEALTH to time(8, 14)), h.settle().settled().latestHeartRateMs)
        }

    @Test
    fun `steps that start before the threshold, of another app or more than 5 minutes after now do not count`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.eveningWalk()
            h.heartRatePastThreshold()
            h.walk(time(7, 5), time(8, 0))
            h.repository.addSteps(OHEALTH, time(7, 59), time(8, 1), 200)
            h.walk(time(8, 0), time(8, 15), packageName = WATCH)
            h.repository.addSteps(OHEALTH, time(8, 21), time(8, 22), 40)

            val waiting = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
            assertTrue(waiting.awaitingSteps)
            assertEquals(time(8, 0), waiting.thresholdMs)

            h.repository.addSteps(OHEALTH, time(8, 14), time(8, 15), 40)
            h.settle().settled()
        }

    @Test
    fun `each recent session of the package needs a walk from its own threshold, read once for all`() =
        harnessTest(dir, now = time(7, 30)) { h ->
            h.eveningWalk()
            h.repository.addSleepSession(OHEALTH, time(4, 21), time(6, 8))
            val last = h.repository.addSleepSession(OHEALTH, time(6, 31), time(7, 29))
            h.repository.addHeartRate(OHEALTH, time(3, 51), time(7, 29))
            h.syncAt(time(7, 34))
            h.clock.nowMs = time(9, 36)
            h.repository.addHeartRate(OHEALTH, time(7, 29), time(9, 36))
            h.repository.addSteps(OHEALTH, time(8, 0), time(8, 1), 30)
            h.repository.clearCalls()

            val waiting = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
            assertEquals(SourceVersion.of(last), waiting.version, "the earlier fragment's threshold is 07:35")
            assertEquals(time(8, 29), waiting.thresholdMs)
            assertTrue(waiting.awaitingSteps)
            assertEquals(
                listOf(Operation.READ_SLEEP_SESSIONS, Operation.READ_HEART_RATE, Operation.READ_NEWEST_STEPS, Operation.READ_STEPS),
                h.repository.calls,
            )

            h.repository.addSteps(OHEALTH, time(8, 29), time(8, 30), 30)
            h.settle().settled()
        }

    @Test
    fun `a package without steps in the last 7 days, or whose newest step record is longer than the margin, is held by heart rate alone`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.heartRatePastThreshold()
            val lookbackStart = time(8, 15) - SourceSettlement.STEPS_LOOKBACK_MS
            h.repository.addSteps(OHEALTH, lookbackStart - MINUTE_MS, lookbackStart, 40)
            h.repository.clearCalls()
            h.settle().settled()
            assertEquals(1, h.repository.callCount(Operation.READ_NEWEST_STEPS))
            assertEquals(0, h.repository.callCount(Operation.READ_STEPS), "no walk to look for")

            // Coarse totals: the newest record lasts 61 minutes, longer than the margin.
            h.repository.addSteps(OHEALTH, time(-3, 0), time(-2, 1), 900)
            h.settle().settled()

            // A record of 60 minutes is fine-grained enough: a walk after the threshold would show within the margin.
            h.repository.addSteps(OHEALTH, time(-3, 30), time(-2, 30), 900)
            assertTrue(h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA).awaitingSteps)
        }

    @Test
    fun `a step record shorter than a minute counts as a minute`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.eveningWalk()
            h.heartRatePastThreshold()
            h.repository.addSteps(OHEALTH, time(8, 10), time(8, 10) + 20_000L, 3)
            assertTrue(h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA).awaitingSteps, "3 steps in 20 s are 3 in that minute")

            h.repository.addSteps(OHEALTH, time(8, 12), time(8, 12) + 30_000L, 5)
            h.settle().settled()
        }

    @Test
    fun `whether a package writes fine-grained steps is told from its own steps`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.walk(time(-3, 0), time(-2, 0), packageName = WATCH)
            h.heartRatePastThreshold()
            h.repository.clearCalls()

            h.settle().settled()
            assertEquals(1, h.repository.callCount(Operation.READ_NEWEST_STEPS))
            assertEquals(0, h.repository.callCount(Operation.READ_STEPS), "OHealth wrote no steps: held by heart rate alone")
        }

    @Test
    fun `a one-minute step record of 6 days ago shows fine-grained steps`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.heartRatePastThreshold()
            h.repository.addSteps(OHEALTH, time(8, 15) - 6 * DAY_MS, time(8, 16) - 6 * DAY_MS, 40)

            assertTrue(h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA).awaitingSteps, "some days without the band")
        }

    @Test
    fun `the longest step record that counts as fine-grained follows mergeGapMin`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.repository.addNight(OHEALTH, nightStart(0))
            h.syncAt(time(7, 5))
            h.clock.nowMs = time(8, 30)
            h.repository.addHeartRate(OHEALTH, time(7, 30), time(8, 30))
            // Records of 70 minutes: longer than the default margin, 60 minutes ...
            h.repository.addSteps(OHEALTH, time(-3, 0), time(-2, 10), 900)
            h.settle().settled()

            // ... but not than the margin with mergeGapMin 45, 75 minutes, which heart rate also reaches.
            h.settings.set { it.copy(sources = it.sources.copy(mergeGapMin = 45)) }
            val waiting = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
            assertTrue(waiting.awaitingSteps)
            assertEquals(time(8, 15), waiting.thresholdMs)
        }

    @Test
    fun `a step record that starts up to 5 minutes after now is the newest one`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.heartRatePastThreshold()
            h.repository.addSteps(OHEALTH, time(-3, 0), time(-1, 0), 1800)
            h.settle().settled()

            // A minute of 2 steps, no walk, stamped by a clock a few minutes ahead: newer than the 2-hour record.
            h.repository.addSteps(OHEALTH, time(8, 19), time(8, 20), 2)
            assertTrue(h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA).awaitingSteps)
        }

    @Test
    fun `a wait for steps ends when the session leaves the scope 12 hours after its end`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.eveningWalk()
            val night = h.heartRatePastThreshold()
            h.clock.nowMs = time(18, 59)
            h.repository.addHeartRate(OHEALTH, time(8, 15), time(18, 59))
            assertTrue(h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA).awaitingSteps, "never a walk")

            h.clock.nowMs = night.endMs + SourceSettlement.SETTLE_TIMEOUT_MS + MINUTE_MS
            h.repository.clearCalls()
            val settled = h.settle().settled()
            assertEquals(listOf(SourceVersion.of(night)), settled.versions, "changed at 07:05: still in scope, no longer recent")
            assertEquals(0, h.repository.callCount(Operation.READ_HEART_RATE))
            assertEquals(0, h.repository.callCount(Operation.READ_NEWEST_STEPS))
        }

    /**
     * The sleep tracker (first by name) waits for steps, OHealth for heart rate: OHealth's wait is returned, with its
     * threshold as the time to check again; the tracker's comes once OHealth's heart rate has arrived.
     */
    @Test
    fun `the heart rate of every package is checked before the steps of any`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.enableSources(OHEALTH, OTHER_APP)
            h.walk(time(-3, 0), time(-2, 0), packageName = OTHER_APP)
            val tracker =
                h.repository.addSleepSession(OTHER_APP, nightStart(0), time(3, 0), stages = listOf(HcStage(nightStart(0), time(3, 0), 4)))
            h.repository.addHeartRate(OTHER_APP, nightStart(0) - 30 * MINUTE_MS, time(8, 15))
            val ohealth = h.repository.addSleepSession(OHEALTH, time(3, 30), time(7, 0))
            h.repository.addHeartRate(OHEALTH, time(3, 0), time(7, 30))
            h.syncAt(time(7, 5))

            h.clock.nowMs = time(7, 50)
            val heartRate = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
            assertEquals(SourceVersion.of(ohealth), heartRate.version)
            assertFalse(heartRate.awaitingSteps)
            assertEquals(time(8, 0), heartRate.thresholdMs)

            h.clock.nowMs = time(8, 15)
            h.repository.addHeartRate(OHEALTH, time(7, 30), time(8, 15))
            val steps = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
            assertEquals(SourceVersion.of(tracker), steps.version)
            assertTrue(steps.awaitingSteps)
            assertEquals(time(7, 10), steps.thresholdMs, "changed at 07:05, ended at 03:00")
        }

    @Test
    fun `a session without heart rate of its package in it is not held by steps`() =
        harnessTest(dir, now = time(7, 40)) { h ->
            h.enableSources(OTHER_APP)
            h.walk(time(-3, 0), time(-2, 0), packageName = OTHER_APP)
            h.repository.addSleepSession(OTHER_APP, nightStart(0), time(7, 0), stages = listOf(HcStage(nightStart(0), time(7, 0), 4)))
            h.syncAt(time(7, 40))

            h.clock.nowMs = time(8, 40)
            h.repository.clearCalls()
            val settled = h.settle().settled()
            assertEquals(1, settled.versions.size)
            assertEquals(0, h.repository.callCount(Operation.READ_NEWEST_STEPS), "the quiet rule as before")
            assertEquals(0, h.repository.callCount(Operation.READ_STEPS))
        }

    @Test
    fun `Health Connect errors of the steps reads fail the check`() =
        harnessTest(dir) { h ->
            h.settledNight()
            h.eveningWalk()
            val newest = HealthDataException.Transient("fake: newest steps")
            h.repository.failOn(Operation.READ_NEWEST_STEPS, newest)
            assertSame(newest, assertInstanceOf(Settlement.Failed::class.java, h.settle()).error)
            val steps = HealthDataException.QuotaExceeded("fake: steps")
            h.repository.failOn(Operation.READ_STEPS, steps)
            assertSame(steps, assertInstanceOf(Settlement.Failed::class.java, h.settle()).error)

            h.repository.revoke(HealthPermissions.READ_STEPS)
            val denied = assertInstanceOf(Settlement.Failed::class.java, h.settle()).error
            assertInstanceOf(HealthDataException.PermissionDenied::class.java, denied)
        }
}

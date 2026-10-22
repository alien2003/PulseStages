package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SourceSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Gate G4 of docs/prereg/prereg-merge-gap.md, the Sleep as Android side: [SourceSettlement] gives each session the margin
 * threshold(K) + [SourceSettlement.DATA_MARGIN_EXTRA_MS] of the fixture, under every arm; and under arm C a night that
 * covers at least the nap threshold waits for heart rate 90 min after its end, a shorter session 60 min.
 */
class SourceSettlementMergeGapTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `the margin of every session of every case is the fixture's threshold plus 30 min`() =
        harnessTest(dir) { h ->
            val fixture = MergeGapFixture.load()
            assertEquals(SourceSettlement.DATA_MARGIN_EXTRA_MS, fixture.dataMarginExtraMs)
            val mismatches =
                fixture.cases.flatMap { case ->
                    fixture.arms.mapNotNull { arm ->
                        val settings = AppSettings(sources = MergeGapFixture.sources(case, arm), algo = MergeGapFixture.algo(case))
                        val got = h.settlement.marginsOf(case.sessions, settings)
                        val want =
                            case.expected
                                .getValue(arm.name)
                                .thresholds
                                .mapValues { it.value + fixture.dataMarginExtraMs }
                        if (got == want) null else "${case.name} / ${arm.name}: got $got, want $want"
                    }
                }
            assertEquals(emptyList<String>(), mismatches)
        }

    @Test
    fun `the largest margin follows the larger of the two gaps`() {
        val c = AppSettings(sources = SourceSettings(mergeGapMin = 30, continuationGapMin = 60))
        assertEquals(90 * MINUTE_MS, SourceSettlement.dataMarginMs(c))
        assertEquals(60 * MINUTE_MS, SourceSettlement.dataMarginMs(AppSettings()))
        val small = AppSettings(sources = SourceSettings(mergeGapMin = 45, continuationGapMin = 20))
        assertEquals(75 * MINUTE_MS, SourceSettlement.dataMarginMs(small), "a continuation gap below the merge gap is unused")
    }

    @Test
    fun `under arm C a long night waits for heart rate 90 min after its end`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.settings.set { it.copy(sources = it.sources.copy(continuationGapMin = 60)) }
            h.settledNight()

            val waiting = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
            assertEquals(time(8, 30), waiting.thresholdMs, "the night (23:00-07:00) covers 8 h >= 3 h: 60 + 30 min")
            h.clock.nowMs = time(8, 41)
            h.repository.addHeartRate(OHEALTH, time(8, 10), time(8, 41))
            h.settle().settled()
        }

    @Test
    fun `under arm C a session that covers less than the nap threshold keeps the 60-min margin`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.settings.set { it.copy(sources = it.sources.copy(continuationGapMin = 60)) }
            h.repository.addSleepSession(OHEALTH, time(5, 0), time(7, 0))
            h.repository.addHeartRate(OHEALTH, time(4, 30), time(8, 10))
            h.syncAt(time(8, 15))

            h.settle().settled()
        }
}

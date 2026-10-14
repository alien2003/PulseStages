package io.github.alien2003.pulsestages.ui.compare

import io.github.alien2003.pulsestages.algo.metrics.IntervalScoring
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceNight
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceNightScore
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Compare screen's metrics split nights and naps when the reference sessions are of both modes. */
class CompareMetricsTest {
    private val minute = 60_000L

    private fun score(
        mode: SessionMode,
        startMs: Long,
        minutes: Long,
        predicted: SleepStage,
    ): ReferenceNightScore {
        val end = startMs + minutes * minute
        val truth = listOf(StageInterval(startMs, end, SleepStage.LIGHT))
        val session = HcSleepSession("u$startMs", "app", startMs, end, 0, 0, emptyList(), null, null, null, 0, 0)
        val night = ReferenceNight(session, truth, NightInput(startMs, end, listOf(HrSample(startMs, 60.0))), null)
        val stages = listOf(StageInterval(startMs, end, predicted))
        return ReferenceNightScore(night, EstimatorType.HYBRID, IntervalScoring.score(truth, stages), null, mode)
    }

    @Test
    fun `nights and naps are reported apart only when both occur`() {
        val night = score(SessionMode.NIGHT, 0, 480, SleepStage.LIGHT)
        val nap = score(SessionMode.NAP, 1_000 * minute, 60, SleepStage.DEEP)

        val mixed = CompareMetrics.of(ReferenceReport(listOf(night, nap)))

        assertEquals(listOf(SessionMode.NIGHT, SessionMode.NAP), mixed.byMode.map { it.mode })
        assertEquals(1.0, mixed.byMode.first().accuracy)
        assertEquals(0.0, mixed.byMode.last().accuracy)
        assertEquals(listOf(1, 1), mixed.byMode.map { it.scoredNights })
        assertEquals(2, mixed.scoredNights)
        assertTrue(CompareMetrics.of(ReferenceReport(listOf(night))).byMode.isEmpty(), "nights only: no split")
        assertEquals(SessionMode.NAP, ReferenceNightItem.of(nap, emptyMap()).mode)
    }
}

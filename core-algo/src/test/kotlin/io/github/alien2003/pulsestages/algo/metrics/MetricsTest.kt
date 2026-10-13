package io.github.alien2003.pulsestages.algo.metrics

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.SleepStage.AWAKE
import io.github.alien2003.pulsestages.algo.model.SleepStage.DEEP
import io.github.alien2003.pulsestages.algo.model.SleepStage.LIGHT
import io.github.alien2003.pulsestages.algo.model.SleepStage.REM
import io.github.alien2003.pulsestages.algo.model.SleepStage.UNKNOWN
import io.github.alien2003.pulsestages.algo.model.StageInterval
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricsTest {
    private fun repeatPairs(vararg groups: Triple<SleepStage, SleepStage, Int>): Pair<List<SleepStage>, List<SleepStage>> {
        val truth = groups.flatMap { (t, _, n) -> List(n) { t } }
        val predicted = groups.flatMap { (_, p, n) -> List(n) { p } }
        return truth to predicted
    }

    @Test
    fun `kappa of the Wikipedia two-rater examples`() {
        // Yes = AWAKE, No = LIGHT. (a, b, c, d) = (20, 5, 10, 15) -> kappa 0.4.
        val (t1, p1) = repeatPairs(Triple(AWAKE, AWAKE, 20), Triple(AWAKE, LIGHT, 5), Triple(LIGHT, AWAKE, 10), Triple(LIGHT, LIGHT, 15))
        assertEquals(0.4, Metrics.cohenKappa(t1, p1), 1e-12)
        assertEquals(0.7, Metrics.confusion(t1, p1).accuracy, 1e-12)
        // (45, 15, 25, 15): p_o = 0.6, p_e = 0.54 -> 0.1304...
        val (t2, p2) = repeatPairs(Triple(AWAKE, AWAKE, 45), Triple(AWAKE, LIGHT, 15), Triple(LIGHT, AWAKE, 25), Triple(LIGHT, LIGHT, 15))
        assertEquals((0.6 - 0.54) / (1 - 0.54), Metrics.cohenKappa(t2, p2), 1e-12)
        // (25, 35, 5, 35): p_o = 0.6, p_e = 0.46 -> 0.2593...
        val (t3, p3) = repeatPairs(Triple(AWAKE, AWAKE, 25), Triple(AWAKE, LIGHT, 35), Triple(LIGHT, AWAKE, 5), Triple(LIGHT, LIGHT, 35))
        assertEquals((0.6 - 0.46) / (1 - 0.46), Metrics.cohenKappa(t3, p3), 1e-12)
    }

    @Test
    fun `kappa matches scikit-learn on a three-class example`() {
        // sklearn.metrics.cohen_kappa_score([2, 0, 2, 2, 0, 1], [0, 0, 2, 2, 0, 2]) = 0.42857142857142855
        val classes = listOf(AWAKE, LIGHT, DEEP)
        val truth = listOf(2, 0, 2, 2, 0, 1).map { classes[it] }
        val predicted = listOf(0, 0, 2, 2, 0, 2).map { classes[it] }

        assertEquals(0.42857142857142855, Metrics.cohenKappa(truth, predicted), 1e-12)
    }

    @Test
    fun `perfect agreement, chance agreement and degenerate cases`() {
        val seq = listOf(AWAKE, LIGHT, DEEP, REM, LIGHT)
        assertEquals(1.0, Metrics.cohenKappa(seq, seq), 1e-12)
        val (t, p) = repeatPairs(Triple(AWAKE, AWAKE, 25), Triple(AWAKE, LIGHT, 25), Triple(LIGHT, AWAKE, 25), Triple(LIGHT, LIGHT, 25))
        assertEquals(0.0, Metrics.cohenKappa(t, p), 1e-12)
        assertTrue(Metrics.cohenKappa(listOf(LIGHT, LIGHT), listOf(LIGHT, LIGHT)).isNaN())
        assertTrue(Metrics.cohenKappa(emptyList(), emptyList()).isNaN())
    }

    @Test
    fun `unknown pairs are skipped and counted`() {
        val matrix = Metrics.confusion(listOf(AWAKE, UNKNOWN, LIGHT, DEEP), listOf(AWAKE, LIGHT, UNKNOWN, DEEP))

        assertEquals(2, matrix.total)
        assertEquals(2, matrix.skipped)
        assertEquals(1.0, matrix.accuracy, 0.0)
    }

    @Test
    fun `precision, recall, F1 and macro F1`() {
        // truth AWAKE x4: predicted AWAKE 3, LIGHT 1; truth LIGHT x4: AWAKE 1, LIGHT 2, DEEP 1; truth DEEP x2: DEEP 2.
        val (t, p) =
            repeatPairs(
                Triple(AWAKE, AWAKE, 3),
                Triple(AWAKE, LIGHT, 1),
                Triple(LIGHT, AWAKE, 1),
                Triple(LIGHT, LIGHT, 2),
                Triple(LIGHT, DEEP, 1),
                Triple(DEEP, DEEP, 2),
            )
        val m = Metrics.confusion(t, p)

        assertEquals(0.75, m.stage(AWAKE).precision, 1e-12)
        assertEquals(0.75, m.stage(AWAKE).recall, 1e-12)
        assertEquals(2.0 / 3.0, m.stage(LIGHT).precision, 1e-12)
        assertEquals(0.5, m.stage(LIGHT).recall, 1e-12)
        assertEquals(2 * (2.0 / 3) * 0.5 / (2.0 / 3 + 0.5), m.stage(LIGHT).f1, 1e-12)
        assertEquals(2.0 / 3.0, m.stage(DEEP).precision, 1e-12)
        assertEquals(1.0, m.stage(DEEP).recall, 1e-12)
        assertEquals(0.0, m.stage(REM).precision, 0.0)
        assertEquals(0.0, m.stage(REM).f1, 0.0)
        val macro = (m.stage(AWAKE).f1 + m.stage(LIGHT).f1 + m.stage(DEEP).f1) / 3
        assertEquals(macro, m.macroF1, 1e-12)
        assertEquals(m, Metrics.confusion(t, p))
        assertEquals(20, (m + m).total)
        assertEquals(m.kappa, (m + m).kappa, 1e-12)
    }

    @Test
    fun `interval scoring samples the prediction at 30-second chunk midpoints`() {
        val t0 = 1_000_000L
        val truth = listOf(StageInterval(t0, t0 + 75_000, LIGHT), StageInterval(t0 + 75_000, t0 + 135_000, DEEP))
        val predicted = listOf(StageInterval(t0, t0 + 40_000, LIGHT), StageInterval(t0 + 40_000, t0 + 120_000, DEEP))

        val chunks = IntervalScoring.chunks(truth, predicted)

        assertEquals(listOf(30_000L, 30_000L, 15_000L, 30_000L, 30_000L), chunks.map { it.endMs - it.startMs })
        // Midpoints: 15 s (LIGHT), 45 s (DEEP), 67.5 s (DEEP), 90 s (DEEP), 120 s (end of prediction -> UNKNOWN).
        assertEquals(listOf(LIGHT, DEEP, DEEP, DEEP, UNKNOWN), chunks.map { it.predicted })
        val score = IntervalScoring.score(truth, predicted)
        assertEquals(4, score.confusion.total)
        assertEquals(1, score.confusion.skipped)
        assertEquals(30.0 / 60, score.truthMinutes.deep, 1e-12)
        assertEquals(75.0 / 60, score.predictedMinutes.deep, 1e-12)
        assertEquals(45.0 / 60, score.minutesError.deep, 1e-12)
    }

    @Test
    fun `stage minutes of intervals`() {
        val minutes =
            Metrics.stageMinutes(
                listOf(StageInterval(0, 600_000, AWAKE), StageInterval(600_000, 690_000, REM), StageInterval(690_000, 750_000, UNKNOWN)),
            )

        assertEquals(StageMinutes(awake = 10.0, light = 0.0, deep = 0.0, rem = 1.5), minutes)
        val error = StageMinutesError.between(minutes, StageMinutes(awake = 7.0, light = 1.0, deep = 2.0, rem = 1.0))
        assertEquals(StageMinutesError(deep = 2.0, rem = 0.5, awake = 3.0), error)
    }
}

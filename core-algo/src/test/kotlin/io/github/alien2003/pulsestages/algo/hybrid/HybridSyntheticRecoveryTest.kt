package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.metrics.ConfusionMatrix
import io.github.alien2003.pulsestages.algo.metrics.IntervalScoring
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.DurationModel
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.testing.SyntheticHybridModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * HYBRID recovery of 30 held-out synthetic nights (seeds 2001..2030) with a bundle trained by the test fixture on
 * nights 1001..1040 (softmax regression on the hybrid features, durations / transitions / time prior counted from the
 * synthetic truth), default [AlgoParams], scored with [IntervalScoring] (30-s chunks).
 *
 * Observed (ALGO_VERSION 1 after the review fixes), per-night Cohen's kappa:
 *
 * | estimator          | mean   | median | min    | pooled accuracy |
 * |--------------------|--------|--------|--------|-----------------|
 * | HYBRID / EXPLICIT  | 0.7349 | 0.7304 | 0.5663 | 0.8377          |
 * | HYBRID / GEOMETRIC | 0.7241 | 0.7233 | 0.5625 | 0.8301          |
 * | HMM (same nights)  | 0.5065 | 0.5195 | 0.2600 | 0.6663          |
 *
 * Regression thresholds = mean of the first run (EXPLICIT 0.7411, GEOMETRIC 0.7348) − 0.05, rounded down to 0.01:
 * EXPLICIT 0.69, GEOMETRIC 0.68; and HYBRID must not be worse than the HMM on the same nights. Synthetic data
 * overstate real accuracy (the bundle is trained on the same generator); these are regression guards only (see
 * core-algo/SYNTHETIC_RESULTS.md).
 */
class HybridSyntheticRecoveryTest {
    private val nights = (2001L..2030L).map { SyntheticNightGenerator(it).generate() }
    private val stager = SleepStager(SyntheticHybridModels.default)

    private class Summary(
        val kappas: List<Double>,
        val pooled: ConfusionMatrix,
    ) {
        val mean: Double get() = kappas.average()
        val median: Double get() = kappas.sorted().let { (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        val min: Double get() = kappas.min()

        override fun toString(): String =
            String.format(Locale.ROOT, "mean=%.4f median=%.4f min=%.4f accuracy=%.4f", mean, median, min, pooled.accuracy)
    }

    private fun evaluate(
        type: EstimatorType,
        durationModel: DurationModel = DurationModel.EXPLICIT,
    ): Summary {
        val base = AlgoParams(estimator = type)
        val params = base.copy(hybrid = base.hybrid.copy(durationModel = durationModel))
        val scores =
            nights.map { night ->
                val estimate = (stager.run(night.input, params) as EstimationOutcome.Estimated).estimate
                assertEquals(type, estimate.estimator)
                IntervalScoring.score(night.truth, estimate.stages).confusion
            }
        return Summary(scores.map { it.kappa }, scores.reduce { a, b -> a + b })
    }

    @Test
    fun `hybrid recovers held-out synthetic hypnograms`() {
        val explicit = evaluate(EstimatorType.HYBRID, DurationModel.EXPLICIT)
        val geometric = evaluate(EstimatorType.HYBRID, DurationModel.GEOMETRIC)
        val hmm = evaluate(EstimatorType.HMM)
        println("synthetic recovery HYBRID/EXPLICIT: $explicit")
        println("synthetic recovery HYBRID/GEOMETRIC: $geometric")
        println("synthetic recovery HMM (same nights): $hmm")

        assertTrue(explicit.mean >= EXPLICIT_MIN_MEAN_KAPPA, "HYBRID/EXPLICIT mean kappa ${explicit.mean} < $EXPLICIT_MIN_MEAN_KAPPA")
        assertTrue(geometric.mean >= GEOMETRIC_MIN_MEAN_KAPPA, "HYBRID/GEOMETRIC mean kappa ${geometric.mean} < $GEOMETRIC_MIN_MEAN_KAPPA")
        assertTrue(explicit.mean >= hmm.mean, "HYBRID (${explicit.mean}) must not be worse than HMM (${hmm.mean})")
    }

    private companion object {
        const val EXPLICIT_MIN_MEAN_KAPPA = 0.69
        const val GEOMETRIC_MIN_MEAN_KAPPA = 0.68
    }
}

package io.github.alien2003.pulsestages.algo

import io.github.alien2003.pulsestages.algo.metrics.ConfusionMatrix
import io.github.alien2003.pulsestages.algo.metrics.IntervalScoring
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Recovery of 50 seeded synthetic nights (seeds 1..50, default [AlgoParams] apart from the estimator), scored with
 * [IntervalScoring] against the generating hypnogram (30-s chunks).
 *
 * Observed (ALGO_VERSION 1 after the review fixes), Cohen's kappa per night:
 *
 * | estimator | mean   | median | min    | pooled accuracy |
 * |-----------|--------|--------|--------|-----------------|
 * | HMM       | 0.4927 | 0.4961 | 0.3336 | 0.6556          |
 * | RULES     | 0.3975 | 0.3903 | 0.2376 | 0.6770          |
 *
 * Regression thresholds = mean of the first run (HMM 0.4924, RULES 0.3956) − 0.05, rounded down to 0.01: HMM 0.44,
 * RULES 0.34. The synthetic data come
 * from the same model family the HMM assumes, so these numbers overstate real-world accuracy (see
 * core-algo/SYNTHETIC_RESULTS.md); they guard against regressions only and are never used to pick defaults.
 */
class SyntheticRecoveryTest {
    private val nights = (1..50).map { SyntheticNightGenerator(it.toLong()).generate() }
    private val stager = SleepStager()

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

    private fun evaluate(type: EstimatorType): Summary {
        val scores =
            nights.map { night ->
                val outcome = stager.run(night.input, AlgoParams(estimator = type))
                val estimate = (outcome as EstimationOutcome.Estimated).estimate
                assertEquals(type, estimate.estimator)
                IntervalScoring.score(night.truth, estimate.stages).confusion
            }
        return Summary(scores.map { it.kappa }, scores.reduce { a, b -> a + b })
    }

    @Test
    fun `HMM and RULES recover synthetic hypnograms and HMM is not worse than RULES`() {
        val hmm = evaluate(EstimatorType.HMM)
        val rules = evaluate(EstimatorType.RULES)
        println("synthetic recovery HMM: $hmm")
        println("synthetic recovery RULES: $rules")

        assertTrue(hmm.mean >= HMM_MIN_MEAN_KAPPA, "HMM mean kappa ${hmm.mean} < $HMM_MIN_MEAN_KAPPA")
        assertTrue(rules.mean >= RULES_MIN_MEAN_KAPPA, "RULES mean kappa ${rules.mean} < $RULES_MIN_MEAN_KAPPA")
        assertTrue(hmm.mean >= rules.mean, "HMM (${hmm.mean}) must not be worse than RULES (${rules.mean})")
    }

    private companion object {
        const val HMM_MIN_MEAN_KAPPA = 0.44
        const val RULES_MIN_MEAN_KAPPA = 0.34
    }
}

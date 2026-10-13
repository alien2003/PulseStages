package io.github.alien2003.pulsestages.algo

import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.night.PreparedNight
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SleepStagerTest {
    private val input = SyntheticNightGenerator(11).generate().input

    private class FakeHybrid(
        private val result: (PreparedNight) -> HybridAttempt,
    ) : HybridEstimation {
        var calls = 0
        var lastNight: PreparedNight? = null
        override val modelFingerprint: String = "fake-model"
        override val personalFingerprint: String? = null

        override fun estimate(night: PreparedNight): HybridAttempt {
            calls++
            lastNight = night
            return result(night)
        }
    }

    private fun estimated(outcome: EstimationOutcome): NightEstimate =
        assertInstanceOf(EstimationOutcome.Estimated::class.java, outcome).estimate

    @Test
    fun `hybrid without a model falls back to HMM with a warning`() {
        val estimate = estimated(SleepStager().run(input, AlgoParams()))

        assertEquals(EstimatorType.HMM, estimate.estimator)
        assertTrue(estimate.warnings.any { it.code == WarningCode.HYBRID_MODEL_UNAVAILABLE })
        val hmm = SleepStager().estimateWith(EstimatorType.HMM, input, AlgoParams())
        assertEquals(hmm.stages, estimate.stages)
    }

    @Test
    fun `hybrid seam result is used when available`() {
        val canned = SleepStager().estimateWith(EstimatorType.RULES, input, AlgoParams()).copy(estimator = EstimatorType.HYBRID)
        val fake = FakeHybrid { HybridAttempt.Done(canned) }
        val stager = SleepStager(fake)

        val estimate = estimated(stager.run(input, AlgoParams()))

        assertEquals(1, fake.calls)
        assertSame(input, fake.lastNight?.input)
        assertEquals(EstimatorType.HYBRID, estimate.estimator)
        assertEquals(canned.stages, estimate.stages)
        assertEquals(stager.paramsHash(AlgoParams()), estimate.paramsHash)
        assertTrue(estimate.warnings.none { it.code == WarningCode.HYBRID_MODEL_UNAVAILABLE })
    }

    @Test
    fun `unavailable hybrid model reports its reason`() {
        val stager = SleepStager(FakeHybrid { HybridAttempt.Unavailable("featureNames mismatch") })

        val estimate = stager.estimateWith(EstimatorType.HYBRID, input, AlgoParams())

        assertEquals(EstimatorType.HMM, estimate.estimator)
        val warning = estimate.warnings.single { it.code == WarningCode.HYBRID_MODEL_UNAVAILABLE }
        assertEquals("featureNames mismatch", warning.detail)
        assertEquals(stager.paramsHash(AlgoParams()), estimate.paramsHash)
    }

    @Test
    fun `estimateWith ignores the configured estimator and the seam is not used for other types`() {
        val fake = FakeHybrid { error("must not be called") }
        val stager = SleepStager(fake)
        val params = AlgoParams(estimator = EstimatorType.HYBRID)

        assertEquals(EstimatorType.RULES, stager.estimateWith(EstimatorType.RULES, input, params).estimator)
        assertEquals(EstimatorType.HMM, stager.estimateWith(EstimatorType.HMM, input, params).estimator)
        assertEquals(EstimatorType.RULES, estimated(stager.run(input, params.copy(estimator = EstimatorType.RULES))).estimator)
        assertEquals(0, fake.calls)
    }

    @Test
    fun `prepared nights can be reused across decoder parameters`() {
        val params = AlgoParams(estimator = EstimatorType.HMM)
        val prepared = NightPreparer.prepare(input, params)
        val stager = SleepStager()

        assertEquals(stager.run(input, params), stager.run(prepared))
        val other = params.copy(hmm = params.hmm.copy(cyclePriorWeight = 0.0), post = params.post.copy(minDeepMin = 10.0))
        assertEquals(stager.run(input, other), stager.run(prepared.withParams(other)))
        assertEquals(
            stager.estimateWith(EstimatorType.RULES, input, other),
            stager.estimateWith(EstimatorType.RULES, prepared.withParams(other)),
        )
        val resampled = params.copy(preprocess = params.preprocess.copy(epochSec = 30))
        assertEquals(stager.run(input, resampled), stager.run(prepared.withParams(resampled)))
    }

    @Test
    fun `estimates carry the algorithm version, params and hash`() {
        val params = AlgoParams(estimator = EstimatorType.HMM)
        val estimate = estimated(SleepStager().run(input, params))

        assertEquals(ALGO_VERSION, estimate.algoVersion)
        assertEquals(params, estimate.params)
        assertEquals(ParamsHasher.hash(params), estimate.paramsHash)
        assertEquals(input.heartRate.count { it.bpm in 30.0..180.0 }, estimate.hrSampleCount)
        assertTrue(estimate.nadirBpm != null)
        assertTrue(estimate.confidence > 0.0)
    }
}

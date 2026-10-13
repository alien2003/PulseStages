package io.github.alien2003.pulsestages.algo.params

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AlgoParamsValidatorTest {
    private fun paths(params: AlgoParams) = AlgoParamsValidator.problems(params).map { it.path }

    @Test
    fun `defaults and the physiology table are valid`() {
        assertEquals(emptyList<ParamProblem>(), AlgoParamsValidator.problems(AlgoParams()))
        for (epochSec in AlgoParamsValidator.EPOCH_SEC) {
            assertTrue(paths(AlgoParams(preprocess = PreprocessParams(epochSec = epochSec))).isEmpty(), "epochSec=$epochSec")
        }
    }

    @Test
    fun `values the estimators cannot run with are reported with their path`() {
        val base = AlgoParams()
        val invalid =
            base.copy(
                preprocess = base.preprocess.copy(epochSec = 0, contextMarginMin = -30, hampelWindow = 4, minSessionMin = -1.0),
                features = base.features.copy(varHalfWindowEpochs = -1, normLowPercentile = 60.0),
                hmm =
                    base.hmm.copy(
                        emissions =
                            base.hmm.emissions.copy(
                                deep =
                                    base.hmm.emissions.deep
                                        .copy(hrVar = Gaussian(0.6, 0.0)),
                            ),
                        epsilon = 0.0,
                    ),
                rules = base.rules.copy(remMinHrNorm = Double.NaN, calibrationThresholdShift = -0.1),
                hybrid = base.hybrid.copy(maxBoutMin = 0, priorExponent = -0.5),
                post = base.post.copy(minDeepMin = -5.0, targetRemMin = 0.5),
            )

        val problems = AlgoParamsValidator.problems(invalid)

        assertEquals(
            listOf(
                "preprocess.hampelWindow",
                "preprocess.epochSec",
                "preprocess.contextMarginMin",
                "preprocess.minSessionMin",
                "features.normLowPercentile",
                "features.varHalfWindowEpochs",
                "hmm.emissions.deep.hrVar.sd",
                "hmm.epsilon",
                "rules.remMinHrNorm",
                "rules.calibrationThresholdShift",
                "hybrid.maxBoutMin",
                "hybrid.priorExponent",
                "post.minDeepMin",
                "post.targetRemMin",
            ),
            problems.map { it.path },
        )
        assertEquals("preprocess.epochSec = 0 (expected one of 30, 60, 120)", problems[1].toString())
        assertEquals("odd, 3..31", problems[0].constraint)
        assertEquals("10..600", problems.single { it.path == "hybrid.maxBoutMin" }.constraint)
    }

    @Test
    fun `the nap thresholds accept 0 to 720 minutes`() {
        val base = AlgoParams()

        fun thresholds(
            baseline: Double,
            proportions: Double,
        ) = base.copy(
            features = base.features.copy(baselineMinSessionMin = baseline),
            post = base.post.copy(proportionsMinSessionMin = proportions),
        )

        assertTrue(paths(thresholds(0.0, 0.0)).isEmpty())
        assertTrue(paths(thresholds(720.0, 720.0)).isEmpty())
        assertEquals(listOf("features.baselineMinSessionMin", "post.proportionsMinSessionMin"), paths(thresholds(-1.0, 720.5)))
        assertEquals(listOf("features.baselineMinSessionMin", "post.proportionsMinSessionMin"), paths(thresholds(Double.NaN, 1e9)))
        assertEquals(
            "0.0..720.0",
            AlgoParamsValidator.problems(thresholds(721.0, 0.0)).single().constraint,
        )
    }

    @Test
    fun `session thresholds compare whole milliseconds rounded up`() {
        val features = FeatureParams(baselineMinSessionMin = 180.0)
        assertEquals(10_800_000L, features.baselineMinSessionMs)
        assertTrue(features.countsForBaseline(10_800_000L))
        assertFalse(features.countsForBaseline(10_799_999L))
        // A fractional minute rounds up: 0.5 ms more than 1 min needs 60 001 ms.
        assertEquals(60_001L, FeatureParams(baselineMinSessionMin = 60_000.5 / 60_000.0).baselineMinSessionMs)
        assertEquals(0L, FeatureParams(baselineMinSessionMin = 0.0).baselineMinSessionMs)
        assertTrue(FeatureParams(baselineMinSessionMin = 0.0).countsForBaseline(1L))
        val post = PostprocessParams(proportionsMinSessionMin = 90.0)
        assertTrue(post.checksProportions(90 * 60_000L))
        assertFalse(post.checksProportions(90 * 60_000L - 1))
        assertTrue(PostprocessParams(proportionsMinSessionMin = 0.0).checksProportions(1L))
    }
}

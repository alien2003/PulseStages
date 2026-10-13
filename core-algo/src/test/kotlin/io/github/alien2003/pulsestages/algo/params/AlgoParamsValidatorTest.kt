package io.github.alien2003.pulsestages.algo.params

import org.junit.jupiter.api.Assertions.assertEquals
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
                hybrid = base.hybrid.copy(maxBoutMin = 0),
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
                "post.minDeepMin",
                "post.targetRemMin",
            ),
            problems.map { it.path },
        )
        assertEquals("preprocess.epochSec = 0 (expected one of 30, 60, 120)", problems[1].toString())
        assertEquals("odd, 3..31", problems[0].constraint)
        assertEquals("10..600", problems.single { it.path == "hybrid.maxBoutMin" }.constraint)
    }
}

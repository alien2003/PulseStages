package io.github.alien2003.pulsestages.algo.params

import io.github.alien2003.pulsestages.algo.model.SessionMode
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
                hybrid = base.hybrid.copy(maxBoutMin = 0, priorExponent = -0.5),
                post = base.post.copy(minDeepMin = -5.0, targetRemMin = 0.5),
                napMode = base.napMode.copy(maxSessionMin = -30.0),
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
                "napMode.maxSessionMin",
            ),
            problems.map { it.path },
        )
        assertEquals("preprocess.epochSec = 0 (expected one of 30, 60, 120)", problems[1].toString())
        assertEquals("odd, 3..31", problems[0].constraint)
        assertEquals("10..600", problems.single { it.path == "hybrid.maxBoutMin" }.constraint)
    }

    @Test
    fun `the nap threshold accepts 0 to 720 minutes`() {
        val base = AlgoParams()

        fun threshold(minutes: Double) = base.copy(napMode = base.napMode.copy(maxSessionMin = minutes))

        assertTrue(paths(threshold(0.0)).isEmpty())
        assertTrue(paths(threshold(720.0)).isEmpty())
        assertEquals(listOf("napMode.maxSessionMin"), paths(threshold(-1.0)))
        assertEquals(listOf("napMode.maxSessionMin"), paths(threshold(Double.NaN)))
        assertEquals("0.0..720.0", AlgoParamsValidator.problems(threshold(721.0)).single().constraint)
    }

    @Test
    fun `nap overrides are checked when set and their pair with the night value`() {
        val base = AlgoParams()

        fun nap(
            post: NapPostprocessParams = NapPostprocessParams(),
            hybrid: NapHybridParams = NapHybridParams(),
        ) = base.copy(napMode = base.napMode.copy(post = post, hybrid = hybrid))

        assertTrue(paths(nap()).isEmpty())
        assertTrue(paths(nap(NapPostprocessParams(minDeepMin = 0.0, sanityDeepMin = 0.0, sanityDeepMax = 1.0))).isEmpty())
        assertEquals(
            listOf(
                "napMode.post.minDeepMin",
                "napMode.post.minRemMin",
                "napMode.post.minAwakeMin",
                "napMode.post.sanityRemMax",
                "napMode.post.lowConfidenceThreshold",
                "napMode.hybrid.emissionWeight",
                "napMode.hybrid.timePriorWeight",
            ),
            paths(
                nap(
                    NapPostprocessParams(
                        minDeepMin = -1.0,
                        minRemMin = -1.0,
                        minAwakeMin = -0.5,
                        sanityRemMax = 1.5,
                        lowConfidenceThreshold = 2.0,
                    ),
                    NapHybridParams(emissionWeight = -0.1, timePriorWeight = Double.NaN),
                ),
            ),
        )
        // DEEP min above the night's max (0.35), and the night's min (0.03) above a nap max.
        val minAbove = AlgoParamsValidator.problems(nap(NapPostprocessParams(sanityDeepMin = 0.5))).single()
        assertEquals("napMode.post.sanityDeepMin", minAbove.path)
        assertEquals("<= post.sanityDeepMax (0.35)", minAbove.constraint)
        val maxBelow = AlgoParamsValidator.problems(nap(NapPostprocessParams(sanityDeepMax = 0.01))).single()
        assertEquals("napMode.post.sanityDeepMax", maxBelow.path)
        assertEquals(">= post.sanityDeepMin (0.03)", maxBelow.constraint)
        val both = AlgoParamsValidator.problems(nap(NapPostprocessParams(sanityDeepMin = 0.4, sanityDeepMax = 0.2))).single()
        assertEquals("napMode.post.sanityDeepMin", both.path)
        assertEquals("<= napMode.post.sanityDeepMax (0.2)", both.constraint)
    }

    @Test
    fun `the nap context margin is empty or 0 up to the night's context margin`() {
        val base = AlgoParams()

        fun margin(
            nap: Int?,
            night: Int = base.preprocess.contextMarginMin,
        ) = base.copy(preprocess = base.preprocess.copy(contextMarginMin = night), napMode = base.napMode.copy(contextMarginMin = nap))

        assertEquals(30, base.preprocess.contextMarginMin)
        for (accepted in listOf(margin(null), margin(0), margin(15), margin(30), margin(60, night = 60), margin(0, night = 0))) {
            assertTrue(paths(accepted).isEmpty(), "${accepted.napMode.contextMarginMin} with ${accepted.preprocess.contextMarginMin}")
        }
        val negative = AlgoParamsValidator.problems(margin(-1)).single()
        assertEquals("napMode.contextMarginMin", negative.path)
        assertEquals("-1", negative.value)
        assertEquals(">= 0", negative.constraint)
        val wider = AlgoParamsValidator.problems(margin(31)).single()
        assertEquals("napMode.contextMarginMin = 31 (expected <= preprocess.contextMarginMin (30))", wider.toString())
        assertEquals(listOf("napMode.contextMarginMin"), paths(margin(30, night = 20)))
        // An invalid night margin is reported at its own path; a nap override below it is not reported again.
        assertEquals(listOf("preprocess.contextMarginMin"), paths(margin(null, night = 241)))
        assertEquals(listOf("preprocess.contextMarginMin"), paths(margin(240, night = 241)))
    }

    @Test
    fun `the nap threshold compares whole milliseconds rounded up`() {
        val napMode = NapModeParams(maxSessionMin = 180.0)
        assertEquals(10_800_000L, napMode.maxSessionMs)
        assertEquals(SessionMode.NIGHT, napMode.modeOf(10_800_000L))
        assertEquals(SessionMode.NAP, napMode.modeOf(10_799_999L))
        // A fractional minute rounds up: 0.5 ms more than 1 min needs 60 001 ms.
        assertEquals(60_001L, NapModeParams(maxSessionMin = 60_000.5 / 60_000.0).maxSessionMs)
        assertEquals(SessionMode.NAP, NapModeParams(maxSessionMin = 60_000.5 / 60_000.0).modeOf(60_000L))
        assertEquals(0L, NapModeParams(maxSessionMin = 0.0).maxSessionMs)
        assertEquals(SessionMode.NIGHT, NapModeParams(maxSessionMin = 0.0).modeOf(1L))
        assertEquals(SessionMode.NIGHT, NapModeParams(maxSessionMin = Double.NaN).modeOf(1L))
    }
}

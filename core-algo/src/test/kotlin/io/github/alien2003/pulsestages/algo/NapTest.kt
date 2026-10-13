package io.github.alien2003.pulsestages.algo

import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticOptions
import io.github.alien2003.pulsestages.algo.testing.SyntheticHybridModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Naps (sessions shorter than `features.baselineMinSessionMin` / `post.proportionsMinSessionMin`, 180 min by default)
 * for every estimator: never compared with the nadir baseline, never checked or calibrated against whole-night stage
 * shares; nights keep both rules, and the thresholds set to 0 restore the behaviour without them.
 */
class NapTest {
    private val stager = SleepStager(SyntheticHybridModels.default)

    /** A baseline far below any synthetic nadir: every session compared with it is elevated. */
    private val lowBaseline = 30.0

    private fun session(
        minutes: Int?,
        seed: Long = 11,
    ): NightInput =
        SyntheticNightGenerator(seed).generate(SyntheticOptions(durationMin = minutes)).input.copy(baselineNadirBpm = lowBaseline)

    private fun estimate(
        input: NightInput,
        params: AlgoParams,
    ): NightEstimate {
        val estimate = assertInstanceOf(EstimationOutcome.Estimated::class.java, stager.run(input, params)).estimate
        assertEquals(params.estimator, estimate.estimator)
        Invariants.check(estimate, input, "${params.estimator}")
        return estimate
    }

    private fun codes(estimate: NightEstimate) = estimate.warnings.map { it.code }

    /** Sanity limits that every session fails (DEEP below 100 %), so the check's effect is deterministic. */
    private fun AlgoParams.alwaysFailingSanity() = copy(post = post.copy(sanityDeepMax = 1.0, sanityDeepMin = 1.0))

    @ParameterizedTest
    @MethodSource("naps")
    fun `a nap with a nadir far above the baseline is not elevated`(case: Case) {
        val nap = session(case.minutes)

        val estimate = estimate(nap, case.params)

        assertFalse(estimate.flags.elevatedHr, "$case")
        assertFalse(WarningCode.ELEVATED_HR in codes(estimate), "$case")
        // Without the rule the same nap is flagged and its confidence lowered.
        val off = case.params.copy(features = case.params.features.copy(baselineMinSessionMin = 0.0))
        val flagged = estimate(nap, off)
        assertTrue(flagged.flags.elevatedHr, "$case")
        assertTrue(WarningCode.ELEVATED_HR in codes(flagged), "$case")
        assertTrue(flagged.confidence < estimate.confidence, "$case: ${flagged.confidence} < ${estimate.confidence}")
    }

    @ParameterizedTest
    @MethodSource("naps")
    fun `a nap is neither sanity-checked nor calibrated against night proportions`(case: Case) {
        val nap = session(case.minutes)
        val strict = case.params.alwaysFailingSanity()
        val calibrating = strict.copy(post = strict.post.copy(calibrateProportions = true, targetDeepMin = 0.9, targetDeepMax = 1.0))

        val estimate = estimate(nap, calibrating)

        assertTrue(codes(estimate).none { it.name.startsWith("SANITY_") }, "$case: ${codes(estimate)}")
        assertFalse(WarningCode.PROPORTIONS_CALIBRATED in codes(estimate), "$case")
        assertFalse(estimate.flags.calibrated, "$case")
        // With the threshold at 0 the night corridors apply to the nap again.
        val off = strict.copy(post = strict.post.copy(proportionsMinSessionMin = 0.0))
        val checked = estimate(nap, off)
        assertTrue(WarningCode.SANITY_DEEP_LOW in codes(checked), "$case: ${codes(checked)}")
        assertTrue(checked.flags.lowConfidence, "$case")
    }

    @ParameterizedTest
    @MethodSource("nights")
    fun `a night keeps the elevated-HR rule and the sanity check`(case: Case) {
        val night = session(case.minutes)

        val estimate = estimate(night, case.params.alwaysFailingSanity())

        assertTrue(estimate.flags.elevatedHr, "$case")
        assertTrue(WarningCode.ELEVATED_HR in codes(estimate), "$case")
        assertTrue(WarningCode.SANITY_DEEP_LOW in codes(estimate), "$case: ${codes(estimate)}")
        assertTrue(estimate.flags.lowConfidence, "$case")
    }

    /** One estimator configuration and session length (null: a regular synthetic night). */
    class Case(
        val params: AlgoParams,
        val minutes: Int?,
    ) {
        override fun toString(): String = "${params.estimator} ${minutes ?: "night"} min"
    }

    companion object {
        private fun cases(minutes: List<Int?>): List<Case> =
            EdgeCaseTest.variants().flatMap { variant -> minutes.map { Case(variant.params, it) } }

        /** Naps of 30–179 minutes. */
        @JvmStatic
        fun naps(): List<Case> = cases(listOf(30, 45, 60, 90, 179))

        /** A session of exactly the threshold and a regular night. */
        @JvmStatic
        fun nights(): List<Case> = cases(listOf(180, null))
    }
}

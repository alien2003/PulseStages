package io.github.alien2003.pulsestages.algo.rules

import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.algo.model.TimeSpan
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.night.PreparedNight
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.RulesParams
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RulesEstimatorTest {
    private val minute = 60_000L
    private val estimator = RulesEstimator()

    /**
     * The rule of section 6, written out independently of the implementation; [offsets] are calibration log-prior
     * offsets that move the hrNorm thresholds by calibrationThresholdShift per unit.
     */
    private fun expected(
        night: PreparedNight,
        k: Int,
        offsets: DoubleArray = DoubleArray(4),
    ): SleepStage {
        val p = night.grid.position(k)
        val f = night.features
        val r = night.params.rules
        val c = r.calibrationThresholdShift
        val awake = r.awakeMinHrNorm + (if (night.statistics.elevated) night.params.features.elevatedAwakeShift else 0.0) - c * offsets[0]
        val hrNorm = f.heartRate.hrNorm[p]
        val hrVar = f.heartRate.hrVar[p]
        return when {
            night.isForcedAwake(k) || f.activity.steps[p] >= 1.0 -> SleepStage.AWAKE
            !night.sessionHasData(k) -> SleepStage.LIGHT
            hrNorm > awake -> SleepStage.AWAKE
            hrNorm < r.deepMaxHrNorm + c * offsets[2] && hrVar < r.deepMaxHrVar -> SleepStage.DEEP
            hrNorm > r.remMinHrNorm - c * offsets[3] && hrVar > r.remMinHrVar && f.t[p] > r.remMinT -> SleepStage.REM
            else -> SleepStage.LIGHT
        }
    }

    private fun awakeEpochs(input: NightInput): Int =
        estimator.classify(NightPreparer.prepare(input, AlgoParams())).count {
            it ==
                SleepStage.AWAKE
        }

    @Test
    fun `classification follows the thresholds`() {
        repeat(10) { seed ->
            val synthetic = SyntheticNightGenerator(seed.toLong()).generate(SyntheticOptions(longGapMin = 30))
            val start = synthetic.input.sessionStartMs
            val input =
                synthetic.input.copy(
                    forcedAwake = listOf(TimeSpan(start + 100 * minute, start + 110 * minute)),
                    steps = synthetic.input.steps + StepsSample(start + 200 * minute, start + 202 * minute, 2),
                    baselineNadirBpm = if (seed % 2 == 0) synthetic.baselineBpm * 0.8 else null,
                )
            val night = NightPreparer.prepare(input, AlgoParams())
            val labels = estimator.classify(night)

            labels.forEachIndexed { k, stage -> assertEquals(expected(night, k), stage, "seed=$seed k=$k") }
            assertTrue(labels.toSet().containsAll(listOf(SleepStage.AWAKE, SleepStage.LIGHT)))
        }
    }

    @Test
    fun `fractions of a step from long step records do not make epochs AWAKE`() {
        val synthetic = SyntheticNightGenerator(3).generate(SyntheticOptions(withSteps = false))
        val input = synthetic.input
        val start = input.sessionStartMs
        val plain = awakeEpochs(input)

        // 20 steps over the whole session (0.04 per epoch), 10 steps over 30 minutes (0.33 per epoch).
        assertEquals(plain, awakeEpochs(input.copy(steps = listOf(StepsSample(start, input.sessionEndMs, 20)))))
        assertEquals(plain, awakeEpochs(input.copy(steps = listOf(StepsSample(start + 120 * minute, start + 150 * minute, 10)))))
        // One whole step in an epoch is a step.
        val stepped =
            NightPreparer.prepare(
                input.copy(steps = listOf(StepsSample(start + 300 * minute, start + 301 * minute, 1))),
                AlgoParams(),
            )
        assertEquals(SleepStage.AWAKE, estimator.classify(stepped)[300])
        assertTrue(plain < input.let { (it.sessionEndMs - it.sessionStartMs) / minute } / 2)
    }

    @Test
    fun `the AWAKE threshold rises by elevatedAwakeShift on elevated nights`() {
        var checked = 0
        for (seed in 0L until 10L) {
            val input = SyntheticNightGenerator(seed).generate(SyntheticOptions(withSteps = false)).input
            val normal = NightPreparer.prepare(input, AlgoParams())
            val elevated = NightPreparer.prepare(input.copy(baselineNadirBpm = 30.0), AlgoParams())
            assertTrue(elevated.statistics.elevated && !normal.statistics.elevated)
            val plainLabels = estimator.classify(normal)
            val elevatedLabels = estimator.classify(elevated)
            for (k in plainLabels.indices) {
                val hrNorm = normal.features.heartRate.hrNorm[normal.grid.position(k)]
                if (normal.sessionHasData(k) && hrNorm > 2.2 && hrNorm < 2.5) {
                    assertEquals(SleepStage.AWAKE, plainLabels[k], "seed=$seed k=$k")
                    assertTrue(elevatedLabels[k] != SleepStage.AWAKE, "seed=$seed k=$k hrNorm=$hrNorm")
                    checked++
                }
            }
        }
        assertTrue(checked > 0, "no epoch with 2.2 < hrNorm < 2.5")
    }

    @Test
    fun `calibration offsets move the rule thresholds and change the labels`() {
        val input = SyntheticNightGenerator(5).generate().input
        val night = NightPreparer.prepare(input, AlgoParams())
        val offsets = doubleArrayOf(-0.6, 0.0, 1.0, 0.8)

        val shifted = estimator.classify(night, offsets)

        shifted.forEachIndexed { k, stage -> assertEquals(expected(night, k, offsets), stage, "k=$k") }
        val plain = estimator.classify(night)
        assertTrue(shifted.count { it == SleepStage.DEEP } > plain.count { it == SleepStage.DEEP })

        // End to end: a DEEP corridor above the plain share makes the calibration raise DEEP and report it.
        val params =
            AlgoParams().let {
                it.copy(
                    post = it.post.copy(calibrateProportions = true, targetDeepMin = 0.6, targetDeepMax = 0.9),
                )
            }
        val before = estimator.estimate(input, AlgoParams())
        val after = estimator.estimate(input, params)

        fun deepShare(e: NightEstimate) = e.perEpoch.count { it.stage == SleepStage.DEEP }.toDouble() / e.perEpoch.size
        assertTrue(after.flags.calibrated)
        assertTrue(after.warnings.any { it.code == WarningCode.PROPORTIONS_CALIBRATED })
        assertTrue(deepShare(after) > deepShare(before), "DEEP ${deepShare(before)} -> ${deepShare(after)}")
        // Without the threshold shift the rules cannot be calibrated.
        val inert = params.copy(rules = params.rules.copy(calibrationThresholdShift = 0.0))
        assertFalse(estimator.estimate(input, inert).flags.calibrated)
    }

    @Test
    fun `thresholds are parameters`() {
        val input = SyntheticNightGenerator(3).generate().input
        val strict = AlgoParams(rules = RulesParams(deepMaxHrNorm = -100.0, remMinHrNorm = 100.0, awakeMinHrNorm = 100.0))
        val night = NightPreparer.prepare(input.copy(steps = emptyList()), strict)

        assertTrue(estimator.classify(night).all { it == SleepStage.LIGHT })
    }

    @Test
    fun `pseudo posteriors are one-hot smoothed`() {
        val input = SyntheticNightGenerator(4).generate().input
        val estimate = estimator.estimate(input, AlgoParams())

        estimate.perEpoch.forEach { epoch ->
            val values = epoch.posterior.toList().sorted()
            assertEquals(0.85, values[3], 1e-12)
            values.take(3).forEach { assertEquals(0.05, it, 1e-12) }
        }
    }
}

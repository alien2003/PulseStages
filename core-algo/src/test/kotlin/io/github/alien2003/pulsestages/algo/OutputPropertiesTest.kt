package io.github.alien2003.pulsestages.algo

import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.TimeSpan
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.DurationModel
import io.github.alien2003.pulsestages.algo.params.GapPolicy
import io.github.alien2003.pulsestages.algo.params.LowConfidencePolicy
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticOptions
import io.github.alien2003.pulsestages.algo.testing.SyntheticHybridModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Property tests of the output invariants (including the minimum bout lengths) on 200 seeded random nights with
 * random parameter variations (epoch length, gap policy, calibration, low-confidence policy, EM adaptation, hybrid
 * maximum bout length and prior weights), session bounds at arbitrary milliseconds and random data gaps /
 * forced-awake spans. HYBRID runs both without a bundle (HMM fallback) and with the synthetic
 * test bundle in both decoders (EXPLICIT and GEOMETRIC).
 */
class OutputPropertiesTest {
    private val minute = 60_000L
    private val stager = SleepStager()
    private val hybridStager = SleepStager(SyntheticHybridModels.default)

    private class Case(
        val input: NightInput,
        val params: AlgoParams,
        val description: String,
    )

    private fun randomParams(random: Random): AlgoParams {
        val base = AlgoParams()
        return base.copy(
            estimator = EstimatorType.entries.random(random),
            preprocess = base.preprocess.copy(epochSec = listOf(30, 60, 120).random(random)),
            hmm = base.hmm.copy(emAdapt = random.nextBoolean()),
            hybrid =
                base.hybrid.copy(
                    durationModel = DurationModel.entries.random(random),
                    maxBoutMin = listOf(20, 60, 120, 180).random(random),
                    timePriorWeight = listOf(0.0, 1.0, 2.0).random(random),
                    emissionWeight = listOf(0.5, 1.0, 1.5).random(random),
                ),
            post =
                base.post.copy(
                    gapPolicy = GapPolicy.entries.random(random),
                    calibrateProportions = random.nextBoolean(),
                    lowConfidencePolicy = LowConfidencePolicy.entries.random(random),
                ),
        )
    }

    private fun randomCase(seed: Int): Case {
        val random = Random(seed)
        val options =
            SyntheticOptions(
                sessionStartMs = SyntheticOptions.DEFAULT_SESSION_START_MS + random.nextLong(0, 86_400_000L),
                durationMin = listOf(null, null, null, null, 1, 2, 45, 90, 200).random(random),
                longGapMin = listOf(0, 0, 15, 45, 90).random(random),
                withSteps = random.nextBoolean(),
            )
        val night = SyntheticNightGenerator(seed.toLong()).generate(options)
        // Session bounds at arbitrary milliseconds (the generator works in whole minutes): partial first-sample
        // offsets and a partial last epoch, down to sessions shorter than one epoch.
        val start = night.input.sessionStartMs + random.nextLong(0, 30_000)
        val end = maxOf(start + 1, night.input.sessionEndMs - random.nextLong(0, 60_000))
        val gaps =
            List(random.nextInt(0, 4)) {
                val from = random.nextLong(start - 30 * minute, end)
                from until from + random.nextLong(5, 61) * minute
            }
        val forced =
            List(random.nextInt(0, 3)) {
                val from = random.nextLong(start, end)
                TimeSpan(from, minOf(end, from + random.nextLong(1, 31) * minute))
            }
        val input =
            night.input.copy(
                sessionStartMs = start,
                sessionEndMs = end,
                heartRate = night.input.heartRate.filter { sample -> gaps.none { sample.timeMs in it } },
                forcedAwake = forced,
                baselineNadirBpm = if (random.nextBoolean()) night.baselineBpm - random.nextDouble(-5.0, 5.0) else null,
            )
        val params = randomParams(random)
        val variation =
            "estimator=${params.estimator} epochSec=${params.preprocess.epochSec} gap=${params.post.gapPolicy} hybrid=${params.hybrid} " +
                "calibrate=${params.post.calibrateProportions} lowConf=${params.post.lowConfidencePolicy} em=${params.hmm.emAdapt}"
        return Case(input, params, "seed=$seed $variation gaps=${gaps.size} forced=${forced.size} options=$options")
    }

    @Test
    fun `invariants hold for every estimator on random nights and parameters`() {
        var estimated = 0
        repeat(200) { seed ->
            val case = randomCase(seed)
            for (type in listOf(EstimatorType.RULES, EstimatorType.HMM, EstimatorType.HYBRID)) {
                Invariants.check(stager.estimateWith(type, case.input, case.params), case.input, "$type ${case.description}")
            }
            for (mode in DurationModel.entries) {
                val params = case.params.copy(hybrid = case.params.hybrid.copy(durationModel = mode))
                val estimate = hybridStager.estimateWith(EstimatorType.HYBRID, case.input, params)
                assertEquals(EstimatorType.HYBRID, estimate.estimator, case.description)
                Invariants.check(estimate, case.input, "HYBRID/$mode ${case.description}")
            }
            val outcome = stager.run(case.input, case.params)
            if (outcome is EstimationOutcome.Estimated) {
                estimated++
                Invariants.check(outcome.estimate, case.input, "run ${case.description}")
            }
            val hybridOutcome = hybridStager.run(case.input, case.params)
            if (hybridOutcome is EstimationOutcome.Estimated) {
                Invariants.check(
                    hybridOutcome.estimate,
                    case.input,
                    "run/hybrid ${case.description}",
                )
            }
        }
        assertTrue(estimated > 100, "most random nights should pass the coverage gates, got $estimated")
    }

    @Test
    fun `estimation is deterministic`() {
        repeat(200) { seed ->
            val case = randomCase(seed)
            assertEquals(stager.run(case.input, case.params), stager.run(case.input, case.params), case.description)
            assertEquals(
                stager.estimateWith(EstimatorType.HMM, case.input, case.params),
                SleepStager().estimateWith(EstimatorType.HMM, case.input, case.params),
                case.description,
            )
            assertEquals(
                hybridStager.run(case.input, case.params),
                SleepStager(SyntheticHybridModels.default).run(case.input, case.params),
                case.description,
            )
        }
    }
}

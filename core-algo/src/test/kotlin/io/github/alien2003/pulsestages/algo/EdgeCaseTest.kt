package io.github.alien2003.pulsestages.algo

import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.DurationModel
import io.github.alien2003.pulsestages.algo.params.GapPolicy
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticOptions
import io.github.alien2003.pulsestages.algo.testing.SyntheticHybridModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The edge cases of the specification (one sample, all-equal heart rate, midnight, 90 min, 14 h, outliers, gaps,
 * coverage gates) for every estimator: RULES, HMM and HYBRID with the synthetic test bundle in both decoders.
 */
class EdgeCaseTest {
    /** One estimator configuration. */
    class Variant(
        private val name: String,
        val params: AlgoParams,
    ) {
        val type: EstimatorType get() = params.estimator

        override fun toString(): String = name
    }

    private val minute = 60_000L
    private val stager = SleepStager(SyntheticHybridModels.default)

    private fun estimated(outcome: EstimationOutcome): NightEstimate =
        assertInstanceOf(EstimationOutcome.Estimated::class.java, outcome).estimate

    private fun codes(estimate: NightEstimate) = estimate.warnings.map { it.code }

    /** Runs the gates and checks that the configured estimator produced the estimate (enough coverage). */
    private fun run(
        input: NightInput,
        variant: Variant,
        params: AlgoParams = variant.params,
    ): NightEstimate {
        val estimate = estimated(stager.run(input, params))
        assertEquals(variant.type, estimate.estimator, "$variant")
        Invariants.check(estimate, input, "$variant")
        return estimate
    }

    @ParameterizedTest
    @MethodSource("variants")
    fun `a single heart-rate sample`(variant: Variant) {
        val input = SyntheticNightGenerator(1).generate(SyntheticOptions(singleSample = true)).input

        val outcome = stager.run(input, variant.params)

        assertEquals(NotEstimatedReason.LOW_COVERAGE, assertInstanceOf(EstimationOutcome.NotEstimated::class.java, outcome).reason)
        val estimate = stager.estimateWith(variant.type, input, variant.params)
        Invariants.check(estimate, input, "$variant")
        assertEquals(1, estimate.hrSampleCount)
        assertTrue(WarningCode.DATA_GAPS in codes(estimate))
    }

    @ParameterizedTest
    @MethodSource("variants")
    fun `all heart-rate values equal`(variant: Variant) {
        val input = SyntheticNightGenerator(2).generate(SyntheticOptions(constantBpm = 57.0)).input

        val estimate = run(input, variant)

        assertTrue(WarningCode.LOW_HR_RANGE in codes(estimate))
        assertTrue(estimate.flags.lowConfidence)
        assertEquals(57.0, estimate.nadirBpm)
    }

    @ParameterizedTest
    @MethodSource("variants")
    fun `session crossing midnight and a DST change is handled in epoch milliseconds`(variant: Variant) {
        // 2026-10-24T22:00Z .. 2026-10-25T06:00Z: crosses midnight and the end of summer time in Europe (01:00Z).
        val start = Instant.parse("2026-10-24T22:00:00Z").toEpochMilli()
        val input = SyntheticNightGenerator(3).generate(SyntheticOptions(sessionStartMs = start, durationMin = 480)).input
        val berlin = ZoneId.of("Europe/Berlin").rules
        assertFalse(berlin.getOffset(Instant.ofEpochMilli(start)) == berlin.getOffset(Instant.ofEpochMilli(input.sessionEndMs)))

        val estimate = run(input, variant)

        assertEquals(480, estimate.perEpoch.size)
        assertEquals(480L * minute, estimate.stages.sumOf { it.endMs - it.startMs })
    }

    @ParameterizedTest
    @MethodSource("variants")
    fun `a data gap across local midnight`(variant: Variant) {
        val zone = ZoneId.of("Europe/Berlin")
        val midnight =
            LocalDate
                .of(2026, 3, 10)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        val start = midnight - 150 * minute
        val synthetic = SyntheticNightGenerator(10).generate(SyntheticOptions(sessionStartMs = start, durationMin = 480))
        val gap = midnight - 20 * minute until midnight + 25 * minute
        val input = synthetic.input.copy(heartRate = synthetic.input.heartRate.filter { it.timeMs !in gap })

        val estimate = run(input, variant)

        val gapEpochs = estimate.perEpoch.filter { !it.hasData }
        assertTrue(gapEpochs.size >= 40, "gap epochs: ${gapEpochs.size}")
        assertTrue(gapEpochs.all { it.stage == SleepStage.UNKNOWN && it.startMs in midnight - 25 * minute until midnight + 30 * minute })
        assertTrue(WarningCode.DATA_GAPS in codes(estimate))
    }

    @ParameterizedTest
    @MethodSource("variants")
    fun `session of exactly 90 minutes`(variant: Variant) {
        val input = SyntheticNightGenerator(4).generate(SyntheticOptions(durationMin = 90)).input

        val estimate = run(input, variant)

        assertEquals(90, estimate.perEpoch.size)
    }

    @ParameterizedTest
    @MethodSource("variants")
    fun `session of 14 hours runs in under two seconds`(variant: Variant) {
        val input = SyntheticNightGenerator(5).generate(SyntheticOptions(durationMin = 14 * 60)).input
        val params = variant.params.copy(post = variant.params.post.copy(calibrateProportions = true))

        val startedAt = System.nanoTime()
        val estimate = run(input, variant, params)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(14 * 60, estimate.perEpoch.size)
        assertTrue(elapsedMs < 2_000, "14-hour session took $elapsedMs ms")
    }

    @ParameterizedTest
    @MethodSource("variants")
    fun `outliers are dropped by the range filter and spikes removed by the Hampel filter`(variant: Variant) {
        val night = SyntheticNightGenerator(6).generate(SyntheticOptions(outlierRate = 0.0))
        val clean = night.input.heartRate
        val polluted =
            clean.mapIndexed { i, sample ->
                when (i % 37) {
                    5 -> sample.copy(bpm = 210.0)
                    11 -> sample.copy(bpm = 0.0)
                    17 -> sample.copy(bpm = 25.0)
                    23 -> sample.copy(bpm = sample.bpm + 70.0)
                    else -> sample
                }
            }
        val input = night.input.copy(heartRate = polluted)

        val estimate = run(input, variant)

        val dropped = polluted.count { it.bpm < 30 || it.bpm > 180 }
        assertEquals(polluted.size - dropped, estimate.hrSampleCount)
        val cleanMax = clean.filter { it.timeMs in input.sessionStartMs until input.sessionEndMs }.maxOf { it.bpm }
        val rawMax = estimate.perEpoch.mapNotNull { it.hrRaw }.max()
        assertTrue(rawMax <= cleanMax + 1e-9, "spikes must be removed: raw max $rawMax > clean max $cleanMax")
    }

    @ParameterizedTest
    @MethodSource("variants")
    fun `long gaps follow the gap policy`(variant: Variant) {
        val input = SyntheticNightGenerator(7).generate(SyntheticOptions(longGapMin = 60)).input
        val noData = { e: NightEstimate -> e.perEpoch.filter { !it.hasData } }

        for (policy in GapPolicy.entries) {
            val params = variant.params.copy(post = variant.params.post.copy(gapPolicy = policy))
            val estimate = run(input, variant, params)
            val gapEpochs = noData(estimate)
            assertTrue(gapEpochs.size >= 50, "gap epochs: ${gapEpochs.size}")
            assertTrue(WarningCode.DATA_GAPS in codes(estimate))
            when (policy) {
                GapPolicy.UNKNOWN -> {
                    assertTrue(gapEpochs.all { it.stage == SleepStage.UNKNOWN })
                    assertTrue(estimate.stages.any { it.stage == SleepStage.UNKNOWN && it.endMs - it.startMs >= 50 * minute })
                }

                GapPolicy.LIGHT -> {
                    assertTrue(gapEpochs.all { it.stage == SleepStage.LIGHT })
                }

                GapPolicy.NEAREST -> {
                    val first = gapEpochs.first().index
                    val last = gapEpochs.last().index
                    assertEquals(estimate.perEpoch[first - 1].stage, estimate.perEpoch[first].stage)
                    assertEquals(estimate.perEpoch[last + 1].stage, estimate.perEpoch[last].stage)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("variants")
    fun `no heart rate is not estimated`(variant: Variant) {
        val base = SyntheticNightGenerator(8).generate().input

        val none = stager.run(base.copy(heartRate = emptyList()), variant.params)
        val outOfRange = stager.run(base.copy(heartRate = base.heartRate.map { it.copy(bpm = 0.0) }), variant.params)
        val farAway = stager.run(base.copy(heartRate = listOf(HrSample(base.sessionStartMs - 10 * 60 * minute, 60.0))), variant.params)

        listOf(none, outOfRange, farAway).forEach {
            assertEquals(NotEstimatedReason.NO_HR_DATA, assertInstanceOf(EstimationOutcome.NotEstimated::class.java, it).reason)
        }
    }

    @ParameterizedTest
    @MethodSource("variants")
    fun `low coverage is not estimated and the band below minCoverage falls back to RULES`(variant: Variant) {
        val base = SyntheticNightGenerator(9).generate().input
        val length = base.sessionEndMs - base.sessionStartMs

        fun keepFirst(fraction: Double): NightInput =
            base.copy(heartRate = base.heartRate.filter { it.timeMs < base.sessionStartMs + (length * fraction).toLong() })

        val low = stager.run(keepFirst(0.3), variant.params)
        assertEquals(NotEstimatedReason.LOW_COVERAGE, assertInstanceOf(EstimationOutcome.NotEstimated::class.java, low).reason)
        assertTrue(low.let { (it as EstimationOutcome.NotEstimated).coverage } < 0.4)

        val band = keepFirst(0.5)
        val fallback = estimated(stager.run(band, variant.params))
        assertEquals(EstimatorType.RULES, fallback.estimator)
        assertEquals(variant.type != EstimatorType.RULES, fallback.flags.rulesFallback)
        assertEquals(variant.type != EstimatorType.RULES, WarningCode.RULES_FALLBACK_LOW_COVERAGE in codes(fallback))
        assertTrue(fallback.coverage in 0.4..0.6)
        Invariants.check(fallback, band)

        run(base, variant)
    }

    @Test
    fun `sessions shorter than minSessionMin are not estimated`() {
        val nap = SyntheticNightGenerator(1).generate(SyntheticOptions(durationMin = 20)).input
        val gated = AlgoParams().let { it.copy(preprocess = it.preprocess.copy(minSessionMin = 90.0)) }

        val skipped = assertInstanceOf(EstimationOutcome.NotEstimated::class.java, stager.run(nap, gated))

        assertEquals(NotEstimatedReason.SESSION_TOO_SHORT, skipped.reason)
        assertTrue(skipped.detail.contains("20.0 min"), skipped.detail)
        // The default 0 disables the gate; estimateWith never applies it.
        estimated(stager.run(nap, AlgoParams()))
        Invariants.check(stager.estimateWith(EstimatorType.HMM, nap, gated), nap)
        estimated(stager.run(SyntheticNightGenerator(1).generate(SyntheticOptions(durationMin = 90)).input, gated))
    }

    companion object {
        /** RULES, HMM and HYBRID in both decoders. */
        @JvmStatic
        fun variants(): List<Variant> {
            val base = AlgoParams()
            return listOf(
                Variant("RULES", base.withEstimator(EstimatorType.RULES)),
                Variant("HMM", base.withEstimator(EstimatorType.HMM)),
                Variant("HYBRID/EXPLICIT", base.withEstimator(EstimatorType.HYBRID)),
                Variant(
                    "HYBRID/GEOMETRIC",
                    base
                        .withEstimator(
                            EstimatorType.HYBRID,
                        ).let { it.copy(hybrid = it.hybrid.copy(durationModel = DurationModel.GEOMETRIC)) },
                ),
            )
        }
    }
}

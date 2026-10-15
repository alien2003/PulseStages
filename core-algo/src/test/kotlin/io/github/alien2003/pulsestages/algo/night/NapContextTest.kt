package io.github.alien2003.pulsestages.algo.night

import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.hybrid.HybridFeatureExtractor
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.HrvSample
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.RespiratoryRateSample
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.Spo2Sample
import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.algo.model.TimeSpan
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.NapHybridParams
import io.github.alien2003.pulsestages.algo.params.TrainedDefaults
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.testing.MinimalBundles
import io.github.alien2003.pulsestages.algo.testing.NapModels
import io.github.alien2003.pulsestages.algo.testing.SyntheticHybridModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `napMode.contextMarginMin` (docs/prereg/prereg-naps-realistic.md section 16): a nap with the override m is prepared
 * from its input restricted to `[start − m, end + m]` on a grid with m minutes of context, so what the caller read
 * around the session cannot reach its features or decode; nights never read the field; a preparation is reused only
 * when the mode, the effective preprocessing, the features and the nap margin are equal.
 */
class NapContextTest {
    private val night: NightInput = SyntheticNightGenerator(SEED).generate().input
    private val napStart = night.sessionStartMs + 60 * MINUTE_MS
    private val napEnd = napStart + 90 * MINUTE_MS

    /** A 90-min nap inside the night with the night's heart rate on both sides and 40-step records 10 min out. */
    private val nap: NightInput =
        night.copy(
            sessionStartMs = napStart,
            sessionEndMs = napEnd,
            steps =
                night.steps +
                    listOf(
                        StepsSample(napStart - 12 * MINUTE_MS, napStart - 10 * MINUTE_MS, CONTEXT_STEPS),
                        StepsSample(napEnd + 10 * MINUTE_MS, napEnd + 12 * MINUTE_MS, CONTEXT_STEPS),
                    ),
        )

    /** [nap] without anything outside its session: heart rate at start ≤ t ≤ end, steps ending in (start, end]. */
    private val napWithoutContext: NightInput =
        nap.copy(
            heartRate = nap.heartRate.filter { it.timeMs in napStart..napEnd },
            steps = nap.steps.filter { it.endMs > napStart && it.endMs <= napEnd },
        )

    private fun AlgoParams.withNapMargin(minutes: Int?): AlgoParams = copy(napMode = napMode.copy(contextMarginMin = minutes))

    /** The defaults with the nap margin empty (the night's), whatever TrainedDefaults sets. */
    private val params = AlgoParams().withNapMargin(null)

    private fun estimate(
        stager: SleepStager,
        input: NightInput,
        params: AlgoParams,
    ): NightEstimate = assertInstanceOf(EstimationOutcome.Estimated::class.java, stager.run(input, params)).estimate

    @Test
    fun `restrict keeps the samples of the margin, both ends included, and the step records ending inside it`() {
        val start = 1_800_000_000_000L
        val end = start + 60 * MINUTE_MS
        val m = 10 * MINUTE_MS
        val times = listOf(start - m - 1, start - m, start, end, end + m, end + m + 1)
        val input =
            NightInput(
                sessionStartMs = start,
                sessionEndMs = end,
                heartRate = times.map { HrSample(it, 60.0) },
                steps = listOf(start - m, start - m + 1, start, end, end + m, end + m + 1).map { StepsSample(it - MINUTE_MS, it, 3L) },
                spo2 = times.map { Spo2Sample(it, 97.0) },
                respiratoryRate = times.map { RespiratoryRateSample(it, 14.0) },
                hrv = times.map { HrvSample(it, 40.0) },
                forcedAwake = listOf(TimeSpan(start + MINUTE_MS, start + 2 * MINUTE_MS)),
                baselineNadirBpm = 50.0,
            )
        val inside = listOf(start - m, start, end, end + m)
        val restricted = NapContext.restrict(input, 10)
        assertEquals(inside, restricted.heartRate.map { it.timeMs })
        assertEquals(inside, restricted.spo2.map { it.timeMs })
        assertEquals(inside, restricted.respiratoryRate.map { it.timeMs })
        assertEquals(inside, restricted.hrv.map { it.timeMs })
        assertEquals(listOf(start - m + 1, start, end, end + m), restricted.steps.map { it.endMs })
        val sessionKept =
            input.copy(
                heartRate = restricted.heartRate,
                steps = restricted.steps,
                spo2 = restricted.spo2,
                respiratoryRate = restricted.respiratoryRate,
                hrv = restricted.hrv,
            )
        assertEquals(sessionKept, restricted, "session bounds, forced-awake spans and baseline are kept")
        // Margin 0: the session itself; a record ending exactly at the start is outside, one ending at the end inside.
        val none = NapContext.restrict(input, 0)
        assertEquals(listOf(start, end), none.heartRate.map { it.timeMs })
        assertEquals(listOf(end), none.steps.map { it.endMs })
        assertEquals(input.forcedAwake, none.forcedAwake)
        assertEquals(input.baselineNadirBpm, none.baselineNadirBpm)
        assertThrows(IllegalArgumentException::class.java) { NapContext.restrict(input, -1) }
    }

    @Test
    fun `forMode sets the nap's context margin and leaves nights and a null override alone`() {
        assertEquals(TrainedDefaults.NAP_CONTEXT_MARGIN_MIN, AlgoParams().napMode.contextMarginMin, "the trained default")
        assertSame(params.preprocess, params.forMode(SessionMode.NAP).preprocess, "null: a nap reads the night's margin")
        val zero = params.withNapMargin(0)
        assertEquals(params.preprocess.copy(contextMarginMin = 0), zero.forMode(SessionMode.NAP).preprocess)
        assertEquals(zero.napMode, zero.forMode(SessionMode.NAP).napMode)
        assertSame(zero, zero.forMode(SessionMode.NIGHT))
        assertEquals(params.preprocess.contextMarginMin, zero.forMode(SessionMode.NIGHT).preprocess.contextMarginMin)
    }

    @Test
    fun `a nap is prepared on a grid with the margin's context and keeps the caller's input`() {
        for ((margin, contextEpochs) in listOf(0 to 0, 10 to 10, null to params.preprocess.contextMarginMin)) {
            val prepared = NightPreparer.prepare(nap, params.withNapMargin(margin))
            assertEquals(SessionMode.NAP, prepared.mode)
            assertEquals(contextEpochs, prepared.grid.contextEpochs, "margin $margin")
            assertSame(nap, prepared.input)
            assertEquals(margin, prepared.preparationKey.napContextMarginMin)
        }
        // With 120-s epochs a margin is rounded up to whole epochs, the input still ends at the margin.
        val coarse = params.withNapMargin(3).let { it.copy(preprocess = it.preprocess.copy(epochSec = 120)) }
        assertEquals(2, NightPreparer.prepare(nap, coarse).grid.contextEpochs)
        val nightPrepared = NightPreparer.prepare(night, params.withNapMargin(0))
        assertEquals(SessionMode.NIGHT, nightPrepared.mode)
        assertEquals(params.preprocess.contextMarginMin, nightPrepared.grid.contextEpochs)
        assertNull(nightPrepared.preparationKey.napContextMarginMin)
    }

    @Test
    fun `with margin 0 the context cannot reach the features or the decode of a nap`() {
        val zero = params.withNapMargin(0)
        val features = HybridFeatureExtractor.extract(NightPreparer.prepare(nap, zero))
        val cutFeatures = HybridFeatureExtractor.extract(NightPreparer.prepare(napWithoutContext, zero))
        assertTrue(features.indices.all { features[it].contentEquals(cutFeatures[it]) }, "identical features")
        val stagers =
            listOf(
                SleepStager(SyntheticHybridModels.default),
                SleepStager(SyntheticHybridModels.default.copy(nap = NapModels.from(MinimalBundles.logreg()))),
                SleepStager(),
            )
        for (stager in stagers) {
            assertEquals(stager.run(napWithoutContext, zero), stager.run(nap, zero))
            // Any other context gives the same estimate too (a different reader margin, a daytime pre-context).
            val shifted = nap.copy(heartRate = nap.heartRate.map { if (it.timeMs < napStart) it.copy(bpm = it.bpm + 10.0) else it })
            assertEquals(stager.run(nap, zero), stager.run(shifted, zero))
        }
        // Without the override the context is read: here it changes the nap's decode.
        val hybrid = stagers.first()
        assertNotEquals(estimate(hybrid, nap, params).perEpoch, estimate(hybrid, nap, zero).perEpoch)
    }

    @Test
    fun `heart rate only in the context is no heart rate for a nap with margin 0`() {
        val contextOnly = nap.copy(heartRate = nap.heartRate.filter { it.timeMs < napStart })
        val stager = SleepStager(SyntheticHybridModels.default)
        val zero = assertInstanceOf(EstimationOutcome.NotEstimated::class.java, stager.run(contextOnly, params.withNapMargin(0)))
        assertEquals(NotEstimatedReason.NO_HR_DATA, zero.reason)
        assertTrue(zero.detail.contains("session ± 0 min"), zero.detail)
        val night = assertInstanceOf(EstimationOutcome.NotEstimated::class.java, stager.run(contextOnly, params))
        assertEquals(NotEstimatedReason.LOW_COVERAGE, night.reason, "the night's margin reads the context samples")
    }

    @Test
    fun `nights decode identically whatever the nap margin`() {
        val stager = SleepStager(SyntheticHybridModels.default)
        val reference = estimate(stager, night, params)
        val durationMs = night.sessionEndMs - night.sessionStartMs
        for (margin in listOf(null, 0, 10, 30)) {
            val other = params.withNapMargin(margin)
            val estimate = estimate(stager, night, other)
            assertEquals(SessionMode.NIGHT, estimate.mode)
            assertEquals(reference, estimate.copy(params = params, paramsHash = reference.paramsHash), "margin $margin")
            assertEquals(NightPreparer.preparationKey(params, durationMs), NightPreparer.preparationKey(other, durationMs))
        }
    }

    @Test
    fun `a preparation is reused only when mode, effective preprocessing, features and nap margin are equal`() {
        val prepared = NightPreparer.prepare(nap, params)
        val decoderOnly = prepared.withParams(params.copy(napMode = params.napMode.copy(hybrid = NapHybridParams(emissionWeight = 0.7))))
        assertSame(prepared.preprocessed, decoderOnly.preprocessed)
        assertSame(prepared.cache, decoderOnly.cache)
        val zero = prepared.withParams(params.withNapMargin(0))
        assertNotSame(prepared.preprocessed, zero.preprocessed)
        assertEquals(0, zero.grid.contextEpochs)
        // A nap with margin 0 does not see the night's margin: another night margin keeps its preparation.
        val wider = params.withNapMargin(0).let { it.copy(preprocess = it.preprocess.copy(contextMarginMin = 60)) }
        assertSame(zero.preprocessed, zero.withParams(wider).preprocessed)
        // The override equal to the night's margin still restricts the input: a different preparation.
        val thirty = params.withNapMargin(params.preprocess.contextMarginMin)
        assertNotEquals(prepared.preparationKey, NightPreparer.preparationKey(thirty, nap.sessionEndMs - nap.sessionStartMs))
        assertNotSame(prepared.preprocessed, prepared.withParams(thirty).preprocessed)
        // A night never reads the override: reused.
        val nightPrepared = NightPreparer.prepare(night, params)
        assertSame(nightPrepared.preprocessed, nightPrepared.withParams(params.withNapMargin(0)).preprocessed)
        // A preparation shared through withParams gives the same estimate as a fresh one.
        val stager = SleepStager(SyntheticHybridModels.default)
        assertEquals(stager.run(nap, params.withNapMargin(0)), stager.run(prepared.withParams(params.withNapMargin(0))))
        assertEquals(stager.run(nap, wider), stager.run(zero.withParams(wider)))
    }

    private companion object {
        const val SEED = 77L
        const val MINUTE_MS = 60_000L
        const val CONTEXT_STEPS = 40L
    }
}

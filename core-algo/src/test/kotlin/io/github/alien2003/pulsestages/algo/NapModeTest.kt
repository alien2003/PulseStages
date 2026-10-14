package io.github.alien2003.pulsestages.algo

import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.DurationModel
import io.github.alien2003.pulsestages.algo.params.LowConfidencePolicy
import io.github.alien2003.pulsestages.algo.params.NapHybridParams
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.algo.params.NapPostprocessParams
import io.github.alien2003.pulsestages.algo.personal.PersonalModel
import io.github.alien2003.pulsestages.algo.personal.PersonalModelKind
import io.github.alien2003.pulsestages.algo.personal.PersonalProvenance
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.testing.MinimalBundles
import io.github.alien2003.pulsestages.algo.testing.NapModels
import io.github.alien2003.pulsestages.algo.testing.SyntheticHybridModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The night / nap modes of [SleepStager] (ALGORITHM.md section 18): the mode follows the session length and
 * `napMode.maxSessionMin`; a nap is estimated with `napMode.estimator` (the night's when null), the nap overrides and
 * the output policy, and the bundle's nap model when it has one; nights are untouched by every nap setting.
 */
class NapModeTest {
    private val model = SyntheticHybridModels.default
    private val stager = SleepStager(model)
    private val night: NightInput = SyntheticNightGenerator(31).generate().input

    /** The defaults without nap decoder overrides (the trained defaults may set them): every nap value inherited. */
    private val noNapOverrides: AlgoParams = AlgoParams().let { it.copy(napMode = it.napMode.copy(hybrid = NapHybridParams())) }

    /** The first [minutes] of [night] (nothing after the end). */
    private fun nap(minutes: Long): NightInput {
        val end = night.sessionStartMs + minutes * MINUTE_MS
        return night.copy(
            sessionEndMs = end,
            heartRate = night.heartRate.filter { it.timeMs <= end },
            steps =
                night.steps.filter {
                    it.endMs <=
                        end
                },
        )
    }

    private fun estimate(
        input: NightInput,
        params: AlgoParams,
        on: SleepStager = stager,
    ): NightEstimate = assertInstanceOf(EstimationOutcome.Estimated::class.java, on.run(input, params)).estimate

    @Test
    fun `the mode is NAP strictly below napMode maxSessionMin and NIGHT from it on, never with 0`() {
        val params = AlgoParams()
        assertEquals(180.0, params.napMode.maxSessionMin)
        assertEquals(SessionMode.NAP, estimate(nap(179), params).mode)
        assertEquals(SessionMode.NIGHT, estimate(nap(180), params).mode)
        assertEquals(SessionMode.NIGHT, estimate(night, params).mode)
        val ms = 180 * MINUTE_MS
        assertEquals(SessionMode.NAP, params.modeOf(ms - 1))
        assertEquals(SessionMode.NIGHT, params.modeOf(ms))
        val off = params.copy(napMode = params.napMode.copy(maxSessionMin = 0.0))
        assertEquals(SessionMode.NIGHT, estimate(nap(30), off).mode)
        val longer = params.copy(napMode = params.napMode.copy(maxSessionMin = 600.0))
        assertEquals(SessionMode.NAP, estimate(night, longer).mode)
    }

    @Test
    fun `the estimate keeps the parameters as given and the stager's params hash`() {
        val params = AlgoParams().copy(napMode = AlgoParams().napMode.copy(post = NapPostprocessParams(minDeepMin = 12.0)))
        val estimate = estimate(nap(90), params)
        assertEquals(params, estimate.params)
        assertEquals(stager.paramsHash(params), estimate.paramsHash)
        // Every nap setting is part of the hash of every estimate, nights included.
        assertNotEquals(stager.paramsHash(AlgoParams()), stager.paramsHash(params))
    }

    @Test
    fun `SKIP declines naps with NAP_SKIPPED and leaves nights alone`() {
        val skip = AlgoParams().copy(napMode = AlgoParams().napMode.copy(outputPolicy = NapOutputPolicy.SKIP))
        val outcome = assertInstanceOf(EstimationOutcome.NotEstimated::class.java, stager.run(nap(60), skip))
        assertEquals(NotEstimatedReason.NAP_SKIPPED, outcome.reason)
        assertTrue(outcome.detail.contains("napMode.outputPolicy = SKIP"), outcome.detail)
        assertTrue(outcome.coverage > 0.9)
        assertEquals(estimate(night, AlgoParams()).stages, estimate(night, skip).stages)
        // The session gate comes first; without heart rate a skipped nap is still NAP_SKIPPED (no data is needed).
        val gated = skip.copy(preprocess = skip.preprocess.copy(minSessionMin = 120.0))
        assertEquals(NotEstimatedReason.SESSION_TOO_SHORT, (stager.run(nap(60), gated) as EstimationOutcome.NotEstimated).reason)
        val empty = nap(60).copy(heartRate = emptyList())
        assertEquals(NotEstimatedReason.NAP_SKIPPED, (stager.run(empty, skip) as EstimationOutcome.NotEstimated).reason)
    }

    @Test
    fun `SLEEP_WAKE estimates the stages like STAGES (the mapping is an output step)`() {
        val sleepWake = AlgoParams().copy(napMode = AlgoParams().napMode.copy(outputPolicy = NapOutputPolicy.SLEEP_WAKE))
        assertEquals(estimate(nap(90), AlgoParams()).stages, estimate(nap(90), sleepWake).stages)
    }

    @Test
    fun `the nap estimator is used for naps only`() {
        val params = AlgoParams().copy(napMode = AlgoParams().napMode.copy(estimator = EstimatorType.RULES))
        assertEquals(EstimatorType.RULES, estimate(nap(90), params).estimator)
        assertEquals(EstimatorType.HYBRID, estimate(night, params).estimator)
        val hybridNaps =
            AlgoParams().copy(estimator = EstimatorType.HMM, napMode = AlgoParams().napMode.copy(estimator = EstimatorType.HYBRID))
        assertEquals(EstimatorType.HYBRID, estimate(nap(90), hybridNaps).estimator, "an explicit nap estimator is kept")
        assertEquals(EstimatorType.HMM, estimate(night, hybridNaps).estimator)
        val both = AlgoParams().withEstimator(EstimatorType.HMM)
        assertEquals(EstimatorType.HMM, estimate(nap(90), both).estimator)
    }

    @Test
    fun `by default naps follow the night's estimator, as a settings file of 0_1_0 expects`() {
        assertNull(AlgoParams().napMode.estimator, "no nap estimator by default: the night's is used")
        for (type in EstimatorType.entries) {
            // A 0.1.0 settings file had only algo.estimator, which applied to every session.
            val stored = AlgoParams().copy(estimator = type)
            assertEquals(type, stored.napEstimator)
            assertEquals(type, stored.forMode(SessionMode.NAP).estimator)
            assertEquals(type, estimate(nap(90), stored).estimator)
        }
    }

    @Test
    fun `post-processing overrides change naps only`() {
        val base = noNapOverrides
        // Every epoch below a posterior of 1 becomes LIGHT: the nap is all LIGHT (no forced epochs in the first hours).
        val override = NapPostprocessParams(lowConfidenceThreshold = 1.0, lowConfidencePolicy = LowConfidencePolicy.LIGHT)
        val params = base.copy(napMode = base.napMode.copy(post = override))
        val napStages = estimate(nap(90), params).stages
        assertTrue(napStages.all { it.stage in LIGHT_AWAKE_OR_GAP }, "$napStages")
        assertNotEquals(estimate(nap(90), base).stages, napStages)
        assertEquals(estimate(night, base).stages, estimate(night, params).stages)
        // The effective nap parameters: overrides applied, nulls inherit the night's values.
        val effective = params.forMode(SessionMode.NAP).post
        assertEquals(LowConfidencePolicy.LIGHT, effective.lowConfidencePolicy)
        assertEquals(1.0, effective.lowConfidenceThreshold)
        assertEquals(base.post.minDeepMin, effective.minDeepMin)
        assertEquals(base, base.forMode(SessionMode.NAP).copy(estimator = base.estimator), "no overrides: a nap uses the night's values")
        assertEquals(params, params.forMode(SessionMode.NIGHT))
    }

    @Test
    fun `minimum bouts of naps can differ from nights`() {
        val base = AlgoParams()
        val long = base.copy(napMode = base.napMode.copy(post = NapPostprocessParams(minDeepMin = 60.0, minRemMin = 60.0)))
        val stages = estimate(nap(120), long).stages
        val minutes = { stage: SleepStage -> stages.filter { it.stage == stage }.map { (it.endMs - it.startMs) / MINUTE_MS } }
        assertTrue((minutes(SleepStage.DEEP) + minutes(SleepStage.REM)).all { it >= 60 }, "$stages")
        assertEquals(estimate(night, base).stages, estimate(night, long).stages)
    }

    @Test
    fun `hybrid decoder overrides change naps only`() {
        val base = noNapOverrides
        val geometric =
            base.copy(
                napMode = base.napMode.copy(hybrid = NapHybridParams(durationModel = DurationModel.GEOMETRIC, emissionWeight = 1.0)),
            )
        val asNightParams = base.copy(hybrid = base.hybrid.copy(durationModel = DurationModel.GEOMETRIC, emissionWeight = 1.0))
        assertEquals(estimate(nap(120), asNightParams).perEpoch, estimate(nap(120), geometric).perEpoch)
        assertNotEquals(estimate(nap(120), base).perEpoch, estimate(nap(120), geometric).perEpoch)
        assertEquals(estimate(night, base).perEpoch, estimate(night, geometric).perEpoch)
        assertEquals(DurationModel.GEOMETRIC, geometric.forMode(SessionMode.NAP).hybrid.durationModel)
        assertEquals(base.hybrid.timePriorWeight, geometric.forMode(SessionMode.NAP).hybrid.timePriorWeight)
    }

    @Test
    fun `the nap check of stage shares runs with checkProportions and the nap corridors`() {
        val base = AlgoParams()
        val failing = base.copy(post = base.post.copy(sanityDeepMin = 1.0, sanityDeepMax = 1.0))
        assertTrue(estimate(nap(90), failing).warnings.none { it.code.name.startsWith("SANITY_") })
        val checked = failing.copy(napMode = failing.napMode.copy(post = NapPostprocessParams(checkProportions = true)))
        assertTrue(estimate(nap(90), checked).warnings.any { it.code == WarningCode.SANITY_DEEP_LOW })
        val napCorridor = checked.copy(napMode = checked.napMode.copy(post = checked.napMode.post.copy(sanityDeepMin = 0.0)))
        assertTrue(estimate(nap(90), napCorridor).warnings.none { it.code.name.startsWith("SANITY_") })
    }

    @Test
    fun `a nap is never compared with the baseline, a night is, and 0 restores it for every session`() {
        val low = 30.0
        assertFalse(estimate(nap(90).copy(baselineNadirBpm = low), AlgoParams()).flags.elevatedHr)
        assertTrue(estimate(night.copy(baselineNadirBpm = low), AlgoParams()).flags.elevatedHr)
        val off = AlgoParams().copy(napMode = AlgoParams().napMode.copy(maxSessionMin = 0.0))
        assertTrue(estimate(nap(90).copy(baselineNadirBpm = low), off).flags.elevatedHr)
        // A changed threshold that turns the nap into a night prepares it again (the baseline counts then).
        val prepared = NightPreparer.prepare(nap(90).copy(baselineNadirBpm = low), AlgoParams())
        assertEquals(SessionMode.NAP, prepared.mode)
        assertFalse(prepared.statistics.elevated)
        val again = prepared.withParams(off)
        assertEquals(SessionMode.NIGHT, again.mode)
        assertTrue(again.statistics.elevated)
    }

    @Test
    fun `naps use the bundle's nap model when it has one, nights the main model`() {
        val napModel = NapModels.from(MinimalBundles.logreg(), stageLogBias = listOf(0.0, 0.5, 0.0, -0.5))
        val withNap = SleepStager(model.copy(nap = napModel))
        val asMain = SleepStager(MinimalBundles.logreg().copy(stageLogBias = listOf(0.0, 0.5, 0.0, -0.5), stageLogBiasShort = null))
        // A nap decodes exactly like the nap model's components as a main model (its log-bias included).
        assertEquals(estimate(nap(90), AlgoParams(), asMain).perEpoch, estimate(nap(90), AlgoParams(), withNap).perEpoch)
        assertNotEquals(estimate(nap(90), AlgoParams()).perEpoch, estimate(nap(90), AlgoParams(), withNap).perEpoch)
        assertEquals(estimate(night, AlgoParams()).perEpoch, estimate(night, AlgoParams(), withNap).perEpoch)
        assertNotEquals(stager.paramsHash(AlgoParams()), withNap.paramsHash(AlgoParams()), "the nap model is part of the fingerprint")
    }

    @Test
    fun `an incompatible nap model sends naps to the HMM fallback, nights keep HYBRID`() {
        val renamed = model.featureNames.toMutableList().also { it[2] = "renamed" }
        val bundle = model.copy(nap = NapModels.from(MinimalBundles.logreg(featureNames = renamed)))
        val napEstimate = estimate(nap(90), AlgoParams(), SleepStager(bundle))
        assertEquals(EstimatorType.HMM, napEstimate.estimator)
        val warning = napEstimate.warnings.single { it.code == WarningCode.HYBRID_MODEL_UNAVAILABLE }
        assertTrue(warning.detail.contains("nap model"), warning.detail)
        assertEquals(EstimatorType.HYBRID, estimate(night, AlgoParams(), SleepStager(bundle)).estimator)
    }

    @Test
    fun `personal classifier deltas apply to the main model, never to the nap model`() {
        val napModel = NapModels.from(MinimalBundles.logreg())
        val bundle = model.copy(nap = napModel)
        val deltas =
            PersonalModel(
                biasDeltas = listOf(3.0, -1.0, -1.0, -1.0),
                baseModelFingerprint = bundle.nightModelFingerprint(),
                provenance = PersonalProvenance(PersonalModelKind.CALIBRATED, nightsUsed = 1),
            )
        val personal = SleepStager(bundle, deltas)
        val plain = SleepStager(bundle)
        assertEquals(estimate(nap(90), AlgoParams(), plain).perEpoch, estimate(nap(90), AlgoParams(), personal).perEpoch)
        assertNotEquals(estimate(night, AlgoParams(), plain).perEpoch, estimate(night, AlgoParams(), personal).perEpoch)
    }

    @Test
    fun `a calibration keeps applying to nights when only the nap parts or the metadata of the bundle change`() {
        val before = model.copy(stageLogBiasShort = null, nap = null)
        val deltas =
            PersonalModel(
                biasDeltas = listOf(3.0, -1.0, -1.0, -1.0),
                baseModelFingerprint = before.nightModelFingerprint(),
                provenance = PersonalProvenance(PersonalModelKind.CALIBRATED, nightsUsed = 1),
            )
        val withPersonal = estimate(night, AlgoParams(), SleepStager(before, deltas)).perEpoch
        assertNotEquals(estimate(night, AlgoParams(), SleepStager(before)).perEpoch, withPersonal, "the deltas change the night")
        // A nap model added (or retrained), a new short-session bias, a new modelId: the night model is the same.
        val after =
            before.copy(
                modelId = "${before.modelId}-with-nap",
                stageLogBiasShort = listOf(0.1, 0.2, -0.1, -0.2),
                nap = NapModels.from(MinimalBundles.logreg()),
            )
        assertNotEquals(before.fingerprint(), after.fingerprint())
        assertEquals(before.nightModelFingerprint(), after.nightModelFingerprint())
        assertEquals(withPersonal, estimate(night, AlgoParams(), SleepStager(after, deltas)).perEpoch)
        // A key stored before the night-model fingerprint existed (the whole bundle's) still applies to that bundle.
        val legacy = deltas.copy(baseModelFingerprint = before.fingerprint())
        assertEquals(withPersonal, estimate(night, AlgoParams(), SleepStager(before, legacy)).perEpoch)
        // A changed night model (its stage bias) drops the deltas.
        val retrained = after.copy(stageLogBias = listOf(0.3, 0.0, -0.1, -0.2))
        assertNotEquals(before.nightModelFingerprint(), retrained.nightModelFingerprint())
        assertEquals(
            estimate(night, AlgoParams(), SleepStager(retrained)).perEpoch,
            estimate(night, AlgoParams(), SleepStager(retrained, deltas)).perEpoch,
        )
    }

    @Test
    fun `estimateWith forces the estimator but keeps the nap overrides`() {
        val override = NapPostprocessParams(lowConfidenceThreshold = 1.0, lowConfidencePolicy = LowConfidencePolicy.LIGHT)
        val params = AlgoParams().copy(napMode = AlgoParams().napMode.copy(post = override, outputPolicy = NapOutputPolicy.SKIP))
        val rules = stager.estimateWith(EstimatorType.RULES, nap(90), params)
        assertEquals(EstimatorType.RULES, rules.estimator)
        assertEquals(SessionMode.NAP, rules.mode)
        assertTrue(rules.stages.all { it.stage in LIGHT_AWAKE_OR_GAP }, "${rules.stages}")
    }

    private companion object {
        const val MINUTE_MS = 60_000L

        /** What an all-LIGHT nap shows: LIGHT, forced AWAKE, and UNKNOWN for the last epoch, which has no sample after it. */
        val LIGHT_AWAKE_OR_GAP = setOf(SleepStage.LIGHT, SleepStage.AWAKE, SleepStage.UNKNOWN)
    }
}

package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.Invariants
import io.github.alien2003.pulsestages.algo.ParamsHasher
import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.export.CsvExporter
import io.github.alien2003.pulsestages.algo.features.BaseFeatures
import io.github.alien2003.pulsestages.algo.hmm.HmmMath
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.Spo2Sample
import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.algo.model.TimeSpan
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.DurationModel
import io.github.alien2003.pulsestages.algo.personal.PersonalModel
import io.github.alien2003.pulsestages.algo.personal.PersonalModelKind
import io.github.alien2003.pulsestages.algo.personal.PersonalProvenance
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.testing.MinimalBundles
import io.github.alien2003.pulsestages.algo.testing.SyntheticHybridModels
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ln

class HybridEstimatorTest {
    private val model = SyntheticHybridModels.default
    private val input = SyntheticNightGenerator(77).generate().input

    /** Post-processing that leaves the decoder's labels unchanged on a night with data everywhere. */
    private fun rawParams(durationModel: DurationModel): AlgoParams {
        val base = AlgoParams(estimator = EstimatorType.HYBRID)
        return base.copy(
            hybrid = base.hybrid.copy(durationModel = durationModel),
            post = base.post.copy(minDeepMin = 0.0, minRemMin = 0.0, minAwakeMin = 0.0),
        )
    }

    private fun personal(
        deltas: List<Double>,
        fingerprint: String? = null,
    ) = PersonalModel(
        biasDeltas = deltas,
        baseModelFingerprint = fingerprint,
        provenance = PersonalProvenance(PersonalModelKind.CALIBRATED, nightsUsed = 1),
    )

    private fun meanPosterior(
        estimate: NightEstimate,
        stage: SleepStage,
    ): Double = estimate.perEpoch.map { it.posterior[stage] }.average()

    @Test
    fun `geometric mode equals the plain HMM on the same scaled likelihoods`() {
        val params = rawParams(DurationModel.GEOMETRIC)
        val night = NightPreparer.prepare(input, params)
        assertTrue(night.sessionDataMask.all { it }, "test night must have data everywhere")
        val emissions = HybridEmissions(night, model, DenseNetwork(model), null).withOffsets(DoubleArray(4))
        val hmm = HybridDecoders.geometric(model, params, night.grid.epochMin, night.grid.sessionEpochs)
        val path = HmmMath.viterbi(hmm, emissions)
        val posteriors = HmmMath.forwardBackward(hmm, emissions).posteriors

        val estimate = SleepStager(model).estimateWith(EstimatorType.HYBRID, input, params)

        assertEquals(EstimatorType.HYBRID, estimate.estimator)
        assertEquals(path.map { SleepStage.ofIndex(it) }, estimate.perEpoch.map { it.stage })
        estimate.perEpoch.forEachIndexed { k, epoch -> assertArrayEquals(posteriors[k], epoch.posterior.toList().toDoubleArray(), 1e-12) }
    }

    @Test
    fun `explicit mode equals the HSMM decoder on the same scaled likelihoods`() {
        val params = rawParams(DurationModel.EXPLICIT)
        val night = NightPreparer.prepare(input, params)
        val emissions = HybridEmissions(night, model, DenseNetwork(model), null).withOffsets(DoubleArray(4))
        val hsmm = HybridDecoders.hsmm(model, params, night.grid.epochMin, relaxSelfTransitions = false)
        val path = HsmmMath.viterbi(hsmm, emissions)
        val posteriors = HsmmMath.forwardBackward(hsmm, emissions).posteriors

        val estimate = SleepStager(model).estimateWith(EstimatorType.HYBRID, input, params)

        assertTrue(path.logScore.isFinite())
        assertEquals(path.states.map { SleepStage.ofIndex(it) }, estimate.perEpoch.map { it.stage })
        estimate.perEpoch.forEachIndexed { k, epoch -> assertArrayEquals(posteriors[k], epoch.posterior.toList().toDoubleArray(), 1e-12) }
        assertNotEquals(
            SleepStager(model).estimateWith(EstimatorType.HYBRID, input, rawParams(DurationModel.GEOMETRIC)).stages,
            estimate.stages,
        )
    }

    @Test
    fun `incompatible bundles fall back to the HMM with a reason`() {
        val renamed = HybridFeatureExtractor.FEATURE_NAMES.toMutableList().also { it[3] = "tt" }
        val incompatible = MinimalBundles.logreg(featureNames = renamed)
        val params = AlgoParams(estimator = EstimatorType.HYBRID)

        val estimate = SleepStager(incompatible).estimateWith(EstimatorType.HYBRID, input, params)

        assertEquals(EstimatorType.HMM, estimate.estimator)
        val warning = estimate.warnings.single { it.code == WarningCode.HYBRID_MODEL_UNAVAILABLE }
        assertTrue(warning.detail.contains("incompatible") && warning.detail.contains("index 3"), warning.detail)
        assertEquals(SleepStager().estimateWith(EstimatorType.HMM, input, params).stages, estimate.stages)
        assertEquals(ParamsHasher.hash(params, incompatible.fingerprint()), estimate.paramsHash)
        val version = MinimalBundles.logreg().copy(featureSetVersion = 99)
        assertEquals(EstimatorType.HMM, HybridEstimator(version).estimate(input, params).estimator)
    }

    @Test
    fun `estimates carry the hybrid features and the model fingerprint`() {
        val params = AlgoParams(estimator = EstimatorType.HYBRID)
        val estimate = HybridEstimator(model).estimate(input, params)

        assertEquals(SleepStager(model).estimateWith(EstimatorType.HYBRID, input, params), estimate)
        val extra = HybridFeatureExtractor.FEATURE_NAMES.filter { it !in BaseFeatures.NAMES }
        assertEquals(BaseFeatures.NAMES + extra, estimate.featureNames)
        val features = HybridFeatureExtractor.extract(NightPreparer.prepare(input, params))
        val column = HybridFeatureExtractor.FEATURE_NAMES.indexOf("relMean30")
        assertEquals(features[10][column], estimate.perEpoch[10].features[estimate.featureNames.indexOf("relMean30")], 0.0)
        assertTrue(
            CsvExporter
                .export(estimate)
                .lineSequence()
                .first()
                .contains(",relMean30,"),
        )
        assertEquals(ParamsHasher.hash(params, model.fingerprint()), estimate.paramsHash)
        assertEquals("synthetic-test", SleepStager(model).modelId)
        Invariants.check(estimate, input)
    }

    @Test
    fun `forced awake spans longer than the maximum bout stay feasible`() {
        val start = input.sessionStartMs
        val forced = input.copy(forcedAwake = listOf(TimeSpan(start + 60 * 60_000L, start + 260 * 60_000L)))
        for (mode in DurationModel.entries) {
            val estimate = SleepStager(model).estimateWith(EstimatorType.HYBRID, forced, rawParams(mode))
            Invariants.check(estimate, forced, "mode=$mode")
            assertEquals(EstimatorType.HYBRID, estimate.estimator)
            assertTrue(estimate.perEpoch.subList(61, 259).all { it.stage == SleepStage.AWAKE })
        }
    }

    @Test
    fun `steps, elevated heart rate and desaturations scale the likelihoods`() {
        val params = AlgoParams()
        val start = input.sessionStartMs
        val k = 150
        val at = start + k * 60_000L
        val withSteps = input.copy(steps = input.steps + StepsSample(at, at + 60_000L, 2))
        val neutral = params.copy(hmm = params.hmm.copy(fewStepsAwakeFactor = 1.0))
        val plain = HybridEmissions(NightPreparer.prepare(withSteps, neutral), model, DenseNetwork(model), null).withOffsets(DoubleArray(4))
        val modified =
            withSteps.copy(
                spo2 = (0 until 480).map { Spo2Sample(start + it * 60_000L, if (it == k + 20) 90.0 else 96.0) },
                baselineNadirBpm = 30.0,
            )
        val night = NightPreparer.prepare(modified, params)
        assertTrue(night.statistics.elevated)
        val scaled = HybridEmissions(night, model, DenseNetwork(model), null).withOffsets(DoubleArray(4))
        val awake = SleepStage.AWAKE.index
        val light = SleepStage.LIGHT.index
        val elevated = ln(params.hybrid.elevatedAwakeFactor)
        val spo2 = ln(params.features.spo2AwakeLightFactor)

        assertEquals(plain[k][awake] + ln(params.hmm.fewStepsAwakeFactor) + elevated, scaled[k][awake], 1e-9)
        assertEquals(plain[k][light], scaled[k][light], 1e-9)
        val dip = k + 20
        assertEquals(plain[dip][light] + spo2, scaled[dip][light], 1e-9)
        assertEquals(plain[dip][SleepStage.DEEP.index], scaled[dip][SleepStage.DEEP.index], 1e-9)
        assertEquals(plain[dip][awake] + spo2 + elevated, scaled[dip][awake], 1e-9)
    }

    @Test
    fun `personal bias deltas shift the posteriors only when enabled and fitted for this bundle`() {
        val params = AlgoParams(estimator = EstimatorType.HYBRID)
        val global = SleepStager(model).estimateWith(EstimatorType.HYBRID, input, params)
        val boost = personal(listOf(0.0, 0.0, 2.0, 0.0), model.fingerprint())

        val personalized = SleepStager(model, boost).estimateWith(EstimatorType.HYBRID, input, params)
        assertTrue(meanPosterior(personalized, SleepStage.DEEP) > meanPosterior(global, SleepStage.DEEP) + 0.02)
        assertEquals(ParamsHasher.hash(params, model.fingerprint(), boost.fingerprint()), personalized.paramsHash)

        val disabled = params.copy(personal = params.personal.copy(enabled = false))
        val off = SleepStager(model, boost).estimateWith(EstimatorType.HYBRID, input, disabled)
        assertEquals(
            SleepStager(model).estimateWith(EstimatorType.HYBRID, input, disabled).copy(paramsHash = ""),
            off.copy(paramsHash = ""),
        )
        assertEquals(ParamsHasher.hash(disabled, model.fingerprint(), null), off.paramsHash)

        val otherBundle = personal(listOf(0.0, 0.0, 2.0, 0.0), "0".repeat(64))
        val ignored = SleepStager(model, otherBundle).estimateWith(EstimatorType.HYBRID, input, params)
        assertEquals(global.perEpoch, ignored.perEpoch)
    }
}

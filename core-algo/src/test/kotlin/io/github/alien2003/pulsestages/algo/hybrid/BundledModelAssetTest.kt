package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.metrics.IntervalScoring
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.DurationModel
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * The model bundle shipped in the app (`app/src/main/assets/models/hybrid_model.json`, trained by tools/train):
 * it parses and validates, declares exactly this build's feature set, and the HYBRID estimator uses it on a
 * synthetic night (no HYBRID_MODEL_UNAVAILABLE fallback) with both duration models and the default parameters.
 */
class BundledModelAssetTest {
    private val asset =
        File(checkNotNull(System.getProperty("pulsestages.hybridModelAsset")) { "system property not set by Gradle" })

    private val model: HybridModel by lazy {
        assertTrue(asset.isFile, "missing $asset (train it: tools/train/README.md)")
        HybridModel.parse(asset.readText())
    }

    @Test
    fun `the bundled model is valid and matches this build's features`() {
        assertEquals(HybridFeatureExtractor.FEATURE_SET_VERSION, model.featureSetVersion)
        assertEquals(HybridFeatureExtractor.FEATURE_NAMES, model.featureNames)
        assertNull(model.compatibilityProblem())
        assertEquals(HybridFeatureExtractor.FEATURE_NAMES.size, model.standardization.mean.size)
        assertTrue(model.modelId.isNotBlank())
        assertTrue(model.classPriors.all { it > 0 } && abs(model.classPriors.sum() - 1) < 1e-6)
        assertTrue(checkNotNull(model.training?.dataset).isNotBlank())
    }

    @Test
    fun `hybrid runs with the bundled model on a synthetic night`() {
        val night = SyntheticNightGenerator(SEED).generate()
        val stager = SleepStager(model)
        val defaults = AlgoParams()
        for (durationModel in DurationModel.entries) {
            val params = defaults.copy(estimator = EstimatorType.HYBRID, hybrid = defaults.hybrid.copy(durationModel = durationModel))
            val outcome = stager.run(night.input, params)
            assertTrue(outcome is EstimationOutcome.Estimated, "$durationModel: $outcome")
            val estimate = (outcome as EstimationOutcome.Estimated).estimate
            assertEquals(EstimatorType.HYBRID, estimate.estimator, "$durationModel")
            assertTrue(estimate.warnings.none { it.code == WarningCode.HYBRID_MODEL_UNAVAILABLE }, "$durationModel: ${estimate.warnings}")
            assertEquals(night.input.sessionStartMs, estimate.stages.first().startMs)
            assertEquals(night.input.sessionEndMs, estimate.stages.last().endMs)
            val stagesSeen = estimate.stages.mapTo(HashSet()) { it.stage }
            assertTrue(stagesSeen.size >= 2, "$durationModel: a single stage all night")
            val score = IntervalScoring.score(night.truth, estimate.stages)
            assertTrue(score.confusion.total > 0)
            assertTrue(estimate.perEpoch.all { abs(it.posterior.toList().sum() - 1) < 1e-6 }, "$durationModel posteriors")
        }
        // The default configuration (AlgoParams() as shipped) also uses the hybrid estimator with this bundle.
        val shipped = (stager.run(night.input, defaults) as EstimationOutcome.Estimated).estimate
        assertTrue(shipped.warnings.none { it.code == WarningCode.HYBRID_MODEL_UNAVAILABLE }, "${shipped.warnings}")
        assertEquals(defaults.estimator, shipped.estimator)
    }

    private companion object {
        const val SEED = 4242L
    }
}

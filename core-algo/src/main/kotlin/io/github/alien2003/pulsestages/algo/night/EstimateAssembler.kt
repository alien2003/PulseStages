package io.github.alien2003.pulsestages.algo.night

import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.algo.ParamsHasher
import io.github.alien2003.pulsestages.algo.features.BaseFeatures
import io.github.alien2003.pulsestages.algo.model.EpochResult
import io.github.alien2003.pulsestages.algo.model.EstimationWarning
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightFlags
import io.github.alien2003.pulsestages.algo.model.StageProbabilities
import io.github.alien2003.pulsestages.algo.post.PostprocessContext
import io.github.alien2003.pulsestages.algo.post.PostprocessResult

/**
 * Feature columns appended to [BaseFeatures.NAMES] in the per-epoch results (e.g. the hybrid features).
 *
 * @property names column names (distinct from [BaseFeatures.NAMES]).
 * @property values `[session epoch][column]`.
 */
internal class ExtraFeatures(
    val names: List<String>,
    val values: Array<DoubleArray>,
)

/** Builds the [NightEstimate] from a prepared night and its post-processed labels. */
internal object EstimateAssembler {
    /**
     * The post-processing context of [night]; the stage-share checks run only for a session of at least
     * `post.proportionsMinSessionMin` ([io.github.alien2003.pulsestages.algo.params.PostprocessParams.checksProportions]).
     */
    fun context(night: PreparedNight): PostprocessContext =
        PostprocessContext(
            night.grid,
            night.sessionDataMask,
            night.forcedAwakeMask,
            night.coverage,
            checkProportions = night.params.post.checksProportions(night.input.sessionEndMs - night.input.sessionStartMs),
        )

    /**
     * Night confidence: mean max posterior over session epochs with data × coverage × elevatedConfidenceFactor (if
     * elevated) × sanityConfidenceFactor (if the sanity check failed), clipped to `[0, 1]`.
     */
    fun confidence(
        night: PreparedNight,
        post: PostprocessResult,
    ): Double {
        val withData = night.sessionDataMask.indices.filter { night.sessionDataMask[it] }
        if (withData.isEmpty()) return 0.0
        var value = withData.sumOf { post.posteriors[it].max() } / withData.size * night.coverage
        if (night.statistics.elevated) value *= night.params.features.elevatedConfidenceFactor
        if (post.sanityFailed) value *= night.params.post.sanityConfidenceFactor
        return value.coerceIn(0.0, 1.0)
    }

    /**
     * Assembles the estimate; warnings are the night's, then [estimatorWarnings], then the post-processing's. The
     * per-epoch features are the base features followed by [extra] (if any).
     */
    fun assemble(
        night: PreparedNight,
        type: EstimatorType,
        post: PostprocessResult,
        estimatorWarnings: List<EstimationWarning>,
        extra: ExtraFeatures? = null,
    ): NightEstimate {
        val grid = night.grid
        val pre = night.preprocessed
        val perEpoch =
            List(grid.sessionEpochs) { k ->
                val p = grid.position(k)
                EpochResult(
                    index = k,
                    startMs = grid.startMs(k),
                    hasData = pre.hasData(p),
                    hrRaw = pre.hrRaw[p].takeUnless { it.isNaN() },
                    hrSmooth = pre.hrSmooth[p].takeUnless { it.isNaN() },
                    features = if (extra == null) night.features.valuesAt(p) else night.features.valuesAt(p) + extra.values[k].asList(),
                    posterior = StageProbabilities.of(post.posteriors[k]),
                    stage = post.stages[k],
                )
            }
        return NightEstimate(
            stages = post.intervals,
            coverage = night.coverage,
            confidence = confidence(night, post),
            perEpoch = perEpoch,
            params = night.params,
            algoVersion = ALGO_VERSION,
            paramsHash = ParamsHasher.hash(night.params),
            estimator = type,
            warnings = night.warnings + estimatorWarnings + post.warnings,
            flags =
                NightFlags(
                    elevatedHr = night.statistics.elevated,
                    lowConfidence = night.statistics.lowRange || post.sanityFailed,
                    calibrated = post.calibrated,
                    rulesFallback = false,
                ),
            featureNames = if (extra == null) BaseFeatures.NAMES else BaseFeatures.NAMES + extra.names,
            nadirBpm = night.statistics.nadirBpm.takeUnless { it.isNaN() },
            hrSampleCount = pre.sampleCount,
        )
    }
}

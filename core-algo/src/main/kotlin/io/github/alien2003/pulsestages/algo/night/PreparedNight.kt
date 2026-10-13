package io.github.alien2003.pulsestages.algo.night

import io.github.alien2003.pulsestages.algo.features.BaseFeatureExtractor
import io.github.alien2003.pulsestages.algo.features.BaseFeatures
import io.github.alien2003.pulsestages.algo.features.NightStatistics
import io.github.alien2003.pulsestages.algo.model.EstimationWarning
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.preprocess.EpochGrid
import io.github.alien2003.pulsestages.algo.preprocess.PreprocessedNight
import io.github.alien2003.pulsestages.algo.preprocess.Preprocessor
import java.util.Locale

/**
 * Memoized derived values of one prepared night (pure functions of the preprocessing, so they are shared by every
 * [PreparedNight.withParams] copy). Thread-safe.
 */
internal class NightCache {
    private val lock = Any()
    private var hybridFeatures: Array<DoubleArray>? = null
    private val logits = HashMap<String, Array<DoubleArray>>()

    /** The hybrid feature matrix, computed by [compute] on first use. */
    fun hybridFeatures(compute: () -> Array<DoubleArray>): Array<DoubleArray> =
        synchronized(lock) { hybridFeatures ?: compute().also { hybridFeatures = it } }

    /** Classifier logits per epoch for the model with fingerprint [modelKey], computed by [compute] on first use. */
    fun logits(
        modelKey: String,
        compute: () -> Array<DoubleArray>,
    ): Array<DoubleArray> = synchronized(lock) { logits.getOrPut(modelKey, compute) }
}

/**
 * Everything the estimators share for one night: the preprocessed heart rate, the night statistics and the base
 * features, all computed with [params].
 */
class PreparedNight internal constructor(
    /** The night as given by the caller. */
    val input: NightInput,
    /** Parameters used for the preparation. */
    val params: AlgoParams,
    /** Preprocessed heart rate. */
    val preprocessed: PreprocessedNight,
    /** Night-level statistics (P10/P50/nadir/elevated). */
    val statistics: NightStatistics,
    /** Per-epoch base features. */
    val features: BaseFeatures,
    internal val cache: NightCache = NightCache(),
) {
    /**
     * This night with the parameters [params]. When the preprocessing and feature parameters are unchanged the
     * preprocessing, statistics, features and cached hybrid features/classifier outputs are reused (only decoder and
     * post-processing parameters differ); otherwise the night is prepared again from [input].
     */
    fun withParams(params: AlgoParams): PreparedNight =
        when {
            params == this.params -> {
                this
            }

            params.preprocess == this.params.preprocess && params.features == this.params.features -> {
                PreparedNight(input, params, preprocessed, statistics, features, cache)
            }

            else -> {
                NightPreparer.prepare(input, params)
            }
        }

    /** The epoch grid. */
    val grid: EpochGrid get() = preprocessed.grid

    /** Fraction of session epochs with heart-rate data. */
    val coverage: Double get() = preprocessed.coverage

    /** Data-level warnings, in this order: DATA_GAPS, LOW_HR_RANGE, ELEVATED_HR. */
    val warnings: List<EstimationWarning> =
        buildList {
            val missing = (0 until grid.sessionEpochs).count { !preprocessed.sessionHasData(it) }
            if (missing > 0) {
                add(EstimationWarning(WarningCode.DATA_GAPS, "$missing of ${grid.sessionEpochs} session epochs have no heart-rate data"))
            }
            if (statistics.lowRange) {
                val detail =
                    "P${fmt(params.features.normHighPercentile)} − P${fmt(params.features.normLowPercentile)} = " +
                        "${fmt(statistics.rangeBpm)} bpm < ${fmt(statistics.normDenominator)} bpm"
                add(EstimationWarning(WarningCode.LOW_HR_RANGE, detail))
            }
            if (statistics.elevated) {
                val detail =
                    "nadir ${fmt(statistics.nadirBpm)} bpm > baseline ${fmt(input.baselineNadirBpm ?: Double.NaN)} bpm " +
                        "+ ${fmt(params.features.elevatedNightPct)} %"
                add(EstimationWarning(WarningCode.ELEVATED_HR, detail))
            }
        }

    /** Whether session epoch [k] has heart-rate data. */
    fun sessionHasData(k: Int): Boolean = preprocessed.sessionHasData(k)

    /**
     * Whether session epoch [k] is forced to AWAKE for every estimator: inside a forcedAwake span, or with at least
     * [io.github.alien2003.pulsestages.algo.params.HmmParams.awakeStepThreshold] steps.
     */
    fun isForcedAwake(k: Int): Boolean = forcedAwakeMask[k]

    /** Session epochs with heart-rate data (read by the estimators; never handed out, so callers cannot change it). */
    internal val sessionDataMask: BooleanArray = BooleanArray(grid.sessionEpochs) { sessionHasData(it) }

    /** Session epochs forced to AWAKE (see [isForcedAwake]); internal for the same reason as [sessionDataMask]. */
    internal val forcedAwakeMask: BooleanArray =
        BooleanArray(grid.sessionEpochs) { k ->
            val p = grid.position(k)
            features.activity.forcedAwake[p] || features.activity.steps[p] >= params.hmm.awakeStepThreshold
        }

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
}

/** Builds [PreparedNight]s. */
object NightPreparer {
    /** Preprocesses [input] and computes its statistics and base features with [params]. */
    fun prepare(
        input: NightInput,
        params: AlgoParams,
    ): PreparedNight {
        val preprocessed = Preprocessor.preprocess(input, params.preprocess)
        val statistics = NightStatistics.compute(preprocessed, params.features, input.baselineNadirBpm)
        val features = BaseFeatureExtractor.extract(input, preprocessed, statistics, params.features)
        return PreparedNight(input, params, preprocessed, statistics, features)
    }
}

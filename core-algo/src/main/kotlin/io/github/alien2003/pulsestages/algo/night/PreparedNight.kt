package io.github.alien2003.pulsestages.algo.night

import io.github.alien2003.pulsestages.algo.features.BaseFeatureExtractor
import io.github.alien2003.pulsestages.algo.features.BaseFeatures
import io.github.alien2003.pulsestages.algo.features.NightStatistics
import io.github.alien2003.pulsestages.algo.model.EstimationWarning
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.FeatureParams
import io.github.alien2003.pulsestages.algo.params.PreprocessParams
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
 * What a preparation ([NightPreparer.prepare]) depends on besides the input: the session's [mode], the preprocessing
 * parameters in effect for that mode ([AlgoParams.forMode]: a nap's `preprocess.contextMarginMin` is
 * `napMode.contextMarginMin` when that is set), the feature parameters and the nap context margin the input is
 * restricted to ([napContextMarginMin], null = not restricted; always null for a night). Equal keys give identical
 * preparations of the same input, so callers may share one ([PreparedNight.withParams], algo-cli `evaluate`).
 *
 * @property mode night or nap.
 * @property preprocess the effective preprocessing parameters of [mode].
 * @property features the feature parameters.
 * @property napContextMarginMin `napMode.contextMarginMin` for a nap, null for a night.
 */
data class PreparationKey(
    val mode: SessionMode,
    val preprocess: PreprocessParams,
    val features: FeatureParams,
    val napContextMarginMin: Int?,
)

/**
 * Everything the estimators share for one night: the preprocessed heart rate, the night statistics and the base
 * features, all computed with [params], and the session's [mode].
 */
class PreparedNight internal constructor(
    /**
     * The night as given by the caller (for a nap with `napMode.contextMarginMin` set, the preprocessing and features
     * were computed from its restriction to that margin, see [NightPreparer.prepare]).
     */
    val input: NightInput,
    /** The parameters as given by the caller ([NightPreparer.prepare], [withParams]); part of the params hash. */
    val configuredParams: AlgoParams,
    /** Preprocessed heart rate. */
    val preprocessed: PreprocessedNight,
    /** Night-level statistics (P10/P50/nadir/elevated). */
    val statistics: NightStatistics,
    /** Per-epoch base features. */
    val features: BaseFeatures,
    internal val cache: NightCache = NightCache(),
) {
    /**
     * Night or nap: decided by the session length (end − start) and `configuredParams.napMode.maxSessionMin`
     * ([AlgoParams.modeOf]).
     */
    val mode: SessionMode = configuredParams.modeOf(input.sessionEndMs - input.sessionStartMs)

    /**
     * The parameters in effect for this session: [configuredParams] for a night, for a nap with the nap overrides of
     * `napMode` applied ([AlgoParams.forMode]). The estimators and the post-processing read these.
     */
    val params: AlgoParams = configuredParams.forMode(mode)

    /** What this preparation depends on besides [input] ([NightPreparer.preparationKey] of [configuredParams]). */
    val preparationKey: PreparationKey = NightPreparer.preparationKey(configuredParams, input.sessionEndMs - input.sessionStartMs)

    /**
     * This night with the parameters [params] (as given; the mode is decided again). When the [PreparationKey] is
     * unchanged (the mode, the effective preprocessing parameters of that mode, the feature parameters and the nap
     * context margin) the preprocessing, statistics, features and cached hybrid features/classifier outputs are reused
     * (only decoder, post-processing, nap-override or night-only preprocessing parameters differ); otherwise the night
     * is prepared again from [input].
     */
    fun withParams(params: AlgoParams): PreparedNight =
        when {
            params == configuredParams -> {
                this
            }

            NightPreparer.preparationKey(params, input.sessionEndMs - input.sessionStartMs) == preparationKey -> {
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
    /**
     * Decides the mode of [input] ([AlgoParams.modeOf]) first, then preprocesses it and computes its statistics and base
     * features with the parameters in effect for that mode. A night is prepared from [input] with
     * `params.preprocess`. A nap with `napMode.contextMarginMin` = m set is prepared from [input] restricted to
     * heart-rate / SpO2 / respiratory-rate / HRV samples with `start − m ≤ t ≤ end + m` and step records ending in
     * `(start − m, end + m]`, on an epoch grid with m minutes of context (`params.forMode(NAP).preprocess`), so that
     * whatever the caller read around the session cannot reach the features; with the override null a nap is prepared
     * like a night. The input's baseline nadir is used only for a night: a nap is never compared with the baseline, so
     * it is never elevated.
     */
    fun prepare(
        input: NightInput,
        params: AlgoParams,
    ): PreparedNight {
        val mode = params.modeOf(input.sessionEndMs - input.sessionStartMs)
        val margin = napContextMargin(params, mode)
        val used = if (margin != null) NapContext.restrict(input, margin) else input
        val preprocessed = Preprocessor.preprocess(used, params.forMode(mode).preprocess)
        val baseline = input.baselineNadirBpm.takeIf { mode == SessionMode.NIGHT }
        val statistics = NightStatistics.compute(preprocessed, params.features, baseline)
        val features = BaseFeatureExtractor.extract(used, preprocessed, statistics, params.features)
        return PreparedNight(input, params, preprocessed, statistics, features)
    }

    /**
     * What a preparation of a session lasting [durationMs] with [params] depends on besides its input
     * ([PreparationKey]): preparations of the same input with equal keys are identical and may be shared.
     */
    fun preparationKey(
        params: AlgoParams,
        durationMs: Long,
    ): PreparationKey {
        val mode = params.modeOf(durationMs)
        return PreparationKey(mode, params.forMode(mode).preprocess, params.features, napContextMargin(params, mode))
    }

    /** `napMode.contextMarginMin` for a nap, null for a night (nights never read it). */
    private fun napContextMargin(
        params: AlgoParams,
        mode: SessionMode,
    ): Int? = params.napMode.contextMarginMin.takeIf { mode == SessionMode.NAP }
}

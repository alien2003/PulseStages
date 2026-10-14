package io.github.alien2003.pulsestages.algo.model

/**
 * Sleep stage labels produced by the estimators.
 *
 * [index] is the fixed position of the stage in every probability vector, emission table and confusion
 * matrix of this module (AWAKE=0, LIGHT=1, DEEP=2, REM=3). [UNKNOWN] marks epochs without usable heart-rate
 * data when [io.github.alien2003.pulsestages.algo.params.GapPolicy.UNKNOWN] is selected; it never appears
 * in probability vectors.
 *
 * @property index position in probability vectors and tables; -1 for [UNKNOWN].
 */
enum class SleepStage(
    val index: Int,
) {
    /** Awake (Health Connect STAGE_TYPE_AWAKE). */
    AWAKE(0),

    /** Light sleep, N1 + N2 (STAGE_TYPE_LIGHT). */
    LIGHT(1),

    /** Deep sleep, N3 (STAGE_TYPE_DEEP). */
    DEEP(2),

    /** REM sleep (STAGE_TYPE_REM). */
    REM(3),

    /** No usable data (STAGE_TYPE_UNKNOWN); never part of a probability vector. */
    UNKNOWN(-1),
    ;

    /** The scored stages and their index lookup. */
    companion object {
        /** The four scored stages in index order. */
        val SCORED: List<SleepStage> = listOf(AWAKE, LIGHT, DEEP, REM)

        /** Number of scored stages (size of every probability vector). */
        const val COUNT: Int = 4

        /** Returns the scored stage with the given [index]. */
        fun ofIndex(index: Int): SleepStage = SCORED[index]
    }
}

/**
 * A single heart-rate measurement.
 *
 * @property timeMs time of the measurement, epoch milliseconds (UTC).
 * @property bpm heart rate in beats per minute.
 */
data class HrSample(
    val timeMs: Long,
    val bpm: Double,
)

/**
 * A step count accumulated over `[startMs, endMs)`. Counts are spread uniformly over the interval.
 *
 * @property startMs start of the interval, epoch milliseconds.
 * @property endMs end of the interval (exclusive), epoch milliseconds.
 * @property count steps in the interval.
 */
data class StepsSample(
    val startMs: Long,
    val endMs: Long,
    val count: Long,
)

/**
 * A blood-oxygen saturation measurement.
 *
 * @property timeMs time of the measurement, epoch milliseconds.
 * @property percent saturation in percent (0–100).
 */
data class Spo2Sample(
    val timeMs: Long,
    val percent: Double,
)

/**
 * A respiratory-rate measurement.
 *
 * @property timeMs time of the measurement, epoch milliseconds.
 * @property rate breaths per minute.
 */
data class RespiratoryRateSample(
    val timeMs: Long,
    val rate: Double,
)

/**
 * A heart-rate-variability (RMSSD) measurement.
 *
 * @property timeMs time of the measurement, epoch milliseconds.
 * @property rmssdMs RMSSD in milliseconds.
 */
data class HrvSample(
    val timeMs: Long,
    val rmssdMs: Double,
)

/**
 * A half-open time interval `[startMs, endMs)` in epoch milliseconds.
 *
 * @property startMs start (inclusive).
 * @property endMs end (exclusive); never before [startMs].
 */
data class TimeSpan(
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(endMs >= startMs) { "endMs ($endMs) < startMs ($startMs)" }
    }

    /** Duration in milliseconds. */
    val durationMs: Long get() = endMs - startMs
}

/**
 * Everything an estimator needs for one night.
 *
 * Sample lists may contain data outside the session (the caller reads a context margin of
 * [io.github.alien2003.pulsestages.algo.params.PreprocessParams.contextMarginMin] on both sides); stages are
 * only produced for `[sessionStartMs, sessionEndMs)`.
 *
 * @property sessionStartMs start of the sleep session, epoch milliseconds.
 * @property sessionEndMs end of the sleep session (exclusive), epoch milliseconds; after [sessionStartMs].
 * @property heartRate heart-rate samples of the session and its context margins, in any order.
 * @property steps step counts (the movement proxy); empty when none were recorded.
 * @property spo2 optional SpO2 measurements; empty when not available or not granted.
 * @property respiratoryRate optional respiratory-rate measurements; empty when not available or not granted.
 * @property hrv optional HRV (RMSSD) measurements; empty when not available or not granted.
 * @property forcedAwake intervals inside the session that must be AWAKE (e.g. gaps between merged fragments).
 * @property baselineNadirBpm rolling median of the nadirs of the previous nights, or null if unknown. Ignored for a
 *   nap ([SessionMode.NAP], a session shorter than `napMode.maxSessionMin`): a nap is never compared with the baseline.
 */
data class NightInput(
    val sessionStartMs: Long,
    val sessionEndMs: Long,
    val heartRate: List<HrSample>,
    val steps: List<StepsSample> = emptyList(),
    val spo2: List<Spo2Sample> = emptyList(),
    val respiratoryRate: List<RespiratoryRateSample> = emptyList(),
    val hrv: List<HrvSample> = emptyList(),
    val forcedAwake: List<TimeSpan> = emptyList(),
    val baselineNadirBpm: Double? = null,
) {
    init {
        require(sessionEndMs > sessionStartMs) { "session end must be after start" }
    }
}

/**
 * A stage interval `[startMs, endMs)` of the output hypnogram.
 *
 * @property startMs start (inclusive), epoch milliseconds.
 * @property endMs end (exclusive), epoch milliseconds.
 * @property stage the stage of the whole interval.
 */
data class StageInterval(
    val startMs: Long,
    val endMs: Long,
    val stage: SleepStage,
)

/**
 * Probability of each scored stage; always sums to 1 (within floating-point error).
 *
 * @property awake probability of AWAKE.
 * @property light probability of LIGHT.
 * @property deep probability of DEEP.
 * @property rem probability of REM.
 */
data class StageProbabilities(
    val awake: Double,
    val light: Double,
    val deep: Double,
    val rem: Double,
) {
    /** Probability of [stage]; 0 for [SleepStage.UNKNOWN]. */
    operator fun get(stage: SleepStage): Double =
        when (stage) {
            SleepStage.AWAKE -> awake
            SleepStage.LIGHT -> light
            SleepStage.DEEP -> deep
            SleepStage.REM -> rem
            SleepStage.UNKNOWN -> 0.0
        }

    /** Largest of the four probabilities. */
    val max: Double get() = maxOf(maxOf(awake, light), maxOf(deep, rem))

    /** Values in [SleepStage.index] order. */
    fun toList(): List<Double> = listOf(awake, light, deep, rem)

    /** Factories and constants. */
    companion object {
        /** Builds from a vector in [SleepStage.index] order. */
        fun of(values: DoubleArray): StageProbabilities {
            require(values.size == SleepStage.COUNT)
            return StageProbabilities(
                awake = values[SleepStage.AWAKE.index],
                light = values[SleepStage.LIGHT.index],
                deep = values[SleepStage.DEEP.index],
                rem = values[SleepStage.REM.index],
            )
        }

        /** The uniform distribution. */
        val UNIFORM: StageProbabilities = (1 / SleepStage.COUNT.toDouble()).let { StageProbabilities(it, it, it, it) }
    }
}

/**
 * Per-epoch diagnostics, used by the night-detail screen and the CSV export.
 *
 * @property index position of the epoch in the session (0 = the epoch starting at the session start).
 * @property startMs start of the epoch, epoch milliseconds.
 * @property hasData the epoch has heart-rate data (measured or interpolated across a short gap).
 * @property hrRaw resampled heart rate before smoothing, null where there is no data.
 * @property hrSmooth smoothed heart rate, null where there is no data.
 * @property features named feature values in the order of [NightEstimate.featureNames].
 * @property posterior posterior stage probabilities of the epoch.
 * @property stage the stage written for the epoch (after post-processing).
 */
data class EpochResult(
    val index: Int,
    val startMs: Long,
    val hasData: Boolean,
    val hrRaw: Double?,
    val hrSmooth: Double?,
    val features: List<Double>,
    val posterior: StageProbabilities,
    val stage: SleepStage,
)

/** Machine-readable warning codes; the UI maps them to localized text. */
enum class WarningCode {
    /** P50 − P10 below the configured minimum: normalization is unreliable. */
    LOW_HR_RANGE,

    /** Night nadir above the long-term baseline by more than the configured percentage. */
    ELEVATED_HR,

    /** Baum–Welch adaptation violated μ_DEEP < μ_LIGHT < μ_REM < μ_AWAKE and was rolled back. */
    EM_ORDER_VIOLATION,

    /** Implausible share of DEEP (too high). */
    SANITY_DEEP_HIGH,

    /** Implausible share of DEEP (too low). */
    SANITY_DEEP_LOW,

    /** Implausible share of REM (too high). */
    SANITY_REM_HIGH,

    /** Stage proportions were calibrated towards the target ranges. */
    PROPORTIONS_CALIBRATED,

    /** Coverage was below the main estimator's minimum; the rules estimator was used instead. */
    RULES_FALLBACK_LOW_COVERAGE,

    /** Fewer than two ultradian minima were found; the cycle prior was disabled. */
    CYCLE_PRIOR_DISABLED,

    /** The hybrid model was unavailable or incompatible; the HMM estimator was used instead. */
    HYBRID_MODEL_UNAVAILABLE,

    /** Some epochs had no heart-rate data for longer than the interpolation limit. */
    DATA_GAPS,
}

/**
 * A warning with an optional human-readable detail.
 *
 * @property code what happened.
 * @property detail English detail for the log; empty when there is nothing to add.
 */
data class EstimationWarning(
    val code: WarningCode,
    val detail: String = "",
)

/**
 * How a session is processed, decided by its length alone
 * ([io.github.alien2003.pulsestages.algo.params.NapModeParams.modeOf]: end − start of the merged session, gaps between
 * fragments included).
 */
enum class SessionMode {
    /**
     * A whole night: a session at least `napMode.maxSessionMin` long (every session when that is 0). Estimated with the
     * configured estimator, the bundle's main model and the night parameters; compared with the nadir baseline and
     * counted for it; the stage-share sanity check applies.
     */
    NIGHT,

    /**
     * A nap: a session shorter than `napMode.maxSessionMin`. Estimated with `napMode.estimator`, the bundle's nap
     * sub-model when it has one (otherwise the main model with its short-session bias), the night parameters with the
     * nap overrides of `napMode` and its output policy; never compared with the nadir baseline nor counted for it.
     */
    NAP,
}

/** Which estimator produced the result. */
enum class EstimatorType {
    /** The threshold rules (section 6): for comparison and as the fallback for sparse data. */
    RULES,

    /** The HMM with physiological priors, decoded by Viterbi (section 3). */
    HMM,

    /** The trained classifier with the explicit-duration (HSMM) decoder (section 8). */
    HYBRID,
}

/**
 * Night-level flags.
 *
 * @property elevatedHr the night's nadir is above the long-term baseline by more than `elevatedNightPct`.
 * @property lowConfidence the night is low-confidence (too small a heart-rate range, or failed sanity checks).
 * @property calibrated stage proportions were calibrated towards the target ranges.
 * @property rulesFallback the coverage was too low for the configured estimator and RULES was used instead.
 */
data class NightFlags(
    val elevatedHr: Boolean = false,
    val lowConfidence: Boolean = false,
    val calibrated: Boolean = false,
    val rulesFallback: Boolean = false,
)

/**
 * The result of estimating one night.
 *
 * Invariants (checked by property tests): [stages] is sorted, contiguous, covers exactly
 * `[NightInput.sessionStartMs, NightInput.sessionEndMs)` and every interval is at least one epoch long
 * (unless the whole session is shorter than one epoch).
 *
 * @property stages the hypnogram: sorted, contiguous intervals covering the session.
 * @property coverage share of the session's epochs with heart-rate data, in `[0, 1]`.
 * @property confidence night confidence in `[0, 1]`.
 * @property perEpoch diagnostics of every epoch of the session, in order.
 * @property params the parameters the night was estimated with.
 * @property algoVersion [io.github.alien2003.pulsestages.algo.ALGO_VERSION] of the code that produced the estimate.
 * @property paramsHash SHA-256 (hex) of the serialized parameters plus model fingerprints.
 * @property estimator the estimator that actually produced the stages (may differ from the configured one).
 * @property warnings everything noteworthy about the night (data gaps, sanity checks, fallbacks).
 * @property flags night-level flags.
 * @property featureNames names of the per-epoch feature values in [EpochResult.features].
 * @property nadirBpm lowest smoothed heart rate inside the session, or null without data.
 * @property hrSampleCount heart-rate samples used (in range, inside the analysis window, duplicates collapsed).
 * @property mode whether the session was processed as a night or as a nap. [params] are the parameters as given; a nap
 *   was estimated with `params.forMode(SessionMode.NAP)` (the nap overrides of `params.napMode` applied).
 */
data class NightEstimate(
    val stages: List<StageInterval>,
    val coverage: Double,
    val confidence: Double,
    val perEpoch: List<EpochResult>,
    val params: io.github.alien2003.pulsestages.algo.params.AlgoParams,
    val algoVersion: Int,
    val paramsHash: String,
    val estimator: EstimatorType,
    val warnings: List<EstimationWarning>,
    val flags: NightFlags,
    val featureNames: List<String>,
    val nadirBpm: Double?,
    val hrSampleCount: Int,
    val mode: SessionMode = SessionMode.NIGHT,
)

/** Why a night was not estimated. */
enum class NotEstimatedReason {
    /** No usable heart-rate sample in the session and its context margins. */
    NO_HR_DATA,

    /** Too few epochs with data, even for the rules fallback. */
    LOW_COVERAGE,

    /** The session is shorter than `preprocess.minSessionMin`. */
    SESSION_TOO_SHORT,

    /** The session is a nap ([SessionMode.NAP]) and `napMode.outputPolicy` is SKIP: naps are not estimated. */
    NAP_SKIPPED,
}

/** Outcome of [io.github.alien2003.pulsestages.algo.SleepStager.run]. */
sealed interface EstimationOutcome {
    /**
     * Stages were estimated.
     *
     * @property estimate the result.
     */
    data class Estimated(
        val estimate: NightEstimate,
    ) : EstimationOutcome

    /**
     * The night was skipped.
     *
     * @property reason why.
     * @property coverage share of the session's epochs with heart-rate data (0 when nothing could be computed).
     * @property detail English explanation for the log.
     */
    data class NotEstimated(
        val reason: NotEstimatedReason,
        val coverage: Double,
        val detail: String,
    ) : EstimationOutcome
}

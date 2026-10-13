package io.github.alien2003.pulsestages.algo.model

/**
 * Sleep stage labels produced by the estimators.
 *
 * [index] is the fixed position of the stage in every probability vector, emission table and confusion
 * matrix of this module (AWAKE=0, LIGHT=1, DEEP=2, REM=3). [UNKNOWN] marks epochs without usable heart-rate
 * data when [io.github.alien2003.pulsestages.algo.params.GapPolicy.UNKNOWN] is selected; it never appears
 * in probability vectors.
 */
enum class SleepStage(
    val index: Int,
) {
    AWAKE(0),
    LIGHT(1),
    DEEP(2),
    REM(3),
    UNKNOWN(-1),
    ;

    companion object {
        /** The four scored stages in index order. */
        val SCORED: List<SleepStage> = listOf(AWAKE, LIGHT, DEEP, REM)

        /** Number of scored stages (size of every probability vector). */
        const val COUNT: Int = 4

        /** Returns the scored stage with the given [index]. */
        fun ofIndex(index: Int): SleepStage = SCORED[index]
    }
}

/** A single heart-rate measurement at [timeMs] (epoch milliseconds, UTC). */
data class HrSample(
    val timeMs: Long,
    val bpm: Double,
)

/** A step count accumulated over `[startMs, endMs)`. Counts are spread uniformly over the interval. */
data class StepsSample(
    val startMs: Long,
    val endMs: Long,
    val count: Long,
)

/** A blood-oxygen saturation measurement in percent. */
data class Spo2Sample(
    val timeMs: Long,
    val percent: Double,
)

/** A respiratory-rate measurement in breaths per minute. */
data class RespiratoryRateSample(
    val timeMs: Long,
    val rate: Double,
)

/** A heart-rate-variability (RMSSD) measurement in milliseconds. */
data class HrvSample(
    val timeMs: Long,
    val rmssdMs: Double,
)

/** A half-open time interval `[startMs, endMs)` in epoch milliseconds. */
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
 * @property forcedAwake intervals inside the session that must be AWAKE (e.g. gaps between merged fragments).
 * @property baselineNadirBpm rolling median of the nadirs of the previous nights, or null if unknown.
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

/** A stage interval `[startMs, endMs)` of the output hypnogram. */
data class StageInterval(
    val startMs: Long,
    val endMs: Long,
    val stage: SleepStage,
)

/** Probability of each scored stage; always sums to 1 (within floating-point error). */
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
 * @property hrRaw resampled heart rate before smoothing, null where there is no data.
 * @property hrSmooth smoothed heart rate, null where there is no data.
 * @property features named feature values in the order of [NightEstimate.featureNames].
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

/** A warning with an optional human-readable (English, for logs) detail. */
data class EstimationWarning(
    val code: WarningCode,
    val detail: String = "",
)

/** Which estimator produced the result. */
enum class EstimatorType { RULES, HMM, HYBRID }

/** Night-level flags. */
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
 * @property estimator the estimator that actually produced the stages (may differ from the configured one).
 * @property paramsHash SHA-256 (hex) of the serialized parameters plus model fingerprints.
 * @property confidence night confidence in `[0, 1]`.
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
)

/** Why a night was not estimated. */
enum class NotEstimatedReason { NO_HR_DATA, LOW_COVERAGE, SESSION_TOO_SHORT }

/** Outcome of [io.github.alien2003.pulsestages.algo.SleepStager.run]. */
sealed interface EstimationOutcome {
    /** Stages were estimated. */
    data class Estimated(
        val estimate: NightEstimate,
    ) : EstimationOutcome

    /** The night was skipped; [detail] is an English explanation for the log. */
    data class NotEstimated(
        val reason: NotEstimatedReason,
        val coverage: Double,
        val detail: String,
    ) : EstimationOutcome
}

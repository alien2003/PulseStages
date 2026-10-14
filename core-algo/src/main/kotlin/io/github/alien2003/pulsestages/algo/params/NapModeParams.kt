package io.github.alien2003.pulsestages.algo.params

import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SessionMode
import kotlinx.serialization.Serializable

/** What is written for a nap ([SessionMode.NAP]). */
@Serializable
enum class NapOutputPolicy {
    /** The estimated stages (AWAKE / LIGHT / DEEP / REM), like a night. */
    STAGES,

    /**
     * Only sleep and wake: LIGHT, DEEP and REM (and data gaps) are written as Health Connect's generic "sleeping" stage,
     * AWAKE stays AWAKE. For readers who want naps in Health Connect without the rough stage breakdown.
     */
    SLEEP_WAKE,

    /**
     * Nothing: naps are not estimated (`NotEstimatedReason.NAP_SKIPPED`) and nothing is written for them; an output
     * written earlier for the nap is removed when the nap is processed again.
     */
    SKIP,
}

/**
 * The two processing modes, night and nap (ALGORITHM.md section 18). A session shorter than [maxSessionMin] minutes
 * (end − start of the merged session, gaps between fragments included) is a nap; every longer one is a night. The
 * app's source setting `minSessionMin` stays the global minimum below which sessions are ignored altogether.
 *
 * A nap is never compared with the nadir baseline (no elevated-HR check) and never counts for it, and is estimated
 * with [estimator], the bundle's nap sub-model when the bundle has one (otherwise its main model with the short-session
 * bias `stageLogBiasShort`), the night's post-processing and decoder parameters with the overrides of [post] and
 * [hybrid], and written as [outputPolicy] says. Every override is null by default (= the night's value), so until a
 * nap model is trained a nap differs from a night only where the pre-registered nap experiment (ALGORITHM.md section
 * 13.11) and the baseline rule decided it.
 *
 * @property maxSessionMin sessions shorter than this many minutes are naps; 0 disables the nap mode (every session is a
 *   night). Replaces the three earlier thresholds `features.baselineMinSessionMin`, `hybrid.shortSessionMaxMin` and
 *   `post.proportionsMinSessionMin` (all 180 min).
 * @property estimator the estimator of naps (the night's is `AlgoParams.estimator`).
 * @property outputPolicy what is written for a nap.
 * @property post post-processing of naps: overrides of the night's `post` values and whether the stage-share checks
 *   apply.
 * @property hybrid decoder overrides of the hybrid estimator for naps.
 */
@Serializable
data class NapModeParams(
    val maxSessionMin: Double = TrainedDefaults.NAP_MAX_SESSION_MIN,
    val estimator: EstimatorType = TrainedDefaults.NAP_ESTIMATOR,
    val outputPolicy: NapOutputPolicy = NapOutputPolicy.STAGES,
    val post: NapPostprocessParams = NapPostprocessParams(),
    val hybrid: NapHybridParams =
        NapHybridParams(
            durationModel = TrainedDefaults.NAP_HYBRID_DURATION_MODEL,
            emissionWeight = TrainedDefaults.NAP_HYBRID_EMISSION_WEIGHT,
            timePriorWeight = TrainedDefaults.NAP_HYBRID_TIME_PRIOR_WEIGHT,
        ),
) {
    /** [maxSessionMin] in whole milliseconds, rounded up (0 for a non-positive or undefined value: no naps). */
    val maxSessionMs: Long get() = minutesToWholeMs(maxSessionMin)

    /**
     * The mode of a session lasting [durationMs] (end − start, merged fragments and their gaps included): [SessionMode.NAP]
     * when it is strictly shorter than [maxSessionMin] minutes, [SessionMode.NIGHT] otherwise (always with 0).
     */
    fun modeOf(durationMs: Long): SessionMode = if (durationMs < maxSessionMs) SessionMode.NAP else SessionMode.NIGHT
}

/**
 * Post-processing of naps. A null value means "the night's value" (`AlgoParams.post`); see [applyTo].
 *
 * @property checkProportions run the stage-share sanity check (and, with `post.calibrateProportions`, the proportion
 *   calibration) on naps too. Off by default: the corridors describe whole nights, and a 30–90-min nap often has no DEEP
 *   or no REM at all, so every such nap would only get a misleading low-confidence flag (ALGORITHM.md section 8).
 * @property minDeepMin minimum DEEP bout of naps (minutes), or null for `post.minDeepMin`.
 * @property minRemMin minimum REM bout of naps (minutes), or null for `post.minRemMin`.
 * @property minAwakeMin minimum AWAKE bout inside sleep of naps (minutes), or null for `post.minAwakeMin`.
 * @property sanityDeepMin a nap with less DEEP than this share is low-confidence (only with [checkProportions]), or
 *   null for `post.sanityDeepMin`.
 * @property sanityDeepMax a nap with more DEEP than this share is low-confidence (only with [checkProportions]), or
 *   null for `post.sanityDeepMax`.
 * @property sanityRemMax a nap with more REM than this share is low-confidence (only with [checkProportions]), or null
 *   for `post.sanityRemMax`.
 * @property lowConfidenceThreshold epochs of a nap whose maximum posterior is below this follow [lowConfidencePolicy],
 *   or null for `post.lowConfidenceThreshold`.
 * @property lowConfidencePolicy what to write for such epochs of a nap, or null for `post.lowConfidencePolicy`.
 */
@Serializable
data class NapPostprocessParams(
    val checkProportions: Boolean = false,
    val minDeepMin: Double? = null,
    val minRemMin: Double? = null,
    val minAwakeMin: Double? = null,
    val sanityDeepMin: Double? = null,
    val sanityDeepMax: Double? = null,
    val sanityRemMax: Double? = null,
    val lowConfidenceThreshold: Double? = null,
    val lowConfidencePolicy: LowConfidencePolicy? = null,
) {
    /** [night] with every non-null override of this object. */
    fun applyTo(night: PostprocessParams): PostprocessParams =
        night.copy(
            minDeepMin = minDeepMin ?: night.minDeepMin,
            minRemMin = minRemMin ?: night.minRemMin,
            minAwakeMin = minAwakeMin ?: night.minAwakeMin,
            sanityDeepMin = sanityDeepMin ?: night.sanityDeepMin,
            sanityDeepMax = sanityDeepMax ?: night.sanityDeepMax,
            sanityRemMax = sanityRemMax ?: night.sanityRemMax,
            lowConfidenceThreshold = lowConfidenceThreshold ?: night.lowConfidenceThreshold,
            lowConfidencePolicy = lowConfidencePolicy ?: night.lowConfidencePolicy,
        )
}

/**
 * Decoder overrides of the hybrid estimator for naps. A null value means "the night's value" (`AlgoParams.hybrid`); see
 * [applyTo]. The defaults come from [TrainedDefaults] (null until a nap model's decoder is chosen by validation).
 *
 * @property durationModel duration model of naps, or null for `hybrid.durationModel`.
 * @property emissionWeight exponent of the scaled likelihoods of naps, or null for `hybrid.emissionWeight`.
 * @property timePriorWeight weight of the time-of-night prior of naps, or null for `hybrid.timePriorWeight`.
 */
@Serializable
data class NapHybridParams(
    val durationModel: DurationModel? = null,
    val emissionWeight: Double? = null,
    val timePriorWeight: Double? = null,
) {
    /** [night] with every non-null override of this object. */
    fun applyTo(night: HybridParams): HybridParams =
        night.copy(
            durationModel = durationModel ?: night.durationModel,
            emissionWeight = emissionWeight ?: night.emissionWeight,
            timePriorWeight = timePriorWeight ?: night.timePriorWeight,
        )
}

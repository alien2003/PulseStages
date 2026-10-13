package io.github.alien2003.pulsestages.algo.params

import io.github.alien2003.pulsestages.algo.model.EstimatorType
import kotlinx.serialization.Serializable

/**
 * All tunable parameters of the stage estimators. Every number used by the algorithms lives here so that it can
 * be edited in the settings, exported/imported as JSON and hashed into
 * [io.github.alien2003.pulsestages.algo.model.NightEstimate.paramsHash].
 *
 * Serialization is part of the persisted settings format: only ADD fields with defaults, never rename.
 */
@Serializable
data class AlgoParams(
    val estimator: EstimatorType = EstimatorType.HYBRID,
    val preprocess: PreprocessParams = PreprocessParams(),
    val features: FeatureParams = FeatureParams(),
    val hmm: HmmParams = HmmParams(),
    val rules: RulesParams = RulesParams(),
    val hybrid: HybridParams = HybridParams(),
    val post: PostprocessParams = PostprocessParams(),
    val personal: PersonalParams = PersonalParams(),
)

/** Preprocessing (section 1 of the specification). */
@Serializable
data class PreprocessParams(
    /** Heart-rate values outside `[minBpm, maxBpm]` are dropped. */
    val minBpm: Double = 30.0,
    val maxBpm: Double = 180.0,
    /** Hampel filter window in samples (odd) and threshold in scaled MADs. */
    val hampelWindow: Int = 5,
    val hampelThreshold: Double = 3.0,
    /** Epoch length in seconds. */
    val epochSec: Int = 60,
    /** Linear interpolation only across gaps up to this length; longer gaps are "no data". */
    val maxInterpGapMin: Double = 10.0,
    /** Median filter length (epochs) applied before the moving average. */
    val medianWindowEpochs: Int = 3,
    /** Moving-average length (epochs). */
    val smoothWindowEpochs: Int = 5,
    /** Context read on both sides of the session, minutes. */
    val contextMarginMin: Int = 30,
    /** Below this coverage the night is not estimated by HMM/HYBRID. */
    val minCoverage: Double = 0.6,
    /** Between this and [minCoverage] the rules estimator is used; below it the night is skipped. */
    val rulesMinCoverage: Double = 0.4,
)

/** Per-epoch features and night-level context (section 2). */
@Serializable
data class FeatureParams(
    /** hrNorm = (hr − P[normLowPercentile]) / (P[normHighPercentile] − P[normLowPercentile]). */
    val normLowPercentile: Double = 10.0,
    val normHighPercentile: Double = 50.0,
    /** Minimum denominator for hrNorm (bpm); smaller ranges flag the night as low-confidence. */
    val minNormRangeBpm: Double = 2.0,
    /** hrVar: std of the unsmoothed series in ±[varHalfWindowEpochs]. */
    val varHalfWindowEpochs: Int = 3,
    /** hrSlope: least-squares slope (bpm/min) in ±[slopeHalfWindowEpochs]. */
    val slopeHalfWindowEpochs: Int = 2,
    /** Ultradian minima: minimum distance between minima and minimum prominence. */
    val cycleMinDistanceMin: Double = 60.0,
    val cycleMinProminenceBpm: Double = 2.0,
    /** Number of previous nights used for the baseline nadir (used by the caller that computes the baseline). */
    val baselineNights: Int = 14,
    /** Nadir above baseline by more than this percentage marks the night as "elevated HR". */
    val elevatedNightPct: Double = 12.0,
    /** On elevated nights the AWAKE threshold is raised by this amount of hrNorm. */
    val elevatedAwakeShift: Double = 0.3,
    /** Night confidence multiplier on elevated nights. */
    val elevatedConfidenceFactor: Double = 0.8,
    /** Optional channels. Each is used only if data is present and the flag is on. */
    val useSpo2: Boolean = true,
    /** SpO2 drop (percentage points below the rolling night median) considered a desaturation. */
    val spo2DropPct: Double = 3.0,
    /** Likelihood multiplier for AWAKE and LIGHT in epochs with a desaturation. */
    val spo2AwakeLightFactor: Double = 2.0,
    val useHrv: Boolean = true,
    /** Weight (σ divisor) of the RMSSD-based variability feature when it replaces hrVar. */
    val hrvWeight: Double = 1.5,
    val useRespiratoryRate: Boolean = true,
    /** Weight of the respiratory-rate variability term. */
    val respiratoryRateWeight: Double = 0.5,
)

/** A univariate Gaussian. */
@Serializable
data class Gaussian(
    val mean: Double,
    val sd: Double,
)

/** Diagonal-Gaussian emission of one hidden state over (hrNorm, hrVar, hrSlope). */
@Serializable
data class StateEmission(
    val hrNorm: Gaussian,
    val hrVar: Gaussian,
    val hrSlope: Gaussian,
)

/** Emission parameters of the four hidden states. */
@Serializable
data class EmissionTable(
    val awake: StateEmission,
    val light: StateEmission,
    val deep: StateEmission,
    val rem: StateEmission,
) {
    companion object {
        /** Starting values from physiology (the specification's table); used as fallback and for MAP shrinkage. */
        val PHYSIOLOGY: EmissionTable =
            EmissionTable(
                deep = StateEmission(Gaussian(0.1, 0.35), Gaussian(0.6, 0.3), Gaussian(0.0, 0.3)),
                light = StateEmission(Gaussian(1.0, 0.45), Gaussian(0.9, 0.4), Gaussian(0.0, 0.4)),
                rem = StateEmission(Gaussian(1.7, 0.55), Gaussian(1.4, 0.5), Gaussian(0.0, 0.5)),
                awake = StateEmission(Gaussian(2.8, 0.9), Gaussian(1.8, 0.7), Gaussian(0.0, 0.8)),
            )
    }
}

/** Mean bout durations in minutes; self-transition per epoch = 1 − epochMin / meanBoutMin. */
@Serializable
data class MeanBouts(
    val awakeMin: Double = 5.0,
    val lightMin: Double = 20.0,
    val deepMin: Double = 20.0,
    val remMin: Double = 20.0,
)

/** Shares of the non-self transition mass. Rows are normalized at use; zeros are replaced by ε. */
@Serializable
data class TransitionWeights(
    val awakeToLight: Double = 0.9,
    val awakeToDeep: Double = 0.0,
    val awakeToRem: Double = 0.1,
    val lightToAwake: Double = 0.2,
    val lightToDeep: Double = 0.45,
    val lightToRem: Double = 0.35,
    val deepToAwake: Double = 0.13,
    val deepToLight: Double = 0.85,
    val deepToRem: Double = 0.02,
    val remToAwake: Double = 0.3,
    val remToLight: Double = 0.7,
    val remToDeep: Double = 0.0,
)

/** HMM estimator (section 3). */
@Serializable
data class HmmParams(
    val emissions: EmissionTable = EmissionTable.PHYSIOLOGY,
    val meanBouts: MeanBouts = MeanBouts(),
    val transitions: TransitionWeights = TransitionWeights(),
    /** Replacement for zero probabilities. */
    val epsilon: Double = 1e-4,
    /** LIGHT→DEEP multiplier = max(deepTimeFloor, deepTimeIntercept − deepTimeSlope·t). */
    val deepTimeIntercept: Double = 1.6,
    val deepTimeSlope: Double = 1.2,
    val deepTimeFloor: Double = 0.3,
    /** LIGHT→REM multiplier = remTimeIntercept + remTimeSlope·t. */
    val remTimeIntercept: Double = 0.4,
    val remTimeSlope: Double = 1.2,
    /** REM is suppressed (probability × remLockoutFactor) during the first remLockoutMin minutes. */
    val remLockoutMin: Double = 45.0,
    val remLockoutFactor: Double = 0.05,
    /** Initial distribution (DEEP and REM get ε). */
    val initialAwake: Double = 0.5,
    val initialLight: Double = 0.5,
    /** Additive log-prior weight of the ultradian-cycle phase (0 disables). */
    val cyclePriorWeight: Double = 0.5,
    /** Cycle phase (0..1 between minima) at which the DEEP and REM priors peak. */
    val cycleDeepPhase: Double = 0.0,
    val cycleRemPhase: Double = 0.7,
    /** Steps in an epoch at or above this force AWAKE; 1..threshold−1 multiply the AWAKE emission. */
    val awakeStepThreshold: Int = 5,
    val fewStepsAwakeFactor: Double = 3.0,
    /** Per-night Baum–Welch adaptation of μ/σ with MAP shrinkage. */
    val emAdapt: Boolean = true,
    val emIterations: Int = 3,
    val emPriorStrength: Double = 20.0,
)

/** Rules estimator thresholds (section 6). */
@Serializable
data class RulesParams(
    val deepMaxHrNorm: Double = 0.45,
    val deepMaxHrVar: Double = 0.8,
    val awakeMinHrNorm: Double = 2.2,
    val remMinHrNorm: Double = 1.3,
    val remMinHrVar: Double = 1.1,
    val remMinT: Double = 0.25,
)

/** Explicit-duration (HSMM) or geometric (plain HMM) decoding for the hybrid estimator. */
@Serializable
enum class DurationModel { EXPLICIT, GEOMETRIC }

/** Hybrid estimator (section 8). Model weights are not parameters; they come from the model bundle. */
@Serializable
data class HybridParams(
    val durationModel: DurationModel = DurationModel.EXPLICIT,
    /** Weight of log P(stage | 30-min bin) relative to the class prior. */
    val timePriorWeight: Double = 1.0,
    /** Maximum bout length for the HSMM, minutes. */
    val maxBoutMin: Int = 120,
    /** Exponent applied to the scaled likelihoods (1 = as trained). */
    val emissionWeight: Double = 1.0,
    /** AWAKE scaled-likelihood multiplier on elevated-HR nights. */
    val elevatedAwakeFactor: Double = 0.6,
)

/** What to write for epochs inside long data gaps. */
@Serializable
enum class GapPolicy { UNKNOWN, LIGHT, NEAREST }

/** What to do with epochs whose maximum posterior is below the threshold. */
@Serializable
enum class LowConfidencePolicy { KEEP, LIGHT }

/** Post-processing (section 4). */
@Serializable
data class PostprocessParams(
    val minDeepMin: Double = 5.0,
    val minRemMin: Double = 5.0,
    val minAwakeMin: Double = 2.0,
    val gapPolicy: GapPolicy = GapPolicy.UNKNOWN,
    /** Sanity check, applied when coverage > sanityMinCoverage. */
    val sanityMinCoverage: Double = 0.8,
    val sanityDeepMax: Double = 0.35,
    val sanityRemMax: Double = 0.40,
    val sanityDeepMin: Double = 0.03,
    val sanityConfidenceFactor: Double = 0.7,
    /** Soft calibration of stage proportions towards the target ranges. */
    val calibrateProportions: Boolean = false,
    val targetDeepMin: Double = 0.10,
    val targetDeepMax: Double = 0.25,
    val targetRemMin: Double = 0.15,
    val targetRemMax: Double = 0.30,
    val targetAwakeMax: Double = 0.15,
    val calibrationStep: Double = 0.2,
    val calibrationMaxIterations: Int = 5,
    val lowConfidenceThreshold: Double = 0.45,
    val lowConfidencePolicy: LowConfidencePolicy = LowConfidencePolicy.KEEP,
)

/** Personalization (section 10). */
@Serializable
data class PersonalParams(
    val enabled: Boolean = true,
    /** Unsupervised EM over all nights every this many new nights. */
    val emEveryNights: Int = 30,
    /** Shrinkage of the population-level EM towards the global parameters (epoch-equivalents per state). */
    val emPriorStrength: Double = 200.0,
    /** L2 strength pulling calibrated biases / μ towards the global model. */
    val calibrationRegularization: Double = 10.0,
    /** Share of reference nights held out to report kappa before/after calibration. */
    val calibrationHoldout: Double = 0.3,
)

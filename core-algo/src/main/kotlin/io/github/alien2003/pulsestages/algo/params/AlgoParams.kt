package io.github.alien2003.pulsestages.algo.params

import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SessionMode
import kotlinx.serialization.Serializable
import kotlin.math.ceil

/**
 * All tunable parameters of the stage estimators. Every number used by the algorithms lives here so that it can
 * be edited in the settings, exported/imported as JSON and hashed into
 * [io.github.alien2003.pulsestages.algo.model.NightEstimate.paramsHash].
 *
 * Serialization is part of the persisted settings format: only ADD fields with defaults, never rename.
 *
 * @property estimator the estimator to run.
 * @property preprocess heart-rate cleaning, resampling and smoothing.
 * @property features per-epoch features and night-level context.
 * @property hmm the HMM estimator.
 * @property rules the rules estimator.
 * @property hybrid the hybrid estimator's decoder (the weights come from the model bundle).
 * @property post post-processing of every estimator's output.
 * @property personal personalization (calibration on reference nights, periodic EM).
 * @property napMode the nap mode: which sessions are naps and how they are estimated and written ([NapModeParams]).
 */
@Serializable
data class AlgoParams(
    /** Default: the estimator chosen by the open-dataset validation ([TrainedDefaults.ESTIMATOR]). */
    val estimator: EstimatorType = TrainedDefaults.ESTIMATOR,
    val preprocess: PreprocessParams = PreprocessParams(),
    val features: FeatureParams = FeatureParams(),
    val hmm: HmmParams = HmmParams(),
    val rules: RulesParams = RulesParams(),
    val hybrid: HybridParams = HybridParams(),
    val post: PostprocessParams = PostprocessParams(),
    val personal: PersonalParams = PersonalParams(),
    val napMode: NapModeParams = NapModeParams(),
) {
    /** These parameters with [type] as the estimator of nights and of naps (`estimator` and `napMode.estimator`). */
    fun withEstimator(type: EstimatorType): AlgoParams = copy(estimator = type, napMode = napMode.copy(estimator = type))

    /** The estimator of naps: `napMode.estimator`, or the night's [estimator] when it is null. */
    val napEstimator: EstimatorType get() = napMode.estimator ?: estimator

    /** The mode of a session lasting [durationMs] ([NapModeParams.modeOf]). */
    fun modeOf(durationMs: Long): SessionMode = napMode.modeOf(durationMs)

    /**
     * The parameters in effect for a session of [mode]: these parameters for [SessionMode.NIGHT]; for
     * [SessionMode.NAP] the estimator [napEstimator] and the post-processing and hybrid decoder parameters with the
     * overrides of `napMode` applied (every other group unchanged, [napMode] included).
     */
    fun forMode(mode: SessionMode): AlgoParams =
        when (mode) {
            SessionMode.NIGHT -> {
                this
            }

            SessionMode.NAP -> {
                copy(
                    estimator = napEstimator,
                    post = napMode.post.applyTo(post),
                    hybrid = napMode.hybrid.applyTo(hybrid),
                )
            }
        }
}

/** Preprocessing (section 1 of the specification). */
@Serializable
data class PreprocessParams(
    /** Heart-rate values below this (bpm) are dropped. */
    val minBpm: Double = 30.0,
    /** Heart-rate values above this (bpm) are dropped. */
    val maxBpm: Double = 180.0,
    /** Hampel filter window in samples (odd). */
    val hampelWindow: Int = 5,
    /** Hampel filter threshold in scaled MADs. */
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
    /**
     * Lower bound (bpm) of the Hampel scale 1.4826·MAD. Heart rate is stored in whole bpm, so a window with three
     * equal readings has MAD = 0; without a floor every 1-bpm deviation would be "replaced" and the variability
     * features flattened. With the default threshold 3 a deviation must exceed 3 bpm in such windows.
     */
    val hampelMinScaleBpm: Double = 1.0,
    /**
     * Sessions shorter than this many minutes are not estimated
     * ([io.github.alien2003.pulsestages.algo.model.NotEstimatedReason.SESSION_TOO_SHORT]); 0 disables the gate. The
     * app filters naps by its source setting before calling the estimators and leaves this at 0.
     */
    val minSessionMin: Double = 0.0,
)

/** Per-epoch features and night-level context (section 2). */
@Serializable
data class FeatureParams(
    /** hrNorm = (hr − P[normLowPercentile]) / (P[normHighPercentile] − P[normLowPercentile]). */
    val normLowPercentile: Double = 10.0,
    /** Upper percentile of the hrNorm scale (≈ light sleep), see [normLowPercentile]. */
    val normHighPercentile: Double = 50.0,
    /** Minimum denominator for hrNorm (bpm); smaller ranges flag the night as low-confidence. */
    val minNormRangeBpm: Double = 2.0,
    /** hrVar: std of the unsmoothed series in ±[varHalfWindowEpochs]. */
    val varHalfWindowEpochs: Int = 3,
    /** hrSlope: least-squares slope (bpm/min) in ±[slopeHalfWindowEpochs]. */
    val slopeHalfWindowEpochs: Int = 2,
    /** Ultradian minima: minimum distance between minima, minutes. */
    val cycleMinDistanceMin: Double = 60.0,
    /** Ultradian minima: minimum prominence, bpm. */
    val cycleMinProminenceBpm: Double = 2.0,
    /**
     * Number of previous nights used for the baseline nadir (used by the caller that computes the baseline). Naps
     * ([SessionMode.NAP], `napMode.maxSessionMin`) never count and are never compared with the baseline.
     */
    val baselineNights: Int = 14,
    /** Nadir above baseline by more than this percentage marks the night as "elevated HR". */
    val elevatedNightPct: Double = 12.0,
    /** On elevated nights the AWAKE threshold is raised by this amount of hrNorm. */
    val elevatedAwakeShift: Double = 0.3,
    /** Night confidence multiplier on elevated nights. */
    val elevatedConfidenceFactor: Double = 0.8,
    /** Use SpO2 when present (each optional channel is used only if data is present and its flag is on). */
    val useSpo2: Boolean = true,
    /** SpO2 drop (percentage points below the rolling night median) considered a desaturation. */
    val spo2DropPct: Double = 3.0,
    /** Likelihood multiplier for AWAKE and LIGHT in epochs with a desaturation. */
    val spo2AwakeLightFactor: Double = 2.0,
    /** Use HRV (RMSSD) when present: it replaces hrVar as the variability feature. */
    val useHrv: Boolean = true,
    /** Weight (σ divisor) of the RMSSD-based variability feature when it replaces hrVar. */
    val hrvWeight: Double = 1.5,
    /** Use the respiratory rate when present (a variability term in the emissions). */
    val useRespiratoryRate: Boolean = true,
    /** Weight of the respiratory-rate variability term. */
    val respiratoryRateWeight: Double = 0.5,
    /** Length (minutes, centred) of the rolling median that defines the SpO2 baseline for desaturations. */
    val spo2MedianWindowMin: Double = 60.0,
    /** An epoch uses the nearest HRV (RMSSD) sample only if it is at most this far from the epoch midpoint. */
    val hrvMaxDistanceMin: Double = 5.0,
    /** Respiratory-rate variability: std of the per-epoch rate in ±[respiratoryHalfWindowEpochs]. */
    val respiratoryHalfWindowEpochs: Int = 5,
)

/**
 * [minutes] as whole milliseconds, rounded up, so that `durationMs >= minutesToWholeMs(minutes)` holds exactly when
 * the duration is at least [minutes] minutes; 0 for a non-positive or undefined value.
 */
internal fun minutesToWholeMs(minutes: Double): Long = if (minutes.isNaN() || minutes <= 0.0) 0L else ceil(minutes * MS_PER_MINUTE).toLong()

private const val MS_PER_MINUTE = 60_000.0

/**
 * A univariate Gaussian.
 *
 * @property mean the mean μ.
 * @property sd the standard deviation σ (positive).
 */
@Serializable
data class Gaussian(
    val mean: Double,
    val sd: Double,
)

/**
 * Diagonal-Gaussian emission of one hidden state over (hrNorm, hrVar, hrSlope).
 *
 * @property hrNorm distribution of the normalized heart rate.
 * @property hrVar distribution of the heart-rate variability proxy.
 * @property hrSlope distribution of the heart-rate slope (bpm/min).
 */
@Serializable
data class StateEmission(
    val hrNorm: Gaussian,
    val hrVar: Gaussian,
    val hrSlope: Gaussian,
)

/**
 * Emission parameters of the four hidden states.
 *
 * @property awake emission of AWAKE.
 * @property light emission of LIGHT.
 * @property deep emission of DEEP.
 * @property rem emission of REM.
 */
@Serializable
data class EmissionTable(
    val awake: StateEmission,
    val light: StateEmission,
    val deep: StateEmission,
    val rem: StateEmission,
) {
    /** Presets. */
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

/**
 * Mean bout durations in minutes; self-transition per epoch = 1 − epochMin / meanBoutMin.
 *
 * @property awakeMin mean AWAKE bout.
 * @property lightMin mean LIGHT bout.
 * @property deepMin mean DEEP bout.
 * @property remMin mean REM bout.
 */
@Serializable
data class MeanBouts(
    val awakeMin: Double = 5.0,
    val lightMin: Double = 20.0,
    val deepMin: Double = 20.0,
    val remMin: Double = 20.0,
)

/**
 * Shares of the non-self transition mass. Rows are normalized at use; zeros are replaced by ε.
 *
 * @property awakeToLight AWAKE → LIGHT.
 * @property awakeToDeep AWAKE → DEEP.
 * @property awakeToRem AWAKE → REM.
 * @property lightToAwake LIGHT → AWAKE.
 * @property lightToDeep LIGHT → DEEP (before the time prior).
 * @property lightToRem LIGHT → REM (before the time prior).
 * @property deepToAwake DEEP → AWAKE.
 * @property deepToLight DEEP → LIGHT.
 * @property deepToRem DEEP → REM.
 * @property remToAwake REM → AWAKE.
 * @property remToLight REM → LIGHT.
 * @property remToDeep REM → DEEP.
 */
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

/**
 * Time-dependent priors and initial distribution of the HMM (section 3 of the specification): the part of [HmmParams]
 * that the supervised estimation of tools/train fits besides the emissions, mean bouts and transition shares. The
 * defaults are the specification's values; [HmmParams] takes its defaults from [TrainedDefaults.HMM_TIME_PRIOR].
 *
 * @property deepTimeIntercept LIGHT→DEEP multiplier = max([deepTimeFloor], [deepTimeIntercept] − [deepTimeSlope]·t).
 * @property deepTimeSlope decrease of the LIGHT→DEEP multiplier over the night (t = elapsed fraction).
 * @property deepTimeFloor lower bound of the LIGHT→DEEP multiplier.
 * @property remTimeIntercept LIGHT→REM multiplier = [remTimeIntercept] + [remTimeSlope]·t.
 * @property remTimeSlope increase of the LIGHT→REM multiplier over the night.
 * @property remLockoutMin minutes after the session start during which REM is suppressed.
 * @property remLockoutFactor REM probability multiplier during the lockout.
 * @property initialAwake initial AWAKE weight (DEEP and REM get ε).
 * @property initialLight initial LIGHT weight.
 */
data class HmmTimePrior(
    val deepTimeIntercept: Double = 1.6,
    val deepTimeSlope: Double = 1.2,
    val deepTimeFloor: Double = 0.3,
    val remTimeIntercept: Double = 0.4,
    val remTimeSlope: Double = 1.2,
    val remLockoutMin: Double = 45.0,
    val remLockoutFactor: Double = 0.05,
    val initialAwake: Double = 0.5,
    val initialLight: Double = 0.5,
)

/**
 * HMM estimator (section 3). The emission, bout-length, transition and time-prior defaults come from
 * [TrainedDefaults]: the supervised estimates of the open-dataset validation if they scored higher than the physiology
 * table and passed its validity checks, otherwise the specification's values ([EmissionTable.PHYSIOLOGY], [MeanBouts],
 * [TransitionWeights], [HmmTimePrior]), which stay available either way ([PHYSIOLOGY]).
 */
@Serializable
data class HmmParams(
    /** Gaussian emissions of the four states. */
    val emissions: EmissionTable = TrainedDefaults.HMM_EMISSIONS,
    /** Mean bout lengths; they set the self-transitions. */
    val meanBouts: MeanBouts = TrainedDefaults.HMM_MEAN_BOUTS,
    /** Shares of the remaining transition mass. */
    val transitions: TransitionWeights = TrainedDefaults.HMM_TRANSITIONS,
    /** Replacement for zero probabilities. */
    val epsilon: Double = 1e-4,
    /** LIGHT→DEEP multiplier = max(deepTimeFloor, deepTimeIntercept − deepTimeSlope·t). */
    val deepTimeIntercept: Double = TrainedDefaults.HMM_TIME_PRIOR.deepTimeIntercept,
    /** Decrease of the LIGHT→DEEP multiplier over the night (t = elapsed fraction), see [deepTimeIntercept]. */
    val deepTimeSlope: Double = TrainedDefaults.HMM_TIME_PRIOR.deepTimeSlope,
    /** Lower bound of the LIGHT→DEEP multiplier, see [deepTimeIntercept]. */
    val deepTimeFloor: Double = TrainedDefaults.HMM_TIME_PRIOR.deepTimeFloor,
    /** LIGHT→REM multiplier = remTimeIntercept + remTimeSlope·t. */
    val remTimeIntercept: Double = TrainedDefaults.HMM_TIME_PRIOR.remTimeIntercept,
    /** Increase of the LIGHT→REM multiplier over the night, see [remTimeIntercept]. */
    val remTimeSlope: Double = TrainedDefaults.HMM_TIME_PRIOR.remTimeSlope,
    /** REM is suppressed (probability × remLockoutFactor) during the first remLockoutMin minutes. */
    val remLockoutMin: Double = TrainedDefaults.HMM_TIME_PRIOR.remLockoutMin,
    /** REM probability multiplier during the lockout, see [remLockoutMin]. */
    val remLockoutFactor: Double = TrainedDefaults.HMM_TIME_PRIOR.remLockoutFactor,
    /** Initial AWAKE weight (DEEP and REM get ε). */
    val initialAwake: Double = TrainedDefaults.HMM_TIME_PRIOR.initialAwake,
    /** Initial LIGHT weight. */
    val initialLight: Double = TrainedDefaults.HMM_TIME_PRIOR.initialLight,
    /** Additive log-prior weight of the ultradian-cycle phase (0 disables). */
    val cyclePriorWeight: Double = 0.5,
    /** Cycle phase (0..1 between minima) at which the DEEP prior peaks. */
    val cycleDeepPhase: Double = 0.0,
    /** Cycle phase (0..1 between minima) at which the REM prior peaks. */
    val cycleRemPhase: Double = 0.7,
    /** Steps in an epoch at or above this force AWAKE; 1..threshold−1 multiply the AWAKE emission. */
    val awakeStepThreshold: Int = 5,
    /** AWAKE emission multiplier for epochs with 1..awakeStepThreshold−1 steps. */
    val fewStepsAwakeFactor: Double = 3.0,
    /** Per-night Baum–Welch adaptation of μ/σ with MAP shrinkage. */
    val emAdapt: Boolean = true,
    /** Baum–Welch iterations of the per-night adaptation. */
    val emIterations: Int = 3,
    /** MAP shrinkage of the adapted μ/σ towards the starting values, in epoch-equivalents. */
    val emPriorStrength: Double = 20.0,
) {
    /** These parameters with the time-dependent priors and initial distribution of [prior]. */
    fun withTimePrior(prior: HmmTimePrior): HmmParams =
        copy(
            deepTimeIntercept = prior.deepTimeIntercept,
            deepTimeSlope = prior.deepTimeSlope,
            deepTimeFloor = prior.deepTimeFloor,
            remTimeIntercept = prior.remTimeIntercept,
            remTimeSlope = prior.remTimeSlope,
            remLockoutMin = prior.remLockoutMin,
            remLockoutFactor = prior.remLockoutFactor,
            initialAwake = prior.initialAwake,
            initialLight = prior.initialLight,
        )

    /** Presets. */
    companion object {
        /**
         * The specification's physiology defaults: [EmissionTable.PHYSIOLOGY], mean bouts AWAKE 5 / LIGHT 20 / DEEP 20 /
         * REM 20 min, the specification's transition shares and time-dependent priors ([HmmTimePrior]), every other
         * field at its default ("reset to the physiology table", and the fixed reference of the synthetic regression
         * tests).
         */
        val PHYSIOLOGY: HmmParams
            get() =
                HmmParams(emissions = EmissionTable.PHYSIOLOGY, meanBouts = MeanBouts(), transitions = TransitionWeights())
                    .withTimePrior(HmmTimePrior())
    }
}

/**
 * Rules estimator thresholds (section 6): DEEP if hrNorm < [deepMaxHrNorm] and hrVar < [deepMaxHrVar]; AWAKE if
 * hrNorm > [awakeMinHrNorm] or steps; REM if hrNorm > [remMinHrNorm] and hrVar > [remMinHrVar] and t > [remMinT];
 * LIGHT otherwise.
 *
 * @property deepMaxHrNorm DEEP: hrNorm below this.
 * @property deepMaxHrVar DEEP: hrVar below this.
 * @property awakeMinHrNorm AWAKE: hrNorm above this.
 * @property remMinHrNorm REM: hrNorm above this.
 * @property remMinHrVar REM: hrVar above this.
 * @property remMinT REM: elapsed fraction of the night above this.
 */
@Serializable
data class RulesParams(
    val deepMaxHrNorm: Double = 0.45,
    val deepMaxHrVar: Double = 0.8,
    val awakeMinHrNorm: Double = 2.2,
    val remMinHrNorm: Double = 1.3,
    val remMinHrVar: Double = 1.1,
    val remMinT: Double = 0.25,
    /**
     * Pseudo-posterior of the chosen stage (the other three share the rest equally). The rules estimator has no
     * probabilistic model; these values only feed post-processing and the confidence.
     */
    val pseudoPosterior: Double = 0.85,
    /**
     * Proportion calibration for the rules (they have no state priors to shift): a per-stage log-prior offset δ moves
     * the hrNorm thresholds by this amount per unit of δ in favour of the stage (deepMaxHrNorm + c·δ_DEEP,
     * remMinHrNorm − c·δ_REM, awakeMinHrNorm − c·δ_AWAKE). 0 disables calibration for the rules.
     */
    val calibrationThresholdShift: Double = 0.25,
)

/** Explicit-duration (HSMM) or geometric (plain HMM) decoding for the hybrid estimator. */
@Serializable
enum class DurationModel {
    /** Hidden semi-Markov decoding with the bundle's gamma bout-length distributions. */
    EXPLICIT,

    /** Plain HMM decoding: geometric bout lengths with the mean of each gamma distribution. */
    GEOMETRIC,
}

/**
 * Hybrid estimator (section 8). Model weights are not parameters; they come from the model bundle. The decoder
 * defaults ([durationModel], [timePriorWeight], [emissionWeight], [priorExponent]) are the values chosen by the
 * nested cross-validation of the open-dataset validation ([TrainedDefaults]); the pre-training values were EXPLICIT,
 * 1.0, 1.0 and 1.0. Naps may override [durationModel], [emissionWeight] and [timePriorWeight] ([NapHybridParams]).
 */
@Serializable
data class HybridParams(
    val durationModel: DurationModel = TrainedDefaults.HYBRID_DURATION_MODEL,
    /** Weight of log P(stage | 30-min bin) relative to the class prior. */
    val timePriorWeight: Double = TrainedDefaults.HYBRID_TIME_PRIOR_WEIGHT,
    /** Maximum bout length for the HSMM, minutes. */
    val maxBoutMin: Int = 120,
    /** Exponent applied to the scaled likelihoods (1 = as trained). */
    val emissionWeight: Double = TrainedDefaults.HYBRID_EMISSION_WEIGHT,
    /** AWAKE scaled-likelihood multiplier on elevated-HR nights. */
    val elevatedAwakeFactor: Double = 0.6,
    /**
     * Exponent β of the class priors in the scaled likelihood `p(s | x) / prior_s^β`: 1 divides the posteriors by the
     * training class frequencies (the classic hybrid HMM), 0 uses the posteriors as they are (which favours the
     * frequent stages), values in between interpolate.
     */
    val priorExponent: Double = TrainedDefaults.HYBRID_PRIOR_EXPONENT,
)

/** What to write for epochs inside long data gaps. */
@Serializable
enum class GapPolicy {
    /** UNKNOWN (no claim about the stage). */
    UNKNOWN,

    /** LIGHT sleep. */
    LIGHT,

    /** The stage of the nearest epoch with data. */
    NEAREST,
}

/** What to do with epochs whose maximum posterior is below the threshold. */
@Serializable
enum class LowConfidencePolicy {
    /** Keep the decoder's stage. */
    KEEP,

    /** Write LIGHT sleep. */
    LIGHT,
}

/**
 * Post-processing (section 4). Naps use these values with the overrides of [NapPostprocessParams]; the sanity check and
 * the proportion calibration run on naps only with `napMode.post.checkProportions`.
 *
 * @property minDeepMin shorter DEEP bouts (minutes) are absorbed by the neighbour with the higher posterior.
 * @property minRemMin shorter REM bouts (minutes) are absorbed likewise.
 * @property minAwakeMin shorter AWAKE bouts inside sleep (minutes) are absorbed likewise.
 * @property gapPolicy what to write for epochs inside data gaps longer than `maxInterpGapMin`.
 * @property sanityDeepMax a night with more DEEP than this share is low-confidence.
 * @property sanityRemMax a night with more REM than this share is low-confidence.
 * @property sanityDeepMin a night with less DEEP than this share is low-confidence.
 * @property sanityConfidenceFactor night confidence multiplier when a sanity check fails.
 * @property targetDeepMin lower end of the DEEP share corridor of the calibration.
 * @property targetDeepMax upper end of the DEEP share corridor.
 * @property targetRemMin lower end of the REM share corridor.
 * @property targetRemMax upper end of the REM share corridor.
 * @property targetAwakeMax upper end of the AWAKE share corridor.
 * @property calibrationStep log-prior shift per calibration iteration.
 * @property calibrationMaxIterations maximum number of calibration iterations.
 * @property lowConfidenceThreshold epochs whose maximum posterior is below this follow [lowConfidencePolicy].
 * @property lowConfidencePolicy what to write for such epochs.
 */
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
    /** Use the personal model (calibration on reference nights and the periodic EM). */
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

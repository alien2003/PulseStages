package io.github.alien2003.pulsestages.algo

import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.params.AlgoParams

/**
 * Integer version of the estimation logic. Increment on ANY change of the algorithms' behaviour (not on
 * parameter changes — those are captured by the params hash).
 */
const val ALGO_VERSION: Int = 1

/** A sleep-stage estimator. Implementations are pure and deterministic: same input + params → same output. */
interface StageEstimator {
    /** The estimator kind. */
    val type: EstimatorType

    /**
     * Estimates the stages of one night. Callers normally go through [SleepStager.run], which applies the
     * coverage gates first; calling this directly on a low-coverage night still returns a (low-confidence)
     * estimate.
     */
    fun estimate(
        input: NightInput,
        params: AlgoParams,
    ): NightEstimate
}

/*
 * Module map (each public type has its own KDoc):
 *
 * SleepStager()                     facade used by the app and algo-cli:
 *   SleepStager(hybridModel: HybridModel?, personalModel: PersonalModel? = null)
 *     fun run(input: NightInput, params: AlgoParams): EstimationOutcome
 *     fun run(night: PreparedNight): EstimationOutcome               // reuse a preparation (PreparedNight.withParams)
 *     fun estimateWith(type: EstimatorType, input: NightInput, params: AlgoParams): NightEstimate // "compare with RULES"
 *     fun paramsHash(params: AlgoParams, extra: String = ""): String // + model and (if enabled) personal fingerprints
 *     val modelId: String?
 *   run(): preprocess → session < preprocess.minSessionMin (default 0 = off) → NotEstimated(SESSION_TOO_SHORT);
 *   mode = NAP when the session is shorter than napMode.maxSessionMin (AlgoParams.modeOf), NIGHT otherwise; a NAP is
 *   run with AlgoParams.forMode(NAP) (napMode.estimator, nap post-processing / decoder overrides, no baseline) and with
 *   napMode.outputPolicy SKIP → NotEstimated(NAP_SKIPPED);
 *   no HR samples → NotEstimated(NO_HR_DATA); coverage ≥ minCoverage: configured estimator;
 *   [rulesMinCoverage, minCoverage): RULES with RULES_FALLBACK_LOW_COVERAGE; below: NotEstimated(LOW_COVERAGE).
 *   HYBRID is served by the internal HybridEstimation seam (HybridEstimator); without a bundle, or when the bundle does
 *   not match HybridFeatureExtractor (featureSetVersion / featureNames), the HMM is used with HYBRID_MODEL_UNAVAILABLE.
 * HmmEstimator, RulesEstimator,     the StageEstimator implementations (algo.hmm, algo.rules, algo.hybrid).
 *   HybridEstimator
 * HybridModel                       the JSON model bundle (docs/INTERCHANGE.md §3): parse/validate, fingerprint();
 *   NapSubModel, HybridComponents   its optional nap model, decoding NAP sessions instead of the main model.
 * HybridFeatureExtractor            FEATURE_SET_VERSION, FEATURE_NAMES and the per-epoch hybrid features.
 * PersonalModel, PersonalCalibrator, personalization (algo.personal): bias deltas / HMM μ,σ overrides, supervised
 *   PopulationEmAdapter             calibration on reference nights, population EM over many nights.
 * NightPreparer / PreparedNight     preprocessing (algo.preprocess), night statistics and base features (algo.features).
 * ParamsHasher                      SHA-256 of the canonical params JSON + "|model=" + fp + "|personal=" + fp + "|" + extra.
 * Metrics, ConfusionMatrix,         kappa, accuracy, per-stage precision/recall/F1, macro-F1, minute errors, the
 *   IntervalScoring, EpochLabels    30-s chunk comparison of interval hypnograms and reference labels per epoch (algo.metrics).
 * SyntheticNightGenerator(seed)     seeded synthetic nights for tests (algo.synthetic).
 * CsvExporter                       per-epoch CSV export (algo.export).
 */

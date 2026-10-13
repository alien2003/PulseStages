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
 * Entry point used by the app (contract; implemented in this module):
 *
 * class SleepStager(
 *     hybridModel: HybridModel?,            // null → HYBRID falls back to HMM with HYBRID_MODEL_UNAVAILABLE
 *     personalModel: PersonalModel? = null, // applied when params.personal.enabled
 * ) {
 *     fun run(input: NightInput, params: AlgoParams): EstimationOutcome
 *     fun estimateWith(type: EstimatorType, input: NightInput, params: AlgoParams): NightEstimate // for "compare with RULES"
 *     fun paramsHash(params: AlgoParams, extra: String = ""): String // extra = output options affecting written data
 * }
 *
 * run(): preprocess → coverage gate (≥ minCoverage: configured estimator; [rulesMinCoverage, minCoverage): RULES
 * with RULES_FALLBACK_LOW_COVERAGE; below: NotEstimated(LOW_COVERAGE)); no HR samples → NotEstimated(NO_HR_DATA).
 *
 * object ParamsHasher { fun hash(params: AlgoParams, modelFingerprint: String?, personalFingerprint: String?, extra: String): String }
 *   SHA-256 hex of canonical JSON (kotlinx.serialization, encodeDefaults = true) of params + "|model=" + fp + ...
 *
 * object Metrics {
 *     fun confusion(truth: List<SleepStage>, predicted: List<SleepStage>): ConfusionMatrix  // UNKNOWN pairs skipped
 * }
 * data class ConfusionMatrix(val counts: Array<LongArray>) { kappa, accuracy, perStage precision/recall/F1 }
 * data class StageMinutesError(...)  // |estimate − truth| minutes for DEEP / REM / AWAKE
 *
 * class SyntheticNightGenerator(seed) { fun generate(...): SyntheticNight(input: NightInput, truth: List<StageInterval>) }
 *
 * class HybridModel  — parsed from the JSON bundle (schema in HYBRID_MODEL_SCHEMA.md), fun fingerprint(): String
 * class PersonalModel — serializable personal adjustments (bias deltas, emission overrides), fun fingerprint(): String
 * object CsvExporter { fun export(estimate: NightEstimate): String }
 */

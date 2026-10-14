package io.github.alien2003.pulsestages.algo

import io.github.alien2003.pulsestages.algo.hmm.HmmEstimator
import io.github.alien2003.pulsestages.algo.hybrid.HybridEstimator
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimationWarning
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.night.PreparedNight
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.algo.personal.PersonalModel
import io.github.alien2003.pulsestages.algo.rules.RulesEstimator
import java.util.Locale

/** Outcome of one hybrid-estimation attempt (see [HybridEstimation]). */
internal sealed interface HybridAttempt {
    /** The hybrid estimator produced [estimate]. */
    class Done(
        val estimate: NightEstimate,
    ) : HybridAttempt

    /** No usable hybrid model ([reason] goes into the HYBRID_MODEL_UNAVAILABLE warning). */
    class Unavailable(
        val reason: String,
    ) : HybridAttempt
}

/**
 * Seam through which the hybrid estimator (discriminative emissions + HSMM decoding) plugs into [SleepStager].
 * Implementations validate their model bundle and return [HybridAttempt.Unavailable] when it is missing or
 * incompatible, in which case the stager falls back to the HMM.
 */
internal interface HybridEstimation {
    /** Fingerprint of the model bundle (part of the params hash), or null without a bundle. */
    val modelFingerprint: String?

    /** Fingerprint of the personal model, or null when there is none. */
    val personalFingerprint: String?

    /** Estimates [night] with the hybrid model and the night's parameters. */
    fun estimate(night: PreparedNight): HybridAttempt
}

/**
 * Entry point of the module: applies the coverage gates, selects the estimator and computes the params hash.
 *
 * Without a hybrid model (the public no-argument constructor) HYBRID falls back to HMM with the
 * HYBRID_MODEL_UNAVAILABLE warning. A personal model is applied when `params.personal.enabled`: its classifier bias
 * deltas in the hybrid estimator and its emission μ/σ overrides in the HMM (also when HYBRID falls back to HMM).
 */
class SleepStager internal constructor(
    private val hybrid: HybridEstimation?,
    private val personal: PersonalModel? = null,
    /** Id of the hybrid model bundle (shown in the UI and stored with the estimates), or null without one. */
    val modelId: String? = null,
) {
    /** A stager without a hybrid model and without a personal model. */
    constructor() : this(null, null, null)

    /**
     * A stager with the hybrid model bundle [hybridModel] (null: HYBRID falls back to HMM) and the personal model
     * [personalModel] (null: global models only).
     */
    constructor(hybridModel: HybridModel?, personalModel: PersonalModel? = null) : this(
        hybrid = hybridModel?.let { HybridEstimator(it, personalModel).seam() },
        personal = personalModel,
        modelId = hybridModel?.modelId,
    )

    private val hmm = HmmEstimator()
    private val rules = RulesEstimator()

    /**
     * Estimates one session with the estimator of its mode after the gates: a session shorter than
     * `preprocess.minSessionMin` minutes → [NotEstimatedReason.SESSION_TOO_SHORT] (the default 0 disables this gate);
     * a nap ([SessionMode.NAP]) with `napMode.outputPolicy` SKIP → [NotEstimatedReason.NAP_SKIPPED]; no heart-rate
     * samples (in range, inside the session ± context) → [NotEstimatedReason.NO_HR_DATA]; coverage ≥ minCoverage → the
     * configured estimator (`estimator` for a night, `napMode.estimator` for a nap); rulesMinCoverage ≤ coverage <
     * minCoverage → RULES (RULES_FALLBACK_LOW_COVERAGE unless RULES was configured anyway); below →
     * [NotEstimatedReason.LOW_COVERAGE]. A nap is estimated with `params.forMode(SessionMode.NAP)`; the estimate's
     * params hash is always that of [params] as given.
     */
    fun run(
        input: NightInput,
        params: AlgoParams,
    ): EstimationOutcome = run(NightPreparer.prepare(input, params))

    /**
     * [run] on an already prepared night with its parameters ([PreparedNight.configuredParams], in effect
     * [PreparedNight.params]). Evaluations that vary only decoder or post-processing parameters reuse the preparation
     * through [PreparedNight.withParams].
     */
    fun run(night: PreparedNight): EstimationOutcome {
        val coverage = night.coverage
        val gates = night.params.preprocess
        val sessionMin = (night.input.sessionEndMs - night.input.sessionStartMs) / MS_PER_MINUTE
        val napMode = night.params.napMode
        return when {
            sessionMin < gates.minSessionMin -> {
                EstimationOutcome.NotEstimated(
                    NotEstimatedReason.SESSION_TOO_SHORT,
                    coverage,
                    "session of ${String.format(Locale.ROOT, "%.1f", sessionMin)} min is shorter than ${gates.minSessionMin} min",
                )
            }

            night.mode == SessionMode.NAP && napMode.outputPolicy == NapOutputPolicy.SKIP -> {
                EstimationOutcome.NotEstimated(
                    NotEstimatedReason.NAP_SKIPPED,
                    coverage,
                    "nap of ${String.format(Locale.ROOT, "%.1f", sessionMin)} min (shorter than napMode.maxSessionMin = " +
                        "${napMode.maxSessionMin} min) and naps are not estimated (napMode.outputPolicy = SKIP)",
                )
            }

            night.preprocessed.sampleCount == 0 -> {
                EstimationOutcome.NotEstimated(
                    NotEstimatedReason.NO_HR_DATA,
                    coverage,
                    "no heart-rate samples within [${gates.minBpm}, ${gates.maxBpm}] bpm in the session ± ${gates.contextMarginMin} min",
                )
            }

            coverage >= gates.minCoverage -> {
                EstimationOutcome.Estimated(estimatePrepared(night.params.estimator, night))
            }

            coverage >= gates.rulesMinCoverage -> {
                EstimationOutcome.Estimated(rulesFallback(night.params.estimator, night))
            }

            else -> {
                EstimationOutcome.NotEstimated(
                    NotEstimatedReason.LOW_COVERAGE,
                    coverage,
                    "coverage ${pct(coverage)} below the rules minimum ${pct(gates.rulesMinCoverage)}",
                )
            }
        }
    }

    /**
     * Estimates with [type] regardless of `params.estimator` (and `napMode.estimator`) and without coverage gates or
     * the nap output policy ("compare with RULES"); a nap still gets the nap overrides of `napMode`.
     */
    fun estimateWith(
        type: EstimatorType,
        input: NightInput,
        params: AlgoParams,
    ): NightEstimate = estimateWith(type, NightPreparer.prepare(input, params))

    /** [estimateWith] on an already prepared night with its parameters. */
    fun estimateWith(
        type: EstimatorType,
        night: PreparedNight,
    ): NightEstimate = estimatePrepared(type, night)

    /**
     * The params hash of estimates made by this stager with [params]; [extra] holds output options that change
     * the written data. Includes the hybrid model fingerprint and, when personalization is enabled, the personal
     * model fingerprint.
     */
    fun paramsHash(
        params: AlgoParams,
        extra: String = "",
    ): String =
        ParamsHasher.hash(
            params = params,
            modelFingerprint = hybrid?.modelFingerprint,
            personalFingerprint = if (params.personal.enabled) personal?.fingerprint() ?: hybrid?.personalFingerprint else null,
            extra = extra,
        )

    private fun estimatePrepared(
        type: EstimatorType,
        night: PreparedNight,
    ): NightEstimate {
        val estimate =
            when (type) {
                EstimatorType.RULES -> rules.estimatePrepared(night)
                EstimatorType.HMM -> hmmEstimate(night)
                EstimatorType.HYBRID -> hybridOrFallback(night)
            }
        return estimate.copy(paramsHash = paramsHash(night.configuredParams))
    }

    /** HMM with the personal μ/σ overrides when personalization is enabled. */
    private fun hmmEstimate(night: PreparedNight): NightEstimate {
        val table = night.params.hmm.emissions
        val active = personal?.takeIf { night.params.personal.enabled }
        return hmm.estimatePrepared(night, active?.applyTo(table) ?: table)
    }

    private fun hybridOrFallback(night: PreparedNight): NightEstimate =
        when (val attempt = hybrid?.estimate(night) ?: HybridAttempt.Unavailable("no hybrid model bundle loaded")) {
            is HybridAttempt.Done -> {
                attempt.estimate
            }

            is HybridAttempt.Unavailable -> {
                val fallback = hmmEstimate(night)
                fallback.copy(warnings = fallback.warnings + EstimationWarning(WarningCode.HYBRID_MODEL_UNAVAILABLE, attempt.reason))
            }
        }

    private fun rulesFallback(
        configured: EstimatorType,
        night: PreparedNight,
    ): NightEstimate {
        val estimate = estimatePrepared(EstimatorType.RULES, night)
        if (configured == EstimatorType.RULES) return estimate
        val warning =
            EstimationWarning(
                WarningCode.RULES_FALLBACK_LOW_COVERAGE,
                "coverage ${pct(night.coverage)} below ${pct(night.params.preprocess.minCoverage)}; $configured replaced by RULES",
            )
        return estimate.copy(warnings = estimate.warnings + warning, flags = estimate.flags.copy(rulesFallback = true))
    }

    private fun pct(value: Double): String = String.format(Locale.ROOT, "%.1f %%", value * PERCENT)

    private companion object {
        const val PERCENT = 100.0
        const val MS_PER_MINUTE = 60_000.0
    }
}

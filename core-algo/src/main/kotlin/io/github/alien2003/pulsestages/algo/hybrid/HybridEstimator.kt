package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.HybridAttempt
import io.github.alien2003.pulsestages.algo.HybridEstimation
import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.StageEstimator
import io.github.alien2003.pulsestages.algo.features.BaseFeatures
import io.github.alien2003.pulsestages.algo.hmm.HmmMath
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.night.EstimateAssembler
import io.github.alien2003.pulsestages.algo.night.ExtraFeatures
import io.github.alien2003.pulsestages.algo.night.PreparedNight
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.DurationModel
import io.github.alien2003.pulsestages.algo.personal.PersonalModel
import io.github.alien2003.pulsestages.algo.post.Decoding
import io.github.alien2003.pulsestages.algo.post.EpochDecoder
import io.github.alien2003.pulsestages.algo.post.Postprocessor

/**
 * Hybrid estimator (section 8 of the specification): a trained discriminative classifier (from [model]) gives
 * per-epoch stage posteriors, which are divided by the class priors into scaled likelihoods (see [HybridEmissions]),
 * combined with the time-of-night prior and the step/SpO2/elevated-night rules, and decoded either by an
 * explicit-duration HSMM with gamma bout lengths ([DurationModel.EXPLICIT]: segment Viterbi for the path,
 * explicit-duration forward–backward for the posteriors) or by a plain HMM with geometric durations
 * ([DurationModel.GEOMETRIC]); then the shared post-processing.
 *
 * Naps ([SessionMode.NAP]) are decoded with the bundle's nap model ([HybridModel.nap]) when it has one: its classifier,
 * standardization, class priors, durations, bout transitions, initial distribution, time prior and log-bias (its
 * length table at the session length when it has one, [NapSubModel.stageLogBiasByLength]); without one, with the main
 * model and its short-session bias `stageLogBiasShort` (or `stageLogBias` when that is absent), exactly as before the
 * two modes existed. Nights always use the main model.
 *
 * [personalModel]'s classifier bias deltas are applied when `params.personal.enabled` (and they were fitted for this
 * bundle's main classifier; never to the nap model). A bundle that does not match this build's features
 * ([HybridModel.compatibilityProblem]; for naps also [HybridModel.napCompatibilityProblem]) is not used: the session is
 * estimated by the HMM with the HYBRID_MODEL_UNAVAILABLE warning.
 */
class HybridEstimator(
    /** The model bundle. */
    val model: HybridModel,
    /** Personal adjustments, or null. */
    val personalModel: PersonalModel? = null,
) : StageEstimator {
    override val type: EstimatorType = EstimatorType.HYBRID

    private val network: DenseNetwork by lazy { DenseNetwork(model) }

    private val napNetwork: DenseNetwork? by lazy { model.nap?.let { DenseNetwork(it) } }

    /** Feature columns appended to the base features in the per-epoch results (hybrid features not already there). */
    private val extraColumns: List<Int> =
        HybridFeatureExtractor.FEATURE_NAMES.indices.filter { HybridFeatureExtractor.FEATURE_NAMES[it] !in BaseFeatures.NAMES }

    /** Estimates through [SleepStager] (no coverage gates, HMM fallback for an incompatible bundle, params hash). */
    override fun estimate(
        input: NightInput,
        params: AlgoParams,
    ): NightEstimate = SleepStager(model, personalModel).estimateWith(EstimatorType.HYBRID, input, params)

    /** The [HybridEstimation] seam of [SleepStager] backed by this estimator. */
    internal fun seam(): HybridEstimation =
        object : HybridEstimation {
            override val modelFingerprint: String get() = model.fingerprint()
            override val personalFingerprint: String? get() = personalModel?.fingerprint()

            override fun estimate(night: PreparedNight): HybridAttempt = attempt(night)
        }

    /** The model that decodes a session: the components, their network, their log-bias and the logits cache key. */
    private class ActiveModel(
        val components: HybridComponents,
        val network: DenseNetwork,
        val stageBias: List<Double>?,
        val logitsKey: String,
        val personal: Boolean,
    )

    /**
     * The model that decodes [night] (see the class documentation). A nap model's log-bias is its length table at the
     * session length (end − start, minutes) when it has one ([NapSubModel.stageLogBiasFor]); the classifier outputs, and
     * so the logits cache key, do not depend on it.
     */
    private fun activeModel(night: PreparedNight): ActiveModel {
        val nap = model.nap
        val napNetwork = napNetwork
        return if (night.mode == SessionMode.NAP && nap != null && napNetwork != null) {
            val sessionMin = (night.input.sessionEndMs - night.input.sessionStartMs) / MS_PER_MINUTE
            ActiveModel(nap, napNetwork, nap.stageLogBiasFor(sessionMin), model.fingerprint() + NAP_LOGITS_SUFFIX, personal = false)
        } else {
            ActiveModel(model, network, HybridEmissions.mainModelBias(model, night.mode), model.fingerprint(), personal = true)
        }
    }

    /** Estimates [night] with its parameters, or reports why the bundle cannot be used. */
    internal fun attempt(night: PreparedNight): HybridAttempt {
        val problem =
            model.compatibilityProblem()?.let { "hybrid model '${model.modelId}' is incompatible: $it" }
                ?: model
                    .napCompatibilityProblem()
                    ?.takeIf { night.mode == SessionMode.NAP }
                    ?.let { "the nap model of hybrid model '${model.modelId}' is incompatible: $it" }
        if (problem != null) return HybridAttempt.Unavailable(problem)
        val active = activeModel(night)
        val params = night.params
        val deltas =
            personalModel
                ?.takeIf { params.personal.enabled && active.personal }
                ?.biasDeltasFor(model.nightModelFingerprint(), model.fingerprint())
        val emissions = HybridEmissions(night, active.components, active.network, deltas, active.stageBias, active.logitsKey)
        val decoder =
            when (params.hybrid.durationModel) {
                DurationModel.EXPLICIT -> explicitDecoder(night, active.components, emissions)
                DurationModel.GEOMETRIC -> geometricDecoder(night, active.components, emissions)
            }
        val post = Postprocessor.run(decoder, EstimateAssembler.context(night), params.post)
        return HybridAttempt.Done(EstimateAssembler.assemble(night, type, post, emptyList(), extraFeatures(night)))
    }

    /**
     * HSMM decoding. The bundle's bout matrix may forbid self-transitions, which makes bouts longer than Dmax
     * impossible; when no segmentation is feasible (a forced-awake span longer than Dmax) the decode is repeated with
     * self-transitions floored at ε.
     */
    private fun explicitDecoder(
        night: PreparedNight,
        components: HybridComponents,
        emissions: HybridEmissions,
    ): EpochDecoder {
        val epochMin = night.grid.epochMin
        val strict = HybridDecoders.hsmm(components, night.params, epochMin, relaxSelfTransitions = false)
        val relaxed by lazy { HybridDecoders.hsmm(components, night.params, epochMin, relaxSelfTransitions = true) }
        return EpochDecoder { offsets ->
            val logEmission = emissions.withOffsets(offsets)
            var hsmm = strict
            var path = HsmmMath.viterbi(hsmm, logEmission)
            if (path.logScore == Double.NEGATIVE_INFINITY) {
                hsmm = relaxed
                path = HsmmMath.viterbi(hsmm, logEmission)
            }
            Decoding(path.states, HsmmMath.forwardBackward(hsmm, logEmission).posteriors)
        }
    }

    private fun geometricDecoder(
        night: PreparedNight,
        components: HybridComponents,
        emissions: HybridEmissions,
    ): EpochDecoder {
        val hmm = HybridDecoders.geometric(components, night.params, night.grid.epochMin, night.grid.sessionEpochs)
        return EpochDecoder { offsets ->
            val logEmission = emissions.withOffsets(offsets)
            Decoding(HmmMath.viterbi(hmm, logEmission), HmmMath.forwardBackward(hmm, logEmission).posteriors)
        }
    }

    private fun extraFeatures(night: PreparedNight): ExtraFeatures {
        val features = HybridFeatureExtractor.cached(night)
        return ExtraFeatures(
            names = extraColumns.map { HybridFeatureExtractor.FEATURE_NAMES[it] },
            values = Array(features.size) { k -> DoubleArray(extraColumns.size) { features[k][extraColumns[it]] } },
        )
    }

    private companion object {
        /** Logits cache key suffix of the nap model (the bundle fingerprint covers the nap model). */
        const val NAP_LOGITS_SUFFIX = "#nap"

        /** Milliseconds per minute (the session length that selects a row of a nap length table). */
        const val MS_PER_MINUTE = 60_000.0
    }
}

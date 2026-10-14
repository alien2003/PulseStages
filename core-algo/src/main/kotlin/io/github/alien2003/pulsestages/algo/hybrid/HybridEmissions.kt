package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.night.EpochEvidence
import io.github.alien2003.pulsestages.algo.night.PreparedNight
import kotlin.math.floor
import kotlin.math.ln

/**
 * Per-epoch log emissions `[session epoch][state]` of the hybrid decoder:
 * - scaled likelihood `emissionWeight · (log p(s|x) − priorExponent · log prior_s) + b_s` on epochs with heart-rate
 *   data (0 = likelihood 1 without data), where p(s|x) is the posterior of [model]'s classifier (the bundle's main or
 *   nap model) with the personal bias deltas added to its logits, priorExponent = β of `p(s|x) / prior_s^β` (1 = the
 *   classic hybrid scaled likelihood) and b = [stageBias] (see [HybridEmissions.stageBias]; 0 when absent);
 * - `timePriorWeight · (log P(s | bin) − log prior_s)` from [model]'s time table (zeros → ε, bins past the end use
 *   the last row; bin = minutes from the session start to the epoch midpoint / binMinutes);
 * - ln(fewStepsAwakeFactor) for AWAKE with 1 … awakeStepThreshold−1 steps, ln(elevatedAwakeFactor) for AWAKE on
 *   elevated-HR nights, ln(spo2AwakeLightFactor) for AWAKE and LIGHT on desaturations (non-positive factors are
 *   ignored);
 * - −∞ for every state but AWAKE on forced epochs (forcedAwake spans, steps ≥ awakeStepThreshold).
 *
 * The classifier outputs are cached on the night under [logitsKey] (one key per bundle and model).
 */
internal class HybridEmissions(
    night: PreparedNight,
    model: HybridComponents,
    network: DenseNetwork,
    biasDeltas: DoubleArray?,
    stageBias: List<Double>?,
    logitsKey: String,
) {
    /**
     * The emissions of the bundle's main [model] for [night], with the main model's log-bias for the night's mode
     * ([mainModelBias]) and the logits cached under the bundle fingerprint.
     */
    constructor(
        night: PreparedNight,
        model: HybridModel,
        network: DenseNetwork,
        biasDeltas: DoubleArray?,
    ) : this(night, model, network, biasDeltas, mainModelBias(model, night.mode), model.fingerprint())

    private val n = night.grid.sessionEpochs
    private val states = SleepStage.COUNT
    private val forced = night.forcedAwakeMask
    private val base: Array<DoubleArray>

    init {
        val features = HybridFeatureExtractor.cached(night)
        val logits = night.cache.logits(logitsKey) { Array(n) { network.logits(features[it]) } }
        val params = night.params
        val logPriors = DoubleArray(states) { ln(model.classPriors[it]) }
        val priorExponent = params.hybrid.priorExponent
        val bias = DoubleArray(states) { stageBias?.get(it) ?: 0.0 }
        val epsilon = params.hmm.epsilon
        val timeTable =
            model.timePrior.table.map { row -> DoubleArray(states) { ln(maxOf(row[it], epsilon)) - logPriors[it] } }
        val additive = AdditiveTerms(night)
        base =
            Array(n) { k ->
                val row = DoubleArray(states)
                if (night.sessionHasData(k)) {
                    val logPosterior = DenseNetwork.logSoftmax(logits[k], biasDeltas)
                    for (s in 0 until states) {
                        row[s] = params.hybrid.emissionWeight * (logPosterior[s] - priorExponent * logPriors[s]) + bias[s]
                    }
                }
                val minutes = (k + HALF) * night.grid.epochMin
                val bin = floor(minutes / model.timePrior.binMinutes).toInt().coerceIn(0, timeTable.size - 1)
                val factors = additive.at(k)
                for (s in 0 until states) row[s] += params.hybrid.timePriorWeight * timeTable[bin][s] + factors[s]
                row
            }
    }

    /** The log emissions plus the per-state log-prior [offsets], with the forced epochs applied. */
    fun withOffsets(offsets: DoubleArray): Array<DoubleArray> =
        Array(n) { k ->
            DoubleArray(states) { s -> if (forced[k] && s != AWAKE) Double.NEGATIVE_INFINITY else base[k][s] + offsets[s] }
        }

    /** The shared step and SpO2 multipliers ([EpochEvidence.logFactors]) plus the elevated-night AWAKE factor (log). */
    private class AdditiveTerms(
        private val night: PreparedNight,
    ) {
        private val elevated = if (night.statistics.elevated) EpochEvidence.logFactor(night.params.hybrid.elevatedAwakeFactor) else 0.0

        fun at(k: Int): DoubleArray = EpochEvidence.logFactors(night, k, night.params).also { it[SleepStage.AWAKE.index] += elevated }
    }

    internal companion object {
        private val AWAKE = SleepStage.AWAKE.index
        private const val HALF = 0.5

        /**
         * The per-stage log-bias b of a session of [mode] decoded with the bundle's main model (a nap model has its
         * own `stageLogBias`): for a nap the bundle's `stageLogBiasShort` when it has one, otherwise its
         * `stageLogBias`; for a night `stageLogBias`; null (no bias) when the chosen field is absent.
         */
        fun mainModelBias(
            model: HybridModel,
            mode: SessionMode,
        ): List<Double>? =
            when (mode) {
                SessionMode.NAP -> model.stageLogBiasShort ?: model.stageLogBias
                SessionMode.NIGHT -> model.stageLogBias
            }
    }
}

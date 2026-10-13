package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.night.EpochEvidence
import io.github.alien2003.pulsestages.algo.night.PreparedNight
import kotlin.math.floor
import kotlin.math.ln

/**
 * Per-epoch log emissions `[session epoch][state]` of the hybrid decoder:
 * - scaled likelihood `emissionWeight · (log p(s|x) − priorExponent · log prior_s) + b_s` on epochs with heart-rate
 *   data (0 = likelihood 1 without data), where p(s|x) is the classifier posterior with the personal bias deltas
 *   added to its logits, priorExponent = β of `p(s|x) / prior_s^β` (1 = the classic hybrid scaled likelihood) and b
 *   the bundle's optional per-stage log-bias ([stageBias]: `stageLogBiasShort` on sessions shorter than
 *   `hybrid.shortSessionMaxMin` minutes when the bundle has it, otherwise `stageLogBias`; 0 when absent);
 * - `timePriorWeight · (log P(s | bin) − log prior_s)` from the bundle's time table (zeros → ε, bins past the end use
 *   the last row; bin = minutes from the session start to the epoch midpoint / binMinutes);
 * - ln(fewStepsAwakeFactor) for AWAKE with 1 … awakeStepThreshold−1 steps, ln(elevatedAwakeFactor) for AWAKE on
 *   elevated-HR nights, ln(spo2AwakeLightFactor) for AWAKE and LIGHT on desaturations (non-positive factors are
 *   ignored);
 * - −∞ for every state but AWAKE on forced epochs (forcedAwake spans, steps ≥ awakeStepThreshold).
 */
internal class HybridEmissions(
    night: PreparedNight,
    model: HybridModel,
    network: DenseNetwork,
    biasDeltas: DoubleArray?,
) {
    private val n = night.grid.sessionEpochs
    private val states = SleepStage.COUNT
    private val forced = night.forcedAwakeMask
    private val base: Array<DoubleArray>

    init {
        val features = HybridFeatureExtractor.cached(night)
        val logits = night.cache.logits(model.fingerprint()) { Array(n) { network.logits(features[it]) } }
        val params = night.params
        val logPriors = DoubleArray(states) { ln(model.classPriors[it]) }
        val priorExponent = params.hybrid.priorExponent
        val sessionMinutes = (night.input.sessionEndMs - night.input.sessionStartMs) / MS_PER_MINUTE
        val stageBias = stageBias(model, sessionMinutes, params.hybrid.shortSessionMaxMin)
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
                        row[s] = params.hybrid.emissionWeight * (logPosterior[s] - priorExponent * logPriors[s]) + stageBias[s]
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
        private const val MS_PER_MINUTE = 60_000.0

        /**
         * The per-stage log-bias b of a session of [sessionMinutes] minutes: the bundle's `stageLogBiasShort` when
         * the session is shorter than [shortSessionMaxMin] (> 0) and the bundle has one, otherwise its
         * `stageLogBias`; zeros when the chosen field is absent.
         */
        fun stageBias(
            model: HybridModel,
            sessionMinutes: Double,
            shortSessionMaxMin: Double,
        ): DoubleArray {
            val short = shortSessionMaxMin > 0 && sessionMinutes < shortSessionMaxMin
            val bias = if (short) model.stageLogBiasShort ?: model.stageLogBias else model.stageLogBias
            return DoubleArray(SleepStage.COUNT) { bias?.get(it) ?: 0.0 }
        }
    }
}

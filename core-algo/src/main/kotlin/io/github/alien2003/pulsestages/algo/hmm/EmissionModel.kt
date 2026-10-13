package io.github.alien2003.pulsestages.algo.hmm

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.night.EpochEvidence
import io.github.alien2003.pulsestages.algo.night.PreparedNight
import io.github.alien2003.pulsestages.algo.numeric.LogMath
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.EmissionTable
import io.github.alien2003.pulsestages.algo.params.StateEmission
import kotlin.math.PI
import kotlin.math.cos

/**
 * Observations of the Gaussian emission over the session epochs.
 *
 * @property values `[epoch][dim]` in [EmissionDims] order; NaN = dimension unavailable.
 * @property variabilitySdScale σ multiplier of the variability dimension (1, or 1/hrvWeight where RMSSD replaced hrVar).
 * @property hasData epochs with heart-rate data (others get no Gaussian term).
 */
internal class HmmObservations(
    val values: Array<DoubleArray>,
    val variabilitySdScale: DoubleArray,
    val hasData: BooleanArray,
) {
    /** Number of session epochs. */
    val size: Int get() = values.size
}

/**
 * Per-epoch log emissions of the HMM, `[epoch][state]` over the session epochs:
 * - diagonal Gaussians over (hrNorm, hrVar, hrSlope) on epochs with heart-rate data, skipping NaN dimensions (0 for
 *   every state on epochs without data); where an HRV (RMSSD) value is available it replaces hrVar with σ/hrvWeight;
 * - respiratoryRateWeight · log N(respVar; hrVar Gaussian) where the respiratory channel is present;
 * - the cycle prior w·cos(2π(φ − c)) for DEEP and REM where the cycle phase is defined;
 * - ln(fewStepsAwakeFactor) for AWAKE with 1 … awakeStepThreshold−1 steps, ln(spo2AwakeLightFactor) for AWAKE and
 *   LIGHT on desaturations;
 * - the calibration offsets; and −∞ for every state but AWAKE on forced epochs.
 */
internal class EmissionModel(
    night: PreparedNight,
    private val params: AlgoParams,
) {
    private val n = night.grid.sessionEpochs
    private val states = SleepStage.COUNT
    private val awake = SleepStage.AWAKE.index

    /** Gaussian observations. */
    val observations: HmmObservations

    /** Session epochs forced to AWAKE (steps ≥ awakeStepThreshold or inside a forcedAwake span). */
    val forced: BooleanArray

    /** Respiratory variability per session epoch (NaN = absent). */
    private val resp: DoubleArray

    /** Table-independent log terms `[epoch][state]`. */
    private val additive: Array<DoubleArray>

    init {
        val features = night.features
        val grid = night.grid
        val hrvWeight = if (params.features.hrvWeight > 0) params.features.hrvWeight else 1.0
        val hrv = DoubleArray(n) { features.optional.hrvVar[grid.position(it)] }
        val values =
            Array(n) { k ->
                val p = grid.position(k)
                val variability = if (hrv[k].isNaN()) features.heartRate.hrVar[p] else hrv[k]
                doubleArrayOf(features.heartRate.hrNorm[p], variability, features.heartRate.hrSlope[p])
            }
        val scale = DoubleArray(n) { if (hrv[it].isNaN()) 1.0 else 1.0 / hrvWeight }
        observations = HmmObservations(values, scale, night.sessionDataMask)
        forced = night.forcedAwakeMask
        resp = DoubleArray(n) { features.optional.respVar[grid.position(it)] }
        additive = Array(n) { k -> additiveTerms(night, k) }
    }

    /** Cycle prior plus the shared step and SpO2 multipliers ([EpochEvidence.logFactors]). */
    private fun additiveTerms(
        night: PreparedNight,
        k: Int,
    ): DoubleArray {
        val p = night.grid.position(k)
        val hmm = params.hmm
        val terms = EpochEvidence.logFactors(night, k, params)
        val phase = night.features.cycle.phase[p]
        if (hmm.cyclePriorWeight != 0.0 && !phase.isNaN()) {
            terms[SleepStage.DEEP.index] += hmm.cyclePriorWeight * cos(2 * PI * (phase - hmm.cycleDeepPhase))
            terms[SleepStage.REM.index] += hmm.cyclePriorWeight * cos(2 * PI * (phase - hmm.cycleRemPhase))
        }
        return terms
    }

    /** Log emissions `[epoch][state]` for [table] plus the per-state log-prior [offsets]. */
    fun logEmissions(
        table: EmissionTable,
        offsets: DoubleArray,
    ): Array<DoubleArray> {
        val gaussians = Array(states) { table.forState(it) }
        val respWeight = params.features.respiratoryRateWeight
        return Array(n) { k ->
            val row = DoubleArray(states)
            val x = observations.values[k]
            for (s in 0 until states) {
                var v = additive[k][s] + offsets[s]
                if (observations.hasData[k]) v += gaussianLog(gaussians, s, x, observations.variabilitySdScale[k])
                if (!resp[k].isNaN() && respWeight != 0.0) {
                    val g = gaussians[s].hrVar
                    v += respWeight * LogMath.logNormalPdf(resp[k], g.mean, g.sd.coerceAtLeast(MIN_SD))
                }
                row[s] = v
            }
            if (forced[k]) for (s in 0 until states) if (s != awake) row[s] = Double.NEGATIVE_INFINITY
            row
        }
    }

    private fun gaussianLog(
        gaussians: Array<StateEmission>,
        state: Int,
        x: DoubleArray,
        variabilityScale: Double,
    ): Double {
        var sum = 0.0
        for (d in 0 until EmissionDims.COUNT) {
            if (!x[d].isNaN()) {
                val g = gaussians[state].dim(d)
                val sd = if (d == EmissionDims.VARIABILITY) g.sd * variabilityScale else g.sd
                sum += LogMath.logNormalPdf(x[d], g.mean, sd.coerceAtLeast(MIN_SD))
            }
        }
        return sum
    }

    private companion object {
        /** Guards against zero or negative σ in user-edited tables. */
        const val MIN_SD = 1e-6
    }
}

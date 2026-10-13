package io.github.alien2003.pulsestages.algo.personal

import io.github.alien2003.pulsestages.algo.hmm.EmAdaptation
import io.github.alien2003.pulsestages.algo.hmm.EmissionDims
import io.github.alien2003.pulsestages.algo.hmm.EmissionModel
import io.github.alien2003.pulsestages.algo.hmm.HmmMath
import io.github.alien2003.pulsestages.algo.hmm.LogHmm
import io.github.alien2003.pulsestages.algo.hmm.TransitionModel
import io.github.alien2003.pulsestages.algo.hmm.dim
import io.github.alien2003.pulsestages.algo.hmm.emissionTableOf
import io.github.alien2003.pulsestages.algo.hmm.forState
import io.github.alien2003.pulsestages.algo.hmm.isHrNormOrdered
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.EmissionTable
import io.github.alien2003.pulsestages.algo.params.Gaussian
import kotlin.math.sqrt

/**
 * Outcome of [PopulationEmAdapter.adapt].
 *
 * @property model the EM personal model, or null when nothing was adapted (no usable night, no iterations, or the
 *   first iteration already broke the order constraint) — the caller then keeps its current model.
 * @property nightsUsed nights that entered the E-steps.
 * @property nightsSkipped nights without heart rate, below minCoverage or with elevated heart rate.
 * @property iterations accepted EM iterations.
 * @property orderViolated an iteration broke μ_DEEP < μ_LIGHT < μ_REM < μ_AWAKE on hrNorm; adaptation stopped and the
 *   starting table was kept (as in the per-night EM).
 */
data class PopulationEmResult(
    val model: PersonalModel?,
    val nightsUsed: Int,
    val nightsSkipped: Int,
    val iterations: Int,
    val orderViolated: Boolean,
)

/** Posterior-weighted sufficient statistics `[state][dim]`: Σγ, Σγx and Σγx². */
internal class SufficientStatistics {
    val weight = Array(SleepStage.COUNT) { DoubleArray(EmissionDims.COUNT) }
    val sum = Array(SleepStage.COUNT) { DoubleArray(EmissionDims.COUNT) }
    val sumSquares = Array(SleepStage.COUNT) { DoubleArray(EmissionDims.COUNT) }

    /** Adds observation [x] (NaN dimensions skipped) with state posteriors [gamma]. */
    fun add(
        x: DoubleArray,
        gamma: DoubleArray,
    ) {
        for (s in 0 until SleepStage.COUNT) {
            for (d in 0 until EmissionDims.COUNT) {
                if (!x[d].isNaN()) {
                    weight[s][d] += gamma[s]
                    sum[s][d] += gamma[s] * x[d]
                    sumSquares[s][d] += gamma[s] * x[d] * x[d]
                }
            }
        }
    }
}

/** Outcome of [PopulationEmAdapter.iterate]. */
internal class PopulationEmIterations(
    val table: EmissionTable,
    val iterations: Int,
    val orderViolated: Boolean,
)

/**
 * Unsupervised population-level EM over many of the user's nights (section 10 of the specification: every
 * `personal.emEveryNights` nights, adapt μ/σ of the HMM emissions with shrinkage to the global parameters).
 *
 * Nights are prepared with the parameters; nights without heart rate, below `preprocess.minCoverage` or with
 * elevated heart rate are skipped. Starting from the base personal model's table (or the global table
 * `params.hmm.emissions`), `hmm.emIterations` Baum–Welch iterations pool the posteriors of the full HMM (transitions,
 * cycle prior, step rules, forced epochs; no per-night adaptation) over all nights and update every μ and σ with MAP
 * shrinkage to the GLOBAL table with strength τ = `personal.emPriorStrength`:
 * `μ' = (Σγx + τμ0)/(Σγ + τ)`, `σ'² = (Σγ(x − μ')² + τ(σ0² + (μ0 − μ')²))/(Σγ + τ)`, σ' ≥ 0.05. After each iteration
 * the hrNorm order μ_DEEP < μ_LIGHT < μ_REM < μ_AWAKE must hold, otherwise adaptation stops with the starting table.
 * The base model's classifier bias deltas are carried over.
 */
object PopulationEmAdapter {
    /** Adapts the HMM emissions to [nights]. */
    fun adapt(
        nights: List<NightInput>,
        params: AlgoParams,
        base: PersonalModel? = null,
    ): PopulationEmResult {
        val usable = nights.mapNotNull { usableNight(it, params) }
        val skipped = nights.size - usable.size
        val global = params.hmm.emissions
        val start = base?.applyTo(global) ?: global
        if (usable.isEmpty() || params.hmm.emIterations <= 0) return PopulationEmResult(null, usable.size, skipped, 0, false)
        val noOffsets = DoubleArray(SleepStage.COUNT)
        val result =
            iterate(start, global, params.personal.emPriorStrength, params.hmm.emIterations) { table ->
                val statistics = SufficientStatistics()
                for (night in usable) {
                    val gamma = HmmMath.forwardBackward(night.hmm, night.emissions.logEmissions(table, noOffsets)).posteriors
                    val observations = night.emissions.observations
                    for (k in 0 until observations.size) if (observations.hasData[k]) statistics.add(observations.values[k], gamma[k])
                }
                statistics
            }
        val model =
            if (result.iterations == 0) {
                null
            } else {
                PersonalModel(
                    biasDeltas = base?.biasDeltas,
                    baseModelFingerprint = base?.baseModelFingerprint,
                    hmmMeans = StageDimValues.meansOf(result.table),
                    hmmSds = StageDimValues.sdsOf(result.table),
                    provenance = PersonalProvenance(kind = PersonalModelKind.EM, nightsUsed = usable.size),
                )
            }
        return PopulationEmResult(model, usable.size, skipped, result.iterations, result.orderViolated)
    }

    /**
     * Runs up to [iterations] EM iterations from [start]; [statistics] is the E-step for a table. Shrinkage goes to
     * [global] with strength [priorStrength]. Stops with [start] on an order violation.
     */
    internal fun iterate(
        start: EmissionTable,
        global: EmissionTable,
        priorStrength: Double,
        iterations: Int,
        statistics: (EmissionTable) -> SufficientStatistics,
    ): PopulationEmIterations {
        var table = start
        for (iteration in 0 until iterations) {
            val updated = mapUpdate(global, statistics(table), priorStrength)
            if (!updated.isHrNormOrdered()) return PopulationEmIterations(start, iteration, orderViolated = true)
            table = updated
        }
        return PopulationEmIterations(table, iterations.coerceAtLeast(0), orderViolated = false)
    }

    /** One M-step with MAP shrinkage to [global] (see the object KDoc). */
    internal fun mapUpdate(
        global: EmissionTable,
        statistics: SufficientStatistics,
        priorStrength: Double,
    ): EmissionTable {
        val tau = priorStrength.coerceAtLeast(0.0)
        return emissionTableOf { state, dim ->
            val g0 = global.forState(state).dim(dim)
            val w = statistics.weight[state][dim]
            if (w + tau <= 0.0) {
                g0
            } else {
                val x = statistics.sum[state][dim]
                val xx = statistics.sumSquares[state][dim]
                val mean = (x + tau * g0.mean) / (w + tau)
                val spread = (xx - 2 * mean * x + mean * mean * w).coerceAtLeast(0.0)
                val variance = (spread + tau * (g0.sd * g0.sd + (g0.mean - mean) * (g0.mean - mean))) / (w + tau)
                Gaussian(mean, sqrt(variance).coerceAtLeast(EmAdaptation.MIN_SD))
            }
        }
    }

    private class UsableNight(
        val emissions: EmissionModel,
        val hmm: LogHmm,
    )

    private fun usableNight(
        input: NightInput,
        params: AlgoParams,
    ): UsableNight? {
        val night = NightPreparer.prepare(input, params)
        val usable =
            night.preprocessed.sampleCount > 0 &&
                night.coverage >= params.preprocess.minCoverage &&
                !night.statistics.elevated
        return if (usable) UsableNight(EmissionModel(night, params), TransitionModel(params.hmm, night.grid).logHmm()) else null
    }
}

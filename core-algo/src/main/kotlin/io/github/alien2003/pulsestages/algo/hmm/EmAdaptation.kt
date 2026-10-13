package io.github.alien2003.pulsestages.algo.hmm

import io.github.alien2003.pulsestages.algo.params.EmissionTable
import io.github.alien2003.pulsestages.algo.params.Gaussian
import kotlin.math.sqrt

/**
 * Outcome of [EmAdaptation.adapt].
 *
 * @property table the adapted table (the starting table after a rollback).
 * @property orderViolated an iteration broke μ_DEEP < μ_LIGHT < μ_REM < μ_AWAKE on hrNorm and was rolled back.
 * @property iterations completed (accepted) iterations.
 */
internal class EmResult(
    val table: EmissionTable,
    val orderViolated: Boolean,
    val iterations: Int,
)

/**
 * Per-night Baum–Welch adaptation of the emission means and standard deviations with MAP shrinkage towards the
 * starting table (transitions are not updated).
 */
internal object EmAdaptation {
    /** Lower bound of an adapted σ. */
    const val MIN_SD = 0.05

    /**
     * Runs up to [iterations] EM iterations starting from [start]. [posteriors] returns the state posteriors
     * `[epoch][state]` of the full model with a given table. After each iteration the hrNorm order is checked; on a
     * violation the starting table is returned with [EmResult.orderViolated] and adaptation stops.
     */
    fun adapt(
        start: EmissionTable,
        observations: HmmObservations,
        iterations: Int,
        priorStrength: Double,
        posteriors: (EmissionTable) -> Array<DoubleArray>,
    ): EmResult {
        var table = start
        for (iteration in 0 until iterations) {
            val updated = mapUpdate(start, observations, posteriors(table), priorStrength)
            if (!updated.isHrNormOrdered()) return EmResult(start, orderViolated = true, iterations = iteration)
            table = updated
        }
        return EmResult(table, orderViolated = false, iterations = iterations.coerceAtLeast(0))
    }

    /**
     * One M-step with MAP shrinkage to [prior] (strength τ = [priorStrength] epoch-equivalents):
     * `μ' = (Σγx + τμ0)/(Σγ + τ)`, `σ'² = (Σγ(x − μ')² + τ(σ0² + (μ0 − μ')²))/(Σγ + τ)`, `σ' ≥ 0.05`.
     * Only epochs with heart-rate data and a non-NaN value of the dimension contribute.
     */
    fun mapUpdate(
        prior: EmissionTable,
        observations: HmmObservations,
        gamma: Array<DoubleArray>,
        priorStrength: Double,
    ): EmissionTable {
        val tau = priorStrength.coerceAtLeast(0.0)
        return emissionTableOf { state, dim ->
            val g0 = prior.forState(state).dim(dim)
            var weight = 0.0
            var weighted = 0.0
            for (k in 0 until observations.size) {
                val x = observations.values[k][dim]
                if (observations.hasData[k] && !x.isNaN()) {
                    weight += gamma[k][state]
                    weighted += gamma[k][state] * x
                }
            }
            if (weight + tau <= 0.0) {
                g0
            } else {
                val mean = (weighted + tau * g0.mean) / (weight + tau)
                var spread = 0.0
                for (k in 0 until observations.size) {
                    val x = observations.values[k][dim]
                    if (observations.hasData[k] && !x.isNaN()) spread += gamma[k][state] * (x - mean) * (x - mean)
                }
                val variance = (spread + tau * (g0.sd * g0.sd + (g0.mean - mean) * (g0.mean - mean))) / (weight + tau)
                Gaussian(mean, sqrt(variance).coerceAtLeast(MIN_SD))
            }
        }
    }
}

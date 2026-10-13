package io.github.alien2003.pulsestages.algo.hmm

import io.github.alien2003.pulsestages.algo.numeric.LogMath
import kotlin.math.exp

/**
 * A hidden Markov model in log space with time-dependent transitions.
 *
 * @property logInitial log initial distribution, one entry per state.
 * @property logTransitions `logTransitions[k]` is the row-major `S×S` log transition matrix from epoch `k−1` to
 *   epoch `k` (entry `i·S + j` = log P(j | i)); entry 0 is unused. Its size equals the number of epochs.
 */
internal class LogHmm(
    val logInitial: DoubleArray,
    val logTransitions: Array<DoubleArray>,
) {
    /** Number of hidden states. */
    val states: Int get() = logInitial.size
}

/** Result of [HmmMath.forwardBackward]. */
internal class ForwardBackwardResult(
    val posteriors: Array<DoubleArray>,
    val logLikelihood: Double,
)

/** Viterbi decoding and forward–backward posteriors in log space. */
internal object HmmMath {
    /**
     * Most probable state path for the per-epoch log emissions [logEmission] (`[epoch][state]`, −∞ forbids a
     * state). Ties are broken towards the lower state index.
     */
    fun viterbi(
        model: LogHmm,
        logEmission: Array<DoubleArray>,
    ): IntArray {
        val n = logEmission.size
        val s = model.states
        if (n == 0) return IntArray(0)
        var delta = DoubleArray(s) { model.logInitial[it] + logEmission[0][it] }
        val back = Array(n) { IntArray(s) }
        for (k in 1 until n) {
            val a = model.logTransitions[k]
            val next = DoubleArray(s)
            for (j in 0 until s) {
                val arg = bestPredecessor(delta, a, j)
                next[j] = delta[arg] + a[arg * s + j] + logEmission[k][j]
                back[k][j] = arg
            }
            delta = next
        }
        val path = IntArray(n)
        path[n - 1] = argMax(delta)
        for (k in n - 1 downTo 1) path[k - 1] = back[k][path[k]]
        return path
    }

    /** The state i maximizing `delta[i] + log P(j | i)` (lowest index on ties). */
    private fun bestPredecessor(
        delta: DoubleArray,
        logTransition: DoubleArray,
        j: Int,
    ): Int {
        val s = delta.size
        var arg = 0
        var best = Double.NEGATIVE_INFINITY
        for (i in 0 until s) {
            val v = delta[i] + logTransition[i * s + j]
            if (v > best) {
                best = v
                arg = i
            }
        }
        return arg
    }

    /** Posterior state marginals (each row sums to 1) and the log likelihood of the observations. */
    fun forwardBackward(
        model: LogHmm,
        logEmission: Array<DoubleArray>,
    ): ForwardBackwardResult {
        val n = logEmission.size
        val s = model.states
        if (n == 0) return ForwardBackwardResult(emptyArray(), 0.0)
        val alpha = Array(n) { DoubleArray(s) }
        val beta = Array(n) { DoubleArray(s) }
        val scratch = DoubleArray(s)
        for (j in 0 until s) alpha[0][j] = model.logInitial[j] + logEmission[0][j]
        for (k in 1 until n) {
            val a = model.logTransitions[k]
            for (j in 0 until s) {
                for (i in 0 until s) scratch[i] = alpha[k - 1][i] + a[i * s + j]
                alpha[k][j] = logEmission[k][j] + LogMath.logSumExp(scratch)
            }
        }
        for (k in n - 2 downTo 0) {
            val a = model.logTransitions[k + 1]
            for (i in 0 until s) {
                for (j in 0 until s) scratch[j] = a[i * s + j] + logEmission[k + 1][j] + beta[k + 1][j]
                beta[k][i] = LogMath.logSumExp(scratch)
            }
        }
        val logLikelihood = LogMath.logSumExp(alpha[n - 1])
        val posteriors =
            Array(n) { k ->
                val row = DoubleArray(s) { exp(alpha[k][it] + beta[k][it] - logLikelihood) }
                normalize(row)
            }
        return ForwardBackwardResult(posteriors, logLikelihood)
    }

    /** Index of the largest value (first on ties). */
    fun argMax(values: DoubleArray): Int {
        var arg = 0
        for (i in 1 until values.size) if (values[i] > values[arg]) arg = i
        return arg
    }

    /** Scales [row] in place to sum 1 (uniform if the sum is not positive) and returns it. */
    fun normalize(row: DoubleArray): DoubleArray {
        val sum = row.sum()
        if (sum > 0.0 && !sum.isNaN() && !sum.isInfinite()) {
            for (i in row.indices) row[i] /= sum
        } else {
            row.fill(1.0 / row.size)
        }
        return row
    }
}

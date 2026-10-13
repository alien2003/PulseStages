package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.hmm.ForwardBackwardResult
import kotlin.math.exp
import kotlin.math.ln

/**
 * An explicit-duration hidden semi-Markov model in log space over S states.
 *
 * A path is a sequence of bouts (segments); a bout of state j and length d (1 ≤ d ≤ [maxDuration] epochs) scores
 * `log duration + Σ log emission`, consecutive bouts i → j score `logTransitions[i][j]` (−∞ forbids, including i = j),
 * and the first bout scores `logInitial[j]`. Four duration tables `[state][d − 1]` distinguish censoring: [logDuration]
 * for interior bouts, [logDurationFirst] for the first bout (left-censored by the session start), [logDurationLast]
 * for the last bout (right-censored by the session end) and [logDurationOnly] for a single bout covering the whole
 * sequence.
 */
internal class HsmmModel(
    val logInitial: DoubleArray,
    val logTransitions: Array<DoubleArray>,
    val logDuration: Array<DoubleArray>,
    val logDurationFirst: Array<DoubleArray>,
    val logDurationLast: Array<DoubleArray>,
    val logDurationOnly: Array<DoubleArray>,
) {
    /** Number of states. */
    val states: Int = logInitial.size

    /** Maximum bout length in epochs. */
    val maxDuration: Int = logDuration.firstOrNull()?.size ?: 0

    init {
        require(states > 0) { "an HSMM needs at least one state" }
        require(maxDuration > 0) { "maxDuration must be positive" }
        require(logTransitions.size == states && logTransitions.all { it.size == states }) { "transitions must be S×S" }
        for (table in listOf(logDuration, logDurationFirst, logDurationLast, logDurationOnly)) {
            require(table.size == states && table.all { it.size == maxDuration }) { "duration tables must be S×maxDuration" }
        }
    }

    /** Log duration score of a bout of [state] lasting [d] epochs that is the [first] and/or the [last] bout. */
    fun logDuration(
        state: Int,
        d: Int,
        first: Boolean,
        last: Boolean,
    ): Double {
        val table =
            when {
                first && last -> logDurationOnly
                first -> logDurationFirst
                last -> logDurationLast
                else -> logDuration
            }
        return table[state][d - 1]
    }
}

/**
 * Sums of log emissions over segments in O(1) via per-state prefix sums. −∞ entries (forbidden states) are counted
 * separately so that a segment containing one is −∞ without producing NaN; NaN entries are treated as −∞.
 */
internal class SegmentSums(
    logEmission: Array<DoubleArray>,
    states: Int,
) {
    private val prefix = Array(states) { DoubleArray(logEmission.size + 1) }
    private val forbidden = Array(states) { IntArray(logEmission.size + 1) }

    init {
        for (s in 0 until states) {
            val p = prefix[s]
            val f = forbidden[s]
            for (t in logEmission.indices) {
                val v = logEmission[t][s]
                val blocked = v.isNaN() || v == Double.NEGATIVE_INFINITY
                p[t + 1] = if (blocked) p[t] else p[t] + v
                f[t + 1] = if (blocked) f[t] + 1 else f[t]
            }
        }
    }

    /** `Σ_{u = from..to} logEmission[u][state]` (inclusive bounds). */
    fun sum(
        state: Int,
        from: Int,
        to: Int,
    ): Double =
        if (forbidden[state][to + 1] > forbidden[state][from]) {
            Double.NEGATIVE_INFINITY
        } else {
            prefix[state][to + 1] - prefix[state][from]
        }
}

/**
 * The most probable segmentation found by [HsmmMath.viterbi].
 *
 * @property states state per epoch.
 * @property logScore log probability of the best segmentation; −∞ when no segmentation is feasible (then [states]
 *   is all zeros and must not be used).
 */
internal class HsmmPath(
    val states: IntArray,
    val logScore: Double,
)

/**
 * Viterbi over segments and explicit-duration forward–backward for [HsmmModel], both in log space using prefix sums
 * of the emissions: O(N·S·D + N·S²) time for N epochs, S states and maximum duration D.
 */
internal object HsmmMath {
    private val NEG_INF = Double.NEGATIVE_INFINITY

    /** Terms more than this far (in log units) below the maximum are dropped from log-sum-exp (relative error < 1e-21). */
    private const val LOG_SKIP = 50.0

    /** Most probable segmentation of [logEmission] (`[epoch][state]`); on ties the shorter bout and lower state win. */
    fun viterbi(
        model: HsmmModel,
        logEmission: Array<DoubleArray>,
    ): HsmmPath {
        val n = logEmission.size
        if (n == 0) return HsmmPath(IntArray(0), 0.0)
        return ViterbiPass(model, SegmentSums(logEmission, model.states), n).run()
    }

    /**
     * Posterior state marginals `[epoch][state]` (rows sum to 1) and the log likelihood (log of the sum over all
     * segmentations). An infeasible model gives a log likelihood of −∞ and uniform rows, which callers must not use.
     */
    fun forwardBackward(
        model: HsmmModel,
        logEmission: Array<DoubleArray>,
    ): ForwardBackwardResult {
        val n = logEmission.size
        if (n == 0) return ForwardBackwardResult(emptyArray(), 0.0)
        val s = model.states
        val pass = ForwardBackwardPass(model, SegmentSums(logEmission, s), n)
        pass.forward()
        val logZ = logSumExp(pass.forward[n - 1], s)
        val posteriors =
            if (logZ == NEG_INF) {
                Array(n) { DoubleArray(s) { 1.0 / s } }
            } else {
                pass.backward()
                pass.posteriors(logZ)
            }
        return ForwardBackwardResult(posteriors, logZ)
    }

    /** Max-product recursion over bouts with back pointers. */
    private class ViterbiPass(
        val model: HsmmModel,
        val sums: SegmentSums,
        val n: Int,
    ) {
        private val s = model.states

        /** `best[t][j]`: best log score of segmentations of `0..t` whose last bout (state j) ends at t. */
        private val best = Array(n) { DoubleArray(s) { NEG_INF } }
        private val bestLength = Array(n) { IntArray(s) }

        /** `enter[a][j]`: best log score of starting a bout of j at a ≥ 1, with its predecessor state in [enterFrom]. */
        private val enter = Array(n) { DoubleArray(s) { NEG_INF } }
        private val enterFrom = Array(n) { IntArray(s) }

        fun run(): HsmmPath {
            for (t in 0 until n) {
                if (t > 0) for (j in 0 until s) bestEntry(t, j)
                for (j in 0 until s) bestBoutEndingAt(t, j)
            }
            var last = 0
            for (j in 1 until s) if (best[n - 1][j] > best[n - 1][last]) last = j
            val score = best[n - 1][last]
            return if (score == NEG_INF) HsmmPath(IntArray(n), NEG_INF) else HsmmPath(backtrack(last), score)
        }

        private fun bestEntry(
            a: Int,
            j: Int,
        ) {
            var arg = 0
            var value = NEG_INF
            for (i in 0 until s) {
                val v = best[a - 1][i] + model.logTransitions[i][j]
                if (v > value) {
                    value = v
                    arg = i
                }
            }
            enter[a][j] = value
            enterFrom[a][j] = arg
        }

        private fun bestBoutEndingAt(
            t: Int,
            j: Int,
        ) {
            var value = NEG_INF
            var length = 1
            val last = t == n - 1
            for (d in 1..minOf(model.maxDuration, t + 1)) {
                val a = t - d + 1
                val start = if (a == 0) model.logInitial[j] else enter[a][j]
                val v = start + model.logDuration(j, d, a == 0, last) + sums.sum(j, a, t)
                if (v > value) {
                    value = v
                    length = d
                }
            }
            best[t][j] = value
            bestLength[t][j] = length
        }

        private fun backtrack(lastState: Int): IntArray {
            val path = IntArray(n)
            var t = n - 1
            var state = lastState
            while (t >= 0) {
                val from = t - bestLength[t][state] + 1
                for (u in from..t) path[u] = state
                if (from > 0) state = enterFrom[from][state]
                t = from - 1
            }
            return path
        }
    }

    /** Sum-product recursions over bouts. */
    private class ForwardBackwardPass(
        val model: HsmmModel,
        val sums: SegmentSums,
        val n: Int,
    ) {
        private val s = model.states
        private val maxD = model.maxDuration

        /** `forward[t][j]`: log Σ over segmentations of `0..t` whose last bout (state j) ends at t. */
        val forward = Array(n) { DoubleArray(s) }

        /** `enter[a][j]`: log Σ of starting a bout of j at a ≥ 1 (after any bout ending at a − 1). */
        private val enter = Array(n) { DoubleArray(s) { NEG_INF } }

        /** `backward[t][j]`: log Σ over segmentations of `t+1..n−1` given that a bout of j ends at t. */
        private val backward = Array(n) { DoubleArray(s) }

        /** `next[a][j]`: log Σ over bouts of j starting at a ≥ 1, times everything after them. */
        private val next = Array(n) { DoubleArray(s) { NEG_INF } }

        private val scratch = DoubleArray(maxOf(s, maxD))

        private fun start(
            a: Int,
            j: Int,
        ): Double = if (a == 0) model.logInitial[j] else enter[a][j]

        fun forward() {
            for (t in 0 until n) {
                if (t > 0) for (j in 0 until s) enter[t][j] = entering(t, j)
                for (j in 0 until s) forward[t][j] = boutsEndingAt(t, j)
            }
        }

        private fun entering(
            a: Int,
            j: Int,
        ): Double {
            for (i in 0 until s) scratch[i] = forward[a - 1][i] + model.logTransitions[i][j]
            return logSumExp(scratch, s)
        }

        private fun boutsEndingAt(
            t: Int,
            j: Int,
        ): Double {
            val count = minOf(maxD, t + 1)
            val last = t == n - 1
            for (d in 1..count) {
                val a = t - d + 1
                scratch[d - 1] = start(a, j) + model.logDuration(j, d, a == 0, last) + sums.sum(j, a, t)
            }
            return logSumExp(scratch, count)
        }

        fun backward() {
            for (t in n - 1 downTo 0) {
                if (t < n - 1) for (i in 0 until s) backward[t][i] = leaving(t, i)
                if (t > 0) for (j in 0 until s) next[t][j] = boutsStartingAt(t, j)
            }
        }

        private fun leaving(
            t: Int,
            i: Int,
        ): Double {
            for (j in 0 until s) scratch[j] = model.logTransitions[i][j] + next[t + 1][j]
            return logSumExp(scratch, s)
        }

        private fun boutsStartingAt(
            a: Int,
            j: Int,
        ): Double {
            val count = minOf(maxD, n - a)
            for (d in 1..count) {
                val b = a + d - 1
                scratch[d - 1] = model.logDuration(j, d, false, b == n - 1) + sums.sum(j, a, b) + backward[b][j]
            }
            return logSumExp(scratch, count)
        }

        /** Marginals from the bout posteriors, accumulated with a difference array over each bout's span. */
        fun posteriors(logZ: Double): Array<DoubleArray> {
            val diff = Array(n + 1) { DoubleArray(s) }
            for (b in 0 until n) {
                for (j in 0 until s) addBoutsEndingAt(b, j, logZ, diff)
            }
            val running = DoubleArray(s)
            return Array(n) { t ->
                for (j in 0 until s) running[j] += diff[t][j]
                normalized(DoubleArray(s) { running[it].coerceAtLeast(0.0) })
            }
        }

        private fun normalized(row: DoubleArray): DoubleArray {
            val total = row.sum()
            if (total > 0) {
                for (j in row.indices) row[j] /= total
            } else {
                row.fill(1.0 / row.size)
            }
            return row
        }

        private fun addBoutsEndingAt(
            b: Int,
            j: Int,
            logZ: Double,
            diff: Array<DoubleArray>,
        ) {
            val tail = backward[b][j] - logZ
            val last = b == n - 1
            for (d in 1..minOf(maxD, b + 1)) {
                val a = b - d + 1
                val logW = start(a, j) + model.logDuration(j, d, a == 0, last) + sums.sum(j, a, b) + tail
                if (logW > -LOG_SKIP) {
                    val w = exp(logW)
                    diff[a][j] += w
                    diff[b + 1][j] -= w
                }
            }
        }
    }

    /** `log Σ exp(values[0 until count])`; −∞ when all are −∞. */
    private fun logSumExp(
        values: DoubleArray,
        count: Int,
    ): Double {
        var max = NEG_INF
        for (i in 0 until count) if (values[i] > max) max = values[i]
        if (max == NEG_INF) return max
        var sum = 0.0
        for (i in 0 until count) {
            val v = values[i] - max
            if (v > -LOG_SKIP) sum += exp(v)
        }
        return max + ln(sum)
    }
}

package io.github.alien2003.pulsestages.algo.hmm

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.numeric.LogMath
import io.github.alien2003.pulsestages.algo.params.HmmParams
import io.github.alien2003.pulsestages.algo.preprocess.EpochGrid

/**
 * Time-dependent transition model of the HMM (section 3 of the specification), states in [SleepStage.index] order.
 *
 * Transition into epoch k: self-transition `1 − epochMin/meanBout` clamped to `[0.5, 0.9999]`; the remainder is
 * shared by the normalized [io.github.alien2003.pulsestages.algo.params.TransitionWeights] (non-positive weights →
 * ε); in the LIGHT row, LIGHT→DEEP × `max(floor, a − b·t)` and LIGHT→REM × `(c + d·t)` with the off-diagonal
 * renormalized to the same remainder; during the first remLockoutMin minutes every transition into REM (and the
 * REM initial probability) × remLockoutFactor, the freed mass going to the other targets proportionally.
 */
internal class TransitionModel(
    private val params: HmmParams,
    private val grid: EpochGrid,
) {
    private val states = SleepStage.COUNT
    private val awake = SleepStage.AWAKE.index
    private val light = SleepStage.LIGHT.index
    private val deep = SleepStage.DEEP.index
    private val rem = SleepStage.REM.index

    private val selfProbabilities: DoubleArray =
        with(params.meanBouts) { doubleArrayOf(awakeMin, lightMin, deepMin, remMin) }
            .map { (1 - grid.epochMin / it).coerceIn(MIN_SELF, MAX_SELF) }
            .toDoubleArray()

    /** Normalized off-diagonal shares per row (diagonal 0). */
    private val baseShares: Array<DoubleArray> =
        with(params.transitions) {
            arrayOf(
                doubleArrayOf(0.0, awakeToLight, awakeToDeep, awakeToRem),
                doubleArrayOf(lightToAwake, 0.0, lightToDeep, lightToRem),
                doubleArrayOf(deepToAwake, deepToLight, 0.0, deepToRem),
                doubleArrayOf(remToAwake, remToLight, remToDeep, 0.0),
            )
        }.mapIndexed { i, row -> shares(row, i) }.toTypedArray()

    private val lockoutMs: Double = params.remLockoutMin * MS_PER_MINUTE

    private fun shares(
        weights: DoubleArray,
        self: Int,
    ): DoubleArray {
        val w =
            DoubleArray(states) { j ->
                if (j == self) {
                    0.0
                } else if (weights[j] > 0) {
                    weights[j]
                } else {
                    params.epsilon
                }
            }
        val sum = w.sum()
        return DoubleArray(states) { w[it] / sum }
    }

    private fun lockedOut(k: Int): Boolean = params.remLockoutMin > 0 && k * grid.epochMs < lockoutMs

    /** Probability matrix `[from][to]` of the transition from epoch k−1 into session epoch [k] (k ≥ 1). */
    fun matrix(k: Int): Array<DoubleArray> {
        val t = ((k + HALF) / grid.sessionEpochs).coerceIn(0.0, 1.0)
        val m =
            Array(states) { i ->
                DoubleArray(states) { j -> if (i == j) selfProbabilities[i] else (1 - selfProbabilities[i]) * baseShares[i][j] }
            }
        val lightShares = baseShares[light].copyOf()
        lightShares[deep] *= maxOf(params.deepTimeFloor, params.deepTimeIntercept - params.deepTimeSlope * t)
        lightShares[rem] *= params.remTimeIntercept + params.remTimeSlope * t
        val adjusted = shares(lightShares, light)
        for (j in 0 until states) if (j != light) m[light][j] = (1 - selfProbabilities[light]) * adjusted[j]
        if (lockedOut(k)) m.forEach { applyLockout(it) }
        return m
    }

    /** Initial distribution: AWAKE/LIGHT from the parameters, DEEP/REM ε; REM × lockout factor during the lockout. */
    fun initial(): DoubleArray {
        val raw = DoubleArray(states) { params.epsilon }
        raw[awake] = if (params.initialAwake > 0) params.initialAwake else params.epsilon
        raw[light] = if (params.initialLight > 0) params.initialLight else params.epsilon
        val p = HmmMath.normalize(raw)
        if (lockedOut(0)) applyLockout(p)
        return p
    }

    private fun applyLockout(row: DoubleArray) {
        val before = row[rem]
        val after = before * params.remLockoutFactor.coerceIn(0.0, 1.0)
        val othersMass = 1 - before
        if (othersMass <= 0) return
        val scale = (1 - after) / othersMass
        for (j in 0 until states) row[j] = if (j == rem) after else row[j] * scale
    }

    /** The log-space HMM over the session epochs. */
    fun logHmm(): LogHmm {
        val transitions =
            Array(grid.sessionEpochs) { k ->
                if (k == 0) {
                    DoubleArray(0)
                } else {
                    val m = matrix(k)
                    DoubleArray(states * states) { LogMath.safeLog(m[it / states][it % states]) }
                }
            }
        return LogHmm(initial().map { LogMath.safeLog(it) }.toDoubleArray(), transitions)
    }

    private companion object {
        const val MIN_SELF = 0.5
        const val MAX_SELF = 0.9999
        const val HALF = 0.5
        const val MS_PER_MINUTE = 60_000.0
    }
}

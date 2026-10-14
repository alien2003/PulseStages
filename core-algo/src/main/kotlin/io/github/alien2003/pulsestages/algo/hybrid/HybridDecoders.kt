package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.hmm.HmmMath
import io.github.alien2003.pulsestages.algo.hmm.LogHmm
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.numeric.LogMath
import io.github.alien2003.pulsestages.algo.numeric.SpecialFunctions
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import kotlin.math.floor
import kotlin.math.ln

/**
 * Log duration tables of one stage on the epoch grid, index d − 1 for d = 1..D (D = Dmax).
 *
 * The training scripts fit the gamma to bout lengths on the lattice d·m (d epochs of m minutes), so a bout of d
 * epochs is the gamma mass of `((d − ½)·m, (d + ½)·m]` (midpoint bins; d = 1 starts at 0 and d = D takes the tail).
 *
 * @property pmf `log P(len = d)`: `F((d + ½)·m) − F((d − ½)·m)` for 1 < d < D, `F(1.5·m)` for d = 1 and the tail mass
 *   `1 − F((D − ½)·m)` at D (F = gamma CDF); an interior bout.
 * @property survival `log S(d) = log P(len ≥ d)` (1 at d = 1, `1 − F((d − ½)·m)` above); the last bout, which is
 *   right-censored by the session end.
 * @property first `log S(d) / Σ_{d'} S(d')`: the remaining length of a bout already under way at the session start
 *   (left-censored). Normalized so that the first bout's stage keeps exactly the bundle's initial distribution
 *   (`initialStage` is estimated as the stage at the first labelled epoch); the raw survival would weight stage j by
 *   its mean bout length E[D_j].
 * @property only `log P(R ≥ d)` with R distributed as [first]: a single bout that covers the whole session (censored
 *   at both ends).
 */
internal class DurationTable(
    val pmf: DoubleArray,
    val survival: DoubleArray,
    val first: DoubleArray,
    val only: DoubleArray,
)

/** Builds the decoders of the hybrid estimator from the model's components (main or nap model) and the parameters. */
internal object HybridDecoders {
    /** Probabilities are floored here so that every duration stays feasible (log ≈ −690). */
    private const val MIN_PROBABILITY = 1e-300

    /** Upper clamp of the geometric self-transition (as in the HMM). */
    private const val MAX_SELF = 0.9999

    private const val HALF = 0.5

    /** `Dmax = ⌊maxBoutMin / epochMin⌋` epochs, at least one. */
    fun maxDurationEpochs(
        params: AlgoParams,
        epochMin: Double,
    ): Int = floor(params.hybrid.maxBoutMin / epochMin).toInt().coerceAtLeast(1)

    /** Discretized gamma bout-length distribution (shape/scale in minutes) on epochs of [epochMin] minutes. */
    fun durationTable(
        gamma: GammaDuration,
        epochMin: Double,
        maxDuration: Int,
    ): DurationTable {
        val k = gamma.shape
        // Bin edges: edge[0] = 0 and edge[i] = (i + ½)·m for i = 1..D−1; bin d − 1 is (edge[d − 1], edge[d]].
        val edges = DoubleArray(maxDuration) { if (it == 0) 0.0 else (it + HALF) * epochMin / gamma.scale }
        val lower = DoubleArray(maxDuration) { SpecialFunctions.regularizedGammaP(k, edges[it]) }
        val upper = DoubleArray(maxDuration) { if (it == 0) 1.0 else SpecialFunctions.regularizedGammaQ(k, edges[it]) }
        val probability =
            DoubleArray(maxDuration) { i ->
                when {
                    i == maxDuration - 1 -> upper[i]
                    upper[i] < HALF -> upper[i] - upper[i + 1]
                    else -> lower[i + 1] - lower[i]
                }.coerceAtLeast(MIN_PROBABILITY)
            }
        // S(d) = P(len ≥ d) = mass above edge[d − 1].
        val survival = DoubleArray(maxDuration) { upper[it].coerceIn(MIN_PROBABILITY, 1.0) }
        val meanLength = survival.sum()
        val residual = DoubleArray(maxDuration) { survival[it] / meanLength }
        val residualTail = DoubleArray(maxDuration)
        var tail = 0.0
        for (i in maxDuration - 1 downTo 0) {
            tail += residual[i]
            residualTail[i] = tail
        }
        return DurationTable(
            pmf = logOf(probability),
            survival = logOf(survival),
            first = logOf(residual),
            only = DoubleArray(maxDuration) { ln(residualTail[it].coerceIn(MIN_PROBABILITY, 1.0)) },
        )
    }

    /**
     * The explicit-duration HSMM: gamma durations ([DurationTable]: interior bouts pmf, the left-censored first bout
     * [DurationTable.first], the right-censored last bout [DurationTable.survival], a single bout [DurationTable.only]),
     * bout transitions from the bundle with off-diagonal zeros replaced by ε = `hmm.epsilon` (rows renormalized) and
     * the diagonal as listed — or, when [relaxSelfTransitions], also floored at ε (used only when the strict model has
     * no feasible segmentation, e.g. a forced-awake span longer than Dmax) — and the initial bout distribution with
     * zeros → ε. With uninformative emissions the segmentations' probabilities sum to 1 and the state marginal of the
     * first epoch is the initial distribution.
     */
    fun hsmm(
        model: HybridComponents,
        params: AlgoParams,
        epochMin: Double,
        relaxSelfTransitions: Boolean,
    ): HsmmModel {
        val maxDuration = maxDurationEpochs(params, epochMin)
        val tables = model.durations.toList().map { durationTable(it, epochMin, maxDuration) }
        val epsilon = params.hmm.epsilon
        val transitions =
            Array(SleepStage.COUNT) { i ->
                val row =
                    DoubleArray(SleepStage.COUNT) { j ->
                        val p = model.boutTransitions[i][j]
                        if (i != j || relaxSelfTransitions) maxOf(p, epsilon) else p
                    }
                logOf(HmmMath.normalize(row))
            }
        return HsmmModel(
            logInitial = initial(model, epsilon),
            logTransitions = transitions,
            logDuration = Array(SleepStage.COUNT) { tables[it].pmf },
            logDurationFirst = Array(SleepStage.COUNT) { tables[it].first },
            logDurationLast = Array(SleepStage.COUNT) { tables[it].survival },
            logDurationOnly = Array(SleepStage.COUNT) { tables[it].only },
        )
    }

    /**
     * The geometric (plain HMM) decoder over [epochs] epochs: self-transition `1 − epochMin/mean` with the gamma mean
     * (shape·scale minutes), clamped to `[ε, 0.9999]`; the remainder shared by the bundle's bout transitions without
     * the diagonal (zeros → ε, renormalized); the same initial distribution as [hsmm].
     */
    fun geometric(
        model: HybridComponents,
        params: AlgoParams,
        epochMin: Double,
        epochs: Int,
    ): LogHmm {
        val epsilon = params.hmm.epsilon
        val s = SleepStage.COUNT
        val means = model.durations.toList().map { it.shape * it.scale }
        val flat = DoubleArray(s * s)
        for (i in 0 until s) {
            val self = (1 - epochMin / means[i]).coerceIn(epsilon, MAX_SELF)
            val shares = HmmMath.normalize(DoubleArray(s) { j -> if (j == i) 0.0 else maxOf(model.boutTransitions[i][j], epsilon) })
            for (j in 0 until s) flat[i * s + j] = LogMath.safeLog(if (j == i) self else (1 - self) * shares[j])
        }
        return LogHmm(initial(model, epsilon), Array(epochs) { if (it == 0) DoubleArray(0) else flat })
    }

    private fun initial(
        model: HybridComponents,
        epsilon: Double,
    ): DoubleArray = logOf(HmmMath.normalize(DoubleArray(SleepStage.COUNT) { maxOf(model.initialStage[it], epsilon) }))

    private fun logOf(probabilities: DoubleArray): DoubleArray = DoubleArray(probabilities.size) { LogMath.safeLog(probabilities[it]) }
}

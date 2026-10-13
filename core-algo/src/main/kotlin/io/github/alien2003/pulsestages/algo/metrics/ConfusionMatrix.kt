package io.github.alien2003.pulsestages.algo.metrics

import io.github.alien2003.pulsestages.algo.model.SleepStage

/**
 * A 4×4 confusion matrix over the scored stages: rows = truth, columns = prediction, both in [SleepStage.index]
 * order. Immutable.
 *
 * @param counts `counts[truth.index][predicted.index]`, copied.
 * @property skipped compared pairs that were skipped because either side was [SleepStage.UNKNOWN].
 */
class ConfusionMatrix(
    counts: Array<LongArray>,
    val skipped: Long = 0,
) {
    init {
        require(counts.size == SleepStage.COUNT && counts.all { it.size == SleepStage.COUNT }) { "confusion matrix must be 4x4" }
        require(counts.all { row -> row.all { it >= 0 } }) { "counts must not be negative" }
    }

    private val cells: Array<LongArray> = Array(SleepStage.COUNT) { counts[it].copyOf() }

    /** Count of pairs with [truth] and [predicted] (0 for UNKNOWN). */
    operator fun get(
        truth: SleepStage,
        predicted: SleepStage,
    ): Long = if (truth == SleepStage.UNKNOWN || predicted == SleepStage.UNKNOWN) 0 else cells[truth.index][predicted.index]

    /** Number of scored pairs. */
    val total: Long get() = cells.sumOf { it.sum() }

    /** Observed agreement; NaN without pairs. */
    val accuracy: Double get() = if (total == 0L) Double.NaN else SleepStage.SCORED.sumOf { this[it, it] }.toDouble() / total

    /**
     * Unweighted Cohen's kappa `(p_o − p_e) / (1 − p_e)`; NaN without pairs or when `p_e = 1` (both sides use a
     * single, identical class).
     */
    val kappa: Double
        get() {
            val n = total.toDouble()
            if (n == 0.0) return Double.NaN
            val expected = SleepStage.SCORED.sumOf { stage(it).support.toDouble() * stage(it).predicted } / (n * n)
            return if (expected >= 1.0) Double.NaN else (accuracy - expected) / (1 - expected)
        }

    /** Precision, recall and F1 of [stage] (see [StageScore]). */
    fun stage(stage: SleepStage): StageScore {
        val support = SleepStage.SCORED.sumOf { this[stage, it] }
        val predicted = SleepStage.SCORED.sumOf { this[it, stage] }
        return StageScore(support = support, predicted = predicted, correct = this[stage, stage])
    }

    /** Mean F1 over the stages present in the truth or the prediction; NaN without pairs. */
    val macroF1: Double
        get() {
            val present = SleepStage.SCORED.map { stage(it) }.filter { it.support + it.predicted > 0 }
            return if (present.isEmpty()) Double.NaN else present.sumOf { it.f1 } / present.size
        }

    /** Element-wise sum (pooling over nights). */
    operator fun plus(other: ConfusionMatrix): ConfusionMatrix =
        ConfusionMatrix(
            Array(SleepStage.COUNT) { i -> LongArray(SleepStage.COUNT) { j -> cells[i][j] + other.cells[i][j] } },
            skipped + other.skipped,
        )

    /** A copy of the counts `[truth][predicted]`. */
    fun toArray(): Array<LongArray> = Array(SleepStage.COUNT) { cells[it].copyOf() }

    override fun equals(other: Any?): Boolean = other is ConfusionMatrix && skipped == other.skipped && cells.contentDeepEquals(other.cells)

    override fun hashCode(): Int = HASH_MULTIPLIER * cells.contentDeepHashCode() + skipped.hashCode()

    override fun toString(): String = "ConfusionMatrix(${cells.joinToString { it.contentToString() }}, skipped=$skipped)"

    /** Constants. */
    companion object {
        private const val HASH_MULTIPLIER = 31

        /** The matrix without any pairs. */
        val EMPTY: ConfusionMatrix = ConfusionMatrix(Array(SleepStage.COUNT) { LongArray(SleepStage.COUNT) })
    }
}

/**
 * Per-stage scores. Undefined ratios are 0, like scikit-learn's default `zero_division` behaviour.
 *
 * @property support pairs whose truth is the stage.
 * @property predicted pairs predicted as the stage.
 * @property correct pairs where both are the stage.
 */
data class StageScore(
    val support: Long,
    val predicted: Long,
    val correct: Long,
) {
    /** `correct / predicted`. */
    val precision: Double get() = if (predicted == 0L) 0.0 else correct.toDouble() / predicted

    /** `correct / support`. */
    val recall: Double get() = if (support == 0L) 0.0 else correct.toDouble() / support

    /** Harmonic mean of precision and recall. */
    val f1: Double
        get() {
            val p = precision
            val r = recall
            return if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
        }
}

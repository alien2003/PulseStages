package io.github.alien2003.pulsestages.algo.post

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.preprocess.EpochGrid
import java.util.Locale

/**
 * Shares of the scored (non-UNKNOWN) session time per stage, weighted by each epoch's clipped duration.
 *
 * @property scoredMs total scored time; 0 when every epoch is UNKNOWN (all shares are then 0).
 */
internal class StageShares(
    private val shares: DoubleArray,
    val scoredMs: Long,
) {
    /** Share of [stage] in `[0, 1]` (0 for UNKNOWN). */
    operator fun get(stage: SleepStage): Double = if (stage == SleepStage.UNKNOWN) 0.0 else shares[stage.index]

    /** Whether any time is scored. */
    val hasScoredTime: Boolean get() = scoredMs > 0

    companion object {
        /** Computes the shares of [stages] (one per session epoch of [grid]). */
        fun of(
            stages: Array<SleepStage>,
            grid: EpochGrid,
        ): StageShares {
            val ms = LongArray(SleepStage.COUNT)
            for (k in stages.indices) if (stages[k] != SleepStage.UNKNOWN) ms[stages[k].index] += grid.clippedDurationMs(k)
            val total = ms.sum()
            return StageShares(DoubleArray(SleepStage.COUNT) { if (total > 0) ms[it].toDouble() / total else 0.0 }, total)
        }
    }

    override fun toString(): String =
        SleepStage.SCORED.joinToString(prefix = "StageShares(", postfix = ")") { "${it.name}=${"%.3f".format(Locale.ROOT, this[it])}" }
}

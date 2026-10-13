package io.github.alien2003.pulsestages.algo.metrics

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.preprocess.EpochGrid

/** Projection of a reference hypnogram (e.g. 30-s PSG epochs) onto the epoch grid of a night. */
object EpochLabels {
    /**
     * The reference stage of every session epoch of [grid]: the scored stage (UNKNOWN intervals are ignored) with
     * the largest overlap with the epoch (clipped to the session end); ties go to the stage covering the epoch
     * midpoint, else to the lowest [SleepStage.index]. Null where no scored interval overlaps the epoch.
     */
    fun majority(
        truth: List<StageInterval>,
        grid: EpochGrid,
    ): Array<SleepStage?> {
        val intervals = truth.filter { it.stage != SleepStage.UNKNOWN && it.endMs > it.startMs }.sortedBy { it.startMs }
        val overlap = LongArray(SleepStage.COUNT)
        var first = 0
        return Array(grid.sessionEpochs) { k ->
            val from = grid.startMs(k)
            val to = grid.clippedEndMs(k)
            while (first < intervals.size && intervals[first].endMs <= from) first++
            overlap.fill(0L)
            var midpointStage: SleepStage? = null
            val mid = from + (to - from) / 2
            var i = first
            while (i < intervals.size && intervals[i].startMs < to) {
                val interval = intervals[i]
                val shared = minOf(to, interval.endMs) - maxOf(from, interval.startMs)
                if (shared > 0) overlap[interval.stage.index] += shared
                if (mid >= interval.startMs && mid < interval.endMs) midpointStage = interval.stage
                i++
            }
            pick(overlap, midpointStage)
        }
    }

    private fun pick(
        overlap: LongArray,
        midpointStage: SleepStage?,
    ): SleepStage? {
        val best = overlap.max()
        return when {
            best <= 0L -> null
            midpointStage != null && overlap[midpointStage.index] == best -> midpointStage
            else -> SleepStage.ofIndex(overlap.indexOfFirst { it == best })
        }
    }
}

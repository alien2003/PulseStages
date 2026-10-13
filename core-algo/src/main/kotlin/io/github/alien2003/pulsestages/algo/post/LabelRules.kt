package io.github.alien2003.pulsestages.algo.post

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.params.GapPolicy
import io.github.alien2003.pulsestages.algo.params.LowConfidencePolicy
import io.github.alien2003.pulsestages.algo.params.PostprocessParams

/** The individual label rewriting steps of the post-processing. All functions return new arrays. */
internal object LabelRules {
    /** Step 1: epochs whose max posterior is below the threshold become LIGHT under [LowConfidencePolicy.LIGHT]. */
    fun lowConfidence(
        stages: Array<SleepStage>,
        posteriors: Array<DoubleArray>,
        forced: BooleanArray,
        params: PostprocessParams,
    ): Array<SleepStage> {
        if (params.lowConfidencePolicy != LowConfidencePolicy.LIGHT) return stages.copyOf()
        return Array(stages.size) { k ->
            if (!forced[k] && posteriors[k].max() < params.lowConfidenceThreshold) SleepStage.LIGHT else stages[k]
        }
    }

    /** Minimum bout length in minutes of [stage] (0 = no minimum). */
    private fun minimumMinutes(
        stage: SleepStage,
        params: PostprocessParams,
    ): Double =
        when (stage) {
            SleepStage.DEEP -> params.minDeepMin
            SleepStage.REM -> params.minRemMin
            SleepStage.AWAKE -> params.minAwakeMin
            SleepStage.LIGHT, SleepStage.UNKNOWN -> 0.0
        }

    /** A maximal run `[from, to]` of equal labels. */
    class Bout(
        val from: Int,
        val to: Int,
        val stage: SleepStage,
    ) {
        /** Number of epochs. */
        val length: Int get() = to - from + 1
    }

    /** The bouts of [stages] in time order. */
    fun bouts(stages: Array<SleepStage>): List<Bout> {
        val out = ArrayList<Bout>()
        var from = 0
        for (k in 1..stages.size) {
            if (k == stages.size || stages[k] != stages[from]) {
                out += Bout(from, k - 1, stages[from])
                from = k
            }
        }
        return out
    }

    /** Whether [stage] is a sleep stage (LIGHT, DEEP or REM). */
    private fun isSleep(stage: SleepStage): Boolean = stage == SleepStage.LIGHT || stage == SleepStage.DEEP || stage == SleepStage.REM

    /** The bouts of one labelling with the first and last sleep epoch (−1 when there is no sleep). */
    private class BoutScan(
        stages: Array<SleepStage>,
    ) {
        val bouts: List<Bout> = bouts(stages)
        private val firstSleep = stages.indexOfFirst { isSleep(it) }
        private val lastSleep = stages.indexOfLast { isSleep(it) }

        /** The neighbour of bout [i] on the [left] or right side, or null at the session edge or for an UNKNOWN bout. */
        fun scoredNeighbour(
            i: Int,
            left: Boolean,
        ): Bout? = bouts.getOrNull(if (left) i - 1 else i + 1)?.takeIf { it.stage != SleepStage.UNKNOWN }

        /** Whether [bout] lies inside sleep: some LIGHT/DEEP/REM epoch before it and some after it. */
        fun insideSleep(bout: Bout): Boolean = firstSleep in 0 until bout.from && lastSleep > bout.to
    }

    /**
     * Step 3 (after the gap policy, so that the minimum lengths hold in the written hypnogram): repeatedly takes the
     * shortest bout below its stage's minimum (earliest on ties) and relabels it to the stage of a scored neighbour,
     * until no such bout is left. A bout is considered only when
     * - it contains no forced-AWAKE epoch,
     * - it has at least one scored neighbour: UNKNOWN bouts (gaps) are walls that never absorb anything, so a bout
     *   between two gaps (or a gap and the session edge) keeps its label, like the only bout of a night,
     * - for AWAKE, it lies inside sleep (a LIGHT, DEEP or REM epoch before and after it): the specification limits
     *   only awakenings within sleep ("AWAKE inside sleep"), so sleep-onset latency and the final wake are exempt.
     * The target is the scored neighbour whose stage has the larger mean posterior over the short bout's epochs (the
     * left one on ties), or the only scored neighbour. Lengths count whole epochs of [epochMin] minutes.
     */
    fun absorbShortBouts(
        stages: Array<SleepStage>,
        posteriors: Array<DoubleArray>,
        forced: BooleanArray,
        epochMin: Double,
        params: PostprocessParams,
    ): Array<SleepStage> {
        val out = stages.copyOf()
        while (true) {
            val scan = BoutScan(out)
            val bouts = scan.bouts
            val shortIndex =
                bouts.indices
                    .filter { i ->
                        val b = bouts[i]
                        b.length * epochMin < minimumMinutes(b.stage, params) &&
                            (b.from..b.to).none { forced[it] } &&
                            (scan.scoredNeighbour(i, left = true) != null || scan.scoredNeighbour(i, left = false) != null) &&
                            (b.stage != SleepStage.AWAKE || scan.insideSleep(b))
                    }.minByOrNull { bouts[it].length } ?: break
            val short = bouts[shortIndex]
            val target =
                absorbingStage(
                    short,
                    scan.scoredNeighbour(shortIndex, left = true),
                    scan.scoredNeighbour(shortIndex, left = false),
                    posteriors,
                )
            for (k in short.from..short.to) out[k] = target
        }
        return out
    }

    /** Stage of the scored neighbour ([left] or [right], at least one non-null) that absorbs [short]. */
    private fun absorbingStage(
        short: Bout,
        left: Bout?,
        right: Bout?,
        posteriors: Array<DoubleArray>,
    ): SleepStage {
        fun meanPosterior(stage: SleepStage): Double = (short.from..short.to).sumOf { posteriors[it][stage.index] } / short.length
        return when {
            left == null -> checkNotNull(right).stage
            right == null -> left.stage
            meanPosterior(right.stage) > meanPosterior(left.stage) -> right.stage
            else -> left.stage
        }
    }

    /**
     * Step 2: epochs without heart-rate data (not forced) get UNKNOWN, LIGHT, or the label of the nearest epoch with
     * data (earlier on ties; UNKNOWN if there is none). Every no-data epoch lies in a gap longer than maxInterpGapMin
     * (shorter gaps are interpolated) or before the first / after the last sample, so all of them are affected.
     */
    fun gapPolicy(
        stages: Array<SleepStage>,
        hasData: BooleanArray,
        forced: BooleanArray,
        policy: GapPolicy,
    ): Array<SleepStage> {
        val nearest = if (policy == GapPolicy.NEAREST) nearestDataEpoch(hasData) else IntArray(0)
        return Array(stages.size) { k ->
            when {
                hasData[k] || forced[k] -> stages[k]
                policy == GapPolicy.UNKNOWN -> SleepStage.UNKNOWN
                policy == GapPolicy.LIGHT -> SleepStage.LIGHT
                nearest[k] >= 0 -> stages[nearest[k]]
                else -> SleepStage.UNKNOWN
            }
        }
    }

    /** For each epoch, the index of the nearest epoch with data (earlier on ties), or −1. */
    private fun nearestDataEpoch(hasData: BooleanArray): IntArray {
        val n = hasData.size
        val previous = IntArray(n)
        var last = -1
        for (k in 0 until n) {
            if (hasData[k]) last = k
            previous[k] = last
        }
        val next = IntArray(n)
        last = -1
        for (k in n - 1 downTo 0) {
            if (hasData[k]) last = k
            next[k] = last
        }
        return IntArray(n) { k ->
            val p = previous[k]
            val q = next[k]
            when {
                p < 0 -> q
                q < 0 -> p
                q - k < k - p -> q
                else -> p
            }
        }
    }

    /**
     * A final partial epoch (shorter than one epoch) takes the label of the epoch before it, so that every output
     * interval is at least one epoch long.
     */
    fun mergePartialLastEpoch(
        stages: Array<SleepStage>,
        lastIsPartial: Boolean,
    ): Array<SleepStage> {
        val out = stages.copyOf()
        if (lastIsPartial && out.size >= 2) out[out.size - 1] = out[out.size - 2]
        return out
    }
}

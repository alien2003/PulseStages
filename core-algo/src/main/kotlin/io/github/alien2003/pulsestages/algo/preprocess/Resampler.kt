package io.github.alien2003.pulsestages.algo.preprocess

/** Resampling of irregular samples onto the [EpochGrid]. */
internal object Resampler {
    /**
     * Returns one value per grid position: the mean of the samples inside the epoch; for epochs without samples,
     * the linear interpolation at the epoch midpoint between the nearest samples before and after, if those are at
     * most [maxInterpGapMs] apart; otherwise NaN ("no data"). Epochs before the first or after the last sample are
     * no data. [times] must be sorted ascending and inside the grid window.
     */
    fun resample(
        times: LongArray,
        values: DoubleArray,
        grid: EpochGrid,
        maxInterpGapMs: Long,
    ): DoubleArray {
        require(times.size == values.size) { "times and values differ in size" }
        val sums = DoubleArray(grid.totalEpochs)
        val counts = IntArray(grid.totalEpochs)
        for (i in times.indices) {
            val position = grid.positionOf(times[i])
            if (position >= 0) {
                sums[position] += values[i]
                counts[position]++
            }
        }
        val out = DoubleArray(grid.totalEpochs) { if (counts[it] > 0) sums[it] / counts[it] else Double.NaN }
        var next = 0 // index of the first sample at or after the current epoch's end
        for (position in 0 until grid.totalEpochs) {
            val epochEnd = grid.startMs(grid.epochAt(position) + 1)
            while (next < times.size && times[next] < epochEnd) next++
            if (counts[position] == 0) {
                out[position] = interpolate(times, values, next, grid.midMs(grid.epochAt(position)), maxInterpGapMs)
            }
        }
        return out
    }

    private fun interpolate(
        times: LongArray,
        values: DoubleArray,
        after: Int,
        atMs: Long,
        maxInterpGapMs: Long,
    ): Double {
        val before = after - 1
        if (before < 0 || after >= times.size) return Double.NaN
        val span = times[after] - times[before]
        val fraction = (atMs - times[before]).toDouble() / span
        return if (span in 1..maxInterpGapMs) values[before] + fraction * (values[after] - values[before]) else Double.NaN
    }
}

package io.github.alien2003.pulsestages.algo.preprocess

import io.github.alien2003.pulsestages.algo.numeric.Stats
import kotlin.math.abs

/** Result of [SignalFilters.hampel]: the filtered values and how many were replaced. */
internal class HampelResult(
    val values: DoubleArray,
    val replaced: Int,
)

/** Outlier removal and smoothing filters. NaN marks a missing value. */
internal object SignalFilters {
    /** Scale factor that makes the MAD a consistent estimator of σ for normal data. */
    private const val MAD_SCALE = 1.4826

    /**
     * Hampel filter on a sample sequence: for each value, the centred window of [window] samples (±(window−1)/2,
     * truncated at the ends) gives median m and MAD; values with `|x − m| > threshold·max(1.4826·MAD, minScale)` are
     * replaced by m. Windows are always taken from the original series.
     *
     * [minScale] (bpm) keeps quantized data from being flattened: heart rate arrives in whole bpm, so three equal
     * readings in a window of five give MAD = 0, and the classic filter (minScale = 0) would then replace every
     * deviation, even 1 bpm, removing genuine variability. A lone spike in such a window still exceeds
     * `threshold·minScale` and is replaced.
     */
    fun hampel(
        values: DoubleArray,
        window: Int,
        threshold: Double,
        minScale: Double = 0.0,
    ): HampelResult {
        val half = (window.coerceAtLeast(1) - 1) / 2
        val out = values.copyOf()
        var replaced = 0
        for (i in values.indices) {
            val from = maxOf(0, i - half)
            val to = minOf(values.size - 1, i + half)
            val slice = values.copyOfRange(from, to + 1)
            val median = Stats.median(slice)
            val mad = Stats.median(DoubleArray(slice.size) { abs(slice[it] - median) })
            val scale = maxOf(MAD_SCALE * mad, minScale)
            if (abs(values[i] - median) > threshold * scale) {
                out[i] = median
                replaced++
            }
        }
        return HampelResult(out, replaced)
    }

    /** Centred running median over the non-NaN run containing each value (NaN stays NaN). */
    fun runningMedian(
        values: DoubleArray,
        window: Int,
    ): DoubleArray = applyWindowed(values, window) { slice -> Stats.median(slice) }

    /** Centred moving average over the non-NaN run containing each value (NaN stays NaN). */
    fun movingAverage(
        values: DoubleArray,
        window: Int,
    ): DoubleArray = applyWindowed(values, window) { slice -> Stats.mean(slice) }

    /**
     * Applies [reduce] to the centred window (`(window−1)/2` before, `window/2` after for even sizes) of every
     * non-NaN value, clipped to the contiguous run of non-NaN values around it, so nothing is smoothed across a gap.
     */
    private fun applyWindowed(
        values: DoubleArray,
        window: Int,
        reduce: (DoubleArray) -> Double,
    ): DoubleArray {
        val w = window.coerceAtLeast(1)
        val before = (w - 1) / 2
        val after = w - 1 - before
        val out = DoubleArray(values.size) { Double.NaN }
        var runStart = 0
        while (runStart < values.size) {
            if (values[runStart].isNaN()) {
                runStart++
            } else {
                var runEnd = runStart
                while (runEnd + 1 < values.size && !values[runEnd + 1].isNaN()) runEnd++
                for (i in runStart..runEnd) {
                    val from = maxOf(runStart, i - before)
                    val to = minOf(runEnd, i + after)
                    out[i] = reduce(values.copyOfRange(from, to + 1))
                }
                runStart = runEnd + 1
            }
        }
        return out
    }
}

package io.github.alien2003.pulsestages.algo.preprocess

import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.params.PreprocessParams

/**
 * Heart rate of one night on the epoch grid.
 *
 * Arrays are position-indexed (see [EpochGrid]) and must not be modified.
 *
 * @property grid the epoch grid (session + context).
 * @property hrRaw resampled heart rate (bpm) before smoothing; NaN = no data.
 * @property hrSmooth [hrRaw] after the running median and the moving average; NaN exactly where [hrRaw] is NaN.
 * @property coverage fraction of session epochs with data, in `[0, 1]`.
 * @property sampleCount heart-rate samples used (in range, inside the grid window, duplicates collapsed).
 * @property outliersReplaced samples replaced by the Hampel filter.
 */
class PreprocessedNight(
    val grid: EpochGrid,
    val hrRaw: DoubleArray,
    val hrSmooth: DoubleArray,
    val coverage: Double,
    val sampleCount: Int,
    val outliersReplaced: Int,
) {
    /** Whether grid [position] has heart-rate data. */
    fun hasData(position: Int): Boolean = !hrRaw[position].isNaN()

    /** Whether session epoch [k] has heart-rate data. */
    fun sessionHasData(k: Int): Boolean = hasData(grid.position(k))
}

/**
 * Preprocessing (section 1 of the specification): range filter, sorting and duplicate collapse, Hampel outlier
 * replacement, resampling with bounded interpolation, smoothing and coverage.
 */
object Preprocessor {
    private const val MS_PER_MINUTE = 60_000.0

    /** Builds the epoch grid for [input] with [params]. */
    fun grid(
        input: NightInput,
        params: PreprocessParams,
    ): EpochGrid = EpochGrid(input.sessionStartMs, input.sessionEndMs, params.epochSec, params.contextMarginMin)

    /** Runs the whole preprocessing chain on the heart rate of [input]. */
    fun preprocess(
        input: NightInput,
        params: PreprocessParams,
    ): PreprocessedNight {
        val grid = grid(input, params)
        val cleaned =
            input.heartRate
                .filter { it.bpm in params.minBpm..params.maxBpm && it.timeMs >= grid.windowStartMs && it.timeMs < grid.windowEndMs }
                .groupBy { it.timeMs }
                .map { (time, samples) -> time to samples.sumOf { it.bpm } / samples.size }
                .sortedBy { it.first }
        val times = LongArray(cleaned.size) { cleaned[it].first }
        val hampel =
            SignalFilters.hampel(
                DoubleArray(cleaned.size) { cleaned[it].second },
                params.hampelWindow,
                params.hampelThreshold,
                params.hampelMinScaleBpm,
            )
        val maxGapMs = (params.maxInterpGapMin * MS_PER_MINUTE).toLong()
        val raw = Resampler.resample(times, hampel.values, grid, maxGapMs)
        val smooth =
            SignalFilters.movingAverage(
                SignalFilters.runningMedian(raw, params.medianWindowEpochs),
                params.smoothWindowEpochs,
            )
        var withData = 0
        for (k in 0 until grid.sessionEpochs) if (!raw[grid.position(k)].isNaN()) withData++
        return PreprocessedNight(
            grid = grid,
            hrRaw = raw,
            hrSmooth = smooth,
            coverage = withData.toDouble() / grid.sessionEpochs,
            sampleCount = cleaned.size,
            outliersReplaced = hampel.replaced,
        )
    }
}

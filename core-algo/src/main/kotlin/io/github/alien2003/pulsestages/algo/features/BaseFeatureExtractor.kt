package io.github.alien2003.pulsestages.algo.features

import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.algo.numeric.Stats
import io.github.alien2003.pulsestages.algo.params.FeatureParams
import io.github.alien2003.pulsestages.algo.preprocess.EpochGrid
import io.github.alien2003.pulsestages.algo.preprocess.PreprocessedNight
import kotlin.math.ceil

/** Computes [BaseFeatures] (section 2 of the specification). */
object BaseFeatureExtractor {
    /** Windowed std and slope need at least this many values. */
    internal const val MIN_WINDOW_VALUES = 3
    private const val HALF = 0.5
    private const val SECONDS_PER_MINUTE = 60.0

    /** Extracts all features of [night] (heart rate preprocessed from [input]) with [stats]. */
    fun extract(
        input: NightInput,
        night: PreprocessedNight,
        stats: NightStatistics,
        params: FeatureParams,
    ): BaseFeatures {
        val grid = night.grid
        return BaseFeatures(
            grid = grid,
            heartRate =
                HeartRateFeatures(
                    hrNorm = DoubleArray(grid.totalEpochs) { (night.hrSmooth[it] - stats.lowPercentileBpm) / stats.normDenominator },
                    hrVar = normalizedWindowStd(night.hrRaw, params.varHalfWindowEpochs, grid),
                    hrSlope = windowSlope(night.hrSmooth, params.slopeHalfWindowEpochs, grid.epochMin),
                ),
            t = DoubleArray(grid.totalEpochs) { ((grid.epochAt(it) + HALF) / grid.sessionEpochs).coerceIn(0.0, 1.0) },
            cycle = cycle(night.hrSmooth, grid, params),
            activity = ActivityFeatures(steps(input.steps, grid), forcedAwake(input, grid)),
            optional = OptionalChannels.compute(input, grid, params),
        )
    }

    /**
     * Population std of the non-NaN values of [values] in ±[halfWindow] (NaN with fewer than
     * [MIN_WINDOW_VALUES] values), divided by the median of that std over the session epochs (1 if the median is 0
     * or undefined).
     */
    internal fun normalizedWindowStd(
        values: DoubleArray,
        halfWindow: Int,
        grid: EpochGrid,
    ): DoubleArray {
        val std =
            DoubleArray(values.size) { i ->
                val window = window(values, i, halfWindow)
                if (window.size < MIN_WINDOW_VALUES) Double.NaN else Stats.populationStd(window)
            }
        val median = Stats.median(Stats.finite(DoubleArray(grid.sessionEpochs) { std[grid.position(it)] }))
        val scale = if (median.isNaN() || median == 0.0) 1.0 else median
        return DoubleArray(values.size) { std[it] / scale }
    }

    private fun window(
        values: DoubleArray,
        center: Int,
        halfWindow: Int,
    ): DoubleArray {
        val from = maxOf(0, center - halfWindow)
        val to = minOf(values.size - 1, center + halfWindow)
        return Stats.finite(values.copyOfRange(from, to + 1))
    }

    /** Least-squares slope (bpm/min) of the non-NaN values in ±[halfWindow]; NaN with fewer than 3 values. */
    private fun windowSlope(
        values: DoubleArray,
        halfWindow: Int,
        epochMin: Double,
    ): DoubleArray = DoubleArray(values.size) { i -> Stats.windowSlope(values, i, halfWindow, epochMin, MIN_WINDOW_VALUES) }

    /** Cycle minima (scipy-like find_peaks on −hrSmooth) and the phase between consecutive minima. */
    internal fun cycle(
        hrSmooth: DoubleArray,
        grid: EpochGrid,
        params: FeatureParams,
    ): CycleFeatures {
        val minima = PeakFinder.findMinima(hrSmooth, cycleDistanceEpochs(params, grid), params.cycleMinProminenceBpm)
        val phase = DoubleArray(hrSmooth.size) { Double.NaN }
        if (minima.size >= 2) {
            for (m in 0 until minima.size - 1) {
                val from = minima[m]
                val to = minima[m + 1]
                for (i in from until to) phase[i] = (i - from).toDouble() / (to - from)
            }
            phase[minima.last()] = 0.0
        }
        return CycleFeatures(minima, phase)
    }

    /** `cycleMinDistanceMin` in epochs (rounded up, at least 1), the `distance` of the minima search. */
    internal fun cycleDistanceEpochs(
        params: FeatureParams,
        grid: EpochGrid,
    ): Int = ceil(params.cycleMinDistanceMin * SECONDS_PER_MINUTE / grid.epochSec).toInt().coerceAtLeast(1)

    /** Steps per position; each sample's count is spread uniformly over its interval (zero-length: one epoch). */
    private fun steps(
        samples: List<StepsSample>,
        grid: EpochGrid,
    ): DoubleArray {
        val out = DoubleArray(grid.totalEpochs)
        for (sample in samples) {
            if (sample.count <= 0) continue
            if (sample.endMs <= sample.startMs) {
                val position = grid.positionOf(sample.startMs)
                if (position >= 0) out[position] += sample.count.toDouble()
            } else {
                spread(sample, grid, out)
            }
        }
        return out
    }

    private fun spread(
        sample: StepsSample,
        grid: EpochGrid,
        out: DoubleArray,
    ) {
        val from = maxOf(sample.startMs, grid.windowStartMs)
        val to = minOf(sample.endMs, grid.windowEndMs)
        if (from >= to) return
        val duration = (sample.endMs - sample.startMs).toDouble()
        for (position in grid.positionOf(from)..grid.positionOf(to - 1)) {
            val k = grid.epochAt(position)
            val overlap = minOf(to, grid.startMs(k + 1)) - maxOf(from, grid.startMs(k))
            out[position] += sample.count * overlap / duration
        }
    }

    private fun forcedAwake(
        input: NightInput,
        grid: EpochGrid,
    ): BooleanArray =
        BooleanArray(grid.totalEpochs) { position ->
            val mid = grid.midMs(grid.epochAt(position))
            input.forcedAwake.any { mid >= it.startMs && mid < it.endMs }
        }
}

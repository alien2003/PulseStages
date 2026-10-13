package io.github.alien2003.pulsestages.algo.features

import io.github.alien2003.pulsestages.algo.model.HrvSample
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.numeric.Stats
import io.github.alien2003.pulsestages.algo.params.FeatureParams
import io.github.alien2003.pulsestages.algo.preprocess.EpochGrid
import kotlin.math.abs
import kotlin.math.roundToInt

/** SpO2, HRV (RMSSD) and respiratory-rate features; each is used only when data is present and enabled. */
internal object OptionalChannels {
    private const val MAX_SPO2_PERCENT = 100.0
    private const val SECONDS_PER_MINUTE = 60.0
    private const val MS_PER_MINUTE = 60_000.0

    /** Computes the three optional channels of [input] on [grid]. */
    fun compute(
        input: NightInput,
        grid: EpochGrid,
        params: FeatureParams,
    ): OptionalChannelFeatures {
        val absent = { DoubleArray(grid.totalEpochs) { Double.NaN } }
        return OptionalChannelFeatures(
            spo2Drop = if (params.useSpo2 && input.spo2.isNotEmpty()) spo2Drop(input, grid, params) else absent(),
            hrvVar = if (params.useHrv && input.hrv.isNotEmpty()) hrv(input, grid, params) else absent(),
            respVar = if (params.useRespiratoryRate && input.respiratoryRate.isNotEmpty()) resp(input, grid, params) else absent(),
        )
    }

    /** Per-position mean of the (time, value) pairs; NaN where an epoch has none. */
    private fun epochMeans(
        samples: List<Pair<Long, Double>>,
        grid: EpochGrid,
    ): DoubleArray {
        val sums = DoubleArray(grid.totalEpochs)
        val counts = IntArray(grid.totalEpochs)
        for ((time, value) in samples) {
            val position = grid.positionOf(time)
            if (position >= 0) {
                sums[position] += value
                counts[position]++
            }
        }
        return DoubleArray(grid.totalEpochs) { if (counts[it] > 0) sums[it] / counts[it] else Double.NaN }
    }

    /** Rolling (centred, [FeatureParams.spo2MedianWindowMin]) median minus the epoch value. */
    private fun spo2Drop(
        input: NightInput,
        grid: EpochGrid,
        params: FeatureParams,
    ): DoubleArray {
        val samples = input.spo2.filter { it.percent > 0 && it.percent <= MAX_SPO2_PERCENT }.map { it.timeMs to it.percent }
        val values = epochMeans(samples, grid)
        val half = (params.spo2MedianWindowMin * SECONDS_PER_MINUTE / grid.epochSec / 2).roundToInt().coerceAtLeast(0)
        return DoubleArray(values.size) { i ->
            if (values[i].isNaN()) {
                Double.NaN
            } else {
                val from = maxOf(0, i - half)
                val to = minOf(values.size - 1, i + half)
                Stats.median(Stats.finite(values.copyOfRange(from, to + 1))) - values[i]
            }
        }
    }

    /** `median_night(RMSSD) / RMSSD` of the nearest sample within [FeatureParams.hrvMaxDistanceMin]. */
    private fun hrv(
        input: NightInput,
        grid: EpochGrid,
        params: FeatureParams,
    ): DoubleArray {
        val samples = input.hrv.filter { it.rmssdMs > 0 }.sortedBy { it.timeMs }
        if (samples.isEmpty()) return DoubleArray(grid.totalEpochs) { Double.NaN }
        val inSession = samples.filter { it.timeMs >= grid.sessionStartMs && it.timeMs < grid.sessionEndMs }
        val median = Stats.median((inSession.ifEmpty { samples }).map { it.rmssdMs }.toDoubleArray())
        val maxDistanceMs = params.hrvMaxDistanceMin * MS_PER_MINUTE
        val times = LongArray(samples.size) { samples[it].timeMs }
        return DoubleArray(grid.totalEpochs) { position ->
            val mid = grid.midMs(grid.epochAt(position))
            val nearest = nearest(times, samples, mid)
            if (abs(nearest.timeMs - mid) <= maxDistanceMs) median / nearest.rmssdMs else Double.NaN
        }
    }

    private fun nearest(
        times: LongArray,
        samples: List<HrvSample>,
        atMs: Long,
    ): HrvSample {
        val found = times.binarySearch(atMs)
        if (found >= 0) return samples[found]
        val insertion = -found - 1
        val candidates = listOfNotNull(samples.getOrNull(insertion - 1), samples.getOrNull(insertion))
        return candidates.minBy { abs(it.timeMs - atMs) }
    }

    /** Std of the per-epoch respiratory rate in ±[FeatureParams.respiratoryHalfWindowEpochs], normalized by its median. */
    private fun resp(
        input: NightInput,
        grid: EpochGrid,
        params: FeatureParams,
    ): DoubleArray {
        val samples = input.respiratoryRate.filter { it.rate > 0 }.map { it.timeMs to it.rate }
        return BaseFeatureExtractor.normalizedWindowStd(epochMeans(samples, grid), params.respiratoryHalfWindowEpochs, grid)
    }
}

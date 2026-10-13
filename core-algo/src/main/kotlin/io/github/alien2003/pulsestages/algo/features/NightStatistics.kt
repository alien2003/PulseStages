package io.github.alien2003.pulsestages.algo.features

import io.github.alien2003.pulsestages.algo.numeric.Stats
import io.github.alien2003.pulsestages.algo.params.FeatureParams
import io.github.alien2003.pulsestages.algo.preprocess.PreprocessedNight

/**
 * Night-level heart-rate statistics over the session epochs with data (smoothed series).
 *
 * All bpm values are NaN when the session has no data.
 *
 * @property lowPercentileBpm P[FeatureParams.normLowPercentile] (default P10, ≈ the deep-sleep nadir).
 * @property highPercentileBpm P[FeatureParams.normHighPercentile] (default P50, ≈ light sleep).
 * @property rangeBpm `highPercentileBpm − lowPercentileBpm`.
 * @property normDenominator denominator of hrNorm: [rangeBpm], or [FeatureParams.minNormRangeBpm] if the range is
 *   smaller (or undefined). A non-positive configured minimum is treated as 1 bpm.
 * @property lowRange the range was below the minimum (LOW_HR_RANGE).
 * @property nadirBpm minimum of the smoothed heart rate.
 * @property elevated the nadir exceeds the long-term baseline by more than [FeatureParams.elevatedNightPct] %.
 */
data class NightStatistics(
    val lowPercentileBpm: Double,
    val highPercentileBpm: Double,
    val rangeBpm: Double,
    val normDenominator: Double,
    val lowRange: Boolean,
    val nadirBpm: Double,
    val elevated: Boolean,
) {
    /** Factory. */
    companion object {
        private const val PERCENT = 100.0

        /** Computes the statistics of [night]; [baselineNadirBpm] is the rolling nadir baseline, if known. */
        fun compute(
            night: PreprocessedNight,
            params: FeatureParams,
            baselineNadirBpm: Double?,
        ): NightStatistics {
            val grid = night.grid
            // A non-positive configured minimum would allow a zero denominator; 1 bpm keeps hrNorm finite then.
            val minRange = if (params.minNormRangeBpm > 0) params.minNormRangeBpm else 1.0
            val values = Stats.finite(DoubleArray(grid.sessionEpochs) { night.hrSmooth[grid.position(it)] })
            if (values.isEmpty()) {
                val nan = Double.NaN
                return NightStatistics(nan, nan, nan, minRange, lowRange = false, nadirBpm = nan, elevated = false)
            }
            val low = Stats.percentile(values, params.normLowPercentile)
            val high = Stats.percentile(values, params.normHighPercentile)
            val range = high - low
            val lowRange = range < minRange
            val nadir = values.min()
            val elevated = baselineNadirBpm != null && nadir > baselineNadirBpm * (1 + params.elevatedNightPct / PERCENT)
            return NightStatistics(
                lowPercentileBpm = low,
                highPercentileBpm = high,
                rangeBpm = range,
                normDenominator = if (lowRange) minRange else range,
                lowRange = lowRange,
                nadirBpm = nadir,
                elevated = elevated,
            )
        }
    }
}

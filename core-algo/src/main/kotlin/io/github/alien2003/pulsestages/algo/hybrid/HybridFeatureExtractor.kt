package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.features.BaseFeatureExtractor
import io.github.alien2003.pulsestages.algo.features.PeakFinder
import io.github.alien2003.pulsestages.algo.night.PreparedNight
import io.github.alien2003.pulsestages.algo.numeric.Stats
import io.github.alien2003.pulsestages.algo.preprocess.EpochGrid
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Per-epoch features of the hybrid estimator (section 8 of the specification), computed for the session epochs from
 * the same preprocessing as the HMM. Windows are centred and use the session and the context margin; only non-NaN
 * values enter a window. Values are NaN where undefined (the classifier maps NaN to the training mean).
 *
 * [FEATURE_NAMES], in order:
 * - base: hrNorm, hrVar, hrSlope, t; cycleSin/cycleCos = sin/cos(2π·cyclePhase) and cycleAvailable (1 where the
 *   phase is defined, else 0); stepsLog1p = ln(1 + steps);
 * - relative heart rate: hrRelSmooth = hrSmooth − nadir, hrRelRaw = hrRaw − nadir;
 * - for W ∈ {5, 15, 30, 60} min (half-width ⌊W/2⌋ min in whole epochs, at least one): relMean/relStd/relMin/relMax
 *   of hrRaw − nadir (std needs 3 values) and slope = least-squares slope of hrSmooth in bpm/min (3 values);
 * - acfLag1..3: Pearson autocorrelation of hrRaw at lags of 1–3 epochs over the pairs inside ±15 min (3 pairs);
 * - minToHrMin / minToHrMax: minutes to the nearest local minimum / maximum of hrSmooth (find_peaks with the cycle
 *   distance and prominence), capped at 180 (180 when there is none);
 * - stepsWin5Log1p: ln(1 + steps in ±5 min); noDataFrac15: share of epochs without heart rate in ±15 min; hasData;
 * - hoursSinceStart / hoursToEnd: from the session start to the epoch midpoint and from the midpoint to the session
 *   end, in hours, capped at 12.
 *
 * Deviation from the specification ("every number is an AlgoParams parameter"): the numbers of these definitions —
 * the windows 5/15/30/60 min, the autocorrelation lags 1–3 and their ±15 min window, the ±5 min step window, the
 * ±15 min gap window and the 180 min / 12 h caps — are fixed constants of [FEATURE_SET_VERSION] 1, not parameters.
 * The trained bundle's weights and standardization are only valid for exactly these features, so changing one of
 * them means a new feature-set version and a retrained bundle, not a setting. At the default 1-min epochs and
 * `slopeHalfWindowEpochs` = 2, slope5 equals hrSlope (both ±2 epochs); a future feature-set version may drop it.
 */
object HybridFeatureExtractor {
    /** Version of the feature definitions; a bundle trained on another version is rejected. */
    const val FEATURE_SET_VERSION: Int = 1

    /** Feature names in column order (see the object KDoc). */
    val FEATURE_NAMES: List<String> =
        listOf(
            "hrNorm",
            "hrVar",
            "hrSlope",
            "t",
            "cycleSin",
            "cycleCos",
            "cycleAvailable",
            "stepsLog1p",
            "hrRelSmooth",
            "hrRelRaw",
            "relMean5",
            "relStd5",
            "relMin5",
            "relMax5",
            "slope5",
            "relMean15",
            "relStd15",
            "relMin15",
            "relMax15",
            "slope15",
            "relMean30",
            "relStd30",
            "relMin30",
            "relMax30",
            "slope30",
            "relMean60",
            "relStd60",
            "relMin60",
            "relMax60",
            "slope60",
            "acfLag1",
            "acfLag2",
            "acfLag3",
            "minToHrMin",
            "minToHrMax",
            "stepsWin5Log1p",
            "noDataFrac15",
            "hasData",
            "hoursSinceStart",
            "hoursToEnd",
        )

    private const val W5 = 5.0
    private const val W15 = 15.0
    private const val W30 = 30.0
    private const val W60 = 60.0
    private const val MAX_LAG = 3
    private const val EXTREMUM_CAP_MIN = 180.0
    private const val TIME_CAP_HOURS = 12.0
    private const val MINUTES_PER_HOUR = 60.0
    private const val MS_PER_HOUR = 3_600_000.0
    private const val HALF = 0.5

    /**
     * The feature matrix `[session epoch][feature]` in [FEATURE_NAMES] order, as a fresh copy that the caller may
     * modify (the matrix the estimators use is computed once per preparation and never handed out).
     */
    fun extract(night: PreparedNight): Array<DoubleArray> = cached(night).let { m -> Array(m.size) { m[it].copyOf() } }

    /**
     * The cached feature matrix of [night], shared with the nights derived by [PreparedNight.withParams]. Internal
     * readers must not modify it.
     */
    internal fun cached(night: PreparedNight): Array<DoubleArray> = night.cache.hybridFeatures { compute(night) }

    private fun compute(night: PreparedNight): Array<DoubleArray> {
        val grid = night.grid
        val n = grid.sessionEpochs
        val pre = night.preprocessed
        val base = night.features
        val nadir = night.statistics.nadirBpm
        val rel = DoubleArray(grid.totalEpochs) { pre.hrRaw[it] - nadir }
        val positions = IntArray(n) { grid.position(it) }
        val phase = DoubleArray(n) { base.cycle.phase[positions[it]] }
        val columns = ArrayList<DoubleArray>(FEATURE_NAMES.size)
        columns += DoubleArray(n) { base.heartRate.hrNorm[positions[it]] }
        columns += DoubleArray(n) { base.heartRate.hrVar[positions[it]] }
        columns += DoubleArray(n) { base.heartRate.hrSlope[positions[it]] }
        columns += DoubleArray(n) { base.t[positions[it]] }
        columns += DoubleArray(n) { sin(2 * PI * phase[it]) }
        columns += DoubleArray(n) { cos(2 * PI * phase[it]) }
        columns += DoubleArray(n) { if (phase[it].isNaN()) 0.0 else 1.0 }
        columns += DoubleArray(n) { ln(1 + base.activity.steps[positions[it]]) }
        columns += DoubleArray(n) { pre.hrSmooth[positions[it]] - nadir }
        columns += DoubleArray(n) { rel[positions[it]] }
        for (minutes in doubleArrayOf(W5, W15, W30, W60)) {
            columns += WindowStats.of(rel, pre.hrSmooth, positions, halfEpochs(minutes / 2, grid), grid.epochMin).columns()
        }
        val acfHalf = halfEpochs(W15, grid)
        for (lag in 1..MAX_LAG) columns += DoubleArray(n) { autocorrelation(pre.hrRaw, positions[it], lag, acfHalf) }
        columns += nearestExtremumMinutes(night.features.cycle.minima, positions, grid)
        columns += nearestExtremumMinutes(maxima(night), positions, grid)
        val stepsHalf = halfEpochs(W5, grid)
        columns += DoubleArray(n) { ln(1 + windowSum(base.activity.steps, positions[it], stepsHalf)) }
        val gapHalf = halfEpochs(W15, grid)
        columns += DoubleArray(n) { noDataFraction(pre.hrRaw, positions[it], gapHalf) }
        columns += DoubleArray(n) { if (night.sessionHasData(it)) 1.0 else 0.0 }
        columns += DoubleArray(n) { ((it + HALF) * grid.epochMin / MINUTES_PER_HOUR).coerceAtMost(TIME_CAP_HOURS) }
        columns += DoubleArray(n) { ((grid.sessionEndMs - grid.midMs(it)) / MS_PER_HOUR).coerceIn(0.0, TIME_CAP_HOURS) }
        check(columns.size == FEATURE_NAMES.size) { "computed ${columns.size} feature columns for ${FEATURE_NAMES.size} names" }
        return Array(n) { k -> DoubleArray(columns.size) { columns[it][k] } }
    }

    /** `⌊minutes / epochMin⌋` epochs, at least one. */
    private fun halfEpochs(
        minutes: Double,
        grid: EpochGrid,
    ): Int = floor(minutes / grid.epochMin).toInt().coerceAtLeast(1)

    private fun maxima(night: PreparedNight): IntArray {
        val params = night.params.features
        return PeakFinder.findMaxima(
            night.preprocessed.hrSmooth,
            BaseFeatureExtractor.cycleDistanceEpochs(params, night.grid),
            params.cycleMinProminenceBpm,
        )
    }

    /** Minutes from each session position to the nearest of [extrema] (positions), capped; the cap without extrema. */
    private fun nearestExtremumMinutes(
        extrema: IntArray,
        positions: IntArray,
        grid: EpochGrid,
    ): DoubleArray =
        DoubleArray(positions.size) { k ->
            var nearest = Int.MAX_VALUE
            for (m in extrema) nearest = minOf(nearest, abs(positions[k] - m))
            if (nearest == Int.MAX_VALUE) EXTREMUM_CAP_MIN else (nearest * grid.epochMin).coerceAtMost(EXTREMUM_CAP_MIN)
        }

    /** Pearson correlation of `(x[i], x[i + lag])` for the non-NaN pairs with both indices in `center ± half`. */
    private fun autocorrelation(
        x: DoubleArray,
        center: Int,
        lag: Int,
        half: Int,
    ): Double {
        val from = maxOf(0, center - half)
        val to = minOf(x.size - 1, center + half)
        var count = 0
        var sa = 0.0
        var sb = 0.0
        var saa = 0.0
        var sbb = 0.0
        var sab = 0.0
        for (i in from..to - lag) {
            val a = x[i]
            val b = x[i + lag]
            if (!a.isNaN() && !b.isNaN()) {
                count++
                sa += a
                sb += b
                saa += a * a
                sbb += b * b
                sab += a * b
            }
        }
        if (count < BaseFeatureExtractor.MIN_WINDOW_VALUES) return Double.NaN
        val cov = sab - sa * sb / count
        val varA = saa - sa * sa / count
        val varB = sbb - sb * sb / count
        val denominator = sqrt(varA * varB)
        return if (denominator > VARIANCE_EPS) (cov / denominator).coerceIn(-1.0, 1.0) else Double.NaN
    }

    /** Variances below this (bpm²·n) are treated as zero (constant windows have no defined correlation). */
    private const val VARIANCE_EPS = 1e-9

    private fun windowSum(
        x: DoubleArray,
        center: Int,
        half: Int,
    ): Double {
        var sum = 0.0
        for (i in maxOf(0, center - half)..minOf(x.size - 1, center + half)) sum += x[i]
        return sum
    }

    private fun noDataFraction(
        x: DoubleArray,
        center: Int,
        half: Int,
    ): Double {
        val from = maxOf(0, center - half)
        val to = minOf(x.size - 1, center + half)
        var missing = 0
        for (i in from..to) if (x[i].isNaN()) missing++
        return missing.toDouble() / (to - from + 1)
    }

    /**
     * Window statistics (half-width [half] epochs) at the session positions: mean, std, min and max of the relative
     * heart rate [rel] and the least-squares slope of [smooth] in bpm/min.
     */
    private class WindowStats(
        private val rel: DoubleArray,
        private val smooth: DoubleArray,
        private val half: Int,
        private val epochMin: Double,
        n: Int,
    ) {
        private val mean = DoubleArray(n)
        private val std = DoubleArray(n)
        private val min = DoubleArray(n)
        private val max = DoubleArray(n)
        private val slope = DoubleArray(n)

        fun columns(): List<DoubleArray> = listOf(mean, std, min, max, slope)

        companion object {
            fun of(
                rel: DoubleArray,
                smooth: DoubleArray,
                positions: IntArray,
                half: Int,
                epochMin: Double,
            ): WindowStats {
                val stats = WindowStats(rel, smooth, half, epochMin, positions.size)
                for (k in positions.indices) stats.fill(k, positions[k])
                return stats
            }
        }

        private fun fill(
            k: Int,
            center: Int,
        ) {
            val from = maxOf(0, center - half)
            val to = minOf(rel.size - 1, center + half)
            val values = Stats.finite(rel.copyOfRange(from, to + 1))
            mean[k] = Stats.mean(values)
            std[k] = if (values.size < BaseFeatureExtractor.MIN_WINDOW_VALUES) Double.NaN else Stats.populationStd(values)
            min[k] = values.minOrNull() ?: Double.NaN
            max[k] = values.maxOrNull() ?: Double.NaN
            slope[k] = Stats.windowSlope(smooth, center, half, epochMin, BaseFeatureExtractor.MIN_WINDOW_VALUES)
        }
    }
}

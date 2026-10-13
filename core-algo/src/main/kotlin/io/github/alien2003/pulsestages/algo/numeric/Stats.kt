package io.github.alien2003.pulsestages.algo.numeric

import kotlin.math.floor
import kotlin.math.sqrt

/** Descriptive statistics on plain arrays. Inputs are never modified; empty inputs give NaN. */
internal object Stats {
    private const val PERCENT = 100.0

    /** Median (mean of the two middle values for an even size). */
    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /**
     * Percentile [p] (0..100) with numpy's default "linear" method: on the sorted values, position
     * `h = (n − 1)·p/100` and linear interpolation between the neighbouring order statistics.
     */
    fun percentile(
        values: DoubleArray,
        p: Double,
    ): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sortedArray()
        val h = (sorted.size - 1) * (p.coerceIn(0.0, PERCENT) / PERCENT)
        val lower = floor(h).toInt().coerceIn(0, sorted.size - 1)
        val upper = (lower + 1).coerceAtMost(sorted.size - 1)
        return sorted[lower] + (h - lower) * (sorted[upper] - sorted[lower])
    }

    /** Arithmetic mean. */
    fun mean(values: DoubleArray): Double = if (values.isEmpty()) Double.NaN else values.sum() / values.size

    /** Population standard deviation (divisor n). */
    fun populationStd(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val m = mean(values)
        var acc = 0.0
        for (v in values) acc += (v - m) * (v - m)
        return sqrt(acc / values.size)
    }

    /** Least-squares slope of [ys] over [xs]; NaN when the xs have no spread. */
    fun slope(
        xs: DoubleArray,
        ys: DoubleArray,
    ): Double {
        require(xs.size == ys.size) { "xs and ys differ in size" }
        if (xs.size < 2) return Double.NaN
        val mx = mean(xs)
        val my = mean(ys)
        var sxy = 0.0
        var sxx = 0.0
        for (i in xs.indices) {
            sxy += (xs[i] - mx) * (ys[i] - my)
            sxx += (xs[i] - mx) * (xs[i] - mx)
        }
        return if (sxx == 0.0) Double.NaN else sxy / sxx
    }

    /**
     * Least-squares slope of the non-NaN `values[j]` for j in `center ± half` (clipped to the array) against
     * `(j − center)·step`, i.e. per unit of [step]; NaN with fewer than [minValues] values.
     */
    fun windowSlope(
        values: DoubleArray,
        center: Int,
        half: Int,
        step: Double,
        minValues: Int,
    ): Double {
        val from = maxOf(0, center - half)
        val to = minOf(values.size - 1, center + half)
        val xs = ArrayList<Double>(maxOf(0, to - from + 1))
        val ys = ArrayList<Double>(maxOf(0, to - from + 1))
        for (j in from..to) {
            if (!values[j].isNaN()) {
                xs += (j - center) * step
                ys += values[j]
            }
        }
        return if (xs.size < minValues) Double.NaN else slope(xs.toDoubleArray(), ys.toDoubleArray())
    }

    /** The finite (non-NaN) values of [values] in their original order. */
    fun finite(values: DoubleArray): DoubleArray = values.filter { !it.isNaN() }.toDoubleArray()
}

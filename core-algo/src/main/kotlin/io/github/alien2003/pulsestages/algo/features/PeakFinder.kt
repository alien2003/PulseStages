package io.github.alien2003.pulsestages.algo.features

/**
 * Peak detection that reproduces `scipy.signal.find_peaks` for the `distance` and `prominence` arguments
 * (scipy applies the distance filter before the prominence filter; this implementation does the same).
 */
internal object PeakFinder {
    /**
     * Indices of local maxima: samples larger than both neighbours; for flat peaks the midpoint (rounded down) of
     * the plateau. The first and last samples are never peaks (scipy `_local_maxima_1d`).
     */
    fun localMaxima(x: DoubleArray): IntArray {
        val peaks = ArrayList<Int>()
        var i = 1
        val last = x.size - 1
        while (i < last) {
            if (x[i - 1] < x[i]) {
                var ahead = i + 1
                while (ahead < last && x[ahead] == x[i]) ahead++
                if (x[ahead] < x[i]) {
                    peaks += (i + ahead - 1) / 2
                    i = ahead
                }
            }
            i++
        }
        return peaks.toIntArray()
    }

    /**
     * Keeps peaks that are at least [distance] samples apart, preferring higher peaks (scipy
     * `_select_by_peak_distance`; among equal heights the later peak is processed first).
     */
    fun selectByDistance(
        x: DoubleArray,
        peaks: IntArray,
        distance: Int,
    ): IntArray {
        if (distance <= 1) return peaks
        val keep = BooleanArray(peaks.size) { true }
        val byPriority = peaks.indices.sortedBy { x[peaks[it]] }
        for (rank in byPriority.indices.reversed()) {
            val j = byPriority[rank]
            if (keep[j]) {
                var k = j - 1
                while (k >= 0 && peaks[j] - peaks[k] < distance) {
                    keep[k] = false
                    k--
                }
                k = j + 1
                while (k < peaks.size && peaks[k] - peaks[j] < distance) {
                    keep[k] = false
                    k++
                }
            }
        }
        return peaks.filterIndexed { index, _ -> keep[index] }.toIntArray()
    }

    /** Topographic prominence of each peak (scipy `_peak_prominences` without a window length). */
    fun prominences(
        x: DoubleArray,
        peaks: IntArray,
    ): DoubleArray =
        DoubleArray(peaks.size) { n ->
            val peak = peaks[n]
            var leftMin = x[peak]
            var i = peak
            while (i >= 0 && x[i] <= x[peak]) {
                if (x[i] < leftMin) leftMin = x[i]
                i--
            }
            var rightMin = x[peak]
            i = peak
            while (i < x.size && x[i] <= x[peak]) {
                if (x[i] < rightMin) rightMin = x[i]
                i++
            }
            x[peak] - maxOf(leftMin, rightMin)
        }

    /** `find_peaks(x, distance = distance, prominence = minProminence)`. */
    fun findPeaks(
        x: DoubleArray,
        distance: Int,
        minProminence: Double,
    ): IntArray {
        val candidates = selectByDistance(x, localMaxima(x), distance)
        val prominence = prominences(x, candidates)
        return candidates.filterIndexed { index, _ -> prominence[index] >= minProminence }.toIntArray()
    }

    /** Local minima of [series] (peaks of −series); NaN values are handled as in [prepareForSearch]. */
    fun findMinima(
        series: DoubleArray,
        distance: Int,
        minProminence: Double,
    ): IntArray = search(series, distance, minProminence, negate = true)

    /** Local maxima of [series] with the same NaN handling as [findMinima]. */
    fun findMaxima(
        series: DoubleArray,
        distance: Int,
        minProminence: Double,
    ): IntArray = search(series, distance, minProminence, negate = false)

    private fun search(
        series: DoubleArray,
        distance: Int,
        minProminence: Double,
        negate: Boolean,
    ): IntArray {
        val prepared = prepareForSearch(series) ?: return IntArray(0)
        val sign = if (negate) -1.0 else 1.0
        val x = DoubleArray(prepared.values.size) { sign * prepared.values[it] }
        return findPeaks(x, distance, minProminence).map { it + prepared.offset }.toIntArray()
    }

    /**
     * Restricts [series] to the span between its first and last non-NaN values and fills interior NaN gaps by
     * linear interpolation (used only for the extremum search). Returns null if there are no values.
     */
    private fun prepareForSearch(series: DoubleArray): FilledSpan? {
        val first = series.indexOfFirst { !it.isNaN() }
        if (first < 0) return null
        val last = series.indexOfLast { !it.isNaN() }
        val values = series.copyOfRange(first, last + 1)
        var previous = 0
        for (i in 1 until values.size) {
            if (!values[i].isNaN()) {
                for (j in previous + 1 until i) {
                    values[j] = values[previous] + (values[i] - values[previous]) * (j - previous) / (i - previous)
                }
                previous = i
            }
        }
        return FilledSpan(first, values)
    }

    private class FilledSpan(
        val offset: Int,
        val values: DoubleArray,
    )
}

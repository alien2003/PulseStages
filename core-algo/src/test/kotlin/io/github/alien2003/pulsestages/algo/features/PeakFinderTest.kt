package io.github.alien2003.pulsestages.algo.features

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Reference values computed with scipy 1.18.1 (`scipy.signal.find_peaks`, `peak_prominences`). */
class PeakFinderTest {
    private val x = doubleArrayOf(0.0, 1.0, 0.0, 3.0, 1.0, 3.0, 0.0, 4.0, 0.0, 2.0, 2.0, 2.0, 0.0, 1.0, 5.0, 1.0)

    @Test
    fun `local maxima include the midpoint of flat peaks and never the ends`() {
        assertArrayEquals(intArrayOf(1, 3, 5, 7, 10, 14), PeakFinder.localMaxima(x))
        assertArrayEquals(intArrayOf(2), PeakFinder.localMaxima(doubleArrayOf(0.0, 1.0, 1.0, 1.0, 1.0, 0.0)))
        assertArrayEquals(intArrayOf(), PeakFinder.localMaxima(doubleArrayOf(3.0, 2.0, 1.0)))
        assertArrayEquals(intArrayOf(), PeakFinder.localMaxima(doubleArrayOf(0.0, 1.0, 1.0)))
    }

    @Test
    fun `prominences match scipy`() {
        val peaks = PeakFinder.localMaxima(x)

        assertArrayEquals(doubleArrayOf(1.0, 3.0, 3.0, 4.0, 2.0, 4.0), PeakFinder.prominences(x, peaks), 1e-12)
    }

    @Test
    fun `find peaks with prominence and distance matches scipy`() {
        assertArrayEquals(intArrayOf(3, 5, 7, 10, 14), PeakFinder.findPeaks(x, distance = 1, minProminence = 2.0))
        assertArrayEquals(intArrayOf(3, 7, 14), PeakFinder.findPeaks(x, distance = 4, minProminence = 0.0))
        assertArrayEquals(intArrayOf(3, 7, 14), PeakFinder.findPeaks(x, distance = 4, minProminence = 2.0))
    }

    @Test
    fun `minima of a heart-rate like series`() {
        val hr =
            doubleArrayOf(
                60.0,
                58.0,
                55.0,
                53.0,
                52.0,
                53.0,
                56.0,
                59.0,
                61.0,
                60.0,
                57.0,
                54.0,
                52.5,
                54.0,
                57.0,
                60.0,
                62.0,
                61.0,
                59.0,
                55.0,
                54.0,
                55.0,
                58.0,
                60.0,
            )
        val negated = DoubleArray(hr.size) { -hr[it] }

        assertArrayEquals(doubleArrayOf(8.0, 8.5, 6.0), PeakFinder.prominences(negated, PeakFinder.localMaxima(negated)), 1e-12)
        assertArrayEquals(intArrayOf(4, 12, 20), PeakFinder.findMinima(hr, distance = 5, minProminence = 2.0))
        assertArrayEquals(intArrayOf(4, 20), PeakFinder.findMinima(hr, distance = 9, minProminence = 2.0))
    }

    @Test
    fun `minima search fills interior NaN linearly and ignores leading and trailing NaN`() {
        val nan = Double.NaN
        val hr = doubleArrayOf(nan, 60.0, 56.0, nan, nan, 50.0, 55.0, 60.0, 58.0, nan, 52.0, 57.0, 61.0, nan)

        val minima = PeakFinder.findMinima(hr, distance = 1, minProminence = 2.0)

        assertArrayEquals(intArrayOf(5, 10), minima)
        assertEquals(0, PeakFinder.findMinima(DoubleArray(5) { nan }, 1, 0.0).size)
    }
}

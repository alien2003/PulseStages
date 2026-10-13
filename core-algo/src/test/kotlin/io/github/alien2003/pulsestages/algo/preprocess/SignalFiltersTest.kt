package io.github.alien2003.pulsestages.algo.preprocess

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignalFiltersTest {
    private val nan = Double.NaN

    @Test
    fun `hampel replaces a spike by the window median`() {
        val values = doubleArrayOf(60.0, 61.0, 59.0, 150.0, 60.0, 62.0, 61.0)

        val result = SignalFilters.hampel(values, window = 5, threshold = 3.0)

        // Window of the spike: [61, 59, 150, 60, 62] -> median 61.
        assertEquals(61.0, result.values[3], 0.0)
        assertEquals(1, result.replaced)
        listOf(0, 1, 2, 4, 5, 6).forEach { assertEquals(values[it], result.values[it], 0.0, "index $it") }
    }

    @Test
    fun `hampel keeps values within the threshold and uses truncated windows at the ends`() {
        val values = doubleArrayOf(100.0, 60.0, 61.0, 62.0, 60.0, 61.0)

        val result = SignalFilters.hampel(values, window = 5, threshold = 3.0)

        // First window is [100, 60, 61]: median 61, MAD 1 -> 39 > 3*1.4826 -> replaced.
        assertEquals(61.0, result.values[0], 0.0)
        assertEquals(1, result.replaced)
        // Second value: window [100, 60, 61, 62] median 61.5, MAD 1 -> |60 - 61.5| = 1.5 is kept.
        assertEquals(60.0, result.values[1], 0.0)
    }

    @Test
    fun `hampel works on the original series, not on already replaced values`() {
        val values = doubleArrayOf(60.0, 60.0, 140.0, 140.0, 60.0, 60.0, 60.0)

        val result = SignalFilters.hampel(values, window = 5, threshold = 3.0)

        // Window of index 2 is [60, 60, 140, 140, 60] -> median 60, MAD 0 -> replaced; same for index 3.
        assertEquals(60.0, result.values[2], 0.0)
        assertEquals(60.0, result.values[3], 0.0)
    }

    @Test
    fun `hampel scales the MAD by 1_4826`() {
        // Window = whole series: median 62, MAD 2. |69 − 62| = 7 lies between 3·MAD = 6 and 3·1.4826·MAD = 8.9.
        val values = doubleArrayOf(56.0, 60.0, 69.0, 64.0, 62.0)

        val result = SignalFilters.hampel(values, window = 5, threshold = 3.0)

        assertArrayEquals(values, result.values, 0.0)
        assertEquals(0, result.replaced)
        assertEquals(62.0, SignalFilters.hampel(doubleArrayOf(56.0, 60.0, 72.0, 64.0, 62.0), 5, 3.0).values[2], 0.0)
    }

    @Test
    fun `a minimum scale keeps whole-bpm variability but still removes spikes`() {
        // Three equal whole-bpm readings in a window give MAD = 0.
        val integer = doubleArrayOf(58.0, 58.0, 59.0, 58.0, 58.0, 60.0, 58.0)
        val drifting =
            doubleArrayOf(58.0, 58.0, 58.0, 59.0, 59.0, 58.0, 59.0, 60.0, 60.0, 60.0, 61.0, 61.0, 60.0, 61.0, 62.0, 62.0, 62.0, 63.0)

        // Classic Hampel (no floor) flattens genuine 1-bpm changes ...
        assertTrue(SignalFilters.hampel(integer, 5, 3.0).values.all { it == 58.0 })
        assertEquals(5, SignalFilters.hampel(drifting, 5, 3.0).replaced)
        // ... the floor of 1 bpm (threshold 3 bpm) keeps them ...
        for (values in listOf(integer, drifting)) {
            val floored = SignalFilters.hampel(values, 5, 3.0, minScale = 1.0)
            assertArrayEquals(values, floored.values, 0.0)
            assertEquals(0, floored.replaced)
        }
        // ... and a lone spike in a MAD = 0 window is still replaced.
        val spike = SignalFilters.hampel(doubleArrayOf(60.0, 60.0, 100.0, 60.0, 60.0), 5, 3.0, minScale = 1.0)
        assertEquals(60.0, spike.values[2], 0.0)
        assertEquals(1, spike.replaced)
        // The threshold in such windows is threshold · minScale = 3 bpm.
        assertEquals(63.0, SignalFilters.hampel(doubleArrayOf(60.0, 60.0, 63.0, 60.0, 60.0), 5, 3.0, minScale = 1.0).values[2], 0.0)
        assertEquals(60.0, SignalFilters.hampel(doubleArrayOf(60.0, 60.0, 64.0, 60.0, 60.0), 5, 3.0, minScale = 1.0).values[2], 0.0)
    }

    @Test
    fun `running median ignores NaN and does not cross gaps`() {
        val values = doubleArrayOf(1.0, 5.0, 2.0, nan, 10.0, 0.0, 4.0, 4.0)

        val result = SignalFilters.runningMedian(values, window = 3)

        assertArrayEquals(doubleArrayOf(3.0, 2.0, 3.5, nan, 5.0, 4.0, 4.0, 4.0), result, 1e-12)
    }

    @Test
    fun `moving average is centred, ignores NaN and does not cross gaps`() {
        val values = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, nan, 10.0, 20.0)

        val result = SignalFilters.movingAverage(values, window = 5)

        // index 0: [1,2,3]; 1: [1..4]; 2: [1..5]; 3: [2..5] (index 5 is NaN); 4: [3..5]; 6: [10,20]; 7: [10,20]
        assertArrayEquals(doubleArrayOf(2.0, 2.5, 3.0, 3.5, 4.0, nan, 15.0, 15.0), result, 1e-12)
        assertTrue(result[5].isNaN())
    }

    @Test
    fun `window of one is the identity`() {
        val values = doubleArrayOf(1.0, nan, 3.0)

        assertArrayEquals(values, SignalFilters.movingAverage(values, 1), 0.0)
        assertArrayEquals(values, SignalFilters.runningMedian(values, 1), 0.0)
    }
}

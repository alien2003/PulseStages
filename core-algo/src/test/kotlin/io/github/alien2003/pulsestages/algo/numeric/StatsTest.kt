package io.github.alien2003.pulsestages.algo.numeric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class StatsTest {
    @Test
    fun `percentile matches numpy linear method`() {
        // Reference values from numpy 2.5.4: np.percentile(data, p) (default method "linear").
        assertEquals(1.3, Stats.percentile(doubleArrayOf(1.0, 2.0, 3.0, 4.0), 10.0), 1e-12)
        assertEquals(2.5, Stats.percentile(doubleArrayOf(4.0, 3.0, 2.0, 1.0), 50.0), 1e-12)
        val five = doubleArrayOf(15.0, 20.0, 35.0, 40.0, 50.0)
        assertEquals(17.0, Stats.percentile(five, 10.0), 1e-12)
        assertEquals(29.0, Stats.percentile(five, 40.0), 1e-12)
        assertEquals(35.0, Stats.percentile(five, 50.0), 1e-12)
        assertEquals(46.0, Stats.percentile(five, 90.0), 1e-12)
        val seven = doubleArrayOf(3.5, 1.2, 9.9, 7.7, 7.7, 2.0, 5.5)
        val expected = mapOf(0.0 to 1.2, 10.0 to 1.68, 25.0 to 2.75, 50.0 to 5.5, 75.0 to 7.7, 99.0 to 9.768, 100.0 to 9.9)
        expected.forEach { (p, value) -> assertEquals(value, Stats.percentile(seven, p), 1e-12, "p=$p") }
        assertEquals(42.0, Stats.percentile(doubleArrayOf(42.0), 10.0), 0.0)
    }

    @Test
    fun `percentile and median of empty input are NaN`() {
        assertTrue(Stats.percentile(DoubleArray(0), 50.0).isNaN())
        assertTrue(Stats.median(DoubleArray(0)).isNaN())
    }

    @Test
    fun `median of odd and even sizes`() {
        assertEquals(3.0, Stats.median(doubleArrayOf(5.0, 1.0, 3.0)), 0.0)
        assertEquals(2.5, Stats.median(doubleArrayOf(4.0, 1.0, 3.0, 2.0)), 0.0)
    }

    @Test
    fun `median does not modify its input`() {
        val values = doubleArrayOf(5.0, 1.0, 3.0)
        Stats.median(values)
        assertEquals(listOf(5.0, 1.0, 3.0), values.toList())
    }

    @Test
    fun `population standard deviation`() {
        assertEquals(2.0, Stats.populationStd(doubleArrayOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)), 1e-12)
        assertEquals(0.0, Stats.populationStd(doubleArrayOf(3.0)), 0.0)
        assertTrue(Stats.populationStd(DoubleArray(0)).isNaN())
    }

    @Test
    fun `least squares slope`() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0)
        assertEquals(2.0, Stats.slope(xs, doubleArrayOf(1.0, 3.0, 5.0, 7.0)), 1e-12)
        // y = [1, 2, 2, 4] -> slope 0.9 (numpy.polyfit(x, y, 1)[0]).
        assertEquals(0.9, Stats.slope(xs, doubleArrayOf(1.0, 2.0, 2.0, 4.0)), 1e-12)
        assertTrue(Stats.slope(doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, 2.0)).isNaN())
    }

    @Test
    fun `log sum exp is stable and handles minus infinity`() {
        assertEquals(ln(exp(1.0) + exp(2.0)), LogMath.logSumExp(doubleArrayOf(1.0, 2.0)), 1e-12)
        assertEquals(1000.0 + ln(2.0), LogMath.logSumExp(doubleArrayOf(1000.0, 1000.0)), 1e-9)
        assertEquals(5.0, LogMath.logSumExp(doubleArrayOf(Double.NEGATIVE_INFINITY, 5.0)), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, LogMath.logSumExp(doubleArrayOf(Double.NEGATIVE_INFINITY)), 0.0)
    }

    @Test
    fun `log normal density`() {
        val expected = -0.5 * ln(2 * Math.PI) - ln(2.0) - 0.5 * 0.25
        assertEquals(expected, LogMath.logNormalPdf(x = 2.0, mean = 1.0, sd = 2.0), 1e-12)
        assertEquals(-0.5 * ln(2 * Math.PI), LogMath.logNormalPdf(0.0, 0.0, 1.0), 1e-12)
        assertEquals(1.0 / sqrt(2 * Math.PI), exp(LogMath.logNormalPdf(0.0, 0.0, 1.0)), 1e-12)
    }
}

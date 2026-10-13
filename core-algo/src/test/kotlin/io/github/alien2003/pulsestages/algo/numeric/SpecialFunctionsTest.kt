package io.github.alien2003.pulsestages.algo.numeric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class SpecialFunctionsTest {
    @Test
    fun `log gamma matches known values`() {
        assertEquals(ln(24.0), SpecialFunctions.logGamma(5.0), 1e-12)
        assertEquals(ln(sqrt(PI)), SpecialFunctions.logGamma(0.5), 1e-12)
        assertEquals(0.0, SpecialFunctions.logGamma(1.0), 1e-12)
        assertEquals(ln(0.75 * sqrt(PI)), SpecialFunctions.logGamma(2.5), 1e-12)
        // Γ(10.3) by the recurrence from Γ(0.3) = 2.991568987687591.
        var gamma = 2.991568987687591
        var x = 0.3
        while (x < 10.0) {
            gamma *= x
            x += 1
        }
        assertEquals(ln(gamma), SpecialFunctions.logGamma(10.3), 1e-11)
    }

    @Test
    fun `incomplete gamma matches closed forms`() {
        for (x in listOf(0.0, 0.01, 0.5, 1.0, 2.0, 3.7, 10.0, 40.0)) {
            assertEquals(1 - exp(-x), SpecialFunctions.regularizedGammaP(1.0, x), 1e-13, "P(1, $x)")
            assertEquals(1 - exp(-x) * (1 + x), SpecialFunctions.regularizedGammaP(2.0, x), 1e-13, "P(2, $x)")
            assertEquals(exp(-x) * (1 + x + x * x / 2), SpecialFunctions.regularizedGammaQ(3.0, x), 1e-13, "Q(3, $x)")
        }
    }

    @Test
    fun `lower and upper incomplete gamma are complementary and monotone`() {
        for (a in listOf(0.3, 1.0, 2.5, 7.0, 30.0)) {
            var previous = 0.0
            for (i in 0..200) {
                val x = i * 0.25
                val p = SpecialFunctions.regularizedGammaP(a, x)
                assertEquals(1.0, p + SpecialFunctions.regularizedGammaQ(a, x), 1e-12, "a=$a x=$x")
                assertEquals(true, p >= previous - 1e-15, "P(a, x) must not decrease: a=$a x=$x")
                previous = p
            }
        }
        assertEquals(1.0, SpecialFunctions.regularizedGammaP(2.0, Double.POSITIVE_INFINITY))
        assertEquals(0.0, SpecialFunctions.regularizedGammaQ(2.0, Double.POSITIVE_INFINITY))
        assertEquals(1.0, SpecialFunctions.regularizedGammaQ(2.0, -1.0))
    }

    @Test
    fun `upper tail keeps relative accuracy`() {
        // Q(1, x) = e^{-x} far below double epsilon relative to 1.
        assertEquals(1.0, SpecialFunctions.regularizedGammaQ(1.0, 60.0) / exp(-60.0), 1e-10)
        assertEquals(1.0, SpecialFunctions.regularizedGammaQ(2.0, 80.0) / (exp(-80.0) * 81), 1e-10)
    }
}

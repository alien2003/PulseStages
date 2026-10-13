package io.github.alien2003.pulsestages.algo.numeric

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Log-gamma and the regularized incomplete gamma functions (used for the gamma bout-duration distributions of the
 * hybrid estimator). Pure Kotlin, deterministic.
 */
internal object SpecialFunctions {
    private const val HALF = 0.5
    private const val LANCZOS_G = 7.0
    private const val L0 = 0.99999999999980993
    private const val L1 = 676.5203681218851
    private const val L2 = -1259.1392167224028
    private const val L3 = 771.32342877765313
    private const val L4 = -176.61502916214059
    private const val L5 = 12.507343278686905
    private const val L6 = -0.13857109526572012
    private const val L7 = 9.9843695780195716e-6
    private const val L8 = 1.5056327351493116e-7
    private val LANCZOS: DoubleArray = doubleArrayOf(L0, L1, L2, L3, L4, L5, L6, L7, L8)
    private val HALF_LOG_TWO_PI: Double = HALF * ln(2 * PI)
    private const val MAX_ITERATIONS = 1000
    private const val RELATIVE_EPS = 1e-15
    private const val TINY = 1e-300

    /** `ln Γ(x)` for x > 0 (Lanczos approximation, g = 7, n = 9; relative error ≈ 1e-15). */
    fun logGamma(x: Double): Double {
        require(x > 0.0 && x.isFinite()) { "logGamma needs a positive finite argument, was $x" }
        if (x < HALF) return ln(PI / sin(PI * x)) - logGamma(1 - x)
        val z = x - 1
        var sum = LANCZOS[0]
        for (i in 1 until LANCZOS.size) sum += LANCZOS[i] / (z + i)
        val t = z + LANCZOS_G + HALF
        return HALF_LOG_TWO_PI + (z + HALF) * ln(t) - t + ln(sum)
    }

    /** Lower regularized incomplete gamma `P(a, x) = γ(a, x)/Γ(a)` for shape a > 0 (0 for x ≤ 0). */
    fun regularizedGammaP(
        a: Double,
        x: Double,
    ): Double =
        when {
            x <= 0.0 -> 0.0
            x.isInfinite() -> 1.0
            x < a + 1 -> lowerSeries(a, x)
            else -> 1 - upperContinuedFraction(a, x)
        }

    /** Upper regularized incomplete gamma `Q(a, x) = 1 − P(a, x)`, accurate in the far tail. */
    fun regularizedGammaQ(
        a: Double,
        x: Double,
    ): Double =
        when {
            x <= 0.0 -> 1.0
            x.isInfinite() -> 0.0
            x < a + 1 -> 1 - lowerSeries(a, x)
            else -> upperContinuedFraction(a, x)
        }

    /** Series `P(a, x) = e^{−x} x^a / Γ(a+1) · Σ x^n / ((a+1)…(a+n))`, converges fast for x < a + 1. */
    private fun lowerSeries(
        a: Double,
        x: Double,
    ): Double {
        var term = 1.0 / a
        var sum = term
        var denominator = a
        var iteration = 0
        while (iteration < MAX_ITERATIONS && abs(term) >= abs(sum) * RELATIVE_EPS) {
            denominator += 1
            term *= x / denominator
            sum += term
            iteration++
        }
        return (sum * exp(-x + a * ln(x) - logGamma(a))).coerceIn(0.0, 1.0)
    }

    /** Continued fraction for `Q(a, x)` (modified Lentz), converges fast for x ≥ a + 1. */
    private fun upperContinuedFraction(
        a: Double,
        x: Double,
    ): Double {
        var b = x + 1 - a
        var c = 1 / TINY
        var d = 1 / b
        var h = d
        for (i in 1..MAX_ITERATIONS) {
            val an = -i * (i - a)
            b += 2
            d = an * d + b
            if (abs(d) < TINY) d = TINY
            c = b + an / c
            if (abs(c) < TINY) c = TINY
            d = 1 / d
            val delta = d * c
            h *= delta
            if (abs(delta - 1) < RELATIVE_EPS) break
        }
        return (exp(-x + a * ln(x) - logGamma(a)) * h).coerceIn(0.0, 1.0)
    }
}

package io.github.alien2003.pulsestages.algo.numeric

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln

/** Log-space arithmetic used by the decoders. */
internal object LogMath {
    private const val HALF = 0.5
    private val HALF_LOG_TWO_PI: Double = HALF * ln(2 * PI)

    /** `log Σ exp(values)`, stable for large magnitudes; −∞ if every value is −∞. */
    fun logSumExp(values: DoubleArray): Double {
        var max = Double.NEGATIVE_INFINITY
        for (v in values) if (v > max) max = v
        if (max == Double.NEGATIVE_INFINITY) return max
        var sum = 0.0
        for (v in values) sum += exp(v - max)
        return max + ln(sum)
    }

    /** Log density of the normal distribution N([mean], [sd]²) at [x]. */
    fun logNormalPdf(
        x: Double,
        mean: Double,
        sd: Double,
    ): Double {
        val z = (x - mean) / sd
        return -HALF_LOG_TWO_PI - ln(sd) - HALF * z * z
    }

    /** Natural log that maps non-positive probabilities to −∞. */
    fun safeLog(p: Double): Double = if (p > 0.0) ln(p) else Double.NEGATIVE_INFINITY
}

package io.github.alien2003.pulsestages.algo

/**
 * Trailing simple moving average. Element `i` of the result is the mean of `values[max(0, i - window + 1)..i]`,
 * so the output has the same size as the input and the first elements average over fewer samples.
 */
fun trailingMovingAverage(
    values: List<Double>,
    window: Int,
): List<Double> {
    require(window > 0) { "window must be positive, was $window" }
    val result = ArrayList<Double>(values.size)
    var runningSum = 0.0
    values.forEachIndexed { index, value ->
        runningSum += value
        if (index >= window) runningSum -= values[index - window]
        result += runningSum / minOf(index + 1, window)
    }
    return result
}

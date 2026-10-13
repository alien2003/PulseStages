package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.model.SleepStage
import kotlin.math.exp
import kotlin.math.ln

/**
 * Pure-Kotlin inference of the bundle's emission classifier: standardization (NaN features → 0, the training mean),
 * then dense layers with relu hidden activations; the output layer's softmax is evaluated in log space by
 * [logSoftmax] so that tiny posteriors never underflow to 0.
 */
internal class DenseNetwork(
    model: HybridModel,
) {
    private val mean = model.standardization.mean.toDoubleArray()
    private val std = model.standardization.std.toDoubleArray()

    /** Row-major weights (`out × in`), biases and relu flags per layer. */
    private val weights: List<DoubleArray> = model.classifier.layers.map { layer -> layer.weights.flatten().toDoubleArray() }
    private val biases: List<DoubleArray> = model.classifier.layers.map { it.bias.toDoubleArray() }
    private val relu: List<Boolean> = model.classifier.layers.map { it.activation == Activation.RELU }

    /** Number of input features. */
    val inputs: Int = mean.size

    /** Standardized copy of the raw feature vector [x] (NaN → 0). */
    fun standardize(x: DoubleArray): DoubleArray =
        DoubleArray(inputs) { j ->
            val z = (x[j] - mean[j]) / std[j]
            if (z.isNaN()) 0.0 else z
        }

    /** Output-layer pre-activations (logits, [SleepStage.index] order) for the raw feature vector [x]. */
    fun logits(x: DoubleArray): DoubleArray {
        require(x.size == inputs) { "expected $inputs features, got ${x.size}" }
        var activation = standardize(x)
        for (layer in weights.indices) activation = dense(layer, activation)
        return activation
    }

    private fun dense(
        layer: Int,
        input: DoubleArray,
    ): DoubleArray {
        val w = weights[layer]
        val b = biases[layer]
        val n = input.size
        return DoubleArray(b.size) { o ->
            var sum = b[o]
            val offset = o * n
            for (i in 0 until n) sum += w[offset + i] * input[i]
            if (relu[layer] && sum < 0) 0.0 else sum
        }
    }

    /** Log-space classifier evaluation. */
    companion object {
        /** `log softmax(logits + delta)`; [delta] (personal bias deltas) may be null. */
        fun logSoftmax(
            logits: DoubleArray,
            delta: DoubleArray?,
        ): DoubleArray {
            val shifted = DoubleArray(logits.size) { logits[it] + (delta?.get(it) ?: 0.0) }
            var max = Double.NEGATIVE_INFINITY
            for (v in shifted) if (v > max) max = v
            var sum = 0.0
            for (v in shifted) sum += exp(v - max)
            val logNorm = max + ln(sum)
            return DoubleArray(shifted.size) { shifted[it] - logNorm }
        }
    }
}

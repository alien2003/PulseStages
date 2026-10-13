package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.testing.MinimalBundles
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.ln

class DenseNetworkTest {
    private val mlp =
        MinimalBundles.logreg().copy(
            featureNames = listOf("a", "b"),
            standardization = Standardization(mean = listOf(1.0, 2.0), std = listOf(2.0, 4.0)),
            classifier =
                ClassifierSpec(
                    ClassifierType.MLP,
                    listOf(
                        DenseLayer(listOf(listOf(1.0, -1.0), listOf(-2.0, 0.5)), listOf(0.0, 0.5), Activation.RELU),
                        DenseLayer(
                            listOf(listOf(1.0, 0.0), listOf(0.0, 1.0), listOf(2.0, 0.0), listOf(0.0, 0.0)),
                            listOf(0.0, 0.0, 0.0, 1.0),
                            Activation.SOFTMAX,
                        ),
                    ),
                ),
        )

    @Test
    fun `standardizes, maps NaN to the mean and applies relu layers`() {
        val network = DenseNetwork(mlp)

        assertArrayEquals(doubleArrayOf(1.0, 0.0), network.standardize(doubleArrayOf(3.0, Double.NaN)), 1e-12)
        // z = (1, 0) → hidden relu(1·1 − 1·0 + 0, −2·1 + 0.5·0 + 0.5) = (1, 0) → logits (1, 0, 2, 1).
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 2.0, 1.0), network.logits(doubleArrayOf(3.0, Double.NaN)), 1e-12)
    }

    @Test
    fun `log softmax is normalized, honours the bias deltas and never underflows`() {
        val logits = doubleArrayOf(1.0, 0.0, 2.0, 1.0)
        val norm = ln(exp(1.0) + exp(0.0) + exp(2.0) + exp(1.0))
        assertArrayEquals(DoubleArray(4) { logits[it] - norm }, DenseNetwork.logSoftmax(logits, null), 1e-12)

        val delta = doubleArrayOf(0.0, 0.0, -2.0, 0.0)
        val shifted = DenseNetwork.logSoftmax(logits, delta)
        assertEquals(1.0, shifted.sumOf { exp(it) }, 1e-12)
        assertEquals(shifted[0], shifted[3], 1e-12)
        assertEquals(shifted[1], shifted[2], 1e-12, "logit 2 − 2 equals logit 0")

        val extreme = DenseNetwork.logSoftmax(doubleArrayOf(1000.0, 0.0, -1000.0, 0.0), null)
        assertTrue(extreme.all { it.isFinite() })
        assertEquals(-2000.0, extreme[2], 1e-9)
    }
}

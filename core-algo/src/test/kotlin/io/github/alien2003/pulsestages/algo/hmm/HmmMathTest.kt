package io.github.alien2003.pulsestages.algo.hmm

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

class HmmMathTest {
    private class Tiny(
        val model: LogHmm,
        val emissions: Array<DoubleArray>,
    )

    private fun randomStochastic(
        random: Random,
        size: Int,
    ): DoubleArray {
        val raw = DoubleArray(size) { random.nextDouble(0.05, 1.0) }
        val sum = raw.sum()
        return DoubleArray(size) { ln(raw[it] / sum) }
    }

    private fun tiny(
        seed: Int,
        states: Int,
        epochs: Int,
        forbidSome: Boolean = false,
    ): Tiny {
        val random = Random(seed)
        val initial = randomStochastic(random, states)
        val transitions =
            Array(epochs) { k ->
                if (k == 0) {
                    DoubleArray(0)
                } else {
                    DoubleArray(states * states).also { flat ->
                        repeat(states) { i -> randomStochastic(random, states).copyInto(flat, i * states) }
                    }
                }
            }
        val emissions =
            Array(epochs) { k ->
                DoubleArray(states) { s ->
                    if (forbidSome && k % 2 == 1 && s != 0) Double.NEGATIVE_INFINITY else random.nextDouble(-3.0, 0.0)
                }
            }
        return Tiny(LogHmm(initial, transitions), emissions)
    }

    /** log P(path, observations) for every one of states^epochs paths. */
    private fun enumerate(tiny: Tiny): List<Pair<IntArray, Double>> {
        val states = tiny.model.states
        val epochs = tiny.emissions.size
        var total = 1
        repeat(epochs) { total *= states }
        return (0 until total).map { code ->
            var rest = code
            val path = IntArray(epochs) { (rest % states).also { rest /= states } }
            var logP = tiny.model.logInitial[path[0]] + tiny.emissions[0][path[0]]
            for (k in 1 until epochs) {
                logP += tiny.model.logTransitions[k][path[k - 1] * states + path[k]] + tiny.emissions[k][path[k]]
            }
            path to logP
        }
    }

    @Test
    fun `viterbi finds the most probable path of a tiny HMM`() {
        repeat(25) { seed ->
            val tiny = tiny(seed, states = 4, epochs = 5)
            val best = enumerate(tiny).maxBy { it.second }

            assertArrayEquals(best.first, HmmMath.viterbi(tiny.model, tiny.emissions), "seed=$seed")
        }
    }

    @Test
    fun `viterbi respects forbidden states`() {
        repeat(10) { seed ->
            val tiny = tiny(seed, states = 3, epochs = 6, forbidSome = true)
            val best = enumerate(tiny).maxBy { it.second }
            val path = HmmMath.viterbi(tiny.model, tiny.emissions)

            assertArrayEquals(best.first, path, "seed=$seed")
            for (k in 1 until path.size step 2) assertEquals(0, path[k])
        }
    }

    @Test
    fun `forward backward marginals and likelihood match brute force`() {
        repeat(25) { seed ->
            val tiny = tiny(seed, states = 4, epochs = 5, forbidSome = seed % 2 == 0)
            val paths = enumerate(tiny)
            val logZ = ln(paths.sumOf { exp(it.second) })
            val result = HmmMath.forwardBackward(tiny.model, tiny.emissions)

            assertEquals(logZ, result.logLikelihood, 1e-9, "seed=$seed")
            for (k in tiny.emissions.indices) {
                for (s in 0 until 4) {
                    val marginal = paths.filter { it.first[k] == s }.sumOf { exp(it.second - logZ) }
                    assertEquals(marginal, result.posteriors[k][s], 1e-9, "seed=$seed k=$k s=$s")
                }
                assertEquals(1.0, result.posteriors[k].sum(), 1e-12)
            }
        }
    }

    @Test
    fun `single epoch reduces to the normalized prior times emission`() {
        val model = LogHmm(doubleArrayOf(ln(0.25), ln(0.75)), arrayOf(DoubleArray(0)))
        val emissions = arrayOf(doubleArrayOf(ln(0.6), ln(0.2)))

        val result = HmmMath.forwardBackward(model, emissions)

        assertEquals(0.5, result.posteriors[0][0], 1e-12)
        assertArrayEquals(intArrayOf(1), HmmMath.viterbi(model, arrayOf(doubleArrayOf(ln(0.2), ln(0.6)))))
    }
}

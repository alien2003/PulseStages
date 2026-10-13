package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.hmm.HmmMath
import io.github.alien2003.pulsestages.algo.hmm.LogHmm
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/** HSMM Viterbi and forward–backward against brute-force enumeration of every segmentation of tiny problems. */
class HsmmMathTest {
    private val negInf = Double.NEGATIVE_INFINITY

    private class Problem(
        val model: HsmmModel,
        val emissions: Array<DoubleArray>,
        val description: String,
    )

    private class Segmentation(
        val path: IntArray,
        val logScore: Double,
    )

    private fun maybeForbidden(
        random: Random,
        chance: Double,
        value: () -> Double,
    ): Double = if (random.nextDouble() < chance) negInf else value()

    /** Random problem: 2–3 states, 1–8 epochs, random Dmax, forbidden self-transitions, emissions and durations. */
    private fun problem(seed: Int): Problem {
        val random = Random(seed)
        val states = random.nextInt(2, 4)
        val n = random.nextInt(1, if (states == 2) 9 else 8)
        val maxD = random.nextInt(1, n + 1)

        fun table() = Array(states) { DoubleArray(maxD) { maybeForbidden(random, 0.1) { random.nextDouble(-3.0, 0.0) } } }
        val transitions =
            Array(states) { i ->
                DoubleArray(states) { j -> if (i == j && random.nextBoolean()) negInf else ln(random.nextDouble(0.05, 1.0)) }
            }
        val model =
            HsmmModel(
                logInitial = DoubleArray(states) { maybeForbidden(random, 0.15) { ln(random.nextDouble(0.05, 1.0)) } },
                logTransitions = transitions,
                logDuration = table(),
                logDurationFirst = table(),
                logDurationLast = table(),
                logDurationOnly = table(),
            )
        val emissions = Array(n) { DoubleArray(states) { maybeForbidden(random, 0.15) { random.nextDouble(-4.0, 1.0) } } }
        return Problem(model, emissions, "seed=$seed states=$states n=$n maxD=$maxD")
    }

    private fun enumerate(problem: Problem): List<Segmentation> {
        val out = ArrayList<Segmentation>()
        val n = problem.emissions.size
        val path = IntArray(n)

        fun extend(
            from: Int,
            previous: Int,
            score: Double,
        ) {
            if (from == n) {
                out += Segmentation(path.copyOf(), score)
                return
            }
            for (d in 1..minOf(problem.model.maxDuration, n - from)) {
                val to = from + d - 1
                for (j in 0 until problem.model.states) {
                    val start = if (from == 0) problem.model.logInitial[j] else problem.model.logTransitions[previous][j]
                    val duration = problem.model.logDuration(j, d, from == 0, to == n - 1)
                    val emission = (from..to).sumOf { problem.emissions[it][j] }
                    for (u in from..to) path[u] = j
                    extend(to + 1, j, score + start + duration + emission)
                }
            }
        }
        extend(0, -1, 0.0)
        return out
    }

    private fun logSumExp(values: List<Double>): Double {
        val max = values.maxOrNull() ?: negInf
        return if (max == negInf) negInf else max + ln(values.sumOf { exp(it - max) })
    }

    @Test
    fun `viterbi finds the best segmentation`() {
        var feasible = 0
        repeat(400) { seed ->
            val problem = problem(seed)
            val all = enumerate(problem)
            val best = all.maxOf { it.logScore }
            val result = HsmmMath.viterbi(problem.model, problem.emissions)

            if (best == negInf) {
                assertEquals(negInf, result.logScore, problem.description)
            } else {
                feasible++
                assertEquals(best, result.logScore, 1e-9, problem.description)
                val withPath = all.filter { it.path.contentEquals(result.states) }.maxOf { it.logScore }
                assertEquals(best, withPath, 1e-9, "decoded path must realize the best score: ${problem.description}")
            }
        }
        assertTrue(feasible > 200, "too few feasible problems: $feasible")
    }

    @Test
    fun `forward-backward matches brute-force marginals and likelihood`() {
        repeat(400) { seed ->
            val problem = problem(seed)
            val all = enumerate(problem)
            val logZ = logSumExp(all.map { it.logScore })
            val result = HsmmMath.forwardBackward(problem.model, problem.emissions)

            if (logZ == negInf) {
                assertEquals(negInf, result.logLikelihood, problem.description)
            } else {
                assertEquals(logZ, result.logLikelihood, 1e-9, problem.description)
                for (t in problem.emissions.indices) {
                    val expected =
                        DoubleArray(problem.model.states) { s ->
                            all.filter { it.path[t] == s && it.logScore > negInf }.sumOf { exp(it.logScore - logZ) }
                        }
                    assertArrayEquals(expected, result.posteriors[t], 1e-9, "t=$t ${problem.description}")
                }
            }
        }
    }

    /** HSMM with geometric durations (first bout not censored) and an HMM with the same self-transitions. */
    private fun geometricPair(seed: Int): Triple<HsmmModel, LogHmm, Array<DoubleArray>> {
        val random = Random(seed)
        val states = random.nextInt(2, 5)
        val n = random.nextInt(1, 30)
        val q = DoubleArray(states) { random.nextDouble(0.3, 0.95) }
        val shares =
            Array(states) { i ->
                val raw = DoubleArray(states) { j -> if (i == j) 0.0 else random.nextDouble(0.05, 1.0) }
                DoubleArray(states) { raw[it] / raw.sum() }
            }
        val initial = DoubleArray(states) { random.nextDouble(0.05, 1.0) }.let { raw -> DoubleArray(states) { ln(raw[it] / raw.sum()) } }
        val pmf = Array(states) { s -> DoubleArray(n) { d -> ln(1 - q[s]) + d * ln(q[s]) } }
        val survival = Array(states) { s -> DoubleArray(n) { d -> d * ln(q[s]) } }
        val hsmm =
            HsmmModel(
                logInitial = initial,
                logTransitions = Array(states) { i -> DoubleArray(states) { j -> if (i == j) negInf else ln(shares[i][j]) } },
                logDuration = pmf,
                logDurationFirst = pmf,
                logDurationLast = survival,
                logDurationOnly = survival,
            )
        val flat = DoubleArray(states * states)
        for (i in 0 until states) {
            for (j in 0 until states) flat[i * states + j] = if (i == j) ln(q[i]) else ln((1 - q[i]) * shares[i][j])
        }
        val hmm = LogHmm(initial, Array(n) { if (it == 0) DoubleArray(0) else flat })
        val emissions = Array(n) { DoubleArray(states) { random.nextDouble(-4.0, 1.0) } }
        return Triple(hsmm, hmm, emissions)
    }

    private fun hmmPathScore(
        hmm: LogHmm,
        emissions: Array<DoubleArray>,
        path: IntArray,
    ): Double {
        var score = hmm.logInitial[path[0]] + emissions[0][path[0]]
        for (k in 1 until path.size) score += hmm.logTransitions[k][path[k - 1] * hmm.states + path[k]] + emissions[k][path[k]]
        return score
    }

    @Test
    fun `explicit decoder with geometric durations equals the HMM`() {
        repeat(200) { seed ->
            val (hsmm, hmm, emissions) = geometricPair(seed)
            val explicit = HsmmMath.forwardBackward(hsmm, emissions)
            val plain = HmmMath.forwardBackward(hmm, emissions)
            assertEquals(plain.logLikelihood, explicit.logLikelihood, 1e-9, "seed=$seed")
            for (t in emissions.indices) assertArrayEquals(plain.posteriors[t], explicit.posteriors[t], 1e-9, "seed=$seed t=$t")

            val path = HsmmMath.viterbi(hsmm, emissions)
            val hmmPath = HmmMath.viterbi(hmm, emissions)
            assertEquals(hmmPathScore(hmm, emissions, hmmPath), path.logScore, 1e-9, "seed=$seed")
            assertArrayEquals(hmmPath, path.states, "seed=$seed")
        }
    }

    @Test
    fun `infeasible models are reported`() {
        val forbidden = Array(2) { DoubleArray(2) { if (it == 0) 0.0 else negInf } }
        val durations = Array(2) { DoubleArray(2) { ln(0.5) } }
        val model =
            HsmmModel(
                logInitial = doubleArrayOf(ln(0.5), ln(0.5)),
                logTransitions = arrayOf(doubleArrayOf(negInf, 0.0), doubleArrayOf(0.0, negInf)),
                logDuration = durations,
                logDurationFirst = durations,
                logDurationLast = durations,
                logDurationOnly = durations,
            )
        val emissions = Array(5) { forbidden[0].copyOf() }

        assertEquals(negInf, HsmmMath.viterbi(model, emissions).logScore)
        val fb = HsmmMath.forwardBackward(model, emissions)
        assertEquals(negInf, fb.logLikelihood)
        assertEquals(0, HsmmMath.viterbi(model, emptyArray()).states.size)
    }
}

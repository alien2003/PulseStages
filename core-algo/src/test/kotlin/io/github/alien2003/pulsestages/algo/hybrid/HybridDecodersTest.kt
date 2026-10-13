package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.hmm.HmmMath
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.testing.MinimalBundles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.ln

class HybridDecodersTest {
    private val model = MinimalBundles.logreg()

    @Test
    fun `discretized gamma pmf sums to one with the tail on Dmax`() {
        for (gamma in listOf(GammaDuration(1.0, 5.0), GammaDuration(3.0, 8.0), GammaDuration(0.6, 30.0), GammaDuration(20.0, 0.5))) {
            for ((epochMin, maxD) in listOf(1.0 to 120, 0.5 to 240, 2.0 to 60, 1.0 to 3)) {
                val table = HybridDecoders.durationTable(gamma, epochMin, maxD)
                assertEquals(1.0, table.pmf.sumOf { exp(it) }, 1e-9, "$gamma epochMin=$epochMin D=$maxD")
                assertEquals(0.0, table.survival[0], 1e-12)
                for (d in 1..maxD) {
                    val tail = (d..maxD).sumOf { exp(table.pmf[it - 1]) }
                    assertEquals(tail, exp(table.survival[d - 1]), 1e-9, "survival at $d for $gamma")
                }
            }
        }
    }

    @Test
    fun `durations are discretized with midpoint bins on the epoch lattice`() {
        // Exponential (shape 1) bout lengths with mean θ minutes on 2-minute epochs: F(x) = 1 − exp(−x/θ).
        val theta = 7.0
        val m = 2.0
        val maxD = 50
        val table = HybridDecoders.durationTable(GammaDuration(1.0, theta), m, maxD)

        fun cdf(x: Double) = 1 - exp(-x / theta)

        assertEquals(cdf(1.5 * m), exp(table.pmf[0]), 1e-12)
        for (d in 2 until maxD) assertEquals(cdf((d + 0.5) * m) - cdf((d - 0.5) * m), exp(table.pmf[d - 1]), 1e-12, "pmf at $d")
        assertEquals(1 - cdf((maxD - 0.5) * m), exp(table.pmf[maxD - 1]), 1e-12)
        assertEquals(1.0, exp(table.survival[0]), 0.0)
        for (d in 2..maxD) assertEquals(1 - cdf((d - 0.5) * m), exp(table.survival[d - 1]), 1e-12, "survival at $d")
    }

    @Test
    fun `discretized lattice data keeps its mean and short bouts`() {
        // A gamma fitted to lattice lengths d·m (as tools/train does) centred on the lattice: mean 6 min on 1-min epochs.
        // Ceiling bins ((d−1)m, dm] would shift the mean up by half an epoch (6.5) and halve P(1).
        for ((gamma, mean) in listOf(GammaDuration(4.0, 1.5) to 6.0, GammaDuration(20.0, 1.0) to 20.0)) {
            val table = HybridDecoders.durationTable(gamma, 1.0, 120)
            val discreteMean = table.pmf.withIndex().sumOf { (i, logP) -> (i + 1) * exp(logP) }
            assertEquals(mean, discreteMean, 0.05, "$gamma")
        }
    }

    @Test
    fun `censored tables are the residual and the survival of the bout length`() {
        for (gamma in listOf(GammaDuration(1.5, 4.0), GammaDuration(3.0, 8.0))) {
            val maxD = 40
            val table = HybridDecoders.durationTable(gamma, 1.0, maxD)
            val survival = DoubleArray(maxD) { exp(table.survival[it]) }
            val mean = survival.sum()
            for (d in 1..maxD) {
                assertEquals(survival[d - 1] / mean, exp(table.first[d - 1]), 1e-12, "first at $d")
                assertEquals((d..maxD).sumOf { survival[it - 1] } / mean, exp(table.only[d - 1]), 1e-12, "only at $d")
            }
            assertEquals(1.0, table.first.sumOf { exp(it) }, 1e-12)
            assertEquals(0.0, table.only[0], 1e-12)
        }
    }

    @Test
    fun `hsmm uses the pmf for interior bouts and the censored tables for the first, last and only bout`() {
        val hsmm = HybridDecoders.hsmm(model, AlgoParams(), 1.0, relaxSelfTransitions = false)
        val tables = model.durations.toList().map { HybridDecoders.durationTable(it, 1.0, hsmm.maxDuration) }
        for (s in 0 until SleepStage.COUNT) {
            for (d in listOf(1, 2, 7, 60, hsmm.maxDuration)) {
                assertEquals(tables[s].pmf[d - 1], hsmm.logDuration(s, d, first = false, last = false), 0.0)
                assertEquals(tables[s].first[d - 1], hsmm.logDuration(s, d, first = true, last = false), 0.0)
                assertEquals(tables[s].survival[d - 1], hsmm.logDuration(s, d, first = false, last = true), 0.0)
                assertEquals(tables[s].only[d - 1], hsmm.logDuration(s, d, first = true, last = true), 0.0)
            }
        }
    }

    @Test
    fun `uninformative emissions reproduce the initial distribution and a total probability of one`() {
        val params = AlgoParams()
        val initial = HmmMath.normalize(DoubleArray(SleepStage.COUNT) { maxOf(model.initialStage[it], params.hmm.epsilon) })
        for (relax in listOf(false, true)) {
            val hsmm = HybridDecoders.hsmm(model, params, 1.0, relaxSelfTransitions = relax)
            // Longer than Dmax (no single bout possible) and shorter than Dmax (the "only" table is used).
            for (n in listOf(480, 30)) {
                val result = HsmmMath.forwardBackward(hsmm, Array(n) { DoubleArray(SleepStage.COUNT) })
                assertEquals(0.0, result.logLikelihood, 1e-9, "relax=$relax n=$n")
                for (s in 0 until SleepStage.COUNT) assertEquals(initial[s], result.posteriors[0][s], 1e-9, "relax=$relax n=$n s=$s")
            }
        }
    }

    @Test
    fun `hsmm keeps forbidden self transitions unless relaxed`() {
        val params = AlgoParams()
        val strict = HybridDecoders.hsmm(model, params, 1.0, relaxSelfTransitions = false)
        val relaxed = HybridDecoders.hsmm(model, params, 1.0, relaxSelfTransitions = true)

        assertEquals(120, strict.maxDuration)
        for (i in 0 until SleepStage.COUNT) {
            assertEquals(Double.NEGATIVE_INFINITY, strict.logTransitions[i][i])
            assertTrue(relaxed.logTransitions[i][i].isFinite())
            assertEquals(1.0, strict.logTransitions[i].sumOf { exp(it) }, 1e-12)
            assertEquals(1.0, relaxed.logTransitions[i].sumOf { exp(it) }, 1e-12)
            assertTrue(
                strict.logTransitions[i]
                    .indices
                    .filter { it != i }
                    .all { j -> strict.logTransitions[i][j].isFinite() },
            )
        }
        assertEquals(1.0, strict.logInitial.sumOf { exp(it) }, 1e-12)
        assertTrue(strict.logInitial.all { it.isFinite() }, "zero initial probabilities are replaced by epsilon")
        assertEquals(60, HybridDecoders.hsmm(model, params, 2.0, false).maxDuration)
        val short = params.copy(hybrid = params.hybrid.copy(maxBoutMin = 0))
        assertEquals(1, HybridDecoders.maxDurationEpochs(short, 1.0))
    }

    @Test
    fun `geometric decoder uses the gamma means for the self transitions`() {
        val hmm = HybridDecoders.geometric(model, AlgoParams(), 1.0, 10)
        assertEquals(10, hmm.logTransitions.size)
        val means = model.durations.toList().map { it.shape * it.scale }
        for (i in 0 until SleepStage.COUNT) {
            val row = DoubleArray(SleepStage.COUNT) { exp(hmm.logTransitions[1][i * SleepStage.COUNT + it]) }
            assertEquals(1.0, row.sum(), 1e-12)
            assertEquals(1 - 1.0 / means[i], row[i], 1e-12)
            val off = model.boutTransitions[i].mapIndexed { j, p -> if (j == i) 0.0 else maxOf(p, 1e-4) }
            for (j in 0 until SleepStage.COUNT) {
                if (j != i) assertEquals((1 - row[i]) * off[j] / off.sum(), row[j], 1e-12)
            }
        }
        assertEquals(ln(0.7 / (1.0 + 2e-4)), hmm.logInitial[0], 1e-9)
    }
}

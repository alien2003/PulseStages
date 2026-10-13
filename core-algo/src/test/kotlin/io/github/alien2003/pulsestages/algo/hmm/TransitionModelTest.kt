package io.github.alien2003.pulsestages.algo.hmm

import io.github.alien2003.pulsestages.algo.params.HmmParams
import io.github.alien2003.pulsestages.algo.params.MeanBouts
import io.github.alien2003.pulsestages.algo.params.TransitionWeights
import io.github.alien2003.pulsestages.algo.preprocess.EpochGrid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp

class TransitionModelTest {
    private val awake = 0
    private val light = 1
    private val deep = 2
    private val rem = 3
    private val start = 1_760_000_000_000L

    private fun grid(
        minutes: Int,
        epochSec: Int = 60,
    ) = EpochGrid(start, start + minutes * 60_000L, epochSec, contextMarginMin = 0)

    @Test
    fun `rows are stochastic at every epoch`() {
        val model = TransitionModel(HmmParams.PHYSIOLOGY, grid(480))
        for (k in 1 until 480) {
            val m = model.matrix(k)
            m.forEachIndexed { i, row -> assertEquals(1.0, row.sum(), 1e-12, "k=$k row=$i") }
        }
        assertEquals(1.0, model.initial().sum(), 1e-12)
    }

    @Test
    fun `self transitions follow the mean bout durations`() {
        val model = TransitionModel(HmmParams.PHYSIOLOGY.copy(remLockoutMin = 0.0), grid(480))
        val m = model.matrix(100)

        assertEquals(1 - 1.0 / 5, m[awake][awake], 1e-12)
        assertEquals(1 - 1.0 / 20, m[light][light], 1e-12)
        assertEquals(1 - 1.0 / 20, m[deep][deep], 1e-12)
        assertEquals(1 - 1.0 / 20, m[rem][rem], 1e-12)

        val twoMinute = TransitionModel(HmmParams.PHYSIOLOGY.copy(remLockoutMin = 0.0), grid(480, epochSec = 120)).matrix(100)
        assertEquals(1 - 2.0 / 5, twoMinute[awake][awake], 1e-12)

        val extremeBouts = HmmParams.PHYSIOLOGY.copy(meanBouts = MeanBouts(awakeMin = 0.5, lightMin = 1e6))
        val clamped = TransitionModel(extremeBouts, grid(480)).matrix(100)
        assertEquals(0.5, clamped[awake][awake], 1e-12)
        assertEquals(0.9999, clamped[light][light], 1e-12)
    }

    @Test
    fun `off-diagonal mass follows the weights and zeros become epsilon`() {
        val model = TransitionModel(HmmParams.PHYSIOLOGY.copy(remLockoutMin = 0.0), grid(480))
        val m = model.matrix(100)
        val eps = 1e-4
        val awakeRemainder = 1.0 / 5
        val awakeSum = 0.9 + eps + 0.1

        assertEquals(awakeRemainder * 0.9 / awakeSum, m[awake][light], 1e-12)
        assertEquals(awakeRemainder * eps / awakeSum, m[awake][deep], 1e-12)
        assertEquals(awakeRemainder * 0.1 / awakeSum, m[awake][rem], 1e-12)
        val deepRemainder = 1.0 / 20
        assertEquals(deepRemainder * 0.85 / 1.0, m[deep][light], 1e-12)
    }

    @Test
    fun `light to deep and light to rem follow the time of night`() {
        val params = HmmParams.PHYSIOLOGY.copy(remLockoutMin = 0.0)
        val n = 400
        val model = TransitionModel(params, grid(n))

        fun ratio(k: Int): Double {
            val m = model.matrix(k)
            return m[light][deep] / m[light][rem]
        }
        for (k in listOf(1, 100, 200, 399)) {
            val t = (k + 0.5) / n
            val deepMultiplier = maxOf(0.3, 1.6 - 1.2 * t)
            val remMultiplier = 0.4 + 1.2 * t
            assertEquals(0.45 * deepMultiplier / (0.35 * remMultiplier), ratio(k), 1e-9, "k=$k")
            val m = model.matrix(k)
            assertEquals(1.0 / 20, m[light][awake] + m[light][deep] + m[light][rem], 1e-12)
        }
        assertTrue(ratio(1) > ratio(399))
    }

    @Test
    fun `rem lockout suppresses transitions into rem and the rem prior`() {
        val params = HmmParams.PHYSIOLOGY
        val model = TransitionModel(params, grid(480))
        val free = TransitionModel(params.copy(remLockoutMin = 0.0), grid(480))

        for (k in listOf(1, 44)) {
            val locked = model.matrix(k)
            val open = free.matrix(k)
            for (i in 0 until 4) {
                assertEquals(open[i][rem] * 0.05, locked[i][rem], 1e-12, "k=$k i=$i")
                assertEquals(1.0, locked[i].sum(), 1e-12)
                // The freed mass goes to the other targets proportionally.
                val others = (0 until 4).filter { it != rem }
                val scale = (1 - locked[i][rem]) / (1 - open[i][rem])
                others.forEach { j -> assertEquals(open[i][j] * scale, locked[i][j], 1e-12) }
            }
        }
        assertEquals(free.matrix(45)[light][rem], model.matrix(45)[light][rem], 1e-15)
        val initial = model.initial()
        // Same rule as for the rows: REM × factor, the freed mass to the other states proportionally.
        val remBefore = 1e-4 / 1.0002
        assertEquals(remBefore * 0.05, initial[rem], 1e-15)
        assertEquals(0.5 / 1.0002 * (1 - remBefore * 0.05) / (1 - remBefore), initial[awake], 1e-12)
    }

    @Test
    fun `initial distribution uses epsilon for deep and rem`() {
        val initial = TransitionModel(HmmParams.PHYSIOLOGY.copy(remLockoutMin = 0.0), grid(60)).initial()
        val sum = 0.5 + 0.5 + 2e-4

        assertEquals(0.5 / sum, initial[awake], 1e-12)
        assertEquals(1e-4 / sum, initial[deep], 1e-12)
        assertEquals(1e-4 / sum, initial[rem], 1e-12)
    }

    @Test
    fun `log model matches the probability matrices`() {
        val model = TransitionModel(HmmParams.PHYSIOLOGY.copy(transitions = TransitionWeights(awakeToRem = 0.0)), grid(30))
        val log = model.logHmm()

        assertEquals(30, log.logTransitions.size)
        assertEquals(model.matrix(7)[awake][rem], exp(log.logTransitions[7][awake * 4 + rem]), 1e-15)
        assertEquals(model.initial()[light], exp(log.logInitial[light]), 1e-15)
    }
}

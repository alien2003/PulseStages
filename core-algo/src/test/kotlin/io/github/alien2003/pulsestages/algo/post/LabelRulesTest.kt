package io.github.alien2003.pulsestages.algo.post

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.SleepStage.AWAKE
import io.github.alien2003.pulsestages.algo.model.SleepStage.DEEP
import io.github.alien2003.pulsestages.algo.model.SleepStage.LIGHT
import io.github.alien2003.pulsestages.algo.model.SleepStage.REM
import io.github.alien2003.pulsestages.algo.model.SleepStage.UNKNOWN
import io.github.alien2003.pulsestages.algo.params.GapPolicy
import io.github.alien2003.pulsestages.algo.params.LowConfidencePolicy
import io.github.alien2003.pulsestages.algo.params.PostprocessParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LabelRulesTest {
    private val params = PostprocessParams()

    /** Parses a compact hypnogram: A=AWAKE, L=LIGHT, D=DEEP, R=REM, U=UNKNOWN. */
    private fun stages(code: String): Array<SleepStage> =
        code
            .map {
                when (it) {
                    'A' -> AWAKE
                    'L' -> LIGHT
                    'D' -> DEEP
                    'R' -> REM
                    else -> UNKNOWN
                }
            }.toTypedArray()

    private fun code(stages: Array<SleepStage>): String = stages.joinToString("") { if (it == UNKNOWN) "U" else it.name.take(1) }

    private fun uniform(n: Int) = Array(n) { DoubleArray(4) { 0.25 } }

    @Test
    fun `low confidence policy writes LIGHT except for forced epochs`() {
        val posteriors = arrayOf(doubleArrayOf(0.4, 0.3, 0.2, 0.1), doubleArrayOf(0.1, 0.1, 0.7, 0.1), doubleArrayOf(0.4, 0.2, 0.2, 0.2))
        val forced = booleanArrayOf(false, false, true)
        val input = stages("ADA")

        val light = LabelRules.lowConfidence(input, posteriors, forced, params.copy(lowConfidencePolicy = LowConfidencePolicy.LIGHT))
        val keep = LabelRules.lowConfidence(input, posteriors, forced, params)

        assertEquals("LDA", code(light))
        assertEquals("ADA", code(keep))
    }

    @Test
    fun `shortest short bout is absorbed first`() {
        // DEEP x2 (shortest) is absorbed before REM x3; posteriors over the DEEP epochs favour REM.
        val input = stages("LLLLLDDRRRLLLLL")
        val posteriors = uniform(input.size)
        posteriors[5] = doubleArrayOf(0.0, 0.2, 0.3, 0.5)
        posteriors[6] = doubleArrayOf(0.0, 0.2, 0.3, 0.5)

        val result = LabelRules.absorbShortBouts(input, posteriors, BooleanArray(input.size), epochMin = 1.0, params = params)

        // DEEP joins REM -> REM x5 is long enough and survives.
        assertEquals("LLLLLRRRRRLLLLL", code(result))
    }

    @Test
    fun `absorption picks the neighbour with the larger mean posterior, left on ties`() {
        val input = stages("LLLLLRRAAAAA")
        val towardsAwake = uniform(input.size).also { p -> (5..6).forEach { p[it] = doubleArrayOf(0.5, 0.3, 0.1, 0.1) } }
        val tie = uniform(input.size)

        assertEquals("LLLLLAAAAAAA", code(LabelRules.absorbShortBouts(input, towardsAwake, BooleanArray(input.size), 1.0, params)))
        assertEquals("LLLLLLLAAAAA", code(LabelRules.absorbShortBouts(input, tie, BooleanArray(input.size), 1.0, params)))
    }

    @Test
    fun `equal lengths are processed in time order and edges have a single neighbour`() {
        val input = stages("LDLLLLLDLLLLLR")

        val result = LabelRules.absorbShortBouts(input, uniform(input.size), BooleanArray(input.size), 1.0, params)

        assertEquals("LLLLLLLLLLLLLL", code(result))
    }

    @Test
    fun `the AWAKE minimum applies only inside sleep`() {
        fun absorb(code: String) =
            code(LabelRules.absorbShortBouts(stages(code), uniform(code.length), BooleanArray(code.length), 1.0, params))

        // Sleep-onset and final wake are exempt; an awakening between sleep epochs is absorbed.
        assertEquals("ALLLLLLLLLLA", absorb("ALLLLLLLLLLA"))
        assertEquals("ALLLLLLLLLLLLLA", absorb("ALLLLLALLLLLLLA"))
        // After a leading gap there is no sleep before the AWAKE bout either.
        assertEquals("UUUALLLLL", absorb("UUUALLLLL"))
        // A gap does not end sleep: the AWAKE bout after it is inside sleep and joins its only scored neighbour.
        assertEquals("LLLLUUULLLL", absorb("LLLLUUUALLL"))
        // DEEP and REM minima apply everywhere, including the session edges.
        assertEquals("LLLLLLLLLL", absorb("RRLLLLLLLL"))
    }

    @Test
    fun `UNKNOWN gaps never absorb and a bout between gaps keeps its label`() {
        fun absorb(code: String) =
            code(LabelRules.absorbShortBouts(stages(code), uniform(code.length), BooleanArray(code.length), 1.0, params))

        // The short REM remainder next to a gap joins the scored neighbour; the gap never takes it.
        assertEquals("LLLLLLLLUUUUU", absorb("LLLLLRRRUUUUU"))
        // Islands of data between gaps (or a gap and the session edge) have no scored neighbour.
        assertEquals("UUUDDDUUU", absorb("UUUDDDUUU"))
        assertEquals("RRUUULLLL", absorb("RRUUULLLL"))
        // A short bout between two scored bouts across no gap is still absorbed as before.
        assertEquals("UUDDDDDDDDLLLL", absorb("UUDDDDDRRDLLLL"))
    }

    @Test
    fun `forced awake bouts are never absorbed and minimum lengths scale with the epoch`() {
        val input = stages("LLLLALLLL")
        val forced = BooleanArray(input.size).also { it[4] = true }

        assertEquals("LLLLALLLL", code(LabelRules.absorbShortBouts(input, uniform(input.size), forced, 1.0, params)))
        // With 2-minute epochs one AWAKE epoch already lasts minAwakeMin = 2 minutes.
        assertEquals("LLLLALLLL", code(LabelRules.absorbShortBouts(input, uniform(input.size), BooleanArray(input.size), 2.0, params)))
        assertEquals("LLLLLLLLL", code(LabelRules.absorbShortBouts(input, uniform(input.size), BooleanArray(input.size), 1.0, params)))
    }

    @Test
    fun `a single bout night is left alone`() {
        val input = stages("DD")

        assertEquals("DD", code(LabelRules.absorbShortBouts(input, uniform(2), BooleanArray(2), 1.0, params)))
    }

    @Test
    fun `gap policies`() {
        val input = stages("LLDDDAAAARRL")
        val hasData = BooleanArray(input.size) { it !in 5..8 }
        val forced = BooleanArray(input.size).also { it[6] = true }

        assertEquals("LLDDDUAUURRL", code(LabelRules.gapPolicy(input, hasData, forced, GapPolicy.UNKNOWN)))
        assertEquals("LLDDDLALLRRL", code(LabelRules.gapPolicy(input, hasData, forced, GapPolicy.LIGHT)))
        // Epoch 5 is nearest to 4 (DEEP); epochs 7 and 8 are nearer to 9 (REM).
        assertEquals("LLDDDDARRRRL", code(LabelRules.gapPolicy(input, hasData, forced, GapPolicy.NEAREST)))
        // Equidistant epochs take the earlier neighbour.
        val tie = stages("DAAAR")
        val tieData = booleanArrayOf(true, false, false, false, true)
        assertEquals("DDDRR", code(LabelRules.gapPolicy(tie, tieData, BooleanArray(5), GapPolicy.NEAREST)))
        val noData = BooleanArray(input.size)
        assertEquals("UUUUUUAUUUUU", code(LabelRules.gapPolicy(input, noData, forced, GapPolicy.NEAREST)))
    }

    @Test
    fun `partial last epoch takes the previous label`() {
        assertEquals("LLD", code(LabelRules.mergePartialLastEpoch(stages("LLD"), lastIsPartial = false)))
        assertEquals("LLL", code(LabelRules.mergePartialLastEpoch(stages("LLD"), lastIsPartial = true)))
        assertEquals("D", code(LabelRules.mergePartialLastEpoch(stages("D"), lastIsPartial = true)))
    }
}

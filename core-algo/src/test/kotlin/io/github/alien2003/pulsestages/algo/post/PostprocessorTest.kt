package io.github.alien2003.pulsestages.algo.post

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.params.GapPolicy
import io.github.alien2003.pulsestages.algo.params.PostprocessParams
import io.github.alien2003.pulsestages.algo.preprocess.EpochGrid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp

class PostprocessorTest {
    private val start = 1_760_000_000_000L
    private val minute = 60_000L

    private fun grid(
        epochs: Int,
        extraMs: Long = 0,
    ) = EpochGrid(start, start + epochs * minute + extraMs, epochSec = 60, contextMarginMin = 0)

    private fun context(
        grid: EpochGrid,
        coverage: Double = 1.0,
        checkProportions: Boolean = true,
    ) = PostprocessContext(grid, BooleanArray(grid.sessionEpochs) { true }, BooleanArray(grid.sessionEpochs), coverage, checkProportions)

    /** A decoder that labels by argmax(score + offsets) with softmax posteriors. */
    private fun scoreDecoder(scores: Array<DoubleArray>) =
        EpochDecoder { offsets ->
            val posteriors =
                Array(scores.size) { k ->
                    val raw = DoubleArray(4) { exp(scores[k][it] + offsets[it]) }
                    val sum = raw.sum()
                    DoubleArray(4) { raw[it] / sum }
                }
            Decoding(IntArray(scores.size) { k -> posteriors[k].indices.maxBy { posteriors[k][it] } }, posteriors)
        }

    private fun fixedDecoder(vararg runs: Pair<SleepStage, Int>): EpochDecoder {
        val labels = runs.flatMap { (stage, n) -> List(n) { stage.index } }.toIntArray()
        val posteriors = Array(labels.size) { k -> DoubleArray(4) { if (it == labels[k]) 0.85 else 0.05 } }
        return EpochDecoder { Decoding(labels, posteriors) }
    }

    @Test
    fun `sanity flags implausible shares only with enough coverage`() {
        val g = grid(100)
        val params = PostprocessParams()
        val deepHigh = fixedDecoder(SleepStage.DEEP to 40, SleepStage.LIGHT to 40, SleepStage.REM to 20)
        val remHighDeepLow = fixedDecoder(SleepStage.DEEP to 2, SleepStage.LIGHT to 48, SleepStage.REM to 50)

        val high = Postprocessor.run(deepHigh, context(g), params)
        assertEquals(listOf(WarningCode.SANITY_DEEP_HIGH), high.warnings.map { it.code })
        assertTrue(high.sanityFailed)

        val other = Postprocessor.run(remHighDeepLow, context(g), params)
        assertEquals(listOf(WarningCode.SANITY_REM_HIGH, WarningCode.SANITY_DEEP_LOW), other.warnings.map { it.code })

        val lowCoverage = Postprocessor.run(deepHigh, context(g, coverage = 0.8), params)
        assertTrue(lowCoverage.warnings.isEmpty())
        assertFalse(lowCoverage.sanityFailed)
    }

    @Test
    fun `calibration step moves offsets towards the corridors`() {
        val params = PostprocessParams()
        val g = grid(100)
        val inside =
            StageShares.of(
                Array(100) {
                    if (it < 20) {
                        SleepStage.DEEP
                    } else if (it < 40) {
                        SleepStage.REM
                    } else {
                        SleepStage.LIGHT
                    }
                },
                g,
            )
        assertNull(Postprocessor.calibrationStep(DoubleArray(4), inside, params))

        val outside =
            StageShares.of(
                Array(100) {
                    if (it <
                        40
                    ) {
                        SleepStage.DEEP
                    } else if (it < 60) {
                        SleepStage.AWAKE
                    } else {
                        SleepStage.LIGHT
                    }
                },
                g,
            )
        val next = Postprocessor.calibrationStep(DoubleArray(4), outside, params)
        assertEquals(listOf(-0.2, 0.0, -0.2, 0.2), next?.toList())
    }

    @Test
    fun `calibration re-decodes and moves shares towards the corridors`() {
        val n = 200
        val g = grid(n)
        // 45 % of epochs prefer DEEP by margins 0.30..0.70; REM trails LIGHT by 0.15..1.15 elsewhere.
        val scores =
            Array(n) { k ->
                val j = k % 20
                when {
                    j < 9 -> doubleArrayOf(-5.0, 0.0, 0.3 + 0.05 * j, -2.0)
                    else -> doubleArrayOf(-5.0, 0.0, -0.5, -0.15 - 0.1 * (j - 9))
                }
            }
        val off = Postprocessor.run(scoreDecoder(scores), context(g), PostprocessParams(minDeepMin = 0.0, minRemMin = 0.0))
        val on =
            Postprocessor.run(
                scoreDecoder(scores),
                context(g),
                PostprocessParams(minDeepMin = 0.0, minRemMin = 0.0, calibrateProportions = true),
            )

        val deepBefore = StageShares.of(off.stages, g)[SleepStage.DEEP]
        val deepAfter = StageShares.of(on.stages, g)[SleepStage.DEEP]
        val remAfter = StageShares.of(on.stages, g)[SleepStage.REM]
        assertEquals(0.45, deepBefore, 1e-12)
        assertEquals(0.0, StageShares.of(off.stages, g)[SleepStage.REM], 0.0)
        // Offsets after three steps: DEEP -0.6 (45 % -> 30 % -> 10 %), REM +0.4 (0 % -> 5 % -> 15 %).
        assertEquals(0.10, deepAfter, 1e-12)
        assertEquals(0.15, remAfter, 1e-12)
        assertTrue(on.calibrated)
        assertTrue(on.warnings.any { it.code == WarningCode.PROPORTIONS_CALIBRATED })
        assertFalse(off.calibrated)
    }

    @Test
    fun `without proportion checks there is neither a sanity flag nor a calibration`() {
        val g = grid(100)
        val deepHigh = fixedDecoder(SleepStage.DEEP to 40, SleepStage.LIGHT to 40, SleepStage.REM to 20)
        val params = PostprocessParams(calibrateProportions = true)

        val checked = Postprocessor.run(deepHigh, context(g), params)
        val nap = Postprocessor.run(deepHigh, context(g, checkProportions = false), params)

        assertTrue(checked.sanityFailed)
        assertTrue(checked.warnings.any { it.code == WarningCode.SANITY_DEEP_HIGH })
        assertFalse(nap.sanityFailed)
        assertFalse(nap.calibrated)
        assertTrue(nap.warnings.isEmpty(), "${nap.warnings}")
        assertEquals(40, nap.stages.count { it == SleepStage.DEEP }, "labels of the plain decode")
    }

    @Test
    fun `calibration that changes nothing is not reported`() {
        val g = grid(100)
        val result =
            Postprocessor.run(
                fixedDecoder(SleepStage.DEEP to 40, SleepStage.LIGHT to 60),
                context(g),
                PostprocessParams(calibrateProportions = true),
            )

        assertFalse(result.calibrated)
        assertTrue(result.warnings.none { it.code == WarningCode.PROPORTIONS_CALIBRATED })
    }

    @Test
    fun `intervals cover the session exactly and the partial last epoch joins the previous interval`() {
        val g = grid(10, extraMs = 20_000)
        val decoder = fixedDecoder(SleepStage.LIGHT to 5, SleepStage.DEEP to 5, SleepStage.AWAKE to 1)

        val result = Postprocessor.run(decoder, context(g), PostprocessParams())

        assertEquals(2, result.intervals.size)
        assertEquals(start, result.intervals.first().startMs)
        assertEquals(start + 5 * minute, result.intervals[0].endMs)
        assertEquals(start + 5 * minute, result.intervals[1].startMs)
        assertEquals(start + 10 * minute + 20_000, result.intervals.last().endMs)
        assertEquals(SleepStage.DEEP, result.stages.last())
    }

    @Test
    fun `minimum bouts hold after the gap policy`() {
        val g = grid(30)
        // LIGHT 10, REM 6, LIGHT 14; epochs 12..21 have no data, so the gap cuts the REM bout to a 2-epoch remainder.
        val decoder = fixedDecoder(SleepStage.LIGHT to 10, SleepStage.REM to 6, SleepStage.LIGHT to 14)
        val context = PostprocessContext(g, BooleanArray(30) { it !in 12..21 }, BooleanArray(30), coverage = 2.0 / 3)

        fun run(policy: GapPolicy) = Postprocessor.run(decoder, context, PostprocessParams(gapPolicy = policy)).stages

        val unknown = run(GapPolicy.UNKNOWN)
        assertEquals(List(12) { SleepStage.LIGHT } + List(10) { SleepStage.UNKNOWN } + List(8) { SleepStage.LIGHT }, unknown.toList())
        assertTrue(run(GapPolicy.LIGHT).all { it == SleepStage.LIGHT })
        // NEAREST extends the remainder into the gap (epochs 10..16), which makes it long enough.
        assertEquals(List(7) { SleepStage.REM }, run(GapPolicy.NEAREST).toList().subList(10, 17))
        for (policy in GapPolicy.entries) {
            assertTrue(LabelRules.bouts(run(policy)).none { it.stage == SleepStage.REM && it.length < 5 }, "$policy")
        }
    }

    @Test
    fun `session shorter than one epoch gives one interval`() {
        val g = EpochGrid(start, start + 20_000, epochSec = 60, contextMarginMin = 0)

        val result = Postprocessor.run(fixedDecoder(SleepStage.AWAKE to 1), context(g), PostprocessParams())

        assertEquals(1, result.intervals.size)
        assertEquals(start + 20_000, result.intervals.single().endMs)
    }
}

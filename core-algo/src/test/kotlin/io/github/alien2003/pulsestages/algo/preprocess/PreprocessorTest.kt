package io.github.alien2003.pulsestages.algo.preprocess

import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.params.PreprocessParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreprocessorTest {
    private val minute = 60_000L
    private val start = 1_760_000_000_000L

    private fun grid(
        sessionMin: Int,
        contextMin: Int = 0,
    ) = EpochGrid(start, start + sessionMin * minute, epochSec = 60, contextMarginMin = contextMin)

    @Test
    fun `epoch value is the mean of its samples`() {
        val g = grid(3)
        val times = longArrayOf(start + 10_000, start + 50_000, start + minute + 1_000, start + 2 * minute)
        val values = doubleArrayOf(60.0, 64.0, 70.0, 80.0)

        val raw = Resampler.resample(times, values, g, maxInterpGapMs = 10 * minute)

        assertEquals(listOf(62.0, 70.0, 80.0), raw.toList())
    }

    @Test
    fun `gaps up to the limit are interpolated at epoch midpoints`() {
        val g = grid(6)
        // Samples at 0:30 and 4:30 (gap 4 min) -> epochs 1..3 interpolated at 1:30, 2:30, 3:30.
        val times = longArrayOf(start + 30_000, start + 4 * minute + 30_000)
        val values = doubleArrayOf(60.0, 68.0)

        val raw = Resampler.resample(times, values, g, maxInterpGapMs = 4 * minute)

        assertEquals(60.0, raw[0], 1e-12)
        assertEquals(62.0, raw[1], 1e-12)
        assertEquals(64.0, raw[2], 1e-12)
        assertEquals(66.0, raw[3], 1e-12)
        assertEquals(68.0, raw[4], 1e-12)
        assertTrue(raw[5].isNaN(), "after the last sample there is no data")
    }

    @Test
    fun `gaps longer than the limit are no data`() {
        val g = grid(13, contextMin = 1)
        val times = longArrayOf(start + 30_000, start + 11 * minute + 30_000 + 1)
        val values = doubleArrayOf(60.0, 68.0)

        val raw = Resampler.resample(times, values, g, maxInterpGapMs = 11 * minute)

        assertTrue(raw[g.position(-1)].isNaN(), "before the first sample there is no data")
        assertEquals(60.0, raw[g.position(0)], 0.0)
        (1..10).forEach { assertTrue(raw[g.position(it)].isNaN(), "epoch $it") }
        assertEquals(68.0, raw[g.position(11)], 0.0)
    }

    @Test
    fun `range filter drops implausible values and duplicates are averaged`() {
        val input =
            NightInput(
                sessionStartMs = start,
                sessionEndMs = start + 4 * minute,
                heartRate =
                    listOf(
                        HrSample(start + 2 * minute, 0.0),
                        HrSample(start + 10_000, 60.0),
                        HrSample(start + 10_000, 62.0),
                        HrSample(start + minute + 5_000, 220.0),
                        HrSample(start + minute + 6_000, 25.0),
                        HrSample(start + 3 * minute, 70.0),
                        HrSample(start + 100 * minute, 70.0),
                    ),
            )
        val params = PreprocessParams(contextMarginMin = 0, hampelWindow = 1)

        val night = Preprocessor.preprocess(input, params)

        assertEquals(2, night.sampleCount)
        assertEquals(61.0, night.hrRaw[0], 1e-12)
        assertEquals(70.0, night.hrRaw[3], 1e-12)
        assertEquals(1.0, night.coverage, 1e-12)
    }

    @Test
    fun `hampel removes spikes that pass the range filter`() {
        val samples = (0 until 30).map { HrSample(start + it * 2 * minute, if (it == 15) 170.0 else 55.0 + it % 3) }
        val input = NightInput(start, start + 60 * minute, samples)

        val night = Preprocessor.preprocess(input, PreprocessParams(contextMarginMin = 0))

        assertEquals(1, night.outliersReplaced)
        val maxRaw = night.hrRaw.filter { !it.isNaN() }.max()
        assertTrue(maxRaw < 60.0, "spike must be removed, max was $maxRaw")
    }

    @Test
    fun `the Hampel scale floor is a parameter and keeps whole-bpm readings`() {
        val pattern = listOf(58.0, 58.0, 59.0, 58.0, 58.0, 60.0, 58.0)
        val samples = (0 until 30).map { HrSample(start + it * 2 * minute, pattern[it % pattern.size]) }
        val input = NightInput(start, start + 60 * minute, samples)

        val floored = Preprocessor.preprocess(input, PreprocessParams(contextMarginMin = 0))
        val classic = Preprocessor.preprocess(input, PreprocessParams(contextMarginMin = 0, hampelMinScaleBpm = 0.0))

        assertEquals(0, floored.outliersReplaced)
        assertTrue(classic.outliersReplaced >= 8, "classic Hampel replaced ${classic.outliersReplaced}")
        assertTrue(floored.hrRaw.any { it == 60.0 })
    }

    @Test
    fun `coverage counts session epochs only and smoothing keeps NaN`() {
        // 60-min session; samples every 2 min during the first 30 min only; 30 min of context.
        val samples = (-15..15).map { HrSample(start + it * 2 * minute, 60.0) }
        val input = NightInput(start, start + 60 * minute, samples)

        val night = Preprocessor.preprocess(input, PreprocessParams())

        assertEquals(31.0 / 60.0, night.coverage, 1e-12)
        val g = night.grid
        assertFalse(night.hrSmooth[g.position(-30)].isNaN())
        assertTrue(night.hrSmooth[g.position(40)].isNaN())
        night.hrRaw.indices.forEach { assertEquals(night.hrRaw[it].isNaN(), night.hrSmooth[it].isNaN(), "position $it") }
    }

    @Test
    fun `no samples gives zero coverage`() {
        val night = Preprocessor.preprocess(NightInput(start, start + 10 * minute, emptyList()), PreprocessParams())

        assertEquals(0.0, night.coverage, 0.0)
        assertEquals(0, night.sampleCount)
        assertTrue(night.hrRaw.all { it.isNaN() })
    }
}

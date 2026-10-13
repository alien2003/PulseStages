package io.github.alien2003.pulsestages.ui.nights

import io.github.alien2003.pulsestages.algo.model.EpochResult
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.model.StageProbabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HypnogramGeometryTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `stage rows put awake on top and deep at the bottom, unknown has no row`() {
        assertEquals(0, hypnogramRow(SleepStage.AWAKE))
        assertEquals(1, hypnogramRow(SleepStage.REM))
        assertEquals(2, hypnogramRow(SleepStage.LIGHT))
        assertEquals(3, hypnogramRow(SleepStage.DEEP))
        assertNull(hypnogramRow(SleepStage.UNKNOWN))
        assertEquals(10f, rowCenterY(0, heightPx = 80f))
        assertEquals(70f, rowCenterY(3, heightPx = 80f))
    }

    @Test
    fun `the time axis maps the night linearly onto the width and back, clamped`() {
        val axis = TimeAxis(startMs = 1_000, endMs = 1_000 + 8 * hour, widthPx = 800f)
        assertEquals(0f, axis.x(1_000))
        assertEquals(400f, axis.x(1_000 + 4 * hour))
        assertEquals(800f, axis.x(1_000 + 8 * hour))
        assertEquals(0f, axis.x(0), "before the night")
        assertEquals(800f, axis.x(1_000 + 9 * hour), "after the night")
        assertEquals(1_000 + 2 * hour, axis.time(200f))
        assertEquals(1_000L, axis.time(-50f))
        assertEquals(1_000 + 8 * hour, axis.time(900f))
        assertEquals(1_000L, TimeAxis(1_000, 2_000, widthPx = 0f).time(10f), "no width yet")
    }

    @Test
    fun `bars merge equal neighbours and turn uncovered time into unknown gaps`() {
        val start = 0L
        val stages =
            listOf(
                StageInterval(start, start + 10 * minute, SleepStage.LIGHT),
                StageInterval(start + 10 * minute, start + 20 * minute, SleepStage.LIGHT),
                StageInterval(start + 30 * minute, start + 40 * minute, SleepStage.DEEP),
                StageInterval(start + 40 * minute, start + 50 * minute, SleepStage.UNKNOWN),
            )
        val axis = TimeAxis(start, start + 60 * minute, widthPx = 600f)

        val bars = hypnogramBars(stages, axis)

        assertEquals(
            listOf(
                HypnogramBar(0f, 200f, SleepStage.LIGHT, 2),
                HypnogramBar(200f, 300f, SleepStage.UNKNOWN, null),
                HypnogramBar(300f, 400f, SleepStage.DEEP, 3),
                HypnogramBar(400f, 600f, SleepStage.UNKNOWN, null),
            ),
            bars,
        )
    }

    @Test
    fun `very short bouts stay visible`() {
        val axis = TimeAxis(0, 14 * hour, widthPx = 100f)
        val bars = hypnogramBars(listOf(StageInterval(hour, hour + 1_000, SleepStage.REM)), axis)
        val rem = bars.single { it.stage == SleepStage.REM }
        assertTrue(rem.x1 - rem.x0 >= MIN_BAR_PX - 1e-3f)
    }

    @Test
    fun `gaps are filled and intervals clipped to the night`() {
        val filled =
            withGaps(
                listOf(StageInterval(-5, 10, SleepStage.AWAKE), StageInterval(20, 120, SleepStage.REM)),
                startMs = 0,
                endMs = 100,
            )
        assertEquals(
            listOf(
                StageInterval(0, 10, SleepStage.AWAKE),
                StageInterval(10, 20, SleepStage.UNKNOWN),
                StageInterval(20, 100, SleepStage.REM),
            ),
            filled,
        )
        assertEquals(listOf(StageInterval(0, 100, SleepStage.UNKNOWN)), withGaps(emptyList(), 0, 100))
        assertEquals(
            listOf(StageInterval(0, 30, SleepStage.DEEP)),
            mergeAdjacent(listOf(StageInterval(0, 10, SleepStage.DEEP), StageInterval(10, 30, SleepStage.DEEP))),
        )
    }

    @Test
    fun `the stage at a time comes from the covering interval`() {
        val stages = listOf(StageInterval(0, 10, SleepStage.LIGHT), StageInterval(10, 20, SleepStage.DEEP))
        assertEquals(SleepStage.LIGHT, stageAt(stages, 0))
        assertEquals(SleepStage.DEEP, stageAt(stages, 10))
        assertNull(stageAt(stages, 20))
    }

    @Test
    fun `the epoch at a time is found by binary search`() {
        val starts = listOf(0L, 60_000L, 120_000L, 180_000L)
        assertEquals(0, epochIndexAt(starts, 60_000, 0))
        assertEquals(1, epochIndexAt(starts, 60_000, 60_000))
        assertEquals(2, epochIndexAt(starts, 60_000, 179_999))
        assertEquals(3, epochIndexAt(starts, 60_000, 239_999))
        assertNull(epochIndexAt(starts, 60_000, 240_000), "after the last epoch")
        assertNull(epochIndexAt(starts, 60_000, -1), "before the first epoch")
        assertNull(epochIndexAt(emptyList(), 60_000, 0))
    }

    @Test
    fun `the heart-rate range is padded and rounded to whole steps`() {
        assertEquals(50.0..75.0, hrRange(listOf(52.0, null, 71.4, Double.NaN)))
        assertEquals(55.0..65.0, hrRange(listOf(60.0)))
        assertNull(hrRange(listOf(null, null)))
        assertEquals(0f, valueY(75.0, 45.0..75.0, heightPx = 100f), "the maximum is at the top")
        assertEquals(100f, valueY(45.0, 45.0..75.0, heightPx = 100f))
        assertEquals(50f, valueY(60.0, 45.0..75.0, heightPx = 100f))
        assertEquals(100f, valueY(10.0, 45.0..75.0, heightPx = 100f), "clamped")
    }

    @Test
    fun `curves split at epochs without a value`() {
        fun epoch(
            index: Int,
            bpm: Double?,
        ) = EpochResult(index, index * 60_000L, bpm != null, bpm, bpm, emptyList(), StageProbabilities.UNIFORM, SleepStage.LIGHT)
        val epochs = listOf(epoch(0, 60.0), epoch(1, 61.0), epoch(2, null), epoch(3, 58.0))

        val segments = curveSegments(epochs, epochMs = 60_000) { it.hrSmooth }

        assertEquals(listOf(listOf(30_000L to 60.0, 90_000L to 61.0), listOf(210_000L to 58.0)), segments)
        assertTrue(curveSegments(listOf(epoch(0, null)), 60_000) { it.hrRaw }.isEmpty())
    }

    @Test
    fun `hour ticks fall on full local hours inside the night`() {
        val start = 22 * hour + 30 * minute
        assertEquals(listOf(23 * hour, 24 * hour, 25 * hour), hourTicks(start, start + 3 * hour, offsetSec = 0))
        // At UTC+03:30 the local full hours are at :30 UTC.
        assertEquals(listOf(23 * hour + 30 * minute, 24 * hour + 30 * minute), hourTicks(start, start + 2 * hour + minute, 12_600))
        assertTrue(hourTicks(23 * hour, 23 * hour + 30 * minute, 0).isEmpty(), "ticks at the edges are left out")
    }

    @Test
    fun `stage minutes are summed per stage in display order`() {
        val stages =
            listOf(
                StageInterval(0, 30 * minute, SleepStage.DEEP),
                StageInterval(30 * minute, 40 * minute, SleepStage.AWAKE),
                StageInterval(40 * minute, 70 * minute, SleepStage.DEEP),
            )
        assertEquals(
            listOf(
                StageMinutes(SleepStage.DEEP, 60.0),
                StageMinutes(SleepStage.REM, 0.0),
                StageMinutes(SleepStage.LIGHT, 0.0),
                StageMinutes(SleepStage.AWAKE, 10.0),
            ),
            stageMinutesOf(stages),
        )
        assertEquals(StageMinutes(SleepStage.UNKNOWN, 5.0), stageMinutesOf(listOf(StageInterval(0, 5 * minute, SleepStage.UNKNOWN))).last())
    }
}

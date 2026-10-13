package io.github.alien2003.pulsestages.algo.preprocess

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpochGridTest {
    private val start = 1_700_000_000_000L

    @Test
    fun `session epochs round up and the last epoch is clipped`() {
        val grid = EpochGrid(start, start + 90_500L, epochSec = 30, contextMarginMin = 1)

        assertEquals(4, grid.sessionEpochs)
        assertEquals(2, grid.contextEpochs)
        assertEquals(8, grid.totalEpochs)
        assertEquals(30_000L, grid.clippedDurationMs(0))
        assertEquals(500L, grid.clippedDurationMs(3))
        assertEquals(start + 90_500L, grid.clippedEndMs(3))
        assertEquals(start - 60_000L, grid.windowStartMs)
        assertEquals(start + 180_000L, grid.windowEndMs)
    }

    @Test
    fun `positions map session epochs after the leading context`() {
        val grid = EpochGrid(start, start + 3_600_000L, epochSec = 60, contextMarginMin = 30)

        assertEquals(60, grid.sessionEpochs)
        assertEquals(30, grid.position(0))
        assertEquals(-30, grid.epochAt(0))
        assertEquals(0, grid.positionOf(start - 1_800_000L))
        assertEquals(30, grid.positionOf(start))
        assertEquals(30, grid.positionOf(start + 59_999L))
        assertEquals(31, grid.positionOf(start + 60_000L))
        assertEquals(-1, grid.positionOf(start - 1_800_001L))
        assertEquals(-1, grid.positionOf(grid.windowEndMs))
        assertEquals(start + 30_000L, grid.midMs(0))
        assertEquals(start - 60_000L, grid.startMs(-1))
    }

    @Test
    fun `context margin rounds up to whole epochs`() {
        val grid = EpochGrid(start, start + 600_000L, epochSec = 120, contextMarginMin = 3)

        assertEquals(2, grid.contextEpochs)
    }

    @Test
    fun `session shorter than one epoch has exactly one epoch`() {
        val grid = EpochGrid(start, start + 10_000L, epochSec = 60, contextMarginMin = 0)

        assertEquals(1, grid.sessionEpochs)
        assertEquals(10_000L, grid.clippedDurationMs(0))
    }
}

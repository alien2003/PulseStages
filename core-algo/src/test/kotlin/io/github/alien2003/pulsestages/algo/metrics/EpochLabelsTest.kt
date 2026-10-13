package io.github.alien2003.pulsestages.algo.metrics

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.preprocess.EpochGrid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpochLabelsTest {
    private val s = 30_000L

    @Test
    fun `majority overlap with midpoint tie break and clipping`() {
        // Five 60-s epochs; the session ends 30 s into the last one.
        val grid = EpochGrid(0L, 9 * s, 60, 0)
        val truth =
            listOf(
                StageInterval(0, s, SleepStage.LIGHT),
                StageInterval(s, 2 * s, SleepStage.DEEP), // epoch 0: tie LIGHT/DEEP → midpoint (30 s) is DEEP
                StageInterval(2 * s, 3 * s, SleepStage.REM),
                StageInterval(3 * s, 4 * s, SleepStage.UNKNOWN), // epoch 1: REM only (UNKNOWN ignored)
                StageInterval(4 * s, 4 * s + 40_000, SleepStage.AWAKE),
                StageInterval(4 * s + 40_000, 6 * s, SleepStage.LIGHT), // epoch 2: AWAKE 40 s vs LIGHT 20 s
                StageInterval(8 * s, 12 * s, SleepStage.DEEP), // epoch 4 is clipped to [240 s, 270 s)
            )

        val labels = EpochLabels.majority(truth.shuffled(kotlin.random.Random(1)), grid)

        assertEquals(listOf(SleepStage.DEEP, SleepStage.REM, SleepStage.AWAKE, null, SleepStage.DEEP), labels.toList())
    }

    @Test
    fun `ties without a midpoint label go to the lowest index`() {
        val grid = EpochGrid(0L, 4 * s, 60, 0)
        val truth = listOf(StageInterval(0, 20_000, SleepStage.REM), StageInterval(40_000, 60_000, SleepStage.LIGHT))

        assertEquals(SleepStage.LIGHT, EpochLabels.majority(truth, grid)[0])
        assertEquals(null, EpochLabels.majority(truth, grid)[1])
    }
}

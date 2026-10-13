package io.github.alien2003.pulsestages.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StageEstimationScheduleTest {
    @Test
    fun `null uses the default interval`() {
        assertEquals(StageEstimationSchedule.DEFAULT_REPEAT_INTERVAL_HOURS, StageEstimationSchedule.repeatIntervalHours(null))
    }

    @Test
    fun `interval is clamped to the supported range`() {
        assertEquals(1L, StageEstimationSchedule.repeatIntervalHours(0))
        assertEquals(12L, StageEstimationSchedule.repeatIntervalHours(12))
        assertEquals(24L, StageEstimationSchedule.repeatIntervalHours(1_000))
    }
}

package io.github.alien2003.pulsestages.platform

import io.github.alien2003.pulsestages.testutil.FakeWorkScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackgroundScheduleTest {
    @Test
    fun `start follows the schedule settings once, however often it is called`() =
        runTest {
            val scheduler = FakeWorkScheduler()
            val schedule = BackgroundSchedule(scheduler, backgroundScope)

            schedule.start()
            schedule.start()
            runCurrent()

            assertEquals(1, scheduler.keepScheduledCalls)
        }
}

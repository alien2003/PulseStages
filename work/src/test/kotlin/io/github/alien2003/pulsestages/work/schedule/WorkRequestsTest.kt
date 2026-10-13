package io.github.alien2003.pulsestages.work.schedule

import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkRequest
import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeScope
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.work.run.RecomputeRequest
import io.github.alien2003.pulsestages.work.worker.BackfillWorker
import io.github.alien2003.pulsestages.work.worker.EstimateNewNightsWorker
import io.github.alien2003.pulsestages.work.worker.EstimateTrigger
import io.github.alien2003.pulsestages.work.worker.RecomputeWorker
import io.github.alien2003.pulsestages.work.worker.WorkerInputs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** The built WorkManager requests (plain JVM: the builders only fill a WorkSpec). */
class WorkRequestsTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val now: Long = ZonedDateTime.of(2026, 10, 13, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val schedule = ScheduleSettings()

    private fun all(): List<WorkRequest> =
        listOf(
            WorkRequests.periodic(schedule),
            WorkRequests.daily(schedule, zone, now),
            WorkRequests.estimateNow(),
            WorkRequests.backfill(now),
            WorkRequests.recomputeAll(RecomputeScope.ALL, now),
            WorkRequests.recomputeNight("ps:abc", now),
        )

    @Test
    fun `every request backs off exponentially from at least 30 seconds`() {
        all().forEach { request ->
            assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy, request.workSpec.workerClassName)
            assertEquals(30_000L, request.workSpec.backoffDelayDuration)
            assertTrue(request.workSpec.backoffDelayDuration >= WorkRequest.MIN_BACKOFF_MILLIS)
            assertTrue(WorkRequests.TAG_ALL in request.tags)
        }
    }

    @Test
    fun `the periodic check runs every periodicHours with the battery-not-low constraint when enabled`() {
        val request = WorkRequests.periodic(schedule.copy(periodicHours = 3))

        assertTrue(request.workSpec.isPeriodic)
        assertEquals(TimeUnit.HOURS.toMillis(3), request.workSpec.intervalDuration)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(EstimateNewNightsWorker::class.java.name, request.workSpec.workerClassName)
        assertEquals(EstimateTrigger.PERIODIC, WorkerInputs.triggerOf(request.workSpec.input))
        assertTrue(WorkRequests.periodicTag(schedule.copy(periodicHours = 3)) in request.tags)

        val unconstrained = WorkRequests.periodic(schedule.copy(runOnlyWhenNotLowBattery = false))
        assertFalse(unconstrained.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(TimeUnit.HOURS.toMillis(1), unconstrained.workSpec.intervalDuration)
        assertNotEquals(WorkRequests.periodicTag(schedule), WorkRequests.periodicTag(schedule.copy(runOnlyWhenNotLowBattery = false)))
    }

    @Test
    fun `the daily run is a 24-hour periodic work pinned to the next configured local time`() {
        val request = WorkRequests.daily(schedule, zone, now)

        val tomorrowNine = ZonedDateTime.of(2026, 10, 14, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertTrue(request.workSpec.isPeriodic)
        assertEquals(TimeUnit.HOURS.toMillis(24), request.workSpec.intervalDuration)
        assertEquals(tomorrowNine - now, request.workSpec.initialDelay, "09:00 already passed today")
        assertEquals(tomorrowNine, request.workSpec.nextScheduleTimeOverride)
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertEquals(EstimateTrigger.DAILY, WorkerInputs.triggerOf(request.workSpec.input))
        assertTrue(WorkRequests.dailyTag(schedule, zone) in request.tags)

        val evening = WorkRequests.daily(schedule.copy(dailyTimeMinutes = 20 * 60 + 30), zone, now)
        assertEquals(TimeUnit.MINUTES.toMillis(8 * 60 + 30), evening.workSpec.initialDelay, "20:30 is still ahead today")
    }

    @Test
    fun `estimate now is expedited and falls back to normal work without expedited quota`() {
        val request = WorkRequests.estimateNow()

        assertTrue(request.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, request.workSpec.outOfQuotaPolicy)
        assertFalse(request.workSpec.isPeriodic)
        assertEquals(EstimateTrigger.NOW, WorkerInputs.triggerOf(request.workSpec.input))
        assertTrue(EstimateTrigger.NOW.userInitiated)
        assertFalse(request.workSpec.constraints.requiresBatteryNotLow(), "expedited work supports no battery constraint")
    }

    @Test
    fun `backfill and recompute are one-time works of their workers with their inputs`() {
        val backfill = WorkRequests.backfill(now)
        assertEquals(BackfillWorker::class.java.name, backfill.workSpec.workerClassName)
        assertFalse(backfill.workSpec.expedited)
        assertTrue(WorkRequests.Tags.BACKFILL in backfill.tags)
        assertEquals(now, WorkerInputs.backfillRequestedAtOf(backfill.workSpec.input), "a cancel after this time stops every retry")
        assertEquals(null, WorkerInputs.backfillRequestedAtOf(Data.EMPTY))

        val all = WorkRequests.recomputeAll(RecomputeScope.STALE, now)
        assertEquals(RecomputeWorker::class.java.name, all.workSpec.workerClassName)
        assertEquals(RecomputeRequest.All(RecomputeScope.STALE, now), WorkerInputs.recomputeRequestOf(all.workSpec.input))

        val night = WorkRequests.recomputeNight("ps:abc", now)
        assertTrue(night.workSpec.expedited)
        assertEquals(
            RecomputeRequest.Nights(listOf("ps:abc"), now),
            WorkerInputs.recomputeRequestOf(night.workSpec.input),
            "the request time travels with the work: a deletion after it is not undone",
        )
        assertEquals(
            RecomputeRequest.Nights(listOf("ps:abc")),
            WorkerInputs.recomputeRequestOf(WorkerInputs.recompute(RecomputeRequest.Nights(listOf("ps:abc")))),
        )
        assertEquals("recompute-night:ps:abc", WorkRequests.Names.recomputeNight("ps:abc"))
    }

    @Test
    fun `the next daily time is today when still ahead, tomorrow otherwise, and survives DST changes`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val morning = ZonedDateTime.of(2026, 10, 13, 8, 59, 0, 0, berlin).toInstant().toEpochMilli()
        assertEquals(morning + 60_000L, DailySchedule.nextRunMs(morning, 9 * 60, berlin))
        assertEquals(
            morning + 60_000L + TimeUnit.DAYS.toMillis(1),
            DailySchedule.nextRunMs(morning + 60_000L, 9 * 60, berlin),
            "not now again",
        )

        // 2026-03-29 02:30 does not exist in Berlin (clocks jump from 02:00 to 03:00).
        val beforeJump = ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, berlin).toInstant().toEpochMilli()
        val expected = ZonedDateTime.of(2026, 3, 29, 3, 30, 0, 0, berlin).toInstant().toEpochMilli()
        assertEquals(expected, DailySchedule.nextRunMs(beforeJump, 2 * 60 + 30, berlin))

        // 2026-10-25: 25-hour day; 09:00 is still 09:00 local time.
        val beforeFallBack = ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, berlin).toInstant().toEpochMilli()
        val nineNextDay = ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, berlin).toInstant().toEpochMilli()
        assertEquals(nineNextDay, DailySchedule.nextRunMs(beforeFallBack, 9 * 60, berlin))
        assertEquals(TimeUnit.HOURS.toMillis(24), nineNextDay - beforeFallBack, "23 local hours plus the repeated hour")
    }
}

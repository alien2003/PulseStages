package io.github.alien2003.pulsestages.work.schedule

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkRequest
import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeScope
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.work.run.RecomputeRequest
import io.github.alien2003.pulsestages.work.worker.BackfillWorker
import io.github.alien2003.pulsestages.work.worker.EstimateNewNightsWorker
import io.github.alien2003.pulsestages.work.worker.EstimateTrigger
import io.github.alien2003.pulsestages.work.worker.RecomputeWorker
import io.github.alien2003.pulsestages.work.worker.SleepAsAndroidSyncWorker
import io.github.alien2003.pulsestages.work.worker.WorkerInputs
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** The local time of the next daily run. */
object DailySchedule {
    /** A daily run whose scheduled time is further than this from the configured time is pinned again. */
    const val DRIFT_TOLERANCE_MS: Long = 30L * 60 * 1000

    private const val MINUTES_PER_HOUR = 60
    private const val LAST_MINUTE_OF_DAY = 24 * 60 - 1

    /**
     * The first time strictly after [nowMs] at which the local time in [zone] is [dailyTimeMinutes] after midnight (a
     * time skipped by a DST change moves forward to the first valid instant).
     */
    fun nextRunMs(
        nowMs: Long,
        dailyTimeMinutes: Int,
        zone: ZoneId,
    ): Long {
        val minutes = dailyTimeMinutes.coerceIn(0, LAST_MINUTE_OF_DAY)
        val time = LocalTime.of(minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val candidate = ZonedDateTime.of(today, time, zone).toInstant().toEpochMilli()
        return if (candidate > nowMs) candidate else ZonedDateTime.of(today.plusDays(1), time, zone).toInstant().toEpochMilli()
    }
}

/**
 * The work requests of PulseStages. Every request uses [BackoffPolicy.EXPONENTIAL] with [BACKOFF_DELAY_MS] (WorkManager
 * doubles it per retry up to 5 hours), carries [TAG_ALL] and its job tag; periodic requests also carry a tag
 * describing their schedule, so that a change of the schedule settings is recognised on the enqueued work.
 * - periodic: every `periodicMinutes` minutes (15 at least, WorkManager's minimum), battery-not-low constraint when
 *   `runOnlyWhenNotLowBattery`;
 * - daily: a 24-hour periodic work whose next run is pinned to the next `dailyTimeMinutes` local time (initial delay
 *   and next-schedule-time override) — approximate, Doze and the constraint may delay it;
 * - "estimate now": one-time, expedited, run as normal work when the expedited quota is used up
 *   ([OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST]);
 * - backfill and "recompute all": one-time long-running works (they go foreground themselves);
 * - recompute of one night: one-time, expedited like "estimate now";
 * - the sync request to Sleep as Android: one-time with an initial delay and the constraints of the scheduled works
 *   (it checks whether the recent nights are complete, which reads Health Connect).
 */
object WorkRequests {
    /** Initial backoff delay: 30 s (WorkRequest.MIN_BACKOFF_MILLIS is 10 s). */
    const val BACKOFF_DELAY_MS: Long = 30_000L

    /** Interval of the daily work. */
    const val DAILY_INTERVAL_HOURS: Long = 24L

    /** Tag of every PulseStages work. */
    const val TAG_ALL: String = "pulsestages"

    /** Unique work names. */
    object Names {
        /** The periodic Changes-API check. */
        const val PERIODIC: String = "estimate-periodic"

        /** The daily run. */
        const val DAILY: String = "estimate-daily"

        /** "Estimate new now". */
        const val NOW: String = "estimate-now"

        /** The history backfill. */
        const val BACKFILL: String = "backfill"

        /** "Recompute all" / "recompute stale". */
        const val RECOMPUTE_ALL: String = "recompute-all"

        /**
         * The sync request to Sleep as Android, sent once the recent nights are complete: the owed request, its follow-up
         * and the checks of a request that waits (at most one pending). The name of 0.4.0's follow-up work is kept: one
         * may still be pending under it after an update, and the next one scheduled replaces or follows it instead of
         * running beside it (when it runs, it checks like a new one).
         */
        const val SLEEP_AS_ANDROID_FOLLOW_UP: String = "sleep-as-android-follow-up"

        /** Recompute of one night. */
        fun recomputeNight(clientRecordId: String): String = "recompute-night:$clientRecordId"
    }

    /** Job tags. */
    object Tags {
        /** EstimateNewNightsWorker. */
        const val ESTIMATE: String = "estimate"

        /** BackfillWorker. */
        const val BACKFILL: String = "backfill"

        /** RecomputeWorker. */
        const val RECOMPUTE: String = "recompute"

        /** SleepAsAndroidSyncWorker. */
        const val SLEEP_AS_ANDROID: String = "sleep-as-android"
    }

    /** The schedule tag of the periodic work for [schedule]. */
    fun periodicTag(schedule: ScheduleSettings): String =
        "schedule:periodic:${schedule.periodicMinutes}min:batteryNotLow=${schedule.runOnlyWhenNotLowBattery}"

    /** The schedule tag of the daily work for [schedule] in [zone]. */
    fun dailyTag(
        schedule: ScheduleSettings,
        zone: ZoneId,
    ): String = "schedule:daily:${schedule.dailyTimeMinutes}min:batteryNotLow=${schedule.runOnlyWhenNotLowBattery}:zone=${zone.id}"

    /** Constraints of the scheduled (periodic and daily) works. */
    fun constraints(schedule: ScheduleSettings): Constraints =
        Constraints.Builder().setRequiresBatteryNotLow(schedule.runOnlyWhenNotLowBattery).build()

    /** The periodic check every `periodicMinutes` minutes. */
    fun periodic(schedule: ScheduleSettings): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<EstimateNewNightsWorker>(schedule.periodicMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints(schedule))
            .setInputData(WorkerInputs.estimate(EstimateTrigger.PERIODIC))
            .withDefaults(Tags.ESTIMATE)
            .addTag(periodicTag(schedule))
            .build()

    /** The daily run, next at the first `dailyTimeMinutes` local time in [zone] after [nowMs]. */
    fun daily(
        schedule: ScheduleSettings,
        zone: ZoneId,
        nowMs: Long,
    ): PeriodicWorkRequest {
        val next = DailySchedule.nextRunMs(nowMs, schedule.dailyTimeMinutes, zone)
        return PeriodicWorkRequestBuilder<EstimateNewNightsWorker>(DAILY_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints(schedule))
            .setInitialDelay(next - nowMs, TimeUnit.MILLISECONDS)
            .setNextScheduleTimeOverride(next)
            .setInputData(WorkerInputs.estimate(EstimateTrigger.DAILY))
            .withDefaults(Tags.ESTIMATE)
            .addTag(dailyTag(schedule, zone))
            .build()
    }

    /** "Estimate new now": expedited, normal work when out of expedited quota. */
    fun estimateNow(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<EstimateNewNightsWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(WorkerInputs.estimate(EstimateTrigger.NOW))
            .withDefaults(Tags.ESTIMATE)
            .build()

    /** The history backfill requested at [requestedAtMs] (a cancel after that time stops it and every retry of it). */
    fun backfill(requestedAtMs: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<BackfillWorker>()
            .setInputData(WorkerInputs.backfill(requestedAtMs))
            .withDefaults(Tags.BACKFILL)
            .build()

    /** "Recompute all" of [scope] as requested at [requestedAtMs]. */
    fun recomputeAll(
        scope: RecomputeScope,
        requestedAtMs: Long,
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<RecomputeWorker>()
            .setInputData(WorkerInputs.recompute(RecomputeRequest.All(scope, requestedAtMs)))
            .withDefaults(Tags.RECOMPUTE)
            .build()

    /**
     * Recompute of the night [clientRecordId] as requested at [requestedAtMs]: expedited, normal work when out of
     * expedited quota.
     */
    fun recomputeNight(
        clientRecordId: String,
        requestedAtMs: Long,
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<RecomputeWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(WorkerInputs.recompute(RecomputeRequest.Nights(listOf(clientRecordId), requestedAtMs)))
            .withDefaults(Tags.RECOMPUTE)
            .build()

    /** The work of the sync request to Sleep as Android, [delayMs] from now, with the constraints of [schedule]. */
    fun sleepAsAndroidFollowUp(
        schedule: ScheduleSettings,
        delayMs: Long,
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SleepAsAndroidSyncWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints(schedule))
            .withDefaults(Tags.SLEEP_AS_ANDROID)
            .build()

    private fun <B : WorkRequest.Builder<B, *>> B.withDefaults(jobTag: String): B =
        setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_MS, TimeUnit.MILLISECONDS)
            .addTag(TAG_ALL)
            .addTag(jobTag)
}

package io.github.alien2003.pulsestages.work.schedule

import androidx.work.WorkInfo
import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeScope
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.work.run.RunReport
import io.github.alien2003.pulsestages.work.run.WorkResults
import io.github.alien2003.pulsestages.work.worker.JobProgress
import io.github.alien2003.pulsestages.work.worker.WorkerProgress
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId

/** The works whose status the UI shows. */
enum class WorkJob(
    /** Unique work name. */
    val uniqueName: String,
) {
    /** The periodic Changes-API check. */
    ESTIMATE_PERIODIC(WorkRequests.Names.PERIODIC),

    /** The daily run. */
    ESTIMATE_DAILY(WorkRequests.Names.DAILY),

    /** "Estimate new now". */
    ESTIMATE_NOW(WorkRequests.Names.NOW),

    /** The history backfill. */
    BACKFILL(WorkRequests.Names.BACKFILL),

    /** "Recompute all" / "recompute stale". */
    RECOMPUTE_ALL(WorkRequests.Names.RECOMPUTE_ALL),
}

/** State of a work as the UI shows it. */
enum class JobState {
    /** Not enqueued (never, or pruned). */
    NOT_SCHEDULED,

    /** Waiting to run (constraints, delay, backoff). */
    ENQUEUED,

    /** Running. */
    RUNNING,

    /** Waiting for another work. */
    BLOCKED,

    /** Finished successfully (blocked runs too: see [WorkStatus.lastOutput]). */
    SUCCEEDED,

    /** Gave up (out of retries). */
    FAILED,

    /** Cancelled. */
    CANCELLED,
}

/**
 * Status of one work.
 *
 * @property state its state.
 * @property progress progress of a running backfill / recompute, or null.
 * @property nextScheduleTimeMillis when an enqueued work will run next (epoch ms; approximate, see WorkManager's
 *   `WorkInfo.nextScheduleTimeMillis`), or null.
 * @property lastOutput the output of a finished work, or null.
 * @property runAttemptCount runs so far of the current request (retries).
 */
data class WorkStatus(
    val state: JobState,
    val progress: JobProgress? = null,
    val nextScheduleTimeMillis: Long? = null,
    val lastOutput: RunReport? = null,
    val runAttemptCount: Int = 0,
) {
    /** Constructors. */
    companion object {
        /** A work that is not enqueued. */
        val NOT_SCHEDULED: WorkStatus = WorkStatus(JobState.NOT_SCHEDULED)

        /**
         * The status of a unique work from its [infos]: the running one, else the enqueued / blocked one, else the
         * last finished one.
         */
        fun of(infos: List<WorkInfo>): WorkStatus {
            val info =
                infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?: infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
                    ?: infos.firstOrNull { it.state == WorkInfo.State.BLOCKED }
                    ?: infos.lastOrNull()
            return info?.let(::of) ?: NOT_SCHEDULED
        }

        /** The status of one work. */
        fun of(info: WorkInfo): WorkStatus =
            WorkStatus(
                state = stateOf(info.state),
                progress = if (info.state == WorkInfo.State.RUNNING) WorkerProgress.parse(info.progress) else null,
                nextScheduleTimeMillis =
                    info.nextScheduleTimeMillis.takeIf {
                        info.state == WorkInfo.State.ENQUEUED && it != Long.MAX_VALUE
                    },
                lastOutput = if (info.state.isFinished) WorkResults.parse(info.outputData) else null,
                runAttemptCount = info.runAttemptCount,
            )

        private fun stateOf(state: WorkInfo.State): JobState =
            when (state) {
                WorkInfo.State.ENQUEUED -> JobState.ENQUEUED
                WorkInfo.State.RUNNING -> JobState.RUNNING
                WorkInfo.State.SUCCEEDED -> JobState.SUCCEEDED
                WorkInfo.State.FAILED -> JobState.FAILED
                WorkInfo.State.BLOCKED -> JobState.BLOCKED
                WorkInfo.State.CANCELLED -> JobState.CANCELLED
            }
    }
}

/** The device's time zone, for the daily run; abstracted so tests can fix it. */
fun interface LocalZone {
    /** The current zone. */
    fun current(): ZoneId
}

/** [LocalZone] of the device's default zone. */
object SystemLocalZone : LocalZone {
    override fun current(): ZoneId = ZoneId.systemDefault()
}

/**
 * Schedules PulseStages' background work (the UI's entry point to WorkManager). Every job is a unique work, so
 * repeated requests never pile up.
 */
interface WorkScheduler {
    /**
     * Makes sure the periodic check (every `periodicMinutes`, battery-not-low when enabled) and the daily run (at
     * `dailyTimeMinutes` local time) are enqueued with [schedule]. Works that already match are left alone; changed
     * settings re-enqueue them with ExistingPeriodicWorkPolicy.UPDATE (a running work is not interrupted).
     */
    suspend fun ensureScheduled(schedule: ScheduleSettings)

    /** Follows the schedule settings and calls [ensureScheduled] on every change; suspends forever (until cancelled). */
    suspend fun keepScheduled()

    /** Pins the next daily run to the configured time again (called after every daily run). */
    suspend fun realignDaily()

    /**
     * "Estimate new now" (expedited): runs now; a request still pending (e.g. waiting for the backoff of a retry) is
     * replaced by the new one, a running one is kept.
     */
    suspend fun estimateNow()

    /**
     * Starts or resumes the history backfill (ignored while one is pending or running). A backfill cancelled before
     * this call starts over.
     */
    fun startBackfill()

    /**
     * Cancels the backfill: it stops before its next night and its state becomes CANCELLED; a backfill that has not
     * begun processing (waiting for a retry, or its worker still starting) is removed from WorkManager. The cancel is
     * durable: no retry or re-run of the cancelled request processes anything.
     */
    suspend fun cancelBackfill()

    /** Recomputes every night of [scope] (ignored while a recompute-all is pending or running). */
    fun recomputeAll(scope: RecomputeScope = RecomputeScope.ALL)

    /**
     * Recomputes the night [clientRecordId] (expedited): runs now; a pending recompute of that night is replaced, a
     * running one is kept.
     */
    suspend fun recomputeNight(clientRecordId: String)

    /** Status of [job]. */
    fun status(job: WorkJob): Flow<WorkStatus>

    /** Status of the recompute of the night [clientRecordId]. */
    fun nightRecomputeStatus(clientRecordId: String): Flow<WorkStatus>
}

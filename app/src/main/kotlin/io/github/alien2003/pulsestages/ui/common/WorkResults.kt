package io.github.alien2003.pulsestages.ui.common

import androidx.compose.runtime.Immutable
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.work.run.OutcomeKind
import io.github.alien2003.pulsestages.work.run.RunReport
import io.github.alien2003.pulsestages.work.run.RunSummary
import io.github.alien2003.pulsestages.work.schedule.JobState
import io.github.alien2003.pulsestages.work.schedule.WorkStatus
import io.github.alien2003.pulsestages.work.worker.JobProgress

/** Phase of a background job as the screens show it. */
enum class JobPhase {
    /** Not enqueued, or finished. */
    IDLE,

    /** Waiting to run (constraints, another job, or the backoff of a retry). */
    PENDING,

    /** Running. */
    RUNNING,
}

/**
 * A background job as a screen shows it.
 *
 * @property phase idle, pending or running.
 * @property progress progress of a running backfill or recompute, or null.
 * @property retrying the job waits for a retry after a rate-limit or transient error.
 */
@Immutable
data class JobUi(
    val phase: JobPhase = JobPhase.IDLE,
    val progress: JobProgress? = null,
    val retrying: Boolean = false,
) {
    /** Pending or running: its start button is replaced by a progress indicator. */
    val active: Boolean get() = phase != JobPhase.IDLE

    /** Mapping. */
    companion object {
        /** The job of a WorkManager [status]. */
        fun of(status: WorkStatus): JobUi =
            when (status.state) {
                JobState.RUNNING -> JobUi(JobPhase.RUNNING, status.progress)
                JobState.ENQUEUED, JobState.BLOCKED -> JobUi(JobPhase.PENDING, retrying = status.runAttemptCount > 0)
                JobState.NOT_SCHEDULED, JobState.SUCCEEDED, JobState.FAILED, JobState.CANCELLED -> JobUi()
            }
    }
}

/** Whether a job in this state is still to run or running (enqueued, blocked by another job, running). */
val JobState.isActive: Boolean
    get() = this == JobState.ENQUEUED || this == JobState.RUNNING || this == JobState.BLOCKED

/**
 * Follows a WorkManager job that the user started from a screen and tells when the requested run finished, so that
 * its result can be shown once. Status updates arrive asynchronously and the status flow first repeats the result of
 * an earlier run: a finished status counts only after the job was seen active since [request], or when it differs from
 * the status seen at the time of the request (a run so fast that its active states were never observed). Not
 * thread-safe: use it from one thread (the main thread of a ViewModel).
 */
class JobWatch {
    private var requested = false
    private var sawActive = false
    private var baseline: WorkStatus? = null
    private var last: WorkStatus? = null

    /** The user just started the job. */
    fun request() {
        requested = true
        sawActive = false
        baseline = last
    }

    /** Feeds the latest [status]; returns it when it is the end of the requested run, otherwise null. */
    fun onStatus(status: WorkStatus): WorkStatus? {
        last = status
        if (!requested) return null
        return when {
            status.state.isActive -> {
                sawActive = true
                null
            }

            status.state == JobState.NOT_SCHEDULED && !sawActive -> {
                null
            }

            sawActive || status != baseline -> {
                requested = false
                status
            }

            else -> {
                null
            }
        }
    }
}

/** What a finished job was, for the wording of its result. */
enum class RunSubject {
    /** "Estimate new". */
    ESTIMATE,

    /** History backfill. */
    BACKFILL,

    /** Recompute all / recompute stale. */
    RECOMPUTE,

    /** Recompute of one night. */
    RECOMPUTE_NIGHT,
}

/** Snackbar texts of finished background jobs. */
object WorkResultText {
    /** The text of the finished [status] of a [subject] job, or null when [status] is not finished. */
    fun of(
        subject: RunSubject,
        status: WorkStatus,
    ): UiText? =
        when (status.state) {
            JobState.CANCELLED -> UiText.of(R.string.result_cancelled)
            JobState.SUCCEEDED -> status.lastOutput?.let { report(subject, it) } ?: UiText.of(R.string.result_done)
            JobState.FAILED -> status.lastOutput?.let { report(subject, it) } ?: UiText.of(R.string.result_failed)
            JobState.NOT_SCHEDULED, JobState.ENQUEUED, JobState.RUNNING, JobState.BLOCKED -> null
        }

    /** The text of a finished run's [report]. */
    fun report(
        subject: RunSubject,
        report: RunReport,
    ): UiText =
        when (report.kind) {
            OutcomeKind.COMPLETED -> report.summary?.let { summary(subject, it) } ?: UiText.of(R.string.result_done)
            OutcomeKind.BLOCKED -> blocked(report.blockedReason)
            OutcomeKind.RETRY_EXHAUSTED -> UiText.of(R.string.result_busy)
            OutcomeKind.FAILED -> UiText.of(R.string.result_failed)
        }

    /** Why a blocked run read nothing. */
    fun blocked(reason: BlockedReason?): UiText =
        when (reason) {
            BlockedReason.HEALTH_CONNECT_UNAVAILABLE -> UiText.of(R.string.error_hc_unavailable)
            BlockedReason.BACKGROUND_PERMISSION_MISSING -> UiText.of(R.string.result_blocked_background)
            BlockedReason.PERMISSION_MISSING, null -> UiText.of(R.string.result_blocked_permission)
        }

    private fun summary(
        subject: RunSubject,
        summary: RunSummary,
    ): UiText {
        val main =
            when {
                summary.alreadyRunning -> {
                    UiText.of(R.string.result_already_running)
                }

                summary.cancelled -> {
                    UiText.of(R.string.result_cancelled)
                }

                subject == RunSubject.RECOMPUTE_NIGHT -> {
                    UiText.of(if (summary.estimated > 0) R.string.result_night_recomputed else R.string.result_night_not_recomputed)
                }

                else -> {
                    val estimated = UiText.plural(R.plurals.result_estimated, summary.estimated)
                    UiText.of(templateOf(subject), estimated, summary.found, summary.skipped)
                }
            }
        return if (summary.failed > 0) {
            UiText.of(R.string.result_with_failures, main, UiText.plural(R.plurals.result_failed_nights, summary.failed))
        } else {
            main
        }
    }

    private fun templateOf(subject: RunSubject): Int =
        when (subject) {
            RunSubject.ESTIMATE -> R.string.result_summary_estimate
            RunSubject.BACKFILL -> R.string.result_summary_backfill
            RunSubject.RECOMPUTE, RunSubject.RECOMPUTE_NIGHT -> R.string.result_summary_recompute
        }
}

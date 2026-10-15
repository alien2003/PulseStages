package io.github.alien2003.pulsestages.work.run

import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.hc.pipeline.PipelineReport
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import kotlin.coroutines.cancellation.CancellationException

/** Why a run should be retried later with backoff. */
enum class RetryCause {
    /** Health Connect's rate limit was hit (after the repository's own short retries). */
    QUOTA,

    /** A transient Health Connect error (IPC / IO). */
    TRANSIENT,
}

/**
 * Counters of a finished run.
 *
 * @property found nights found.
 * @property estimated nights estimated and written.
 * @property skipped nights not written (naps, sessions with stages, duplicates, up to date, ...).
 * @property stale WRITTEN nights left stale.
 * @property failed nights whose processing failed (logged; the run continued).
 * @property superseded older outputs replaced or removed.
 * @property refreshed written nights rewritten because their input changed after the write (late heart rate; not
 *   counted in [estimated]).
 * @property cancelled the run was cancelled by the user (backfill).
 * @property alreadyRunning another run of the same job was in progress; nothing was done.
 * @property personalModelUpdated population EM stored a new personal model after the run.
 */
data class RunSummary(
    val found: Int = 0,
    val estimated: Int = 0,
    val skipped: Int = 0,
    val stale: Int = 0,
    val failed: Int = 0,
    val superseded: Int = 0,
    val refreshed: Int = 0,
    val cancelled: Boolean = false,
    val alreadyRunning: Boolean = false,
    val personalModelUpdated: Boolean = false,
) {
    /** Constructors. */
    companion object {
        /** The counters of a pipeline [report]. */
        fun of(report: PipelineReport): RunSummary =
            RunSummary(
                found = report.found,
                estimated = report.estimated,
                skipped = report.skipped,
                stale = report.stale,
                failed = report.failed,
                superseded = report.superseded,
                refreshed = report.refreshed,
                cancelled = report.cancelled,
            )
    }
}

/** What a run achieved, independent of WorkManager; [WorkResults] maps it to a `ListenableWorker.Result`. */
sealed interface WorkOutcome {
    /** The run finished (some nights may have failed; they are logged and retried by later runs). */
    data class Completed(
        /** Counters. */
        val summary: RunSummary,
    ) : WorkOutcome

    /**
     * Nothing was read (or the run stopped): Health Connect is unavailable, a permission is missing, or a background
     * run lacks READ_HEALTH_DATA_IN_BACKGROUND. Not retried with backoff: the next scheduled run tries again.
     */
    data class Blocked(
        /** Why. */
        val reason: BlockedReason,
        /** English detail, if any. */
        val detail: String? = null,
    ) : WorkOutcome

    /** Retry later with exponential backoff. */
    data class Retry(
        /** Why. */
        val cause: RetryCause,
        /** English detail, if any. */
        val detail: String? = null,
    ) : WorkOutcome

    /** An unexpected error (a bug, invalid data): retried a few times, then the work fails. */
    data class Unexpected(
        /** English detail. */
        val detail: String,
    ) : WorkOutcome

    /**
     * A failure that a retry of the same request cannot fix (e.g. a backfill that stored its FAILED state: a retry
     * would start it over and stop at the same place): the work fails at once.
     */
    data class Failed(
        /** English detail. */
        val detail: String,
    ) : WorkOutcome
}

/**
 * How often a work is retried before WorkManager gets `Result.failure()`. `runAttemptCount` is 0 on the first run, so
 * a limit of N means N retries after the first attempt.
 *
 * @property maxRetries quota / transient errors (exponential backoff from 30 s, capped at 5 h by WorkManager).
 * @property maxUnexpectedRetries unexpected errors.
 */
data class RetryLimits(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val maxUnexpectedRetries: Int = DEFAULT_MAX_UNEXPECTED_RETRIES,
) {
    /** Defaults. */
    companion object {
        /** About two and a half days of exponential backoff. */
        const val DEFAULT_MAX_RETRIES: Int = 20

        /** Three retries of an unexpected error. */
        const val DEFAULT_MAX_UNEXPECTED_RETRIES: Int = 3
    }
}

/**
 * Runs [block] and turns every failure into a [WorkOutcome] (logged): Health Connect errors by their kind (a refused
 * permission is classified by [classifyDenial]), anything else — any [Throwable] — as [WorkOutcome.Unexpected]. Only
 * cancellation is rethrown (structured concurrency). This is what guarantees that a worker never throws.
 */
@Suppress("TooGenericExceptionCaught") // Workers must never throw: every Throwable except cancellation becomes an outcome.
suspend fun guardedRun(
    logger: AppLogger,
    tag: String,
    classifyDenial: suspend () -> BlockedReason = { BlockedReason.PERMISSION_MISSING },
    block: suspend () -> WorkOutcome,
): WorkOutcome =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HealthDataException) {
        logger.w(tag, "stopped by a Health Connect error: ${e.message}", throwable = e)
        outcomeOf(e, classifyDenial)
    } catch (e: Throwable) {
        logger.e(tag, "failed unexpectedly: ${e.javaClass.simpleName}: ${e.message}", throwable = e)
        WorkOutcome.Unexpected("${e.javaClass.simpleName}: ${e.message}")
    }

/** The outcome of a Health Connect error that ended a run. */
suspend fun outcomeOf(
    error: HealthDataException,
    classifyDenial: suspend () -> BlockedReason = { BlockedReason.PERMISSION_MISSING },
): WorkOutcome =
    when (error) {
        is HealthDataException.QuotaExceeded -> WorkOutcome.Retry(RetryCause.QUOTA, error.message)
        is HealthDataException.Transient -> WorkOutcome.Retry(RetryCause.TRANSIENT, error.message)
        is HealthDataException.PermissionDenied -> WorkOutcome.Blocked(classifyDenial(), error.message)
        is HealthDataException.Unavailable -> WorkOutcome.Blocked(BlockedReason.HEALTH_CONNECT_UNAVAILABLE, error.message)
        is HealthDataException.InvalidData -> WorkOutcome.Unexpected(error.message.orEmpty())
    }

/** The outcome of a pipeline [report]: blocked → [WorkOutcome.Blocked], quota → retry, otherwise completed. */
fun outcomeOf(report: PipelineReport): WorkOutcome {
    val blocked = report.blockedReason
    return when {
        blocked != null -> WorkOutcome.Blocked(blocked)
        report.quotaHit -> WorkOutcome.Retry(RetryCause.QUOTA)
        else -> WorkOutcome.Completed(RunSummary.of(report))
    }
}

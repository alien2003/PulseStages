package io.github.alien2003.pulsestages.work.run

import androidx.work.Data
import androidx.work.ListenableWorker
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason

/** What WorkManager is told after a run. */
enum class ResultKind {
    /** `Result.success(output)`: done (blocked runs too — no retry storm; the next scheduled run tries again). */
    SUCCESS,

    /** `Result.retry()`: run again after the request's exponential backoff. */
    RETRY,

    /** `Result.failure(output)`: give up this request (a periodic work still runs in its next period). */
    FAILURE,
}

/** How a run ended, as stored in the output [Data] of a work. */
enum class OutcomeKind {
    /** [WorkOutcome.Completed]. */
    COMPLETED,

    /** [WorkOutcome.Blocked]. */
    BLOCKED,

    /** [WorkOutcome.Retry] that ran out of retries. */
    RETRY_EXHAUSTED,

    /** [WorkOutcome.Unexpected] that ran out of retries, or [WorkOutcome.Failed]. */
    FAILED,
}

/**
 * The output of a finished work, read back from its [Data] (see [WorkResults.parse]).
 *
 * @property kind how the run ended.
 * @property summary counters of a completed run, null otherwise.
 * @property blockedReason why a blocked run read nothing.
 * @property retryCause the cause of the last retry of a run that ran out of retries.
 * @property detail English detail, if any.
 */
data class RunReport(
    val kind: OutcomeKind,
    val summary: RunSummary?,
    val blockedReason: BlockedReason?,
    val retryCause: RetryCause?,
    val detail: String?,
)

/**
 * Maps a [WorkOutcome] to a WorkManager result (pure: [kindOf] decides, [toResult] builds it) and stores / reads the
 * output [Data] of finished works:
 * - [WorkOutcome.Completed] → success with the counters;
 * - [WorkOutcome.Blocked] → success with outcome BLOCKED and the reason (nothing to retry until the user acts or the
 *   next period comes);
 * - [WorkOutcome.Retry] → retry while `runAttemptCount < maxRetries`, then failure (RETRY_EXHAUSTED);
 * - [WorkOutcome.Unexpected] → retry while `runAttemptCount < maxUnexpectedRetries`, then failure (FAILED);
 * - [WorkOutcome.Failed] → failure at once (FAILED).
 */
object WorkResults {
    /** Output key: [OutcomeKind] name. */
    const val KEY_OUTCOME: String = "outcome"

    /** Output key: [BlockedReason] name. */
    const val KEY_BLOCKED_REASON: String = "blockedReason"

    /** Output key: [RetryCause] name. */
    const val KEY_RETRY_CAUSE: String = "retryCause"

    /** Output key: English detail. */
    const val KEY_DETAIL: String = "detail"

    /** Output key: nights found. */
    const val KEY_FOUND: String = "found"

    /** Output key: nights estimated. */
    const val KEY_ESTIMATED: String = "estimated"

    /** Output key: nights skipped. */
    const val KEY_SKIPPED: String = "skipped"

    /** Output key: nights left stale. */
    const val KEY_STALE: String = "stale"

    /** Output key: nights failed. */
    const val KEY_FAILED: String = "failed"

    /** Output key: outputs superseded. */
    const val KEY_SUPERSEDED: String = "superseded"

    /** Output key: written nights rewritten because their input changed. */
    const val KEY_REFRESHED: String = "refreshed"

    /** Output key: cancelled by the user. */
    const val KEY_CANCELLED: String = "cancelled"

    /** Output key: another run was in progress. */
    const val KEY_ALREADY_RUNNING: String = "alreadyRunning"

    /** Output key: a new personal model was stored. */
    const val KEY_MODEL_UPDATED: String = "personalModelUpdated"

    /** Output key: Sleep as Android was asked to sync. */
    const val KEY_SLEEP_AS_ANDROID_SYNC: String = "sleepAsAndroidSyncRequested"

    /** Longest stored detail (WorkManager limits a Data object to 10 KB). */
    private const val MAX_DETAIL_LENGTH = 1_000

    /** The result kind of [outcome] on attempt [runAttemptCount] (0 = first run). */
    fun kindOf(
        outcome: WorkOutcome,
        runAttemptCount: Int,
        limits: RetryLimits,
    ): ResultKind =
        when (outcome) {
            is WorkOutcome.Completed, is WorkOutcome.Blocked -> ResultKind.SUCCESS
            is WorkOutcome.Retry -> if (runAttemptCount < limits.maxRetries) ResultKind.RETRY else ResultKind.FAILURE
            is WorkOutcome.Unexpected -> if (runAttemptCount < limits.maxUnexpectedRetries) ResultKind.RETRY else ResultKind.FAILURE
            is WorkOutcome.Failed -> ResultKind.FAILURE
        }

    /** The WorkManager result of [outcome] on attempt [runAttemptCount]. */
    fun toResult(
        outcome: WorkOutcome,
        runAttemptCount: Int,
        limits: RetryLimits,
    ): ListenableWorker.Result =
        when (kindOf(outcome, runAttemptCount, limits)) {
            ResultKind.SUCCESS -> ListenableWorker.Result.success(output(outcome))
            ResultKind.RETRY -> ListenableWorker.Result.retry()
            ResultKind.FAILURE -> ListenableWorker.Result.failure(output(outcome))
        }

    /** The output [Data] of a finished run with [outcome]. */
    fun output(outcome: WorkOutcome): Data {
        val builder = Data.Builder()
        when (outcome) {
            is WorkOutcome.Completed -> {
                builder.putString(KEY_OUTCOME, OutcomeKind.COMPLETED.name)
                putSummary(builder, outcome.summary)
            }

            is WorkOutcome.Blocked -> {
                builder.putString(KEY_OUTCOME, OutcomeKind.BLOCKED.name).putString(KEY_BLOCKED_REASON, outcome.reason.name)
                outcome.detail?.let { builder.putString(KEY_DETAIL, it.take(MAX_DETAIL_LENGTH)) }
            }

            is WorkOutcome.Retry -> {
                builder.putString(KEY_OUTCOME, OutcomeKind.RETRY_EXHAUSTED.name).putString(KEY_RETRY_CAUSE, outcome.cause.name)
                outcome.detail?.let { builder.putString(KEY_DETAIL, it.take(MAX_DETAIL_LENGTH)) }
            }

            is WorkOutcome.Unexpected -> {
                builder.putString(KEY_OUTCOME, OutcomeKind.FAILED.name).putString(KEY_DETAIL, outcome.detail.take(MAX_DETAIL_LENGTH))
            }

            is WorkOutcome.Failed -> {
                builder.putString(KEY_OUTCOME, OutcomeKind.FAILED.name).putString(KEY_DETAIL, outcome.detail.take(MAX_DETAIL_LENGTH))
            }
        }
        return builder.build()
    }

    /** The report stored in [data] by [output], or null when [data] holds none (running, periodic, or never ran). */
    fun parse(data: Data): RunReport? {
        val kind = enumOrNull<OutcomeKind>(data.getString(KEY_OUTCOME)) ?: return null
        return RunReport(
            kind = kind,
            summary = if (kind == OutcomeKind.COMPLETED) summaryOf(data) else null,
            blockedReason = enumOrNull<BlockedReason>(data.getString(KEY_BLOCKED_REASON)),
            retryCause = enumOrNull<RetryCause>(data.getString(KEY_RETRY_CAUSE)),
            detail = data.getString(KEY_DETAIL),
        )
    }

    private fun putSummary(
        builder: Data.Builder,
        summary: RunSummary,
    ) {
        builder
            .putInt(KEY_FOUND, summary.found)
            .putInt(KEY_ESTIMATED, summary.estimated)
            .putInt(KEY_SKIPPED, summary.skipped)
            .putInt(KEY_STALE, summary.stale)
            .putInt(KEY_FAILED, summary.failed)
            .putInt(KEY_SUPERSEDED, summary.superseded)
            .putInt(KEY_REFRESHED, summary.refreshed)
            .putBoolean(KEY_CANCELLED, summary.cancelled)
            .putBoolean(KEY_ALREADY_RUNNING, summary.alreadyRunning)
            .putBoolean(KEY_MODEL_UPDATED, summary.personalModelUpdated)
            .putBoolean(KEY_SLEEP_AS_ANDROID_SYNC, summary.sleepAsAndroidSyncRequested)
    }

    private fun summaryOf(data: Data): RunSummary =
        RunSummary(
            found = data.getInt(KEY_FOUND, 0),
            estimated = data.getInt(KEY_ESTIMATED, 0),
            skipped = data.getInt(KEY_SKIPPED, 0),
            stale = data.getInt(KEY_STALE, 0),
            failed = data.getInt(KEY_FAILED, 0),
            superseded = data.getInt(KEY_SUPERSEDED, 0),
            refreshed = data.getInt(KEY_REFRESHED, 0),
            cancelled = data.getBoolean(KEY_CANCELLED, false),
            alreadyRunning = data.getBoolean(KEY_ALREADY_RUNNING, false),
            personalModelUpdated = data.getBoolean(KEY_MODEL_UPDATED, false),
            sleepAsAndroidSyncRequested = data.getBoolean(KEY_SLEEP_AS_ANDROID_SYNC, false),
        )

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }
}

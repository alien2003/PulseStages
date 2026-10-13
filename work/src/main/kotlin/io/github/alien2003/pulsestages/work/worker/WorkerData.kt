package io.github.alien2003.pulsestages.work.worker

import androidx.work.Data
import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeProgress
import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeScope
import io.github.alien2003.pulsestages.data.local.db.BackfillStateEntity
import io.github.alien2003.pulsestages.work.run.RecomputeRequest

/** Why EstimateNewNightsWorker runs. */
enum class EstimateTrigger(
    /**
     * Requested by the user: the run goes foreground (a foreground service), so that leaving the app does not turn it
     * into a background read. Whether it reads as a background run is still decided by the app's state when it runs.
     */
    val userInitiated: Boolean,
) {
    /** The periodic Changes-API check (every `periodicHours`). */
    PERIODIC(userInitiated = false),

    /** The daily run at `dailyTimeMinutes`. */
    DAILY(userInitiated = false),

    /** "Estimate new now" from the UI (expedited). */
    NOW(userInitiated = true),
}

/** Input data of the workers. */
object WorkerInputs {
    /** EstimateNewNightsWorker: [EstimateTrigger] name. */
    const val KEY_TRIGGER: String = "trigger"

    /** RecomputeWorker: [MODE_NIGHTS] or a [RecomputeScope] name. */
    const val KEY_RECOMPUTE_MODE: String = "recomputeMode"

    /** RecomputeWorker: clientRecordIds of the nights (mode [MODE_NIGHTS]). */
    const val KEY_NIGHT_IDS: String = "nightIds"

    /** RecomputeWorker and BackfillWorker: when the user asked (epoch ms). */
    const val KEY_REQUESTED_AT: String = "requestedAt"

    /** Recompute mode of selected nights. */
    const val MODE_NIGHTS: String = "NIGHTS"

    /** Input of EstimateNewNightsWorker. */
    fun estimate(trigger: EstimateTrigger): Data = Data.Builder().putString(KEY_TRIGGER, trigger.name).build()

    /** The trigger in [data] ([EstimateTrigger.PERIODIC] when missing or unknown). */
    fun triggerOf(data: Data): EstimateTrigger =
        EstimateTrigger.entries.firstOrNull { it.name == data.getString(KEY_TRIGGER) } ?: EstimateTrigger.PERIODIC

    /** Input of BackfillWorker: the backfill the user requested at [requestedAtMs]. */
    fun backfill(requestedAtMs: Long): Data = Data.Builder().putLong(KEY_REQUESTED_AT, requestedAtMs).build()

    /** When the backfill in [data] was requested, or null when the input does not say. */
    fun backfillRequestedAtOf(data: Data): Long? =
        if (data.keyValueMap.containsKey(KEY_REQUESTED_AT)) data.getLong(KEY_REQUESTED_AT, 0L) else null

    /** Input of RecomputeWorker for [request]. */
    fun recompute(request: RecomputeRequest): Data =
        when (request) {
            is RecomputeRequest.Nights -> {
                Data
                    .Builder()
                    .putString(KEY_RECOMPUTE_MODE, MODE_NIGHTS)
                    .putStringArray(KEY_NIGHT_IDS, request.clientRecordIds.toTypedArray())
                    .build()
            }

            is RecomputeRequest.All -> {
                Data
                    .Builder()
                    .putString(KEY_RECOMPUTE_MODE, request.scope.name)
                    .putLong(KEY_REQUESTED_AT, request.requestedAtMs)
                    .build()
            }
        }

    /** The request in [data], or null when it is missing or malformed. */
    fun recomputeRequestOf(data: Data): RecomputeRequest? {
        val mode = data.getString(KEY_RECOMPUTE_MODE)
        val scope = RecomputeScope.entries.firstOrNull { it.name == mode }
        return when {
            mode == MODE_NIGHTS -> data.getNullableStringArray(KEY_NIGHT_IDS)?.filterNotNull()?.let { RecomputeRequest.Nights(it) }
            scope != null -> RecomputeRequest.All(scope, data.getLong(KEY_REQUESTED_AT, Long.MAX_VALUE))
            else -> null
        }
    }
}

/**
 * Progress of a running work (`setProgress`).
 *
 * @property done windows (backfill) or nights (recompute) processed.
 * @property total windows or nights in total.
 * @property nightsFound nights found so far (backfill).
 * @property nightsEstimated nights with an up-to-date estimate so far (backfill).
 * @property nightsSkipped nights skipped so far (backfill).
 * @property nightsFailed nights failed so far (backfill).
 */
data class JobProgress(
    val done: Int,
    val total: Int,
    val nightsFound: Int = 0,
    val nightsEstimated: Int = 0,
    val nightsSkipped: Int = 0,
    val nightsFailed: Int = 0,
)

/** Progress data of the workers. */
object WorkerProgress {
    /** Units done. */
    const val KEY_DONE: String = "done"

    /** Units in total. */
    const val KEY_TOTAL: String = "total"

    /** Nights found. */
    const val KEY_FOUND: String = "nightsFound"

    /** Nights estimated. */
    const val KEY_ESTIMATED: String = "nightsEstimated"

    /** Nights skipped. */
    const val KEY_SKIPPED: String = "nightsSkipped"

    /** Nights failed. */
    const val KEY_FAILED: String = "nightsFailed"

    /** The progress of a backfill in [state]. */
    fun of(state: BackfillStateEntity): JobProgress =
        JobProgress(
            state.windowsDone,
            state.windowsTotal,
            state.nightsFound,
            state.nightsEstimated,
            state.nightsSkipped,
            state.nightsFailed,
        )

    /** The progress of a recompute. */
    fun of(progress: RecomputeProgress): JobProgress = JobProgress(progress.done, progress.total)

    /** [progress] as work progress data. */
    fun toData(progress: JobProgress): Data =
        Data
            .Builder()
            .putInt(KEY_DONE, progress.done)
            .putInt(KEY_TOTAL, progress.total)
            .putInt(KEY_FOUND, progress.nightsFound)
            .putInt(KEY_ESTIMATED, progress.nightsEstimated)
            .putInt(KEY_SKIPPED, progress.nightsSkipped)
            .putInt(KEY_FAILED, progress.nightsFailed)
            .build()

    /** The progress in [data], or null when it holds none. */
    fun parse(data: Data): JobProgress? =
        if (!data.keyValueMap.containsKey(KEY_TOTAL)) {
            null
        } else {
            JobProgress(
                done = data.getInt(KEY_DONE, 0),
                total = data.getInt(KEY_TOTAL, 0),
                nightsFound = data.getInt(KEY_FOUND, 0),
                nightsEstimated = data.getInt(KEY_ESTIMATED, 0),
                nightsSkipped = data.getInt(KEY_SKIPPED, 0),
                nightsFailed = data.getInt(KEY_FAILED, 0),
            )
        }
}

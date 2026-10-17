package io.github.alien2003.pulsestages.work.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.work.run.BackfillRunner
import io.github.alien2003.pulsestages.work.run.EstimateNewNightsRunner
import io.github.alien2003.pulsestages.work.run.RecomputeRunner
import io.github.alien2003.pulsestages.work.run.WorkOutcome
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidSyncRequests
import io.github.alien2003.pulsestages.work.schedule.WorkScheduler
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finds and estimates new nights (periodic, daily, or "estimate now"): a thin adapter over [EstimateNewNightsRunner].
 * "Estimate now" first goes foreground ([WorkerSupport.enterForeground]) and re-checks every due recent night at once
 * (`userRequested`); every run then reads as a background run unless the app is in the foreground at that moment
 * ([WorkerSupport.inBackground]: a visible activity or the foreground service), whatever started it. After a daily run
 * (unless it is retried with backoff) the next daily run is pinned to the configured time again (WorkManager would
 * otherwise drift by the run's delay). Never throws out of [doWork] except for cancellation.
 */
@HiltWorker
class EstimateNewNightsWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val runner: EstimateNewNightsRunner,
        private val support: WorkerSupport,
        private val scheduler: WorkScheduler,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val trigger = WorkerInputs.triggerOf(inputData)
            val result =
                support.complete(this, TAG) {
                    if (trigger.userInitiated) support.enterForeground(this, TAG, support.notifications.estimateInfo())
                    runner.run(support.inBackground(), userRequested = trigger.userInitiated)
                }
            // A retry keeps its backoff; otherwise the next daily run is pinned to the configured time again.
            if (trigger == EstimateTrigger.DAILY && result !is Result.Retry) realignDaily()
            return result
        }

        override suspend fun getForegroundInfo(): ForegroundInfo = support.notifications.estimateInfo()

        @Suppress("TooGenericExceptionCaught") // Rescheduling is best effort: the periodic run still runs anyway.
        private suspend fun realignDaily() {
            try {
                scheduler.realignDaily()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                support.logger.w(TAG, "could not pin the next daily run", throwable = e)
            }
        }

        private companion object {
            const val TAG = "EstimateNewNightsWorker"
        }
    }

/**
 * Processes the whole history ([BackfillRunner]) as a long-running work: a foreground service of type dataSync with a
 * progress notification (when the platform allows one; otherwise it continues without, as a background run),
 * progress after every 7-day window, cancellation between nights (WorkScheduler.cancelBackfill; a cancel made after
 * the backfill was requested also stops a retry or a run that has not begun), resumable from the persisted cursor
 * after a quota or transient error, a stop by the system or process death (WorkManager re-runs the unique work). An
 * unexpected error fails the work at once (the state says FAILED; starting it again starts over). Never throws out of
 * [doWork] except for cancellation.
 */
@HiltWorker
class BackfillWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val runner: BackfillRunner,
        private val support: WorkerSupport,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result =
            support.complete(this, TAG) {
                var foreground = support.enterForeground(this, TAG, support.notifications.backfillInfo(null))
                runner.run(support.inBackground(), WorkerInputs.backfillRequestedAtOf(inputData)) { state ->
                    val progress = WorkerProgress.of(state)
                    support.publish(this, TAG, progress)
                    if (foreground) foreground = support.tryForeground(this, TAG, support.notifications.backfillInfo(progress))
                }
            }

        override suspend fun getForegroundInfo(): ForegroundInfo = support.notifications.backfillInfo(null)

        private companion object {
            const val TAG = "BackfillWorker"
        }
    }

/**
 * Recomputes selected nights or every (stale) night ([RecomputeRunner]) as a foreground service like the backfill
 * (when the platform allows one; otherwise as a background run) and reports its progress. A malformed request fails
 * the work. Never throws out of [doWork] except for cancellation.
 */
@HiltWorker
class RecomputeWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val runner: RecomputeRunner,
        private val support: WorkerSupport,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result =
            support.complete(this, TAG) {
                val request = WorkerInputs.recomputeRequestOf(inputData)
                if (request == null) {
                    WorkOutcome.Unexpected("malformed recompute request: ${inputData.keyValueMap}")
                } else {
                    var foreground = support.enterForeground(this, TAG, support.notifications.recomputeInfo(null))
                    runner.run(request, support.inBackground()) { recomputed ->
                        val progress = WorkerProgress.of(recomputed)
                        support.publish(this, TAG, progress)
                        if (foreground) foreground = support.tryForeground(this, TAG, support.notifications.recomputeInfo(progress))
                    }
                }
            }

        override suspend fun getForegroundInfo(): ForegroundInfo = support.notifications.recomputeInfo(null)

        private companion object {
            const val TAG = "RecomputeWorker"
        }
    }

/**
 * Sends the follow-up sync request to Sleep as Android ([SleepAsAndroidSyncRequests.sendFollowUp]) that a run which
 * wrote a new night scheduled about 10 minutes later. Reads nothing from Health Connect and always succeeds: a failed
 * request is logged (a retry could only send it late). Never throws out of [doWork] except for cancellation.
 */
@HiltWorker
class SleepAsAndroidSyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val requests: SleepAsAndroidSyncRequests,
        private val logger: AppLogger,
    ) : CoroutineWorker(appContext, params) {
        @Suppress("TooGenericExceptionCaught") // Workers must never throw: a failed request is logged, not retried.
        override suspend fun doWork(): Result {
            try {
                requests.sendFollowUp()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.e(TAG, "follow-up sync request failed unexpectedly", throwable = e)
            }
            return Result.success()
        }

        private companion object {
            const val TAG = "SleepAsAndroidSyncWorker"
        }
    }

package io.github.alien2003.pulsestages.work.worker

import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import io.github.alien2003.pulsestages.data.hc.AppForegroundState
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.work.notify.WorkNotifications
import io.github.alien2003.pulsestages.work.run.RetryLimits
import io.github.alien2003.pulsestages.work.run.WorkOutcome
import io.github.alien2003.pulsestages.work.run.WorkResults
import io.github.alien2003.pulsestages.work.run.guardedRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** What every worker needs besides its runner: the foreground state, the retry limits, notifications and the log. */
class WorkerSupport
    @Inject
    constructor(
        /** Whether Health Connect sees the app in the foreground (a visible activity or a foreground service). */
        val foregroundState: AppForegroundState,
        /** Retry limits of the Result mapping. */
        val retryLimits: RetryLimits,
        /** Foreground notifications. */
        val notifications: WorkNotifications,
        /** The internal log. */
        val logger: AppLogger,
    ) {
        /**
         * Whether the run reads as a background run right now, from the app's real state, never from what started it:
         * true unless an activity of the app is in front or the work runs as a foreground service. A run the user
         * started is a background run too when it executes later in the background (a WorkManager retry, which is
         * never expedited, a resume after process death, a foreground service the platform refused). Without
         * READ_HEALTH_DATA_IN_BACKGROUND such a run is blocked (BACKGROUND_PERMISSION_MISSING) instead of reading;
         * Health Connect would refuse it, or (android14-release) silently return no data of other apps. A run that
         * loses the foreground later is stopped by the repository's background rule
         * ([io.github.alien2003.pulsestages.data.hc.BackgroundRuleHealthRepository]).
         */
        fun inBackground(): Boolean = !foregroundState.isInForeground()

        /**
         * Makes a run the user asked for a foreground service ([tryForeground] with [info]), so that it keeps reading in
         * the foreground when the user leaves the app, and waits up to [FOREGROUND_SETTLE_MS] until the process is
         * promoted (the service starts asynchronously). Returns whether the foreground service was started.
         */
        suspend fun enterForeground(
            worker: CoroutineWorker,
            tag: String,
            info: ForegroundInfo,
        ): Boolean {
            val started = tryForeground(worker, tag, info)
            if (started) {
                val promoted =
                    withTimeoutOrNull(FOREGROUND_SETTLE_MS) {
                        while (!foregroundState.isInForeground()) delay(FOREGROUND_POLL_MS)
                        true
                    }
                if (promoted == null) logger.w(tag, "foreground service started, but the app is not in the foreground yet")
            }
            return started
        }

        /**
         * Runs [block] for [worker] and maps its outcome to a [ListenableWorker.Result] (see [WorkResults]). Never
         * throws except for cancellation, which is rethrown.
         */
        suspend fun complete(
            worker: ListenableWorker,
            tag: String,
            block: suspend () -> WorkOutcome,
        ): ListenableWorker.Result {
            val outcome = guardedRun(logger, tag, block = block)
            val attempt = worker.runAttemptCount
            val kind = WorkResults.kindOf(outcome, attempt, retryLimits)
            logger.i(tag, "run ${attempt + 1} finished: $outcome -> $kind")
            return WorkResults.toResult(outcome, attempt, retryLimits)
        }

        /**
         * Shows [info] as the foreground notification of [worker] (a long-running work). When the platform does not
         * allow a foreground service right now (Android 12+ from the background, a missing FGS permission) the work
         * goes on without one; returns false then, so the caller stops trying.
         */
        @Suppress("TooGenericExceptionCaught") // Any failure to go foreground must not stop the work itself.
        suspend fun tryForeground(
            worker: CoroutineWorker,
            tag: String,
            info: ForegroundInfo,
        ): Boolean =
            try {
                worker.setForeground(info)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(tag, "cannot run as a foreground service; continuing in the background", throwable = e)
                false
            }

        /** Publishes [progress] of [worker]; a failure is logged and ignored. */
        @Suppress("TooGenericExceptionCaught") // Progress is informational: a failed update must not stop the work.
        suspend fun publish(
            worker: CoroutineWorker,
            tag: String,
            progress: JobProgress,
        ) {
            try {
                worker.setProgress(WorkerProgress.toData(progress))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(tag, "cannot publish progress", throwable = e)
            }
        }

        /** Timing of [enterForeground]. */
        companion object {
            /** Longest wait for the process to be promoted after the foreground service was started. */
            const val FOREGROUND_SETTLE_MS: Long = 3_000L

            /** Interval of the foreground checks while waiting. */
            const val FOREGROUND_POLL_MS: Long = 50L
        }
    }

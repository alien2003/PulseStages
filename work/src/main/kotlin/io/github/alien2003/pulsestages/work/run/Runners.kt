package io.github.alien2003.pulsestages.work.run

import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.pipeline.BackfillEngine
import io.github.alien2003.pulsestages.data.hc.pipeline.BackfillOutcome
import io.github.alien2003.pulsestages.data.hc.pipeline.BackfillReport
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.hc.pipeline.HealthAccessGuard
import io.github.alien2003.pulsestages.data.hc.pipeline.NewNightsSync
import io.github.alien2003.pulsestages.data.hc.pipeline.NightRecomputer
import io.github.alien2003.pulsestages.data.hc.pipeline.PipelineReport
import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeProgress
import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeScope
import io.github.alien2003.pulsestages.data.hc.pipeline.SyncReport
import io.github.alien2003.pulsestages.data.hc.pipeline.WriteCount
import io.github.alien2003.pulsestages.data.hc.usecase.EmRunResult
import io.github.alien2003.pulsestages.data.hc.usecase.PersonalizationService
import io.github.alien2003.pulsestages.data.local.db.BackfillStateEntity
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidSyncRequests
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * The work of EstimateNewNightsWorker, without WorkManager: Health Connect availability → permissions (the required
 * ones, plus READ_HEALTH_DATA_IN_BACKGROUND for a background run) → [NewNightsSync] (which performs both checks and
 * records the run in SyncStateStore) → [SleepAsAndroidSyncRequests.afterRun] with the sessions the sync wrote (also when
 * it stopped early, ended with a Health Connect error or was cancelled) → [PersonalizationService.maybeRunPopulationEm]
 * → outcome. Runs of the periodic, daily and "estimate now" works are serialized. Never throws except for
 * cancellation.
 */
@Singleton
class EstimateNewNightsRunner
    @Inject
    constructor(
        private val sync: NewNightsSync,
        private val personalization: PersonalizationService,
        private val sleepAsAndroid: SleepAsAndroidSyncRequests,
        private val logger: AppLogger,
    ) {
        private val mutex = Mutex()

        /**
         * One run; [inBackground] is true when the app is not visible (Health Connect's background rule applies),
         * [userRequested] for "Estimate new now" (recent nights are re-checked at once, see [NewNightsSync.run]).
         */
        suspend fun run(
            inBackground: Boolean,
            userRequested: Boolean = false,
        ): WorkOutcome =
            mutex.withLock {
                guardedRun(logger, TAG) {
                    // Sleep as Android is asked before population EM, so that it starts at once.
                    when (val outcome = sleepAsAndroid.requestSyncAfter { outcomeOf(sync.run(inBackground, userRequested, it)) }) {
                        is WorkOutcome.Completed -> outcome.copy(summary = outcome.summary.copy(personalModelUpdated = adapt(inBackground)))
                        else -> outcome
                    }
                }
            }

        /** Population EM after a completed sync; its failures never fail the sync. True when a model was stored. */
        @Suppress("TooGenericExceptionCaught") // The sync already succeeded: an EM failure is logged, not propagated.
        private suspend fun adapt(inBackground: Boolean): Boolean {
            val result =
                try {
                    personalization.maybeRunPopulationEm(inBackground)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e(TAG, "population EM failed", throwable = e)
                    null
                }
            when (result) {
                is EmRunResult.Adapted -> logger.i(TAG, "personal model adapted (population EM)")
                is EmRunResult.Blocked -> logger.i(TAG, "population EM not run: ${result.reason}")
                is EmRunResult.Interrupted -> logger.w(TAG, "population EM postponed: ${result.detail}")
                null -> Unit
                else -> logger.d(TAG, "population EM: $result")
            }
            return result is EmRunResult.Adapted
        }

        /** Mapping. */
        companion object {
            private const val TAG = "EstimateNewNights"

            /**
             * The outcome of a sync [report]: SUCCESS / PARTIAL_FAILURE → completed (failed nights are logged and retried
             * by later runs); QUOTA_EXCEEDED → retry; a transient FAILED → retry; another FAILED → unexpected;
             * PERMISSION_DENIED / HEALTH_CONNECT_UNAVAILABLE / BACKGROUND_READ_BLOCKED → blocked.
             */
            fun outcomeOf(report: SyncReport): WorkOutcome =
                when (report.outcome) {
                    SyncOutcome.SUCCESS, SyncOutcome.PARTIAL_FAILURE, SyncOutcome.CANCELLED -> {
                        WorkOutcome.Completed(RunSummary.of(report.pipeline))
                    }

                    SyncOutcome.QUOTA_EXCEEDED -> {
                        WorkOutcome.Retry(RetryCause.QUOTA, report.error)
                    }

                    SyncOutcome.PERMISSION_DENIED -> {
                        WorkOutcome.Blocked(BlockedReason.PERMISSION_MISSING, report.error)
                    }

                    SyncOutcome.HEALTH_CONNECT_UNAVAILABLE -> {
                        WorkOutcome.Blocked(BlockedReason.HEALTH_CONNECT_UNAVAILABLE, report.error)
                    }

                    SyncOutcome.BACKGROUND_READ_BLOCKED -> {
                        WorkOutcome.Blocked(BlockedReason.BACKGROUND_PERMISSION_MISSING, report.error)
                    }

                    SyncOutcome.FAILED -> {
                        if (report.retryable) {
                            WorkOutcome.Retry(RetryCause.TRANSIENT, report.error)
                        } else {
                            WorkOutcome.Unexpected(report.error ?: "sync failed")
                        }
                    }
                }
        }
    }

/**
 * The work of BackfillWorker, without WorkManager: [BackfillEngine.run] (resumes a RUNNING / WAITING_RETRY state from
 * its persisted cursor, stops between nights after [cancel], does not start when it was cancelled after it was
 * requested) → [SleepAsAndroidSyncRequests.afterRun] with the sessions this run wrote (also when it stopped early or
 * was cancelled) → outcome. Never throws except for cancellation.
 */
class BackfillRunner
    @Inject
    constructor(
        private val engine: BackfillEngine,
        private val sleepAsAndroid: SleepAsAndroidSyncRequests,
        private val logger: AppLogger,
    ) {
        /**
         * Runs or resumes the backfill the user requested at [requestedAtMs] (see [BackfillEngine.run]); [onProgress]
         * receives the persisted state after every window.
         */
        suspend fun run(
            inBackground: Boolean,
            requestedAtMs: Long? = null,
            onProgress: suspend (BackfillStateEntity) -> Unit = {},
        ): WorkOutcome =
            guardedRun(logger, TAG) {
                sleepAsAndroid.requestSyncAfter { outcomeOf(engine.run(inBackground, requestedAtMs, it, onProgress)) }
            }

        /** Asks a running backfill to stop before its next night (one that has not begun does not start) and marks it CANCELLED. */
        suspend fun cancel() {
            engine.cancel()
        }

        /** True while the backfill engine is processing in this process (a started worker may not have reached it yet). */
        val isRunning: Boolean get() = engine.isRunning

        /** Mapping. */
        companion object {
            private const val TAG = "Backfill"

            /**
             * The outcome of a backfill [report]: DONE / CANCELLED / ALREADY_RUNNING → completed; QUOTA_EXCEEDED /
             * RETRY_LATER → retry (the next attempt resumes from the cursor); BLOCKED → blocked (state WAITING_RETRY,
             * resumable by starting it again); FAILED → failed at once: the engine stored FAILED (invalid data or an
             * unexpected error), so a retry would start the whole history over only to stop at the same place.
             */
            fun outcomeOf(report: BackfillReport): WorkOutcome {
                val state = report.state
                val summary =
                    RunSummary(
                        found = state?.nightsFound ?: 0,
                        estimated = state?.nightsEstimated ?: 0,
                        skipped = state?.nightsSkipped ?: 0,
                        failed = state?.nightsFailed ?: 0,
                    )
                return when (report.outcome) {
                    BackfillOutcome.DONE -> WorkOutcome.Completed(summary)
                    BackfillOutcome.CANCELLED -> WorkOutcome.Completed(summary.copy(cancelled = true))
                    BackfillOutcome.ALREADY_RUNNING -> WorkOutcome.Completed(RunSummary(alreadyRunning = true))
                    BackfillOutcome.QUOTA_EXCEEDED -> WorkOutcome.Retry(RetryCause.QUOTA, report.error)
                    BackfillOutcome.RETRY_LATER -> WorkOutcome.Retry(RetryCause.TRANSIENT, report.error)
                    BackfillOutcome.BLOCKED -> WorkOutcome.Blocked(report.blockedReason ?: BlockedReason.PERMISSION_MISSING, report.error)
                    BackfillOutcome.FAILED -> WorkOutcome.Failed(report.error ?: "backfill failed")
                }
            }
        }
    }

/** What RecomputeWorker recomputes. */
sealed interface RecomputeRequest {
    /**
     * The nights with [clientRecordIds] ("Recompute" on the night screen), as requested at [requestedAtMs]: a night
     * the user deleted after that time is not written again, and "delete all my sessions" after it cancels the request.
     *
     * @property clientRecordIds the nights.
     * @property requestedAtMs when the user asked (null: unknown, the run's start counts).
     */
    data class Nights(
        val clientRecordIds: List<String>,
        val requestedAtMs: Long? = null,
    ) : RecomputeRequest

    /**
     * Every night of [scope] last changed before [requestedAtMs] ("Recompute all" / "N nights are stale"); the time
     * makes a retried run skip the nights an interrupted attempt already recomputed.
     *
     * @property scope which nights.
     * @property requestedAtMs when the user asked.
     */
    data class All(
        val scope: RecomputeScope,
        val requestedAtMs: Long,
    ) : RecomputeRequest
}

/**
 * The work of RecomputeWorker, without WorkManager: [NightRecomputer] → [SleepAsAndroidSyncRequests.afterRun] with the
 * sessions it rewrote (also when it stopped early, a Health Connect error ended it or it was cancelled) → outcome.
 * Never throws except for cancellation.
 */
class RecomputeRunner
    @Inject
    constructor(
        private val recomputer: NightRecomputer,
        repository: HealthRepository,
        private val sleepAsAndroid: SleepAsAndroidSyncRequests,
        private val logger: AppLogger,
    ) {
        private val guard = HealthAccessGuard(repository)

        /** Recomputes what [request] asks for; [onProgress] is told after every group of nights. */
        suspend fun run(
            request: RecomputeRequest,
            inBackground: Boolean,
            onProgress: suspend (RecomputeProgress) -> Unit = {},
        ): WorkOutcome =
            guardedRun(logger, TAG, classifyDenial = { guard.reasonForDenial() }) {
                sleepAsAndroid.requestSyncAfter { outcomeOf(recompute(request, inBackground, it, onProgress)) }
            }

        private suspend fun recompute(
            request: RecomputeRequest,
            inBackground: Boolean,
            writes: WriteCount,
            onProgress: suspend (RecomputeProgress) -> Unit,
        ): PipelineReport =
            when (request) {
                is RecomputeRequest.Nights -> {
                    recomputer.recompute(request.clientRecordIds, inBackground, onProgress, request.requestedAtMs, writes)
                }

                is RecomputeRequest.All -> {
                    recomputer.recomputeAll(request.scope, inBackground, request.requestedAtMs, writes, onProgress)
                }
            }

        private companion object {
            const val TAG = "Recompute"
        }
    }

/**
 * Runs [work] with a fresh [WriteCount] and asks Sleep as Android to sync ([SleepAsAndroidSyncRequests.afterRun]) for
 * the sessions it wrote, exactly once per run: after [work] returned (whether a request was sent goes into the summary
 * of a completed outcome), and also when [work] threw, whatever it threw (a Health Connect error, any other exception
 * or a cancellation): the sessions written before it are in Health Connect. The request is not cancellable in either
 * case, so a stop that arrives after [work] returned still sends it; after a throw it comes before the exception goes
 * on. A process death still loses it; the next request, whose sync reads the last 30 days, covers such sessions too.
 */
@Suppress("TooGenericExceptionCaught") // Every Throwable is rethrown: the catch only adds the request for the writes before it.
private suspend fun SleepAsAndroidSyncRequests.requestSyncAfter(work: suspend (WriteCount) -> WorkOutcome): WorkOutcome {
    val writes = WriteCount()
    val outcome =
        try {
            work(writes)
        } catch (e: Throwable) {
            withContext(NonCancellable) { afterRun(writes.written, writes.rewritten) }
            throw e
        }
    val requested = withContext(NonCancellable) { afterRun(writes.written, writes.rewritten) }
    return if (outcome is WorkOutcome.Completed) {
        outcome.copy(summary = outcome.summary.copy(sleepAsAndroidSyncRequested = requested))
    } else {
        outcome
    }
}

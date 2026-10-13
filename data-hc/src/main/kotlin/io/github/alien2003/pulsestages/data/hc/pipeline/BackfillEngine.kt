package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.local.db.BackfillStateDao
import io.github.alien2003.pulsestages.data.local.db.BackfillStateEntity
import io.github.alien2003.pulsestages.data.local.db.BackfillStatus
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** How a [BackfillEngine.run] ended. */
enum class BackfillOutcome {
    /** Every window was processed (or there was nothing to process). */
    DONE,

    /** The Health Connect quota was hit; the state is WAITING_RETRY and the next run resumes from the cursor. */
    QUOTA_EXCEEDED,

    /** Health Connect unavailable / permission missing / background read not allowed; resumable later. */
    BLOCKED,

    /**
     * Cancelled by the user ([BackfillEngine.cancel]), during the run or before it began (see `requestedAtMs`); a run
     * requested after the cancel starts over.
     */
    CANCELLED,

    /** A transient error stopped the run; WAITING_RETRY, resumable. */
    RETRY_LATER,

    /** An unexpected error stopped the run; FAILED (the next run starts over). */
    FAILED,

    /** Another backfill run is already in progress in this process. */
    ALREADY_RUNNING,
}

/**
 * Result of [BackfillEngine.run].
 *
 * @property outcome how the run ended.
 * @property state the persisted state afterwards (null for [BackfillOutcome.ALREADY_RUNNING], when no state existed
 *   yet, or when the state could not be stored).
 * @property blockedReason why the run was blocked, for [BackfillOutcome.BLOCKED].
 * @property error English detail of the error, if any.
 */
data class BackfillReport(
    val outcome: BackfillOutcome,
    val state: BackfillStateEntity?,
    val blockedReason: BlockedReason? = null,
    val error: String? = null,
)

/**
 * Processes the whole history in 7-day windows, oldest first (so that the rolling baselines exist for later nights),
 * with its state in `backfill_state`:
 * - range: `[now − backfillDays, now]` (backfillDays 0 = all history); the start is moved forward to the day of the
 *   oldest sleep session Health Connect returns. Without READ_HEALTH_DATA_HISTORY Health Connect only returns about
 *   30 days before the first grant; the state is then flagged `historyLimited` so the UI can say so;
 * - a window owns the nights whose first fragment starts in it (reads have one day of overlap so that merging works);
 * - the cursor and the counters are persisted after every window; a RUNNING / WAITING_RETRY state is resumed from
 *   the cursor (after process death, a quota error or a transient error); DONE / CANCELLED / FAILED / IDLE start over;
 * - [cancel] stops a running backfill between nights and marks it CANCELLED; coroutine cancellation (WorkManager
 *   stopping the worker) leaves it RUNNING so it resumes next time. A cancel is durable for the run it was meant for:
 *   a run with `requestedAtMs` (when the user started this backfill, the same for every retry of the work) stops
 *   without processing anything when a cancel was requested at or after that time — even when the cancel arrived
 *   before the run began (while the worker was still starting) or the process died in between (the CANCELLED state
 *   is persisted then); only a backfill requested after the cancel starts over;
 * - progress is reported after every window through `onProgress`; `nightsEstimated` counts the nights that have an
 *   up-to-date estimate afterwards (written now or already current), so a resumed window is not under-counted.
 */
@Singleton
class BackfillEngine
    @Inject
    constructor(
        private val repository: HealthRepository,
        private val settingsRepository: SettingsRepository,
        private val backfillDao: BackfillStateDao,
        private val windowReader: NightWindowReader,
        private val pipeline: NightPipeline,
        private val logger: AppLogger,
        private val clock: Clock,
    ) {
        private val guard = HealthAccessGuard(repository)
        private val runLock = Mutex()

        /** The cancel requests so far ([cancel]). */
        private val cancels = AtomicReference(CancelMark(count = 0, atMs = Long.MIN_VALUE))

        /** How many times [cancel] was called and when it was last called (epoch ms). */
        private data class CancelMark(
            val count: Int,
            val atMs: Long,
        )

        /** Whether a cancel request applies to the current run; see [run]. */
        private class CancelScope(
            private val requestedAtMs: Long?,
            private val atStart: CancelMark,
        ) {
            fun appliesTo(mark: CancelMark): Boolean =
                if (requestedAtMs == null) mark.count != atStart.count else mark.count > 0 && mark.atMs >= requestedAtMs
        }

        @Volatile
        private var scope = CancelScope(null, cancels.get())

        /** True while [run] is in progress in this process. */
        val isRunning: Boolean get() = runLock.isLocked

        /** The persisted state (null = never started). */
        val state: Flow<BackfillStateEntity?> get() = backfillDao.observe()

        /**
         * Runs (or resumes) the backfill. [inBackground] is true when the app is in the background at the start (Health
         * Connect's background rule applies). [requestedAtMs] is when the user asked for this backfill (the enqueue time
         * of the work, the same for its retries): a [cancel] at or after it applies to this run even when it came before
         * the run began, and a CANCELLED state stored at or after it ends the run at once ([BackfillOutcome.CANCELLED],
         * nothing processed, the state left as it is). Without it the run counts as requested now: only cancels made
         * during the run apply and a CANCELLED state starts over. [onProgress] receives the state after it is persisted
         * (start and every window). Never throws except for cancellation.
         */
        @Suppress("TooGenericExceptionCaught") // Every failure is persisted and reported, never thrown.
        suspend fun run(
            inBackground: Boolean,
            requestedAtMs: Long? = null,
            onProgress: suspend (BackfillStateEntity) -> Unit = {},
        ): BackfillReport {
            if (!runLock.tryLock()) return BackfillReport(BackfillOutcome.ALREADY_RUNNING, null)
            try {
                scope = CancelScope(requestedAtMs, cancels.get())
                return try {
                    cancelledBeforeStart(requestedAtMs) ?: execute(inBackground, onProgress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HealthDataException) {
                    stopWith(e)
                } catch (e: Exception) {
                    logger.e(TAG, "backfill failed unexpectedly", throwable = e)
                    val message = "${e.javaClass.simpleName}: ${e.message}"
                    BackfillReport(BackfillOutcome.FAILED, persist(BackfillStatus.FAILED, message), error = message)
                }
            } finally {
                runLock.unlock()
            }
        }

        /**
         * Requests cancellation: a running backfill stops before its next night, one that has not begun yet (or is
         * waiting for a retry) does not start; the state becomes CANCELLED.
         */
        suspend fun cancel() {
            val now = clock.millis()
            cancels.updateAndGet { CancelMark(it.count + 1, maxOf(now, it.atMs)) }
            val current = backfillDao.get() ?: return
            if (current.status == BackfillStatus.RUNNING || current.status == BackfillStatus.WAITING_RETRY) {
                backfillDao.save(current.copy(status = BackfillStatus.CANCELLED, updatedAt = now))
                logger.i(TAG, "backfill cancelled by the user")
            }
        }

        /** Forgets the state (back to "never started"); does not touch Health Connect or the nights. */
        suspend fun reset() {
            backfillDao.clear()
        }

        /** The report of a run that a cancel requested at or after [requestedAtMs] stops before it begins, or null. */
        private suspend fun cancelledBeforeStart(requestedAtMs: Long?): BackfillReport? {
            if (requestedAtMs == null) return null
            val stored = backfillDao.get()
            val cancelledInStore = stored?.status == BackfillStatus.CANCELLED && stored.updatedAt >= requestedAtMs
            val cancelled = cancelledInStore || scope.appliesTo(cancels.get())
            if (cancelled) logger.i(TAG, "backfill not started: cancelled after it was requested")
            return if (cancelled) BackfillReport(BackfillOutcome.CANCELLED, stored) else null
        }

        private suspend fun execute(
            inBackground: Boolean,
            onProgress: suspend (BackfillStateEntity) -> Unit,
        ): BackfillReport {
            val settings = settingsRepository.settings.first()
            val access = guard.check(inBackground)
            val blocked = access.blockedReason
            if (blocked == null) return processWindows(settings, access.granted, onProgress)
            logger.w(TAG, "backfill blocked: $blocked (${access.detail})")
            return BackfillReport(BackfillOutcome.BLOCKED, backfillDao.get(), blockedReason = blocked)
        }

        /** Resumes or starts the state and processes windows until done, interrupted or cancelled. */
        private suspend fun processWindows(
            settings: AppSettings,
            granted: Set<String>,
            onProgress: suspend (BackfillStateEntity) -> Unit,
        ): BackfillReport {
            var state = startOrResume(settings, granted)
            backfillDao.save(state)
            onProgress(state)
            var stop: BackfillReport? = null
            while (stop == null && state.cursorMs < state.rangeEndMs) {
                currentCoroutineContext().ensureActive()
                val window = processWindow(state, settings, granted)
                state = window.state
                stop = window.stop
                if (stop == null) {
                    backfillDao.save(state)
                    onProgress(state)
                }
            }
            return stop ?: finish(state, onProgress)
        }

        /** Result of one window: the state afterwards and, when the run ends here, its report. */
        private class WindowResult(
            val state: BackfillStateEntity,
            val stop: BackfillReport?,
        )

        /** Processes the window starting at the cursor of [state]. */
        private suspend fun processWindow(
            state: BackfillStateEntity,
            settings: AppSettings,
            granted: Set<String>,
        ): WindowResult {
            if (isCancelled()) return WindowResult(state, cancelled(state))
            val windowEnd = minOf(state.cursorMs + state.windowDays * DAY_MS, state.rangeEndMs)
            val nights = windowReader.nightsInWindow(state.cursorMs, windowEnd, settings)
            val report = pipeline.process(nights, settings, PipelineTrigger.BACKFILL, granted, shouldStop = ::cancelRequested)
            val interrupted = interruption(state, report)
            val after = if (interrupted == null) state.advancedBy(windowEnd, report, clock.millis()) else state
            val stop = interrupted ?: if (isCancelled()) cancelled(after) else null
            return WindowResult(after, stop)
        }

        private suspend fun startOrResume(
            settings: AppSettings,
            granted: Set<String>,
        ): BackfillStateEntity {
            val previous = backfillDao.get()
            return if (previous != null && (previous.status == BackfillStatus.RUNNING || previous.status == BackfillStatus.WAITING_RETRY)) {
                logger.i(TAG, "resuming backfill at ${previous.cursorMs} (${previous.windowsDone}/${previous.windowsTotal})")
                previous.copy(status = BackfillStatus.RUNNING, updatedAt = clock.millis(), lastError = null)
            } else {
                newState(settings, granted)
            }
        }

        /** The report of a window that stopped early, or null when the window was processed completely. */
        private suspend fun interruption(
            state: BackfillStateEntity,
            report: PipelineReport,
        ): BackfillReport? {
            val blocked = report.blockedReason
            return when {
                report.cancelled -> cancelled(state)
                report.quotaHit -> BackfillReport(BackfillOutcome.QUOTA_EXCEEDED, save(state, BackfillStatus.WAITING_RETRY, "quota"))
                blocked != null -> BackfillReport(BackfillOutcome.BLOCKED, save(state, BackfillStatus.WAITING_RETRY, blocked.name), blocked)
                else -> null
            }
        }

        private suspend fun finish(
            state: BackfillStateEntity,
            onProgress: suspend (BackfillStateEntity) -> Unit,
        ): BackfillReport {
            val done = save(state, BackfillStatus.DONE, null)
            onProgress(done)
            logger.i(TAG, "backfill done: ${done.nightsEstimated} estimated, ${done.nightsSkipped} skipped, ${done.nightsFailed} failed")
            return BackfillReport(BackfillOutcome.DONE, done)
        }

        /**
         * The state after the window ending at [windowEnd] was processed with [report]. Nights whose output is already
         * current count as estimated: after a resumed window (or a second backfill) "estimated" still means "has an
         * up-to-date estimate".
         */
        private fun BackfillStateEntity.advancedBy(
            windowEnd: Long,
            report: PipelineReport,
            now: Long,
        ): BackfillStateEntity =
            copy(
                cursorMs = windowEnd,
                windowsDone = windowsDone + 1,
                nightsFound = nightsFound + report.found,
                nightsEstimated = nightsEstimated + report.estimated + report.upToDate,
                nightsSkipped = nightsSkipped + report.skipped - report.upToDate,
                nightsFailed = nightsFailed + report.failed,
                updatedAt = now,
            )

        /** A fresh state for [settings]; the range starts at the day of the oldest visible session. */
        private suspend fun newState(
            settings: AppSettings,
            granted: Set<String>,
        ): BackfillStateEntity {
            val now = clock.millis()
            val backfillDays = settings.sources.backfillDays
            val requestedStart = if (backfillDays == 0) HISTORY_START_MS else now - backfillDays * DAY_MS
            val packages = windowReader.enabledPackages(settings)
            val oldest =
                if (packages.isEmpty()) {
                    null
                } else {
                    repository
                        .readSleepSessions(
                            requestedStart,
                            now,
                            packages,
                        ).minOfOrNull { it.startMs }
                }
            val rangeStart = oldest?.let { maxOf(requestedStart, it - Math.floorMod(it, DAY_MS)) } ?: now
            val windowMs = BackfillStateEntity.DEFAULT_WINDOW_DAYS * DAY_MS
            val windows = if (now > rangeStart) ((now - rangeStart + windowMs - 1) / windowMs).toInt() else 0
            val historyLimited = HealthPermissions.READ_HEALTH_DATA_HISTORY !in granted
            logger.i(TAG, "backfill of $windows window(s) from $rangeStart (history limited: $historyLimited)")
            return BackfillStateEntity(
                status = BackfillStatus.RUNNING,
                rangeStartMs = rangeStart,
                rangeEndMs = now,
                cursorMs = rangeStart,
                windowDays = BackfillStateEntity.DEFAULT_WINDOW_DAYS,
                windowsTotal = windows,
                historyLimited = historyLimited,
                startedAt = now,
                updatedAt = now,
            )
        }

        private fun cancelRequested(): Boolean = scope.appliesTo(cancels.get())

        private suspend fun isCancelled(): Boolean = cancelRequested() || backfillDao.get()?.status == BackfillStatus.CANCELLED

        private suspend fun cancelled(state: BackfillStateEntity): BackfillReport {
            logger.i(TAG, "backfill stopped: cancelled")
            return BackfillReport(BackfillOutcome.CANCELLED, save(state, BackfillStatus.CANCELLED, null))
        }

        private suspend fun stopWith(e: HealthDataException): BackfillReport {
            logger.w(TAG, "backfill stopped: ${e.message}", throwable = e)
            return when (e) {
                is HealthDataException.QuotaExceeded -> {
                    BackfillReport(BackfillOutcome.QUOTA_EXCEEDED, persist(BackfillStatus.WAITING_RETRY, e.message), error = e.message)
                }

                is HealthDataException.Transient -> {
                    BackfillReport(BackfillOutcome.RETRY_LATER, persist(BackfillStatus.WAITING_RETRY, e.message), error = e.message)
                }

                is HealthDataException.PermissionDenied -> {
                    BackfillReport(
                        BackfillOutcome.BLOCKED,
                        persist(BackfillStatus.WAITING_RETRY, e.message),
                        // E.g. the app left the foreground during a run without the background permission.
                        blockedReason = guard.reasonForDenial(),
                        error = e.message,
                    )
                }

                is HealthDataException.Unavailable -> {
                    BackfillReport(
                        BackfillOutcome.BLOCKED,
                        persist(BackfillStatus.WAITING_RETRY, e.message),
                        blockedReason = BlockedReason.HEALTH_CONNECT_UNAVAILABLE,
                        error = e.message,
                    )
                }

                is HealthDataException.InvalidData -> {
                    BackfillReport(BackfillOutcome.FAILED, persist(BackfillStatus.FAILED, e.message), error = e.message)
                }
            }
        }

        /** Sets [status] on the persisted state (if any) and returns it; a storage failure is logged, never thrown. */
        @Suppress("TooGenericExceptionCaught") // Called while reporting another failure: it must not throw itself.
        private suspend fun persist(
            status: BackfillStatus,
            error: String?,
        ): BackfillStateEntity? =
            try {
                backfillDao.get()?.let { save(it, status, error) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "could not store the backfill state $status", throwable = e)
                null
            }

        private suspend fun save(
            state: BackfillStateEntity,
            status: BackfillStatus,
            error: String?,
        ): BackfillStateEntity {
            // A cancel() that raced with this run wins over anything but DONE.
            val effective = if (status != BackfillStatus.DONE && isCancelledInStore()) BackfillStatus.CANCELLED else status
            val updated = state.copy(status = effective, updatedAt = clock.millis(), lastError = error)
            backfillDao.save(updated)
            return updated
        }

        private suspend fun isCancelledInStore(): Boolean = backfillDao.get()?.status == BackfillStatus.CANCELLED

        private companion object {
            const val TAG = "Backfill"
            const val DAY_MS = 24L * 60 * 60 * 1000

            /** "All history" starts at the epoch; the oldest visible session moves the start forward. */
            const val HISTORY_START_MS = 0L
        }
    }

package io.github.alien2003.pulsestages.work.saa

import io.github.alien2003.pulsestages.data.hc.pipeline.Settlement
import io.github.alien2003.pulsestages.data.hc.pipeline.SourceSettlement
import io.github.alien2003.pulsestages.data.hc.pipeline.SourceVersion
import io.github.alien2003.pulsestages.data.hc.pipeline.UnsettledReason
import io.github.alien2003.pulsestages.data.hc.pipeline.keyOf
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.data.local.sync.SleepAsAndroidRequestState
import io.github.alien2003.pulsestages.data.local.sync.SyncStateStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.absoluteValue

/** What [SleepAsAndroidSyncRequests.trySend] did about the sync request. */
enum class SleepAsAndroidRequest {
    /** Nothing is owed (any more: the option is off, Sleep as Android is missing, or the request was dropped). */
    NONE,

    /** The request was sent. */
    SENT,

    /** A request is owed and waits: for complete nights, or for the gap after the last request. */
    DEFERRED,
}

/**
 * Asks Sleep as Android to sync with Health Connect once the nights PulseStages wrote are complete
 * (`write.requestSleepAsAndroidSync`, on by default: nothing is sent while Sleep as Android is not installed, and the
 * request carries no data; see [SleepAsAndroid]). Sleep as Android imports a night it lacks once and never updates it,
 * while OHealth publishes a provisional night (cut at about 07:29 while the user still sleeps) and extends the same
 * session later, so a request right after a write would lock the provisional version in:
 * - a run that wrote or rewrote a session leaves a request owed ([recordRun]); a new or recomputed write also owes a
 *   follow-up [FOLLOW_UP_DELAY_MS] after it, because Sleep as Android attaches heart rate and SpO2 only to the records
 *   it had before a sync;
 * - an owed request (the follow-up too) is sent only when [SourceSettlement] has found the sessions in Sleep as
 *   Android's window settled at every check for [CONFIRM_MS], and a run that started after they first looked settled
 *   has completed its late-data pass (requested at that moment; it is waited for at most [CHECK_WAIT_MS]);
 * - the checks run after a completed run ([trySend]) and in a one-time work ([onWork], [SleepAsAndroidFollowUps]): runs
 *   schedule it with REPLACE, the work schedules itself with APPEND_OR_REPLACE and never cancels itself; a deferral
 *   stores when to check again, and nothing is read before then;
 * - without READ_HEALTH_DATA_IN_BACKGROUND the work cannot read Health Connect: it sends once a settled streak that a
 *   check in the foreground stored is [CONFIRM_MS] old, and otherwise leaves the check to the next run in the
 *   foreground;
 * - two requests are at least [FOLLOW_UP_DELAY_MS] apart (Sleep as Android skips a request that arrives while it
 *   syncs), and an owed request is dropped after [MAX_OWED_MS] without a write or a request.
 *
 * The state is stored in [SyncStateStore] before anything is sent or scheduled, so a work that runs again sends nothing
 * twice. Calls are serialized; never throws except for cancellation: a failure is logged and counts as not sent.
 */
@Singleton
class SleepAsAndroidSyncRequests
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val syncState: SyncStateStore,
        private val sender: SyncRequestSender,
        private val followUps: SleepAsAndroidFollowUps,
        private val clock: Clock,
        private val logger: AppLogger,
        private val settlement: SourceSettlement,
    ) {
        private val mutex = Mutex()

        /** What the last deferral logged at INFO (or WARN) waited for: a repeat of it is logged at DEBUG. */
        private var loggedHold: String? = null

        /** The error of the last check if it failed: an owed request dropped after it is a warning. */
        private var failedCheck: String? = null

        /**
         * What a check of an owed request concludes ([decide]).
         *
         * @property state the request state to store before anything is sent or scheduled.
         * @property action what to do once it is stored.
         * @property requestLateDataPassAtMs a late-data pass to request in the same edit (a new settled streak), or null.
         * @property changed the log line about a settled streak that a changed session ended, or null.
         * @property falsified the warning about a session that changed after a request was sent, or null.
         */
        data class Step(
            val state: SleepAsAndroidRequestState,
            val action: Action,
            val requestLateDataPassAtMs: Long? = null,
            val changed: String? = null,
            val falsified: String? = null,
        )

        /** What to do with an owed request after a check. */
        sealed interface Action {
            /**
             * Send it now ([Step.state] holds it as sent).
             *
             * @property fallback Health Connect could not be read (no background access): a settled streak stored by an
             *   earlier check counts.
             */
            data class Send(
                val fallback: Boolean,
            ) : Action

            /**
             * Check again at [atMs] ([Step.state] holds it as the due time).
             *
             * @property atMs when.
             * @property hold what it waits for: a change of it is logged at INFO, a repeat at DEBUG.
             * @property detail English detail for the log.
             * @property nightKey the ledger night it concerns, if any.
             */
            data class Defer(
                val atMs: Long,
                val hold: String,
                val detail: String,
                val nightKey: String? = null,
            ) : Action

            /**
             * Schedule nothing: Health Connect cannot be read and no settled streak is stored; the next run in the
             * foreground checks.
             *
             * @property detail English detail for the log.
             */
            data class Wait(
                val detail: String,
            ) : Action
        }

        /**
         * Called once by every run with the number of sessions it wrote to Health Connect ([written]: new or recomputed)
         * and rewrote there ([rewritten]: refreshed after late data), also when it stopped early, ended with an exception
         * or was cancelled (the caller makes this call non-cancellable). It reads nothing from Health Connect. With the
         * option on and something written, a request is owed (now after a new or recomputed write, with a follow-up;
         * after a rewrite only, at the time already owed or now) and a settled streak starts again (a write means that a
         * batch just arrived or a record changed); the work is scheduled for it in case [trySend] does not come. With the
         * option off or without Sleep as Android an owed request is forgotten.
         */
        suspend fun recordRun(
            written: Int,
            rewritten: Int,
        ) {
            if (written + rewritten == 0) return
            mutex.withLock {
                guarded("could not record the sync request to Sleep as Android") {
                    record(written, rewritten)
                    SleepAsAndroidRequest.NONE
                }
            }
        }

        /** Checks an owed request after a run (cancellable: the scheduled work covers a cancelled one) and sends it if due. */
        suspend fun trySend(): SleepAsAndroidRequest = attempt(fromWork = false)

        /** [trySend] for SleepAsAndroidSyncWorker: it schedules the work again without cancelling the running one. */
        suspend fun onWork(): SleepAsAndroidRequest = attempt(fromWork = true)

        private suspend fun attempt(fromWork: Boolean): SleepAsAndroidRequest =
            mutex.withLock { guarded("could not ask Sleep as Android to sync") { owed(fromWork) } }

        private suspend fun record(
            written: Int,
            rewritten: Int,
        ) {
            val settings = settingsRepository.settings.first()
            when {
                !settings.write.requestSleepAsAndroidSync -> drop("the option is off", cancel = true)
                !sender.isInstalled() -> drop("Sleep as Android is not installed", cancel = true)
                else -> owe(written, rewritten, settings.schedule)
            }
        }

        /** Stores the request a run that wrote owes and schedules the work for its due time (or a recheck). */
        private suspend fun owe(
            written: Int,
            rewritten: Int,
            schedule: ScheduleSettings,
        ) {
            val now = clock.millis()
            val state =
                syncState.state
                    .first()
                    .sleepAsAndroidRequest
                    .adjustedTo(now)
            val due = if (written > 0) now else state.dueAtMs ?: now
            val owed =
                state.copy(
                    dueAtMs = due,
                    followUp = written > 0 || state.followUp,
                    owedSinceMs = now,
                    settledKey = null,
                    settledSinceMs = null,
                )
            syncState.updateSleepAsAndroidRequest { owed }
            loggedHold = null
            logger.d(TAG, "a sync request is owed (written=$written rewritten=$rewritten)")
            followUps.schedule(if (due > now) due - now else RECHECK_MS, schedule)
        }

        /** Whether anything is owed, the option, the age, the due time and the gap after the last request; then [check]. */
        private suspend fun owed(fromWork: Boolean): SleepAsAndroidRequest {
            val sync = syncState.state.first()
            val now = clock.millis()
            val state = sync.sleepAsAndroidRequest.adjustedTo(now)
            val settings = settingsRepository.settings.first()
            val due = state.dueAtMs
            val gapLeftMs = state.lastSentAtMs?.let { it + FOLLOW_UP_DELAY_MS - now }
            return when {
                due == null -> {
                    SleepAsAndroidRequest.NONE
                }

                !settings.write.requestSleepAsAndroidSync -> {
                    drop("the option is off", cancel = !fromWork)
                }

                !sender.isInstalled() -> {
                    drop("Sleep as Android is not installed", cancel = !fromWork)
                }

                now - (state.owedSinceMs ?: now) > MAX_OWED_MS -> {
                    expire(cancel = !fromWork)
                }

                due > now -> {
                    if (state != sync.sleepAsAndroidRequest) syncState.updateSleepAsAndroidRequest { state }
                    // A work that runs early (one of 0.4.0, or after a clock change) waits for the due time.
                    if (fromWork) followUps.scheduleFromWork(due - now, settings.schedule)
                    SleepAsAndroidRequest.DEFERRED
                }

                gapLeftMs != null && gapLeftMs > 0 -> {
                    syncState.updateSleepAsAndroidRequest { state.copy(dueAtMs = now + gapLeftMs) }
                    val detail = "Sleep as Android was asked to sync ${minutes(FOLLOW_UP_DELAY_MS - gapLeftMs)} min ago"
                    defer(Action.Defer(now + gapLeftMs, HOLD_GAP, detail), now, fromWork, settings.schedule)
                }

                else -> {
                    check(state, settings, now, fromWork, sync.lateDataPassAtMs)
                }
            }
        }

        /** Checks the sessions, stores what [decide] concludes in one edit, logs it, then sends or schedules. */
        private suspend fun check(
            state: SleepAsAndroidRequestState,
            settings: AppSettings,
            now: Long,
            fromWork: Boolean,
            lateDataPassAtMs: Long?,
        ): SleepAsAndroidRequest {
            val verdict = settlement.check(settings, setOf(SleepAsAndroid.PACKAGE), SleepAsAndroid.SYNC_WINDOW_MS)
            failedCheck = (verdict as? Settlement.Failed)?.let { it.error.message.orEmpty() }
            val step = decide(state, verdict, now, lateDataPassAtMs)
            syncState.updateSleepAsAndroidRequest(step.requestLateDataPassAtMs) { step.state }
            step.falsified?.let { logger.w(TAG, it) }
            step.changed?.let { logger.i(TAG, it) }
            return when (val action = step.action) {
                is Action.Send -> send(step, sentLine(state, step, verdict, now), fromWork, settings.schedule)
                is Action.Defer -> defer(action, now, fromWork, settings.schedule)
                is Action.Wait -> logHold(HOLD_UNREADABLE, "not asked yet: ${action.detail}")
            }
        }

        /** Sends the request [step] stored, logs [line] and, as the last call, schedules the follow-up it owes. */
        private suspend fun send(
            step: Step,
            line: String,
            fromWork: Boolean,
            schedule: ScheduleSettings,
        ): SleepAsAndroidRequest {
            loggedHold = null
            val sent = broadcast()
            return if (sent == true) {
                logger.i(TAG, line)
                if (step.state.dueAtMs != null) {
                    // The request is out: a failure to schedule its follow-up is logged on its own and does not undo it.
                    guarded("asked Sleep as Android to sync, but could not schedule the follow-up") {
                        scheduleWork(FOLLOW_UP_DELAY_MS, fromWork, schedule)
                        SleepAsAndroidRequest.SENT
                    }
                }
                SleepAsAndroidRequest.SENT
            } else {
                // Not sent: nothing is owed any more (no follow-up either); the next write owes a request again.
                clear(cancel = !fromWork)
                if (sent == false) logger.d(TAG, "Sleep as Android is not installed: no sync request")
                SleepAsAndroidRequest.NONE
            }
        }

        /** [SyncRequestSender.send]; null when it threw (logged). */
        @Suppress("TooGenericExceptionCaught") // The platform may refuse the broadcast in any way; the request is then not sent.
        private fun broadcast(): Boolean? =
            try {
                sender.send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "could not ask Sleep as Android to sync", throwable = e)
                null
            }

        /** Logs the deferral [action] and schedules the work for it. */
        private fun defer(
            action: Action.Defer,
            now: Long,
            fromWork: Boolean,
            schedule: ScheduleSettings,
        ): SleepAsAndroidRequest {
            logHold(
                action.hold,
                "not asked yet: ${action.detail}; checking again in ${minutesUntil(action.atMs - now)} min",
                action.nightKey,
            )
            scheduleWork(action.atMs - now, fromWork, schedule)
            return SleepAsAndroidRequest.DEFERRED
        }

        /**
         * Logs [message] of a request that waits for [hold]: at INFO when it waits for something else than the last one
         * logged (a failed check: WARN), at DEBUG when it still waits for the same.
         */
        private fun logHold(
            hold: String,
            message: String,
            nightKey: String? = null,
        ): SleepAsAndroidRequest {
            val level =
                when {
                    hold == loggedHold -> LogLevel.DEBUG
                    hold == HOLD_FAILED -> LogLevel.WARN
                    else -> LogLevel.INFO
                }
            loggedHold = hold
            logger.log(level, TAG, message, nightKey)
            return SleepAsAndroidRequest.DEFERRED
        }

        /** The work in [delayMs] with the constraints of [schedule]: from the work itself without cancelling it. */
        private fun scheduleWork(
            delayMs: Long,
            fromWork: Boolean,
            schedule: ScheduleSettings,
        ) {
            if (fromWork) followUps.scheduleFromWork(delayMs, schedule) else followUps.schedule(delayMs, schedule)
        }

        /** Forgets the request because of [why]; cancels the work when [cancel] (never from the work itself). */
        private suspend fun drop(
            why: String,
            cancel: Boolean,
        ): SleepAsAndroidRequest {
            clear(cancel)
            logger.d(TAG, "no sync request to Sleep as Android: $why")
            return SleepAsAndroidRequest.NONE
        }

        /** Drops a request owed for longer than [MAX_OWED_MS]: a warning when the last check failed. */
        private suspend fun expire(cancel: Boolean): SleepAsAndroidRequest {
            clear(cancel)
            val message = "sync request to Sleep as Android dropped: owed for ${hours(MAX_OWED_MS)} h without the nights looking complete"
            val error = failedCheck
            if (error == null) logger.i(TAG, message) else logger.w(TAG, "$message; the last check failed: $error")
            return SleepAsAndroidRequest.NONE
        }

        /** Nothing owed and nothing remembered; cancels the work when [cancel]. */
        private suspend fun clear(cancel: Boolean) {
            syncState.updateSleepAsAndroidRequest { SleepAsAndroidRequestState() }
            loggedHold = null
            if (cancel) followUps.cancel()
        }

        @Suppress("TooGenericExceptionCaught") // A sync request is best effort: its failure must not fail the run that asked.
        private suspend fun guarded(
            what: String,
            block: suspend () -> SleepAsAndroidRequest,
        ): SleepAsAndroidRequest =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, what, throwable = e)
                SleepAsAndroidRequest.NONE
            }

        /** Timing and the decision. */
        companion object {
            /**
             * Delay of the follow-up request after one for a new or recomputed night, and the shortest gap between two
             * requests. Sleep as Android starts its Health Connect sync about 10 s after a request and imports the night in
             * it; heart rate and SpO2 attach only on the next sync, so the follow-up waits well past the first one (which
             * reads 30 days of heart rate), and a request that arrives while Sleep as Android syncs is skipped.
             */
            const val FOLLOW_UP_DELAY_MS: Long = 10L * 60 * 1000

            /** When an owed request is checked again while the nights are not settled: periodic runs come every 15–16 min. */
            const val RECHECK_MS: Long = 15L * 60 * 1000

            /**
             * How long the nights must have looked settled at every check before a request is sent: OHealth wrote a
             * session again 0–16 min after its final version (18 and 19 Oct 2026), and how spread out one of its uploads
             * can be has not been measured.
             */
            const val CONFIRM_MS: Long = 15L * 60 * 1000

            /**
             * The longest wait, after the nights first looked settled, for a run that started after that and completed its
             * late-data pass (which may still rewrite a night): the default check interval. Only a backfill, a recompute
             * or a check interval over an hour waits that long.
             */
            const val CHECK_WAIT_MS: Long = 60L * 60 * 1000

            /**
             * An owed request is dropped after this long without a write or a request: every hold other than a failing
             * check ends 12 h after the last change of a session, and a check interval of up to 24 h is allowed.
             */
            const val MAX_OWED_MS: Long = 24L * 60 * 60 * 1000

            private const val TAG = "SleepAsAndroid"

            /** The characters of a session uuid shown in the log: enough to tell the sessions of a few nights apart. */
            private const val UUID_SHOWN = 8

            /** The separator of the session keys in a settled key ([keyOf]). */
            private const val KEY_SEPARATOR = ";"

            /** One session key, `uuid:start:end:lastModified` ([SourceVersion.key]). */
            private val SESSION_KEY = Regex("(.+):(-?\\d+):(-?\\d+):-?\\d+")

            /** Hold: the nights look settled and the streak is not [CONFIRM_MS] old yet. */
            private const val HOLD_CONFIRMING = "confirming"

            /** Hold: the streak is confirmed and waits for a run's late-data pass that started after it began. */
            private const val HOLD_WAITING_FOR_CHECK = "waiting for a check"

            /** Hold: Health Connect cannot be read in the background and no settled streak is stored. */
            private const val HOLD_UNREADABLE = "unreadable"

            /** Hold: the check failed with a Health Connect error. */
            private const val HOLD_FAILED = "failed"

            /** Hold: the last request is less than [FOLLOW_UP_DELAY_MS] ago. */
            private const val HOLD_GAP = "gap"

            /** The detail of a check that Health Connect refused in the background. */
            private const val UNREADABLE =
                "Health Connect cannot be read in the background (no background access); checked when PulseStages next runs while open"

            /** Added to the line of a request sent without a new check. */
            private const val NO_CHECK = "without a new check: no background access"

            /**
             * What a check of the owed request in [state] at [nowMs] concludes from [verdict], given the start of the run
             * whose late-data pass last completed ([lateDataPassAtMs]). Pure: the caller stores [Step.state] (and the
             * requested late-data pass) in one edit, logs and then acts.
             * - Settled: the streak continues when its sessions are among the stored streak's (a session that leaves the
             *   window ends no streak), otherwise a new one starts now and requests a late-data pass. It is sent once the
             *   streak is [CONFIRM_MS] old and a pass that started at or after its start completed (or [CHECK_WAIT_MS]
             *   passed); otherwise it waits for that.
             * - Unsettled: the streak ends; checked again in [RECHECK_MS], or at the time the session's data must reach
             *   if that is later.
             * - Unreadable: sent without a new check once a stored streak is [CONFIRM_MS] old, deferred until then, and
             *   without a streak nothing is scheduled.
             * - Failed: the streak is kept; checked again in [RECHECK_MS].
             *
             * Whatever the verdict, a session sent with the last request that now has another start or end is warned about
             * once (Sleep as Android keeps the version it imported).
             */
            fun decide(
                state: SleepAsAndroidRequestState,
                verdict: Settlement,
                nowMs: Long,
                lateDataPassAtMs: Long?,
            ): Step {
                val falsified = falsification(state, verdict, nowMs)
                val checked = if (falsified == null) state else state.copy(sentKey = null)
                val step =
                    when (verdict) {
                        is Settlement.Settled -> {
                            settled(checked, verdict, nowMs, lateDataPassAtMs)
                        }

                        is Settlement.Unsettled -> {
                            val atMs = maxOf(nowMs + RECHECK_MS, verdict.thresholdMs ?: 0L)
                            val action = Action.Defer(atMs, verdict.reason.name, detailOf(verdict, nowMs), verdict.nightClientRecordId)
                            deferred(checked.copy(settledKey = null, settledSinceMs = null), action)
                        }

                        is Settlement.Unreadable -> {
                            unreadable(checked, nowMs)
                        }

                        is Settlement.Failed -> {
                            val detail = "could not check whether the nights are complete: ${verdict.error.message}"
                            deferred(checked, Action.Defer(nowMs + RECHECK_MS, HOLD_FAILED, detail))
                        }
                    }
                return step.copy(falsified = falsified)
            }

            private fun settled(
                state: SleepAsAndroidRequestState,
                verdict: Settlement.Settled,
                nowMs: Long,
                lateDataPassAtMs: Long?,
            ): Step {
                val streak = state.settledKey?.let(::keysOf)
                val streakSince = state.settledSinceMs
                val keys = verdict.versions.map { it.key }
                val continued = streakSince?.takeIf { streak != null && streak.containsAll(keys) }
                val sinceMs = continued ?: nowMs
                val ended = streak != null && streakSince != null && continued == null
                val changed = if (ended) changedLine(state, verdict, nowMs) else null
                val stored = state.copy(settledKey = verdict.keyOf(), settledSinceMs = sinceMs)
                val request = nowMs.takeIf { continued == null }
                val confirmedAtMs = sinceMs + CONFIRM_MS
                val checkedSince = (lateDataPassAtMs ?: Long.MIN_VALUE) >= sinceMs || nowMs - sinceMs >= CHECK_WAIT_MS
                return when {
                    nowMs >= confirmedAtMs && checkedSince -> {
                        Step(sending(stored, nowMs), Action.Send(fallback = false), request, changed)
                    }

                    nowMs < confirmedAtMs -> {
                        val action = Action.Defer(confirmedAtMs, HOLD_CONFIRMING, confirming(confirmedAtMs - nowMs))
                        deferred(stored, action, request, changed)
                    }

                    else -> {
                        val waitEndMs = sinceMs + CHECK_WAIT_MS
                        val action =
                            Action.Defer(
                                minOf(nowMs + RECHECK_MS, waitEndMs),
                                HOLD_WAITING_FOR_CHECK,
                                waitingForCheck(waitEndMs - nowMs),
                            )
                        deferred(stored, action, request, changed)
                    }
                }
            }

            private fun unreadable(
                state: SleepAsAndroidRequestState,
                nowMs: Long,
            ): Step {
                val confirmedAtMs = state.settledSinceMs?.plus(CONFIRM_MS)
                return when {
                    confirmedAtMs == null -> {
                        Step(state, Action.Wait(UNREADABLE))
                    }

                    nowMs >= confirmedAtMs -> {
                        Step(sending(state, nowMs), Action.Send(fallback = true))
                    }

                    else -> {
                        deferred(state, Action.Defer(confirmedAtMs, HOLD_CONFIRMING, confirming(confirmedAtMs - nowMs)))
                    }
                }
            }

            /** A step that stores [action]'s time as the due time. */
            private fun deferred(
                state: SleepAsAndroidRequestState,
                action: Action.Defer,
                request: Long? = null,
                changed: String? = null,
            ): Step = Step(state.copy(dueAtMs = action.atMs), action, request, changed)

            /** [state] once its request is sent at [nowMs]: the follow-up it owes is due [FOLLOW_UP_DELAY_MS] later. */
            private fun sending(
                state: SleepAsAndroidRequestState,
                nowMs: Long,
            ): SleepAsAndroidRequestState =
                state.copy(
                    dueAtMs = if (state.followUp) nowMs + FOLLOW_UP_DELAY_MS else null,
                    followUp = false,
                    owedSinceMs = nowMs,
                    lastSentAtMs = nowMs,
                    sentKey = state.settledKey,
                )

            /**
             * This state with the times a clock that was set back left in the future fixed: when the latest write or
             * request, the last request or the start of the streak is later than [nowMs], those are taken as [nowMs], the
             * streak is dropped and the due time is at most [RECHECK_MS] away. A due time far ahead alone is no sign of
             * that: a deferral waits for the time a session's data must reach, up to its heart-rate margin after its end.
             */
            private fun SleepAsAndroidRequestState.adjustedTo(nowMs: Long): SleepAsAndroidRequestState {
                val since = settledSinceMs
                val setBack = listOfNotNull(owedSinceMs, lastSentAtMs, since).any { it > nowMs }
                return if (!setBack) {
                    this
                } else {
                    copy(
                        dueAtMs = dueAtMs?.coerceAtMost(nowMs + RECHECK_MS),
                        owedSinceMs = owedSinceMs?.coerceAtMost(nowMs),
                        lastSentAtMs = lastSentAtMs?.coerceAtMost(nowMs),
                        settledKey = settledKey.takeIf { since == null || since <= nowMs },
                        settledSinceMs = since?.takeIf { it <= nowMs },
                    )
                }
            }

            /** The warning about a session of the last request ([state]'s sent key) that now has another start or end. */
            private fun falsification(
                state: SleepAsAndroidRequestState,
                verdict: Settlement,
                nowMs: Long,
            ): String? {
                val current = verdict.versions.associateBy { it.uuid }
                val (sent, now) =
                    state.sentKey
                        ?.let(::timesOf)
                        .orEmpty()
                        .firstNotNullOfOrNull { old ->
                            current[old.uuid]?.takeIf { it.startMs != old.startMs || it.endMs != old.endMs }?.let { old to it }
                        } ?: return null
                val endShiftMs = now.endMs - sent.endMs
                val startShiftMs = now.startMs - sent.startMs
                val moved =
                    if (endShiftMs != 0L) {
                        "ended ${duration(endShiftMs.absoluteValue)} ${if (endShiftMs > 0) "earlier" else "later"}"
                    } else {
                        "started ${duration(startShiftMs.absoluteValue)} ${if (startShiftMs > 0) "earlier" else "later"}"
                    }
                return "Sleep as Android was asked to sync ${minutes(nowMs - (state.lastSentAtMs ?: nowMs))} min ago; " +
                    "a session of ${now.packageName} then $moved than it does now: Sleep as Android may keep the earlier version"
            }

            /** The log line about the streak of [state] that [verdict]'s sessions ended: each new or changed session. */
            private fun changedLine(
                state: SleepAsAndroidRequestState,
                verdict: Settlement.Settled,
                nowMs: Long,
            ): String {
                val keys = keysOf(state.settledKey.orEmpty())
                val before = timesOf(state.settledKey.orEmpty()).associateBy { it.uuid }
                val changes =
                    verdict.versions
                        .filter { it.key !in keys }
                        .joinToString(", ") { version -> "${version.uuid.take(UUID_SHOWN)} ${shift(before[version.uuid], version)}" }
                val afterMs = nowMs - (state.settledSinceMs ?: nowMs)
                return "the nights changed while confirming (after ${minutes(afterMs)} min): $changes"
            }

            /** How [version] differs from its [earlier] times: "new", start and end deltas, or "written again". */
            private fun shift(
                earlier: SessionTimes?,
                version: SourceVersion,
            ): String =
                if (earlier == null) {
                    "new"
                } else {
                    listOfNotNull(
                        (version.startMs - earlier.startMs).takeIf { it != 0L }?.let { "start ${signed(it)}" },
                        (version.endMs - earlier.endMs).takeIf { it != 0L }?.let { "end ${signed(it)}" },
                    ).joinToString(" ").ifEmpty { "written again" }
                }

            /** The log detail of an [verdict] at [nowMs]. */
            private fun detailOf(
                verdict: Settlement.Unsettled,
                nowMs: Long,
            ): String {
                val version = verdict.version
                return when (verdict.reason) {
                    UnsettledReason.NOT_PROCESSED -> {
                        val ended = version?.let { " (ended ${duration(nowMs - it.endMs)} ago)" }.orEmpty()
                        "${version?.packageName ?: "a source"} changed a sleep session$ended that PulseStages has not processed yet"
                    }

                    // The session is gone: its package is not known here (the night is in the line's night key).
                    UnsettledReason.DELETION_PENDING -> {
                        "a sleep session was deleted; PulseStages has not processed that yet"
                    }

                    UnsettledReason.LEFTOVER_OUTPUT -> {
                        "an older PulseStages session of this night is still in Health Connect"
                    }

                    UnsettledReason.OUTPUT_BEHIND -> {
                        "the written night ends before its source session"
                    }

                    UnsettledReason.AWAITING_ESTIMATE -> {
                        "this night has no estimate yet"
                    }

                    UnsettledReason.WAITING_FOR_DATA -> {
                        version?.let { waitingForData(verdict, it) } ?: "waiting for more data"
                    }
                }
            }

            /** The detail of [UnsettledReason.WAITING_FOR_DATA] for [version]. */
            private fun waitingForData(
                verdict: Settlement.Unsettled,
                version: SourceVersion,
            ): String =
                if (verdict.ownHeartRate) {
                    val fromMs = (verdict.thresholdMs ?: version.endMs) - version.endMs
                    val latest = verdict.latestHeartRateMs?.let { relative(it - version.endMs, "it") } ?: "none"
                    val changed = relative(version.lastModifiedMs - version.endMs, "its end")
                    "no heart rate from ${version.packageName} from ${minutes(fromMs)} min after the session's end yet " +
                        "(latest: $latest; session last changed $changed)"
                } else {
                    "${version.packageName} wrote no heart rate in this session; " +
                        "waiting until it is unchanged for ${minutes(SourceSettlement.QUIET_MS)} min"
                }

            /** The log line of a request sent after the check of [before] that concluded [step] from [verdict]. */
            private fun sentLine(
                before: SleepAsAndroidRequestState,
                step: Step,
                verdict: Settlement,
                nowMs: Long,
            ): String {
                val fallback = (step.action as? Action.Send)?.fallback == true
                // Nothing was written since the last request: this is its follow-up.
                val followUp = before.lastSentAtMs != null && before.owedSinceMs == before.lastSentAtMs
                val noCheck = if (fallback) "; $NO_CHECK" else ""
                return if (followUp) {
                    "asked Sleep as Android to sync again: heart rate and SpO2 for the nights it imported$noCheck"
                } else {
                    val notes =
                        listOfNotNull(
                            "owed for ${minutes(nowMs - (before.owedSinceMs ?: nowMs))} min",
                            (verdict as? Settlement.Settled)?.let(::heartRateNote),
                            NO_CHECK.takeIf { fallback },
                            "follow-up in ${minutes(FOLLOW_UP_DELAY_MS)} min".takeIf { step.state.dueAtMs != null },
                        )
                    "asked Sleep as Android to sync (${notes.joinToString("; ")})"
                }
            }

            /** How far past the end of its latest session the heart rate of each package reached; null without any. */
            private fun heartRateNote(verdict: Settlement.Settled): String? =
                verdict.latestHeartRateMs.entries
                    .joinToString(", ") { (packageName, latestMs) ->
                        val endMs = verdict.versions.filter { it.packageName == packageName }.maxOfOrNull { it.endMs } ?: latestMs
                        "heart rate of $packageName up to ${minutes(latestMs - endMs)} min after the end"
                    }.ifEmpty { null }

            private fun confirming(leftMs: Long): String =
                "the nights look complete; asking in ${minutesUntil(leftMs)} min if nothing changes"

            private fun waitingForCheck(leftMs: Long): String =
                "the nights look complete; waiting for the next check of the recent nights (at most ${minutesUntil(leftMs)} min)"

            /** The session keys in [key] ([keyOf]). */
            private fun keysOf(key: String): Set<String> = key.split(KEY_SEPARATOR).filter { it.isNotEmpty() }.toSet()

            /** The sessions in [key] ([keyOf]); malformed ones are left out. */
            private fun timesOf(key: String): List<SessionTimes> =
                keysOf(key).mapNotNull { sessionKey ->
                    SESSION_KEY.matchEntire(sessionKey)?.destructured?.let { (uuid, startMs, endMs) ->
                        SessionTimes(uuid, startMs.toLong(), endMs.toLong())
                    }
                }

            private fun minutes(ms: Long): Long = TimeUnit.MILLISECONDS.toMinutes(ms)

            private fun hours(ms: Long): Long = TimeUnit.MILLISECONDS.toHours(ms)

            /** Whole minutes, rounded up: a wait of 30 s is "1 min". */
            private fun minutesUntil(ms: Long): Long = minutes(ms + TimeUnit.MINUTES.toMillis(1) - 1)

            /** "5 min", "2 h" or "2 h 5 min"; a negative duration is 0. */
            private fun duration(ms: Long): String {
                val total = minutes(ms.coerceAtLeast(0L))
                val hours = TimeUnit.MINUTES.toHours(total)
                val rest = total - TimeUnit.HOURS.toMinutes(hours)
                return when {
                    hours == 0L -> "$rest min"
                    rest == 0L -> "$hours h"
                    else -> "$hours h $rest min"
                }
            }

            /** "+5 min" or "-1 h 2 min". */
            private fun signed(ms: Long): String = "${if (ms < 0) "-" else "+"}${duration(ms.absoluteValue)}"

            /** "5 min after [what]" or "5 min before [what]". */
            private fun relative(
                ms: Long,
                what: String,
            ): String = if (ms >= 0) "${minutes(ms)} min after $what" else "${minutes(-ms)} min before $what"

            /**
             * The times of a session in a stored key.
             *
             * @property uuid the session's Health Connect record id.
             * @property startMs its start.
             * @property endMs its end.
             */
            private data class SessionTimes(
                val uuid: String,
                val startMs: Long,
                val endMs: Long,
            )
        }
    }

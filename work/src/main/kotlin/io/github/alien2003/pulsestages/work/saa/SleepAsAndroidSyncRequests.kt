package io.github.alien2003.pulsestages.work.saa

import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.data.local.sync.SyncStateStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Asks Sleep as Android to sync with Health Connect after a run that wrote to it (`write.requestSleepAsAndroidSync`, on
 * by default: nothing is sent while Sleep as Android is not installed, and the request carries no data; see
 * [SleepAsAndroid]). Sleep as Android imports a new night in that sync, but attaches heart rate and SpO2 only to the
 * records it had before the sync, so a run that wrote a new or recomputed night also schedules one follow-up request
 * [FOLLOW_UP_DELAY_MS] later:
 * - the follow-up is a one-time WorkManager work ([SleepAsAndroidFollowUps]) rather than a step of the next run: it
 *   then comes about 10 minutes after the import whatever check interval is set (up to 24 h), needs no Health Connect
 *   access and survives process death;
 * - its due time is kept in [SyncStateStore]: the unique work (REPLACE) keeps at most one pending, the stored time makes
 *   a re-run of the work send nothing twice, a work that runs before that time (one that REPLACE did not stop in time,
 *   or after a clock change) waits for it instead of sending, and the work sends nothing once the option is off
 *   (checked when it runs);
 * - a run that only rewrote nights (a refresh after late heart rate) gets no follow-up: Sleep as Android imported those
 *   nights earlier, so the request's own sync already attaches the new heart rate, and every further sync may add
 *   another set of 10-minute heart-rate values. A pending follow-up that is due by then is taken as sent by it.
 *
 * Calls are serialized; never throws except for cancellation. A failure before the request went out is logged and
 * counts as not sent; once it was sent, a failure to store or schedule its follow-up is logged on its own and the
 * request still counts as sent.
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
    ) {
        private val mutex = Mutex()

        /**
         * Called once by every run with the number of sessions it wrote to Health Connect ([written]: new or recomputed)
         * and rewrote there ([rewritten]: refreshed after late data), also when it stopped early, ended with an exception
         * or was cancelled. With the option on and something written it sends a request and, after a new or recomputed
         * write, schedules the follow-up; with the option off a pending follow-up is cancelled. True when a request was
         * sent.
         */
        suspend fun afterRun(
            written: Int,
            rewritten: Int,
        ): Boolean = mutex.withLock { guarded("could not ask Sleep as Android to sync") { requestAfterRun(written, rewritten) } }

        /**
         * The follow-up (SleepAsAndroidSyncWorker): sends the pending request once, if the option is still on. The
         * pending time is cleared first, so a work that runs again sends nothing. A work that runs before the pending
         * time sends nothing and schedules the follow-up again for the rest of the wait. True when a request was sent.
         */
        suspend fun sendFollowUp(): Boolean =
            mutex.withLock { guarded("could not send the follow-up sync request to Sleep as Android") { requestFollowUp() } }

        private suspend fun requestAfterRun(
            written: Int,
            rewritten: Int,
        ): Boolean {
            val settings = settingsRepository.settings.first()
            val pending = syncState.state.first().sleepAsAndroidFollowUpDueAtMs
            return when {
                !settings.write.requestSleepAsAndroidSync -> {
                    if (pending != null) {
                        dropFollowUp()
                        logger.d(TAG, "pending follow-up sync request cancelled: the option is off")
                    }
                    false
                }

                // Nothing was written: a pending follow-up is sent by its own work.
                written + rewritten == 0 -> {
                    false
                }

                !sender.send() -> {
                    logger.d(TAG, "Sleep as Android is not installed: no sync request")
                    if (pending != null) dropFollowUp()
                    false
                }

                else -> {
                    // The request is out: a failure of the follow-up's bookkeeping is logged on its own and does not undo it.
                    val what = "asked Sleep as Android to sync (written=$written rewritten=$rewritten), but could not update the follow-up"
                    guarded(what) {
                        afterRequest(written, rewritten, pending, settings.schedule)
                        true
                    }
                    true
                }
            }
        }

        /** Schedules or consumes the follow-up after a request was sent for a run that wrote [written] and [rewritten]. */
        private suspend fun afterRequest(
            written: Int,
            rewritten: Int,
            pending: Long?,
            schedule: ScheduleSettings,
        ) {
            val now = clock.millis()
            val note =
                when {
                    written > 0 -> {
                        syncState.setSleepAsAndroidFollowUpDue(now + FOLLOW_UP_DELAY_MS)
                        followUps.schedule(FOLLOW_UP_DELAY_MS, schedule)
                        "; follow-up in ${TimeUnit.MILLISECONDS.toMinutes(FOLLOW_UP_DELAY_MS)} min"
                    }

                    // The follow-up was due: this request comes after the import as well, so it takes its place.
                    pending != null && now >= pending -> {
                        dropFollowUp()
                        "; it replaces the follow-up that was due"
                    }

                    // A rewrite only: Sleep as Android has the night already; a pending follow-up (of a new night) stays.
                    else -> {
                        ""
                    }
                }
            logger.i(TAG, "asked Sleep as Android to sync (written=$written rewritten=$rewritten$note)")
        }

        private suspend fun requestFollowUp(): Boolean {
            val pending = syncState.state.first().sleepAsAndroidFollowUpDueAtMs
            val settings = settingsRepository.settings.first()
            val now = clock.millis()
            return when {
                pending == null -> {
                    logger.d(TAG, "no follow-up sync request is pending")
                    false
                }

                !settings.write.requestSleepAsAndroidSync -> {
                    syncState.setSleepAsAndroidFollowUpDue(null)
                    logger.d(TAG, "follow-up sync request dropped: the option is off")
                    false
                }

                // Not due yet: a work that REPLACE did not stop in time (the pending time is a newer write's), or the clock
                // changed. Sending now would use up the newer follow-up within Sleep as Android's first sync.
                now < pending -> {
                    waitForFollowUp(pending, now, settings.schedule)
                    false
                }

                else -> {
                    // At most one follow-up per request, even when the work runs again (a retry, process death).
                    syncState.setSleepAsAndroidFollowUpDue(null)
                    sendDueFollowUp()
                }
            }
        }

        /**
         * Schedules the follow-up again for the rest of the wait until [pending]. A pending time more than
         * [FOLLOW_UP_DELAY_MS] ahead of [now] can only come from a clock that was set back: it is moved to
         * [FOLLOW_UP_DELAY_MS] from now, so the follow-up is not held back by the size of the clock change.
         */
        private suspend fun waitForFollowUp(
            pending: Long,
            now: Long,
            schedule: ScheduleSettings,
        ) {
            val dueAt = minOf(pending, now + FOLLOW_UP_DELAY_MS)
            if (dueAt != pending) syncState.setSleepAsAndroidFollowUpDue(dueAt)
            val waitMs = dueAt - now
            followUps.schedule(waitMs, schedule)
            logger.d(TAG, "follow-up sync request not due yet: scheduled again in ${TimeUnit.MILLISECONDS.toSeconds(waitMs)} s")
        }

        private fun sendDueFollowUp(): Boolean =
            if (sender.send()) {
                logger.i(TAG, "asked Sleep as Android to sync again: heart rate and SpO2 for the night it imported")
                true
            } else {
                logger.d(TAG, "Sleep as Android is not installed: no follow-up sync request")
                false
            }

        /** Forgets the pending follow-up and cancels its work. */
        private suspend fun dropFollowUp() {
            syncState.setSleepAsAndroidFollowUpDue(null)
            followUps.cancel()
        }

        @Suppress("TooGenericExceptionCaught") // A sync request is best effort: its failure must not fail the run that asked.
        private suspend fun guarded(
            what: String,
            block: suspend () -> Boolean,
        ): Boolean =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, what, throwable = e)
                false
            }

        /** Timing. */
        companion object {
            /**
             * Delay of the follow-up request after a run that wrote a new or recomputed night. Sleep as Android starts its
             * Health Connect sync about 10 s after a request and imports the night in it; heart rate and SpO2 attach only
             * on the next sync, so the follow-up waits well past the first one (which reads 30 days of heart rate).
             */
            const val FOLLOW_UP_DELAY_MS: Long = 10L * 60 * 1000

            private const val TAG = "SleepAsAndroid"
        }
    }

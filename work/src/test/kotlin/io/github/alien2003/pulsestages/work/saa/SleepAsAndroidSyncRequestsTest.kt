package io.github.alien2003.pulsestages.work.saa

import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.cyclingBpm
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.data.local.sync.SleepAsAndroidRequestState
import io.github.alien2003.pulsestages.work.saa.RecordingFollowUps.Scheduled
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidSyncRequests.Companion.RECHECK_MS
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * When PulseStages asks Sleep as Android to sync (`write.requestSleepAsAndroidSync`, on by default): a run that wrote
 * leaves a request owed, and it is sent only once the nights have looked settled for 15 minutes and a run's late-data
 * pass followed (an hour at most), its follow-up 10 minutes later the same way; nothing when the option is off or Sleep
 * as Android is missing, and never an exception. The sessions are checked by the real [SourceSettlement] over the fake
 * Health Connect; with no session at all the nights are settled.
 */
class SleepAsAndroidSyncRequestsTest {
    @TempDir
    lateinit var dir: File

    /** The requests over a harness with recording doubles. */
    private class World(
        val h: PipelineHarness,
    ) {
        val sender = RecordingSyncRequestSender()
        val followUps = RecordingFollowUps()
        val requests = h.sleepAsAndroidRequests(sender, followUps)

        suspend fun request(): SleepAsAndroidRequestState =
            h.syncState.state
                .first()
                .sleepAsAndroidRequest

        fun messages(level: LogLevel = LogLevel.INFO): List<String> =
            h.logger.lines
                .filter { it.level == level }
                .map { it.message }

        /** A run's late-data pass that started at [atMs] completed. */
        suspend fun pass(atMs: Long) = h.syncState.markLateDataPass(atMs)

        /** A write at noon; the nights look settled from then on; the request goes out at 12:15, after a pass at 12:10. */
        suspend fun sendAtQuarterPast(): SleepAsAndroidRequest {
            requests.recordRun(written = 1, rewritten = 0)
            requests.trySend()
            h.clock.nowMs = NOON + 15 * MINUTE_MS
            pass(NOON + 10 * MINUTE_MS)
            return requests.onWork()
        }
    }

    private fun worldTest(block: suspend (World) -> Unit) = harnessTest(dir) { h -> block(World(h)) }

    @Test
    fun `with the option off a write owes nothing and Sleep as Android is not even looked up`() =
        worldTest { w ->
            w.h.requestSleepAsAndroidSync(on = false)

            w.requests.recordRun(written = 2, rewritten = 1)

            assertEquals(SleepAsAndroidRequestState(), w.request())
            assertEquals(SleepAsAndroidRequest.NONE, w.requests.trySend())
            assertEquals(0, w.sender.lookups, "not even looked up")
            assertEquals(0, w.sender.attempts)
            assertTrue(w.followUps.scheduled.isEmpty())
            assertTrue(
                w.h.repository.calls
                    .isEmpty(),
                "nothing is read",
            )
        }

    @Test
    fun `turning the option off forgets an owed request, and only a run cancels the work`() =
        worldTest { w ->
            w.requests.recordRun(written = 1, rewritten = 0)
            w.h.requestSleepAsAndroidSync(on = false)

            assertEquals(SleepAsAndroidRequest.NONE, w.requests.onWork())
            assertEquals(SleepAsAndroidRequestState(), w.request())
            assertEquals(0, w.followUps.cancelled, "the work never cancels itself")

            w.h.requestSleepAsAndroidSync(on = true)
            w.requests.recordRun(written = 1, rewritten = 0)
            w.h.requestSleepAsAndroidSync(on = false)
            assertEquals(SleepAsAndroidRequest.NONE, w.requests.trySend())
            assertEquals(1, w.followUps.cancelled)
            assertEquals(0, w.sender.attempts)
        }

    @Test
    fun `without Sleep as Android a write owes nothing after one lookup, and that is no warning`() =
        worldTest { w ->
            w.sender.installed = false

            w.requests.recordRun(written = 2, rewritten = 0)

            assertEquals(1, w.sender.lookups)
            assertEquals(0, w.sender.attempts)
            assertEquals(SleepAsAndroidRequestState(), w.request())
            assertTrue(w.followUps.scheduled.isEmpty())
            assertEquals(SleepAsAndroidRequest.NONE, w.requests.trySend())
            assertEquals(1, w.sender.lookups, "nothing owed, nothing looked up")

            w.sender.installed = true
            w.requests.recordRun(written = 1, rewritten = 0)
            w.sender.installed = false
            assertEquals(SleepAsAndroidRequest.NONE, w.requests.onWork())
            assertEquals(SleepAsAndroidRequestState(), w.request(), "removed while a request was owed")
            assertEquals(0, w.sender.attempts)
            assertTrue(
                w.h.logger
                    .atLeast(LogLevel.WARN)
                    .isEmpty(),
                "a missing app is not a warning",
            )
        }

    @Test
    fun `a write owes a request with its follow-up, a rewrite one without, and the work is scheduled in case no check comes`() =
        worldTest { w ->
            w.h.settings.set { it.copy(schedule = it.schedule.copy(runOnlyWhenNotLowBattery = false)) }

            w.requests.recordRun(written = 0, rewritten = 2)

            assertEquals(SleepAsAndroidRequestState(dueAtMs = NOON, followUp = false, owedSinceMs = NOON), w.request())
            assertEquals(listOf(Scheduled(RECHECK_MS, ScheduleSettings(runOnlyWhenNotLowBattery = false))), w.followUps.scheduled)

            w.h.clock.advanceBy(MINUTE_MS)
            w.requests.recordRun(written = 1, rewritten = 0)

            assertEquals(
                SleepAsAndroidRequestState(dueAtMs = NOON + MINUTE_MS, followUp = true, owedSinceMs = NOON + MINUTE_MS),
                w.request(),
            )
            assertEquals(2, w.followUps.scheduled.size)
            assertEquals(0, w.sender.attempts)
            assertTrue(
                w.h.repository.calls
                    .isEmpty(),
                "recording a run reads nothing",
            )
        }

    @Test
    fun `nights that look settled are confirmed for 15 minutes, with a late-data pass asked for, and nothing is read before then`() =
        worldTest { w ->
            w.requests.recordRun(written = 1, rewritten = 0)

            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.trySend())

            val state = w.request()
            assertEquals(NOON + 15 * MINUTE_MS, state.dueAtMs)
            assertEquals("", state.settledKey, "no session: an empty key")
            assertEquals(NOON, state.settledSinceMs)
            assertEquals(
                NOON,
                w.h.syncState.state
                    .first()
                    .lateDataPassRequestedAtMs,
            )
            assertEquals(Scheduled(15 * MINUTE_MS, ScheduleSettings()), w.followUps.scheduled.last())
            assertEquals(
                listOf("not asked yet: the nights look complete; asking in 15 min if nothing changes; checking again in 15 min"),
                w.messages(),
            )

            w.h.clock.advanceBy(10 * MINUTE_MS)
            w.h.repository.clearCalls()
            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.trySend())
            assertTrue(
                w.h.repository.calls
                    .isEmpty(),
                "a run before the due time reads nothing: ${w.h.repository.calls}",
            )
            assertEquals(0, w.sender.attempts)
        }

    @Test
    fun `a confirmed request waits for a run's late-data pass that started after the nights looked settled`() =
        worldTest { w ->
            w.requests.recordRun(written = 1, rewritten = 0)
            w.requests.trySend()
            w.pass(NOON - MINUTE_MS)
            w.h.clock.nowMs = NOON + 15 * MINUTE_MS

            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork())
            assertEquals(NOON + 30 * MINUTE_MS, w.request().dueAtMs)
            assertEquals(Scheduled(15 * MINUTE_MS, ScheduleSettings()), w.followUps.scheduledFromWork.last())
            assertEquals(
                "not asked yet: the nights look complete; waiting for the next check of the recent nights (at most 45 min); " +
                    "checking again in 15 min",
                w.messages().last(),
            )

            w.h.clock.nowMs = NOON + 30 * MINUTE_MS
            w.pass(NOON + 20 * MINUTE_MS)
            assertEquals(SleepAsAndroidRequest.SENT, w.requests.onWork())

            assertEquals(1, w.sender.sent)
            assertEquals("asked Sleep as Android to sync (owed for 30 min; follow-up in 10 min)", w.messages().last())
            assertEquals(
                SleepAsAndroidRequestState(
                    dueAtMs = NOON + 40 * MINUTE_MS,
                    owedSinceMs = NOON + 30 * MINUTE_MS,
                    settledKey = "",
                    settledSinceMs = NOON,
                    lastSentAtMs = NOON + 30 * MINUTE_MS,
                    sentKey = "",
                ),
                w.request(),
            )
            assertEquals(Scheduled(10 * MINUTE_MS, ScheduleSettings()), w.followUps.scheduledFromWork.last())
        }

    @Test
    fun `without a late-data pass the request goes out an hour after the nights first looked settled`() =
        worldTest { w ->
            w.requests.recordRun(written = 1, rewritten = 0)
            w.requests.trySend()

            for (minutes in listOf(15, 30, 45)) {
                w.h.clock.nowMs = NOON + minutes * MINUTE_MS
                assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork())
            }
            w.h.clock.nowMs = NOON + 60 * MINUTE_MS

            assertEquals(SleepAsAndroidRequest.SENT, w.requests.onWork())
            assertEquals(1, w.sender.sent)
        }

    @Test
    fun `a night that just ended waits until its source's heart rate can be there`() =
        worldTest { w ->
            val endMs = NOON - 10 * MINUTE_MS
            w.h.repository.addSleepSession(OHEALTH, endMs - 8 * HOUR_MS, endMs)
            w.h.repository.addHeartRate(OHEALTH, endMs - 9 * HOUR_MS, endMs, bpm = cyclingBpm(endMs - 8 * HOUR_MS))
            w.h.sync.run(inBackground = false)
            w.requests.recordRun(written = 1, rewritten = 0)

            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.trySend())

            assertEquals(NOON + 50 * MINUTE_MS, w.request().dueAtMs, "the end + 60 min")
            assertEquals(Scheduled(50 * MINUTE_MS, ScheduleSettings()), w.followUps.scheduled.last())
            assertEquals(
                "not asked yet: no heart rate from $OHEALTH from 60 min after the session's end yet " +
                    "(latest: 2 min before it; session last changed 10 min after its end); checking again in 50 min",
                w.messages().last(),
            )
            assertEquals(
                w.h.ledger
                    .allNights()
                    .single()
                    .clientRecordId,
                w.h.logger.lines
                    .last()
                    .nightKey,
            )

            w.h.clock.nowMs = NOON + 55 * MINUTE_MS
            w.h.repository.addHeartRate(OHEALTH, endMs, NOON + 54 * MINUTE_MS)
            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork())
            assertEquals(NOON + 55 * MINUTE_MS, w.request().settledSinceMs, "settled from now on")
        }

    @Test
    fun `the follow-up comes 10 minutes after the request, checked like it`() =
        worldTest { w ->
            assertEquals(SleepAsAndroidRequest.SENT, w.sendAtQuarterPast())
            w.h.clock.nowMs = NOON + 25 * MINUTE_MS

            assertEquals(SleepAsAndroidRequest.SENT, w.requests.onWork())

            assertEquals(2, w.sender.sent)
            assertEquals("asked Sleep as Android to sync again: heart rate and SpO2 for the nights it imported", w.messages().last())
            assertNull(w.request().dueAtMs)
            assertEquals(SleepAsAndroidRequest.NONE, w.requests.onWork(), "a work that runs again sends nothing")
            assertEquals(2, w.sender.sent)
            assertEquals(0, w.followUps.cancelled)
        }

    @Test
    fun `a follow-up is held while a new session has not been processed`() =
        worldTest { w ->
            w.sendAtQuarterPast()
            w.h.clock.nowMs = NOON + 20 * MINUTE_MS
            w.h.repository.addSleepSession(OHEALTH, NOON - 2 * HOUR_MS, NOON - MINUTE_MS)
            w.h.clock.nowMs = NOON + 25 * MINUTE_MS

            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork())

            assertEquals(1, w.sender.sent)
            assertEquals(
                "not asked yet: $OHEALTH changed a sleep session (ended 26 min ago) that PulseStages has not processed yet; " +
                    "checking again in 15 min",
                w.messages().last(),
            )
            assertNull(w.request().settledSinceMs)
        }

    @Test
    fun `a rewrite before the follow-up keeps its due time and sends nothing at once`() =
        worldTest { w ->
            w.sendAtQuarterPast()
            w.h.clock.nowMs = NOON + 20 * MINUTE_MS

            w.requests.recordRun(written = 0, rewritten = 1)

            val owed = w.request()
            assertEquals(NOON + 25 * MINUTE_MS, owed.dueAtMs, "too early: the new night has no heart rate in Sleep as Android yet")
            assertEquals(NOON + 20 * MINUTE_MS, owed.owedSinceMs)
            assertNull(owed.settledKey, "a write starts the streak again")
            assertEquals(Scheduled(5 * MINUTE_MS, ScheduleSettings()), w.followUps.scheduled.last())
            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.trySend())
            assertEquals(1, w.sender.sent)

            w.h.clock.nowMs = NOON + 25 * MINUTE_MS
            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork(), "a new streak")
            w.h.clock.nowMs = NOON + 40 * MINUTE_MS
            w.pass(NOON + 30 * MINUTE_MS)
            assertEquals(SleepAsAndroidRequest.SENT, w.requests.onWork())

            assertEquals(2, w.sender.sent)
            assertEquals("asked Sleep as Android to sync (owed for 20 min)", w.messages().last(), "it is the follow-up too")
            assertNull(w.request().dueAtMs)
        }

    @Test
    fun `two requests are at least 10 minutes apart`() =
        worldTest { w ->
            w.sendAtQuarterPast()
            w.h.clock.nowMs = NOON + 17 * MINUTE_MS
            w.requests.recordRun(written = 1, rewritten = 0)

            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.trySend())

            assertEquals(NOON + 25 * MINUTE_MS, w.request().dueAtMs)
            assertEquals(Scheduled(8 * MINUTE_MS, ScheduleSettings()), w.followUps.scheduled.last())
            assertEquals("not asked yet: Sleep as Android was asked to sync 2 min ago; checking again in 8 min", w.messages().last())
            assertEquals(1, w.sender.sent)
        }

    @Test
    fun `every write starts the settled streak again`() =
        worldTest { w ->
            w.requests.recordRun(written = 1, rewritten = 0)
            w.requests.trySend()
            w.h.clock.nowMs = NOON + 10 * MINUTE_MS

            w.requests.recordRun(written = 0, rewritten = 1)

            assertNull(w.request().settledSinceMs)
            assertEquals(NOON + 15 * MINUTE_MS, w.request().dueAtMs, "the time already owed")
            w.h.clock.nowMs = NOON + 15 * MINUTE_MS
            w.pass(NOON + 5 * MINUTE_MS)
            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork())
            assertEquals(NOON + 15 * MINUTE_MS, w.request().settledSinceMs)
            assertEquals(
                NOON + 15 * MINUTE_MS,
                w.h.syncState.state
                    .first()
                    .lateDataPassRequestedAtMs,
            )
            assertTrue(w.messages().none { it.startsWith("the nights changed") }, "the write ended the streak, not a change")
        }

    @Test
    fun `a request owed for 24 hours without a write or a request is dropped, as a warning after a failed check`() =
        worldTest { w ->
            w.requests.recordRun(written = 1, rewritten = 0)
            w.h.clock.nowMs = NOON + 24 * HOUR_MS + 1

            assertEquals(SleepAsAndroidRequest.NONE, w.requests.trySend())
            assertEquals(SleepAsAndroidRequestState(), w.request())
            assertEquals("sync request to Sleep as Android dropped: owed for 24 h without the nights looking complete", w.messages().last())
            assertEquals(1, w.followUps.cancelled)

            val start = w.h.clock.millis()
            w.h.repository.failOn(Operation.READ_SLEEP_SESSIONS, HealthDataException.Transient("binder died"), times = Int.MAX_VALUE)
            w.requests.recordRun(written = 1, rewritten = 0)
            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.trySend())
            assertEquals(
                "not asked yet: could not check whether the nights are complete: binder died; checking again in 15 min",
                w.messages(LogLevel.WARN).last(),
            )
            w.h.clock.nowMs = start + 23 * HOUR_MS
            w.requests.recordRun(written = 0, rewritten = 1)
            w.h.clock.nowMs = start + 46 * HOUR_MS
            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork(), "a rewrite restarted the age")
            w.h.clock.nowMs = start + 47 * HOUR_MS + 1

            assertEquals(SleepAsAndroidRequest.NONE, w.requests.onWork())
            assertEquals(
                "sync request to Sleep as Android dropped: owed for 24 h without the nights looking complete; " +
                    "the last check failed: binder died",
                w.messages(LogLevel.WARN).last(),
            )
            assertEquals(1, w.followUps.cancelled, "not from the work")
        }

    @Test
    fun `after the clock was set back the stored times count as now`() =
        worldTest { w ->
            w.sendAtQuarterPast()
            w.h.clock.nowMs = NOON - 2 * HOUR_MS

            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork())

            assertEquals(
                SleepAsAndroidRequestState(
                    dueAtMs = NOON - 105 * MINUTE_MS,
                    owedSinceMs = NOON - 2 * HOUR_MS,
                    lastSentAtMs = NOON - 2 * HOUR_MS,
                    sentKey = "",
                ),
                w.request(),
                "the follow-up waits at most 15 minutes, the streak starts again",
            )
            assertEquals(Scheduled(15 * MINUTE_MS, ScheduleSettings()), w.followUps.scheduledFromWork.last())
            assertEquals(1, w.sender.sent)
        }

    @Test
    fun `a sender that throws clears the request, is logged as a warning and schedules no follow-up`() =
        worldTest { w ->
            w.sender.failure = SecurityException("not allowed")

            assertEquals(SleepAsAndroidRequest.NONE, w.sendAtQuarterPast())

            assertEquals(SleepAsAndroidRequestState(), w.request())
            val warning =
                w.h.logger
                    .atLeast(LogLevel.WARN)
                    .single()
            assertEquals("could not ask Sleep as Android to sync", warning.message)
            assertTrue(warning.throwable is SecurityException)
            assertTrue(w.followUps.scheduledFromWork.isEmpty())
            assertEquals(0, w.followUps.cancelled, "the work never cancels itself")

            w.h.clock.nowMs = NOON
            w.sender.failure = IllegalStateException("gone")
            w.requests.recordRun(written = 1, rewritten = 0)
            w.requests.trySend()
            w.h.clock.nowMs = NOON + 15 * MINUTE_MS
            assertEquals(SleepAsAndroidRequest.NONE, w.requests.trySend())
            assertEquals(1, w.followUps.cancelled, "a run cancels the work")
        }

    @Test
    fun `the request is stored as sent before it is sent, so a work that dies in the send sends nothing twice`() =
        worldTest { w ->
            w.sender.failure = AssertionError("the process died")

            val death = runCatching { w.sendAtQuarterPast() }.exceptionOrNull()

            assertInstanceOf(AssertionError::class.java, death)
            val stored = w.request()
            assertEquals(NOON + 15 * MINUTE_MS, stored.lastSentAtMs)
            assertEquals(NOON + 25 * MINUTE_MS, stored.dueAtMs, "the follow-up")
            assertTrue(w.followUps.scheduledFromWork.isEmpty(), "scheduling comes last")

            w.sender.failure = null
            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork(), "the work runs again")
            assertEquals(1, w.sender.attempts)
        }

    @Test
    fun `the work schedules itself as its last call, never with REPLACE, and a failure to schedule it keeps what was stored`() =
        worldTest { w ->
            w.requests.recordRun(written = 1, rewritten = 0)
            w.followUps.calls.clear()

            w.requests.onWork()
            w.h.clock.nowMs = NOON + 15 * MINUTE_MS
            w.pass(NOON + 15 * MINUTE_MS)
            w.requests.onWork()
            w.h.clock.nowMs = NOON + 25 * MINUTE_MS
            w.requests.onWork()

            assertEquals(2, w.sender.sent)
            assertEquals(listOf("scheduleFromWork", "scheduleFromWork"), w.followUps.calls, "the check, then the follow-up")

            val failing =
                object : SleepAsAndroidFollowUps by w.followUps {
                    override fun scheduleFromWork(
                        delayMs: Long,
                        schedule: ScheduleSettings,
                    ): Unit = error("WorkManager is not initialized")
                }
            val requests = w.h.sleepAsAndroidRequests(w.sender, failing)
            w.h.clock.nowMs = NOON + 40 * MINUTE_MS
            requests.recordRun(written = 1, rewritten = 0)
            assertEquals(SleepAsAndroidRequest.NONE, requests.onWork())
            assertEquals(NOON + 55 * MINUTE_MS, w.request().dueAtMs, "stored before it was scheduled")

            w.h.clock.nowMs = NOON + 55 * MINUTE_MS
            w.pass(NOON + 55 * MINUTE_MS)
            assertEquals(SleepAsAndroidRequest.SENT, requests.onWork(), "sent, although its follow-up cannot be scheduled")
            assertEquals(
                "asked Sleep as Android to sync, but could not schedule the follow-up",
                w.messages(LogLevel.WARN).last(),
            )
        }
}

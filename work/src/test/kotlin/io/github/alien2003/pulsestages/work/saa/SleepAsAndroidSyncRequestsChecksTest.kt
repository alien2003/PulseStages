package io.github.alien2003.pulsestages.work.saa

import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.sync.SleepAsAndroidRequestState
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * How the checks of an owed request to Sleep as Android read Health Connect and what they log: without background
 * access, after a failed read, while the reason to wait stays the same, when a session changes while the nights are
 * confirmed and after a request was sent.
 */
class SleepAsAndroidSyncRequestsChecksTest {
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

        /** The lines of the requests at [level]. */
        fun messages(level: LogLevel): List<String> =
            h.logger.lines
                .filter { it.tag == "SleepAsAndroid" && it.level == level }
                .map { it.message }

        /** The levels of the deferral lines, in order. */
        fun deferrals(): List<LogLevel> =
            h.logger.lines
                .filter { it.message.startsWith("not asked yet") }
                .map { it.level }
    }

    private fun worldTest(block: suspend (World) -> Unit) = harnessTest(dir) { h -> block(World(h)) }

    /** An earlier night of OHealth, published at noon and written by a run: settled by the ledger alone. */
    private suspend fun World.earlierNight() =
        h.repository.addNight(OHEALTH, nightStart(1)).also {
            h.sync.run(inBackground = false)
        }

    @Test
    fun `without background access the work sends on a streak a check in the foreground stored`() =
        worldTest { w ->
            w.h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            w.requests.recordRun(written = 1, rewritten = 0)
            w.requests.trySend()
            w.h.repository.inBackground = true
            w.h.clock.nowMs = NOON + 15 * MINUTE_MS

            assertEquals(SleepAsAndroidRequest.SENT, w.requests.onWork())

            assertEquals(
                "asked Sleep as Android to sync (owed for 15 min; without a new check: no background access; follow-up in 10 min)",
                w.messages(LogLevel.INFO).last(),
            )
            w.h.clock.nowMs = NOON + 25 * MINUTE_MS
            assertEquals(SleepAsAndroidRequest.SENT, w.requests.onWork())
            assertEquals(
                "asked Sleep as Android to sync again: heart rate and SpO2 for the nights it imported; " +
                    "without a new check: no background access",
                w.messages(LogLevel.INFO).last(),
            )
            assertEquals(2, w.sender.sent)
        }

    @Test
    fun `without background access and without a stored streak nothing is scheduled until the app runs in the foreground`() =
        worldTest { w ->
            w.h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            w.requests.recordRun(written = 1, rewritten = 0)
            w.h.repository.inBackground = true
            w.followUps.calls.clear()

            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork())

            assertTrue(w.followUps.calls.isEmpty(), "no 15-minute loop: ${w.followUps.calls}")
            assertEquals(SleepAsAndroidRequestState(dueAtMs = NOON, followUp = true, owedSinceMs = NOON), w.request())
            assertEquals(
                "not asked yet: Health Connect cannot be read in the background (no background access); " +
                    "checked when PulseStages next runs while open",
                w.messages(LogLevel.INFO).last(),
            )
            assertEquals(0, w.sender.attempts)

            w.h.clock.nowMs = NOON + 2 * MINUTE_MS
            w.h.repository.inBackground = false
            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.trySend())
            assertEquals(NOON + 2 * MINUTE_MS, w.request().settledSinceMs, "read in the foreground")
        }

    @Test
    fun `a failed check keeps the settled streak and is a warning once`() =
        worldTest { w ->
            w.requests.recordRun(written = 1, rewritten = 0)
            w.requests.trySend()
            w.h.repository.failOn(Operation.READ_SLEEP_SESSIONS, HealthDataException.Transient("binder died"), times = 2)
            w.h.clock.nowMs = NOON + 15 * MINUTE_MS

            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork())
            w.h.clock.nowMs = NOON + 30 * MINUTE_MS
            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork())

            assertEquals(NOON, w.request().settledSinceMs)
            assertEquals("", w.request().settledKey)
            assertEquals(
                listOf("not asked yet: could not check whether the nights are complete: binder died; checking again in 15 min"),
                w.messages(LogLevel.WARN),
            )
            assertEquals(listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.DEBUG), w.deferrals())

            w.h.clock.nowMs = NOON + 45 * MINUTE_MS
            w.h.syncState.markLateDataPass(NOON + 40 * MINUTE_MS)
            assertEquals(SleepAsAndroidRequest.SENT, w.requests.onWork())
        }

    @Test
    fun `a deferral is logged at INFO when what it waits for changes and at DEBUG while it stays the same`() =
        worldTest { w ->
            val session = w.h.repository.addSleepSession(OHEALTH, NOON - 3 * 60 * MINUTE_MS, NOON - 5 * MINUTE_MS)
            w.requests.recordRun(written = 1, rewritten = 0)

            w.requests.trySend()
            w.h.clock.nowMs = NOON + 15 * MINUTE_MS
            w.requests.onWork()
            // Processed without heart rate: no estimate yet.
            w.h.sync.run(inBackground = false)
            w.h.clock.nowMs = NOON + 30 * MINUTE_MS
            w.requests.onWork()
            w.h.clock.nowMs = NOON + 45 * MINUTE_MS
            w.requests.onWork()

            assertEquals(listOf(LogLevel.INFO, LogLevel.DEBUG, LogLevel.INFO, LogLevel.DEBUG), w.deferrals())
            assertEquals(
                listOf(
                    "not asked yet: $OHEALTH changed a sleep session (ended 5 min ago) that PulseStages has not processed yet; " +
                        "checking again in 15 min",
                    "not asked yet: this night has no estimate yet; checking again in 15 min",
                ),
                w.messages(LogLevel.INFO).filter { it.startsWith("not asked yet") },
            )
            val night =
                w.h.ledger.nightDao
                    .findBySourceUuids(listOf(session.uuid))
                    .single()
            assertEquals(
                night.clientRecordId,
                w.h.logger.lines
                    .last()
                    .nightKey,
            )
        }

    @Test
    fun `a session written again while the nights are confirmed starts the streak again and is logged`() =
        worldTest { w ->
            val night = w.earlierNight()
            w.requests.recordRun(written = 1, rewritten = 0)
            w.requests.trySend()
            w.h.clock.nowMs = NOON + 5 * MINUTE_MS
            w.h.repository.updateSleepSession(night.uuid) { it }
            w.h.clock.nowMs = NOON + 15 * MINUTE_MS
            w.h.syncState.markLateDataPass(NOON + 10 * MINUTE_MS)

            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork())

            assertEquals(
                "the nights changed while confirming (after 15 min): ${night.uuid.take(8)} written again",
                w.messages(LogLevel.INFO).first { it.startsWith("the nights changed") },
            )
            assertEquals(NOON + 15 * MINUTE_MS, w.request().settledSinceMs)
            assertEquals(0, w.sender.attempts)
        }

    @Test
    fun `a session that ends later after Sleep as Android was asked is warned about once`() =
        worldTest { w ->
            val night = w.earlierNight()
            w.requests.recordRun(written = 1, rewritten = 0)
            w.requests.trySend()
            w.h.clock.nowMs = NOON + 15 * MINUTE_MS
            w.h.syncState.markLateDataPass(NOON + 10 * MINUTE_MS)
            assertEquals(SleepAsAndroidRequest.SENT, w.requests.onWork())
            w.h.clock.nowMs = NOON + 20 * MINUTE_MS
            w.h.repository.updateSleepSession(night.uuid) { it.copy(endMs = it.endMs + 34 * MINUTE_MS) }

            w.h.clock.nowMs = NOON + 25 * MINUTE_MS
            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork(), "the follow-up waits for the new version")
            w.h.clock.nowMs = NOON + 40 * MINUTE_MS
            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.onWork())

            assertEquals(
                listOf(
                    "Sleep as Android was asked to sync 10 min ago; a session of $OHEALTH then ended 34 min earlier than it does now: " +
                        "Sleep as Android may keep the earlier version",
                ),
                w.messages(LogLevel.WARN),
            )
            assertEquals(1, w.sender.sent)
        }
}

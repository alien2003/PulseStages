package io.github.alien2003.pulsestages.work.saa

import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * When PulseStages asks Sleep as Android to sync (`write.requestSleepAsAndroidSync`): one request after a run that
 * wrote, one follow-up 10 minutes after a new write, nothing when the option is off or Sleep as Android is missing,
 * and never an exception.
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

        suspend fun pending(): Long? =
            h.syncState.state
                .first()
                .sleepAsAndroidFollowUpDueAtMs

        fun info(): List<String> =
            h.logger.lines
                .filter { it.level == LogLevel.INFO }
                .map { it.message }
    }

    private fun worldTest(block: suspend (World) -> Unit) = harnessTest(dir) { h -> block(World(h)) }

    @Test
    fun `with the option off nothing is sent and nothing is scheduled`() =
        worldTest { w ->
            assertFalse(w.requests.afterRun(written = 2, rewritten = 1))

            assertEquals(0, w.sender.attempts, "not even looked up")
            assertTrue(w.followUps.scheduled.isEmpty())
            assertNull(w.pending())
            assertFalse(w.requests.sendFollowUp())
            assertEquals(0, w.sender.attempts)
        }

    @Test
    fun `turning the option off cancels a pending follow-up at the next run`() =
        worldTest { w ->
            w.h.requestSleepAsAndroidSync()
            assertTrue(w.requests.afterRun(written = 1, rewritten = 0))
            w.h.requestSleepAsAndroidSync(on = false)

            assertFalse(w.requests.afterRun(written = 0, rewritten = 0))

            assertNull(w.pending())
            assertEquals(1, w.followUps.cancelled)
            assertEquals(1, w.sender.sent, "only the first request")
        }

    @Test
    fun `a run that wrote nothing sends nothing and keeps a pending follow-up for its own work`() =
        worldTest { w ->
            w.h.requestSleepAsAndroidSync()

            assertFalse(w.requests.afterRun(written = 0, rewritten = 0))
            assertEquals(0, w.sender.attempts)
            assertTrue(w.followUps.scheduled.isEmpty())

            w.requests.afterRun(written = 1, rewritten = 0)
            val due = w.pending()
            w.h.clock.advanceBy(15 * MINUTE_MS)
            assertFalse(w.requests.afterRun(written = 0, rewritten = 0))

            assertEquals(due, w.pending())
            assertEquals(0, w.followUps.cancelled)
            assertEquals(1, w.sender.sent)
        }

    @Test
    fun `without Sleep as Android nothing is sent and no follow-up is scheduled`() =
        worldTest { w ->
            w.h.requestSleepAsAndroidSync()
            w.sender.installed = false

            assertFalse(w.requests.afterRun(written = 2, rewritten = 0))

            assertEquals(1, w.sender.attempts)
            assertEquals(0, w.sender.sent)
            assertTrue(w.followUps.scheduled.isEmpty())
            assertNull(w.pending())
            assertTrue(w.info().none { it.startsWith("asked Sleep as Android") })
            assertTrue(
                w.h.logger
                    .atLeast(LogLevel.WARN)
                    .isEmpty(),
                "a missing app is not a warning",
            )
        }

    @Test
    fun `Sleep as Android removed while a follow-up is pending drops the follow-up`() =
        worldTest { w ->
            w.h.requestSleepAsAndroidSync()
            w.requests.afterRun(written = 1, rewritten = 0)
            w.sender.installed = false

            assertFalse(w.requests.afterRun(written = 1, rewritten = 0))

            assertNull(w.pending())
            assertEquals(1, w.followUps.cancelled)
            assertEquals(1, w.followUps.scheduled.size)
        }

    @Test
    fun `a new write sends one request and schedules the follow-up 10 minutes later with the schedule's constraints`() =
        worldTest { w ->
            w.h.settings.set { it.copy(schedule = it.schedule.copy(runOnlyWhenNotLowBattery = false)) }
            w.h.requestSleepAsAndroidSync()

            assertTrue(w.requests.afterRun(written = 2, rewritten = 1))

            assertEquals(1, w.sender.sent)
            assertEquals(
                listOf(RecordingFollowUps.Scheduled(600_000L, ScheduleSettings(runOnlyWhenNotLowBattery = false))),
                w.followUps.scheduled,
            )
            assertEquals(10 * MINUTE_MS, SleepAsAndroidSyncRequests.FOLLOW_UP_DELAY_MS)
            assertEquals(NOON + 10 * MINUTE_MS, w.pending())
            assertEquals(listOf("asked Sleep as Android to sync (written=2 rewritten=1; follow-up in 10 min)"), w.info())
        }

    @Test
    fun `another write replaces the pending follow-up`() =
        worldTest { w ->
            w.h.requestSleepAsAndroidSync()
            w.requests.afterRun(written = 1, rewritten = 0)
            w.h.clock.advanceBy(5 * MINUTE_MS)

            assertTrue(w.requests.afterRun(written = 1, rewritten = 0))

            assertEquals(2, w.sender.sent)
            assertEquals(2, w.followUps.scheduled.size, "the work is enqueued again (REPLACE)")
            assertEquals(NOON + 15 * MINUTE_MS, w.pending())
        }

    @Test
    fun `the follow-up is sent once, a second run of its work sends nothing`() =
        worldTest { w ->
            w.h.requestSleepAsAndroidSync()
            w.requests.afterRun(written = 1, rewritten = 0)
            w.h.clock.advanceBy(10 * MINUTE_MS)

            assertTrue(w.requests.sendFollowUp())
            assertEquals(2, w.sender.sent)
            assertNull(w.pending())
            assertEquals(
                "asked Sleep as Android to sync again: heart rate and SpO2 for the night it imported",
                w.info().last(),
            )

            assertFalse(w.requests.sendFollowUp())
            assertEquals(2, w.sender.sent)
            assertEquals(0, w.followUps.cancelled, "the work never cancels itself")
        }

    @Test
    fun `a rewrite only sends one request without a follow-up`() =
        worldTest { w ->
            w.h.requestSleepAsAndroidSync()

            assertTrue(w.requests.afterRun(written = 0, rewritten = 2))

            assertEquals(1, w.sender.sent)
            assertTrue(w.followUps.scheduled.isEmpty())
            assertNull(w.pending())
            assertEquals(listOf("asked Sleep as Android to sync (written=0 rewritten=2)"), w.info())
        }

    @Test
    fun `a rewrite before the follow-up is due keeps it, one at or after its due time takes its place`() =
        worldTest { w ->
            w.h.requestSleepAsAndroidSync()
            w.requests.afterRun(written = 1, rewritten = 0)
            val due = checkNotNull(w.pending())

            w.h.clock.advanceBy(5 * MINUTE_MS)
            assertTrue(w.requests.afterRun(written = 0, rewritten = 1))
            assertEquals(due, w.pending(), "too early: the new night has no heart rate in Sleep as Android yet")
            assertEquals(0, w.followUps.cancelled)

            w.h.clock.nowMs = due
            assertTrue(w.requests.afterRun(written = 0, rewritten = 1))

            assertNull(w.pending())
            assertEquals(1, w.followUps.cancelled)
            assertEquals(3, w.sender.sent)
            assertEquals("asked Sleep as Android to sync (written=0 rewritten=1; it replaces the follow-up that was due)", w.info().last())
            assertFalse(w.requests.sendFollowUp(), "the follow-up was consumed")
            assertEquals(3, w.sender.sent)
        }

    @Test
    fun `the option turned off before the follow-up runs stops it`() =
        worldTest { w ->
            w.h.requestSleepAsAndroidSync()
            w.requests.afterRun(written = 1, rewritten = 0)
            w.h.requestSleepAsAndroidSync(on = false)
            w.h.clock.advanceBy(10 * MINUTE_MS)

            assertFalse(w.requests.sendFollowUp())

            assertEquals(1, w.sender.sent)
            assertNull(w.pending())
        }

    @Test
    fun `a follow-up after Sleep as Android was removed sends nothing`() =
        worldTest { w ->
            w.h.requestSleepAsAndroidSync()
            w.requests.afterRun(written = 1, rewritten = 0)
            w.sender.installed = false

            assertFalse(w.requests.sendFollowUp())

            assertEquals(1, w.sender.sent)
            assertNull(w.pending())
        }

    @Test
    fun `a sender that throws counts as not sent, is logged as a warning and schedules no follow-up`() =
        worldTest { w ->
            w.h.requestSleepAsAndroidSync()
            w.sender.failure = SecurityException("not allowed")

            assertFalse(w.requests.afterRun(written = 1, rewritten = 0))

            assertTrue(w.followUps.scheduled.isEmpty())
            assertNull(w.pending())
            val warning =
                w.h.logger
                    .atLeast(LogLevel.WARN)
                    .single()
            assertEquals(LogLevel.WARN, warning.level)
            assertEquals("could not ask Sleep as Android to sync", warning.message)
            assertTrue(warning.throwable is SecurityException)

            w.sender.failure = null
            w.requests.afterRun(written = 1, rewritten = 0)
            w.sender.failure = IllegalStateException("gone")
            assertFalse(w.requests.sendFollowUp())
            assertEquals(
                "could not send the follow-up sync request to Sleep as Android",
                w.h.logger
                    .atLeast(LogLevel.WARN)
                    .last()
                    .message,
            )
            assertNull(w.pending(), "not sent twice")
        }
}

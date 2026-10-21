package io.github.alien2003.pulsestages.work.saa

import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.fake.InMemoryLedger
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.cyclingBpm
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.sync.SleepAsAndroidRequestState
import io.github.alien2003.pulsestages.data.local.sync.SyncStateStore
import io.github.alien2003.pulsestages.work.run.EstimateNewNightsRunner
import io.github.alien2003.pulsestages.work.run.RecomputeRequest
import io.github.alien2003.pulsestages.work.run.RecomputeRunner
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * How the checks of an owed request to Sleep as Android read Health Connect and what they log: without background
 * access, after a failed read, while the reason to wait stays the same, when a session changes while the nights are
 * confirmed and after a request was sent; and how they notify a night of the last request that PulseStages changed.
 */
class SleepAsAndroidSyncRequestsChecksTest {
    @TempDir
    lateinit var dir: File

    /** The requests over a harness with recording doubles. */
    private class World(
        val h: PipelineHarness,
        val dir: File,
        sentNights: SentNights = SentNights(h.ledger.nightDao),
    ) {
        val sender = RecordingSyncRequestSender()
        val followUps = RecordingFollowUps()
        val notices = RecordingNotices()
        val requests = h.sleepAsAndroidRequests(sender, followUps, sentNights = sentNights, notices = notices)
        val runner = EstimateNewNightsRunner(h.sync, h.personalization, requests, h.logger)

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

    private fun worldTest(block: suspend (World) -> Unit) = harnessTest(dir) { h -> block(World(h, dir)) }

    /** An earlier night of OHealth, published at noon and written by a run: settled by the ledger alone. */
    private suspend fun World.earlierNight() =
        h.repository.addNight(OHEALTH, nightStart(1)).also {
            h.sync.run(inBackground = false)
        }

    /** [earlierNight], and the request for it sent at 12:15 (its follow-up is owed at 12:25). */
    private suspend fun World.sentNight(): HcSleepSession {
        val night = earlierNight()
        requests.recordRun(written = 1, rewritten = 0)
        requests.trySend()
        h.clock.nowMs = NOON + 15 * MINUTE_MS
        h.syncState.markLateDataPass(NOON + 10 * MINUTE_MS)
        assertEquals(SleepAsAndroidRequest.SENT, requests.onWork())
        return night
    }

    /** At [atMs] OHealth extends [night] by [extraMs]; then a run (which checks the request it owes). */
    private suspend fun World.extendAndRun(
        night: HcSleepSession,
        extraMs: Long = 34 * MINUTE_MS,
        atMs: Long = NOON + 30 * MINUTE_MS,
    ) {
        h.clock.nowMs = atMs
        h.repository.updateSleepSession(night.uuid) { it.copy(endMs = it.endMs + extraMs) }
        runner.run(inBackground = false)
    }

    /**
     * At [atMs] OHealth deletes the session [session] and, in the same upload, writes it again under another id with the
     * same start and [endMs], with its heart rate an hour past that end; then a run.
     */
    private suspend fun World.recreate(
        session: HcSleepSession,
        endMs: Long,
        atMs: Long,
    ): HcSleepSession {
        h.clock.nowMs = atMs
        h.repository.deleteSleepSession(session.uuid)
        val recreated = h.repository.addSleepSession(OHEALTH, session.startMs, endMs)
        h.repository.addHeartRate(OHEALTH, session.startMs, endMs + HOUR_MS, bpm = cyclingBpm(session.startMs))
        runner.run(inBackground = false)
        return recreated
    }

    /** OHealth's heart rate for [night] from its end to [toMs] (a wake that PulseStages sees as complete). */
    private fun World.heartRateAfter(
        night: HcSleepSession,
        toMs: Long,
    ) = h.repository.addHeartRate(OHEALTH, night.endMs, toMs, bpm = cyclingBpm(night.startMs))

    /** Moves the end of the WRITTEN ledger night of [uuid] by [extraMs], as a run that rewrote it would (no request owed). */
    private suspend fun World.rewriteInLedger(
        uuid: String,
        extraMs: Long,
    ) {
        h.ledger.nightDao.upsert(nightOf(uuid).let { it.copy(sessionEndMs = it.sessionEndMs + extraMs) })
    }

    /** The WRITTEN ledger night of the source session [uuid]. */
    private suspend fun World.nightOf(uuid: String): EstimatedNightEntity =
        h.ledger.nightDao
            .findBySourceUuids(listOf(uuid))
            .single { it.status == NightStatus.WRITTEN }

    /** The lines of the notices. */
    private fun World.noticeLines() =
        h.logger.lines.filter { it.tag == "SleepAsAndroid" && it.message.contains("PulseStages then changed this night") }

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
            assertEquals(emptyList<ChangedNight>(), w.notices.shown, "PulseStages has not rewritten the night yet")
        }

    @Test
    fun `a night PulseStages rewrites with a later end after the request is notified once`() =
        worldTest { w ->
            val night = w.sentNight()
            val sent = w.nightOf(night.uuid)

            w.extendAndRun(night)

            val change = w.notices.shown.single()
            assertEquals(sent.clientRecordId, change.night.clientRecordId)
            assertEquals(sent.sessionEndMs, change.sentEndMs)
            assertEquals(sent.sessionEndMs + 34 * MINUTE_MS, change.night.sessionEndMs)
            assertEquals(emptySet<String>(), change.replaced)
            assertFalse(change.neverOverwrite, "the repair is Recompute")
            val line = w.noticeLines().single()
            assertEquals(LogLevel.INFO, line.level)
            assertEquals(
                "Sleep as Android was asked to sync 15 min ago; PulseStages then changed this night (end +34 min): " +
                    "Sleep as Android may keep the earlier version (notification shown)",
                line.message,
            )
            assertEquals(sent.clientRecordId, line.nightKey, "in the night's Log")

            w.h.clock.nowMs = NOON + 45 * MINUTE_MS
            w.requests.onWork()
            w.h.clock.nowMs = NOON + 60 * MINUTE_MS
            w.requests.onWork()
            assertEquals(1, w.notices.shown.size, "once per version")
        }

    @Test
    fun `a request stores its nights and clears the notified versions`() =
        worldTest { w ->
            val night = w.sentNight()
            val sent = w.nightOf(night.uuid)
            assertEquals(SentNights.versionOf(sent), w.request().sentNightsKey)
            assertNull(w.request().noticedKey)

            // The version is stored before the notice is shown (read from the file, as DataStore wrote it).
            var storedBefore = false
            w.notices.onShow = { change ->
                storedBefore = File(w.dir, "${SyncStateStore.FILE_NAME}.preferences_pb").readText(Charsets.ISO_8859_1).contains(change.key)
            }
            w.extendAndRun(night)
            val changed = w.nightOf(night.uuid)
            assertTrue(storedBefore)
            assertEquals(SentNights.versionOf(changed), w.request().noticedKey)
            assertEquals(SentNights.versionOf(sent), w.request().sentNightsKey, "kept until the next request")

            w.h.clock.nowMs = NOON + 45 * MINUTE_MS
            w.h.syncState.markLateDataPass(NOON + 40 * MINUTE_MS)
            assertEquals(SleepAsAndroidRequest.SENT, w.requests.onWork())

            assertEquals(SentNights.versionOf(changed), w.request().sentNightsKey, "the request rebases the nights")
            assertNull(w.request().noticedKey)
            assertEquals(1, w.notices.shown.size)
        }

    @Test
    fun `a night merged with a fragment 25 min after its end is notified under the merged night, replacing the old tag`() =
        worldTest { w ->
            val night = w.sentNight()
            val sent = w.nightOf(night.uuid)
            w.h.clock.nowMs = NOON + 30 * MINUTE_MS

            val fragment = w.h.repository.addNight(OHEALTH, night.endMs + 25 * MINUTE_MS, durationMs = HOUR_MS)
            w.runner.run(inBackground = false)

            val merged = w.nightOf(fragment.uuid)
            assertEquals(listOf(night.uuid, fragment.uuid).sorted(), merged.sourceUuids, "merged")
            val change = w.notices.shown.single()
            assertEquals(merged.clientRecordId, change.night.clientRecordId)
            assertEquals(setOf(sent.clientRecordId), change.replaced)
            assertEquals(sent.sessionStartMs to sent.sessionEndMs, change.sentStartMs to change.sentEndMs)
            assertEquals(
                "Sleep as Android was asked to sync 15 min ago; PulseStages then changed this night " +
                    "(end +1 h 25 min, now with other source sessions): Sleep as Android may keep the earlier version (notification shown)",
                w.noticeLines().single().message,
            )
            assertEquals(merged.clientRecordId, w.noticeLines().single().nightKey)
        }

    @Test
    fun `under Never overwrite a night is neither rewritten in place nor merged, so neither is notified`() =
        worldTest { w ->
            val night = w.sentNight()
            val sent = w.nightOf(night.uuid)
            w.h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.NEVER)) }

            w.extendAndRun(night)
            w.h.repository.addNight(OHEALTH, night.endMs + 25 * MINUTE_MS, durationMs = HOUR_MS)
            w.runner.run(inBackground = false)
            w.requests.onWork()

            assertEquals(sent, w.nightOf(night.uuid), "the night is kept")
            assertTrue(w.messages(LogLevel.WARN).any { it.contains("may keep the earlier version") }, "the source is still logged")
            assertEquals(emptyList<ChangedNight>(), w.notices.shown)
        }

    @Test
    fun `under Never overwrite a session the source deleted and wrote again under another id is notified`() =
        worldTest { w ->
            val night = w.sentNight()
            val sent = w.nightOf(night.uuid)
            w.h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.NEVER)) }

            // The deletion retires the night whatever the policy, and the new night overlaps no WRITTEN night any more.
            val recreated = w.recreate(night, night.endMs + 34 * MINUTE_MS, atMs = NOON + 30 * MINUTE_MS)

            val change = w.notices.shown.single()
            assertEquals(w.nightOf(recreated.uuid).clientRecordId, change.night.clientRecordId)
            assertEquals(setOf(sent.clientRecordId), change.replaced)
            assertTrue(change.neverOverwrite, "Recompute rewrites nothing: the notice says to sync in Sleep as Android")
        }

    @Test
    fun `a recompute after the repair notifies nothing`() =
        worldTest { w ->
            val night = w.sentNight()
            w.extendAndRun(night)
            assertEquals(1, w.notices.shown.size)
            val changed = w.nightOf(night.uuid)

            // The user deleted the night in Sleep as Android and taps Recompute.
            w.h.clock.nowMs = NOON + 40 * MINUTE_MS
            RecomputeRunner(w.h.recomputer, w.h.health, w.requests, w.h.logger)
                .run(RecomputeRequest.Nights(listOf(changed.clientRecordId), requestedAtMs = NOON + 40 * MINUTE_MS), inBackground = false)

            assertTrue(w.nightOf(night.uuid).recomputeCount > changed.recomputeCount, "rewritten")
            assertEquals(1, w.notices.shown.size, "the same span")
        }

    @Test
    fun `a notifier that throws loses no request`() =
        worldTest { w ->
            val night = w.sentNight()
            w.notices.failure = SecurityException("revoked")
            w.h.clock.nowMs = NOON + 30 * MINUTE_MS
            w.h.repository.updateSleepSession(night.uuid) { it.copy(endMs = it.endMs + 34 * MINUTE_MS) }
            w.h.sync.run(inBackground = false)
            w.requests.recordRun(written = 1, rewritten = 0)
            w.followUps.calls.clear()

            assertEquals(SleepAsAndroidRequest.DEFERRED, w.requests.trySend())

            assertEquals(listOf("schedule"), w.followUps.calls, "the work is still scheduled")
            assertEquals(1, w.notices.shown.size)
            assertEquals(SentNights.versionOf(w.nightOf(night.uuid)), w.request().noticedKey, "notified once, not again")
            val line = w.noticeLines().single()
            assertEquals(LogLevel.WARN, line.level)
            assertTrue(line.message.endsWith("(could not show the notification: revoked)"), line.message)
        }

    @Test
    fun `notifications off are logged at WARN with the night key`() =
        worldTest { w ->
            val night = w.sentNight()
            w.notices.result = NoticeResult.NOT_ALLOWED

            w.extendAndRun(night)

            val line = w.noticeLines().single()
            assertEquals(LogLevel.WARN, line.level)
            assertTrue(line.message.endsWith("(no notification: notifications are off for PulseStages)"), line.message)
            assertEquals(w.nightOf(night.uuid).clientRecordId, line.nightKey)
            assertEquals(SentNights.versionOf(w.nightOf(night.uuid)), w.request().noticedKey, "a later grant brings no old notices")
        }

    @Test
    fun `a failed broadcast forgets the nights`() =
        worldTest { w ->
            val night = w.sentNight()
            w.extendAndRun(night)
            w.sender.failure = SecurityException("not allowed")

            w.h.clock.nowMs = NOON + 45 * MINUTE_MS
            w.h.syncState.markLateDataPass(NOON + 40 * MINUTE_MS)
            assertEquals(SleepAsAndroidRequest.NONE, w.requests.onWork())

            assertEquals(SleepAsAndroidRequestState(), w.request())
        }

    @Test
    fun `a session the source recreates under another id with a later end is notified under the new night`() =
        worldTest { w ->
            val night = w.sentNight()
            val sent = w.nightOf(night.uuid)
            w.h.clock.nowMs = NOON + 30 * MINUTE_MS

            // In one upload: the session is deleted and written again with an end 34 min later.
            w.h.repository.deleteSleepSession(night.uuid)
            val recreated = w.h.repository.addSleepSession(OHEALTH, night.startMs, night.endMs + 34 * MINUTE_MS)
            w.heartRateAfter(night, night.endMs + 64 * MINUTE_MS)
            w.runner.run(inBackground = false)

            val successor = w.nightOf(recreated.uuid)
            val change = w.notices.shown.single()
            assertEquals(successor.clientRecordId, change.night.clientRecordId)
            assertEquals(sent.sessionStartMs to sent.sessionEndMs, change.sentStartMs to change.sentEndMs)
            assertEquals(setOf(sent.clientRecordId), change.replaced)
            assertEquals(
                "Sleep as Android was asked to sync 15 min ago; PulseStages then changed this night " +
                    "(end +34 min, now with other source sessions): Sleep as Android may keep the earlier version (notification shown)",
                w.noticeLines().single().message,
            )

            w.h.clock.nowMs = NOON + 45 * MINUTE_MS
            w.requests.onWork()
            assertEquals(1, w.notices.shown.size, "once per version")
        }

    @Test
    fun `a merged night that loses the session of the request is followed to the night of the other session`() =
        worldTest { w ->
            val night = w.sentNight()
            val sent = w.nightOf(night.uuid)
            w.h.clock.nowMs = NOON + 30 * MINUTE_MS
            val longer = w.h.repository.addSleepSession(OHEALTH, night.startMs, night.endMs + 34 * MINUTE_MS)
            w.heartRateAfter(night, night.endMs + 64 * MINUTE_MS)
            w.runner.run(inBackground = false)
            val merged = w.nightOf(longer.uuid)
            assertEquals(listOf(night.uuid, longer.uuid).sorted(), merged.sourceUuids, "merged")
            val first = w.notices.shown.single()
            assertEquals(merged.clientRecordId, first.night.clientRecordId)

            w.h.clock.nowMs = NOON + 50 * MINUTE_MS
            w.h.repository.deleteSleepSession(night.uuid)
            w.runner.run(inBackground = false)

            val successor = w.nightOf(longer.uuid)
            assertNotEquals(merged.clientRecordId, successor.clientRecordId)
            val change = w.notices.shown.last()
            assertEquals(successor.clientRecordId, change.night.clientRecordId, "the notice opens a night that can be recomputed")
            assertEquals(setOf(sent.clientRecordId, merged.clientRecordId), change.replaced, "the notice of the merged night goes")
            assertEquals(sent.sessionEndMs, change.sentEndMs)

            // A later change of that night is still notified.
            w.extendAndRun(longer, extraMs = 10 * MINUTE_MS, atMs = NOON + 60 * MINUTE_MS)
            val later = w.notices.shown.last()
            assertEquals(successor.sessionEndMs + 10 * MINUTE_MS, later.night.sessionEndMs)
        }

    @Test
    fun `a check whose broadcast fails still notifies the nights it found changed`() =
        worldTest { w ->
            val night = w.sentNight()
            w.extendAndRun(night)
            assertEquals(1, w.notices.shown.size)
            w.rewriteInLedger(night.uuid, extraMs = 10 * MINUTE_MS)
            w.sender.failure = SecurityException("not allowed")

            w.h.clock.nowMs = NOON + 45 * MINUTE_MS
            w.h.syncState.markLateDataPass(NOON + 40 * MINUTE_MS)
            assertEquals(SleepAsAndroidRequest.NONE, w.requests.onWork())

            assertEquals(2, w.notices.shown.size)
            val (_, change) = w.notices.shown
            assertEquals(w.nightOf(night.uuid), change.night, "the version written after the first notice")
            assertEquals(SleepAsAndroidRequestState(), w.request(), "the nights are forgotten")
        }

    @Test
    fun `a check that sends notifies against the nights of the last request and stores those of the new one`() =
        worldTest { w ->
            val night = w.sentNight()
            val sent = w.nightOf(night.uuid)
            w.extendAndRun(night)
            w.rewriteInLedger(night.uuid, extraMs = 10 * MINUTE_MS)

            w.h.clock.nowMs = NOON + 45 * MINUTE_MS
            w.h.syncState.markLateDataPass(NOON + 40 * MINUTE_MS)
            assertEquals(SleepAsAndroidRequest.SENT, w.requests.onWork())

            val change = w.notices.shown.last()
            assertEquals(2, w.notices.shown.size)
            assertEquals(sent.sessionEndMs, change.sentEndMs, "against the last request")
            assertEquals(sent.sessionEndMs + 44 * MINUTE_MS, change.night.sessionEndMs)
            assertEquals(SentNights.versionOf(w.nightOf(night.uuid)), w.request().sentNightsKey, "the nights of the new request")
            assertNull(w.request().noticedKey)
        }

    @Test
    fun `a session the source recreates twice leaves only the newest notice, and the same times again do not alert`() =
        worldTest { w ->
            val night = w.sentNight()
            val first = w.recreate(night, night.endMs + 34 * MINUTE_MS, atMs = NOON + 30 * MINUTE_MS)
            val second = w.recreate(first, night.endMs + 50 * MINUTE_MS, atMs = NOON + 50 * MINUTE_MS)

            assertEquals(2, w.notices.shown.size)
            assertEquals(
                listOf(w.nightOf(second.uuid).clientRecordId),
                w.notices.shownTags,
                "the first notice goes",
            )
            assertTrue(w.notices.shown.none { it.silent }, "each with new times")

            val third = w.recreate(second, night.endMs + 50 * MINUTE_MS, atMs = NOON + 70 * MINUTE_MS)

            val again = w.notices.shown.last()
            assertEquals(w.nightOf(third.uuid).clientRecordId, again.night.clientRecordId)
            assertTrue(again.silent, "the times of the notice it replaces")
            assertEquals(
                listOf(again.night.clientRecordId),
                w.notices.shownTags,
            )
            assertTrue(
                w
                    .noticeLines()
                    .last()
                    .message
                    .endsWith("(notification shown without a new alert: an earlier one had these times)"),
            )
        }

    @Test
    fun `a merged night folded back into the night of the request with the same times does not alert again`() =
        worldTest { w ->
            val night = w.sentNight()
            val sent = w.nightOf(night.uuid)
            w.h.clock.nowMs = NOON + 30 * MINUTE_MS
            val fragment = w.h.repository.addNight(OHEALTH, night.endMs + 25 * MINUTE_MS, durationMs = HOUR_MS)
            w.runner.run(inBackground = false)
            assertEquals(1, w.notices.shown.size)

            // OHealth deletes the fragment and extends the session over it: the night of the request takes the times back.
            w.h.clock.nowMs = NOON + 50 * MINUTE_MS
            w.h.repository.deleteSleepSession(fragment.uuid)
            w.h.repository.updateSleepSession(night.uuid) { it.copy(endMs = fragment.endMs) }
            w.runner.run(inBackground = false)

            val change = w.notices.shown.last()
            assertEquals(2, w.notices.shown.size)
            assertEquals(sent.clientRecordId, change.night.clientRecordId)
            assertTrue(change.silent)
            assertEquals(
                listOf(sent.clientRecordId),
                w.notices.shownTags,
            )
        }

    @Test
    fun `a night back at the times of the request has its notice withdrawn, and a new change is notified again`() =
        worldTest { w ->
            val night = w.sentNight()
            val sent = w.nightOf(night.uuid)
            w.extendAndRun(night)
            assertEquals(1, w.notices.shown.size)

            w.h.clock.nowMs = NOON + 50 * MINUTE_MS
            w.h.repository.updateSleepSession(night.uuid) { it.copy(endMs = night.endMs) }
            w.runner.run(inBackground = false)

            assertEquals(listOf(sent.clientRecordId), w.notices.withdrawn)
            assertEquals(emptyMap<String, ChangedNight>(), w.notices.shade)
            assertNull(w.request().noticedKey)
            val line =
                w.h.logger.lines
                    .single { it.message.startsWith("this night is back") }
            assertEquals(LogLevel.INFO, line.level)
            assertEquals(sent.clientRecordId, line.nightKey)
            assertEquals(
                "this night is back at the times Sleep as Android was last asked to sync with (notification removed)",
                line.message,
            )

            w.extendAndRun(night, atMs = NOON + 70 * MINUTE_MS)

            assertEquals(2, w.notices.shown.size, "the same version as the withdrawn notice")
            assertEquals(
                listOf(sent.clientRecordId),
                w.notices.shownTags,
            )
        }

    @Test
    fun `a night rewritten back to times notified before has its notice show them again`() =
        worldTest { w ->
            val night = w.sentNight()
            val sent = w.nightOf(night.uuid)
            w.extendAndRun(night)
            w.extendAndRun(night, extraMs = 10 * MINUTE_MS, atMs = NOON + 50 * MINUTE_MS)
            assertEquals(2, w.notices.shown.size)

            w.h.clock.nowMs = NOON + 70 * MINUTE_MS
            w.h.repository.updateSleepSession(night.uuid) { it.copy(endMs = night.endMs + 34 * MINUTE_MS) }
            w.runner.run(inBackground = false)

            assertEquals(3, w.notices.shown.size)
            assertEquals(listOf(sent.clientRecordId), w.notices.shownTags)
            val shown = w.notices.shade.getValue(sent.clientRecordId)
            assertEquals(sent.sessionEndMs + 34 * MINUTE_MS, shown.night.sessionEndMs, "the notice shows B again")
        }

    @Test
    fun `a night notified before a merge is notified again when the merged night gives way to it`() =
        worldTest { w ->
            val night = w.sentNight()
            val sent = w.nightOf(night.uuid)
            w.extendAndRun(night)
            val extendedEnd = night.endMs + 34 * MINUTE_MS
            w.h.clock.nowMs = NOON + 50 * MINUTE_MS
            val fragment = w.h.repository.addNight(OHEALTH, extendedEnd + 25 * MINUTE_MS, durationMs = HOUR_MS)
            w.runner.run(inBackground = false)
            val merged = w.nightOf(fragment.uuid)
            assertEquals(listOf(night.uuid, fragment.uuid).sorted(), merged.sourceUuids, "merged")
            assertEquals(listOf(merged.clientRecordId), w.notices.shownTags, "the notice of the merged night replaced the first")

            // OHealth deletes the fragment: the merged night gives way to the night of the request, at its notified
            // times.
            w.h.clock.nowMs = NOON + 70 * MINUTE_MS
            w.h.repository.deleteSleepSession(fragment.uuid)
            w.runner.run(inBackground = false)

            assertEquals(3, w.notices.shown.size)
            val change = w.notices.shown.last()
            assertEquals(sent.clientRecordId, change.night.clientRecordId, "the notice opens a night that can be recomputed")
            assertEquals(sent.sessionEndMs + 34 * MINUTE_MS, change.night.sessionEndMs)
            assertEquals(listOf(sent.clientRecordId), w.notices.shownTags)
        }

    @Test
    fun `nights of the request merged since are notified once with every span sent`() =
        worldTest { w ->
            // Two nights, 45 min apart, sent together; then OHealth extends the earlier so that the gap falls under
            // "Merge gap", and PulseStages merges them.
            val earlier = w.h.repository.addNight(OHEALTH, nightStart(1))
            val later = w.h.repository.addNight(OHEALTH, earlier.endMs + 45 * MINUTE_MS, durationMs = 2 * HOUR_MS)
            w.h.sync.run(inBackground = false)
            w.requests.recordRun(written = 2, rewritten = 0)
            w.requests.trySend()
            w.h.clock.nowMs = NOON + 15 * MINUTE_MS
            w.h.syncState.markLateDataPass(NOON + 10 * MINUTE_MS)
            assertEquals(SleepAsAndroidRequest.SENT, w.requests.onWork())

            w.extendAndRun(earlier, extraMs = 20 * MINUTE_MS)

            val change = w.notices.shown.single()
            assertEquals(earlier.startMs to later.endMs, change.night.sessionStartMs to change.night.sessionEndMs)
            assertEquals(earlier.startMs to earlier.endMs, change.sentStartMs to change.sentEndMs)
            assertEquals(listOf(SentSpan(earlier.startMs, earlier.endMs), SentSpan(later.startMs, later.endMs)), change.sentSpans)
            assertEquals(earlier.startMs to later.endMs, change.repairStartMs to change.repairEndMs)
            assertTrue(
                w
                    .noticeLines()
                    .single()
                    .message
                    .contains("2 nights of the request merged"),
                w.noticeLines().single().message,
            )
        }

    @Test
    fun `a ledger that cannot be read loses only the notices`() =
        harnessTest(dir) { h ->
            val broken = InMemoryLedger().apply { readFailure = IllegalStateException("disk I/O error") }
            val w = World(h, dir, SentNights(broken.nightDao))

            val night = w.sentNight()

            assertEquals(1, w.sender.sent)
            assertNull(w.request().sentNightsKey)
            assertTrue(
                w.messages(LogLevel.WARN).contains("could not store the nights of this request"),
                w.messages(LogLevel.WARN).toString(),
            )

            // A request stored with its nights before the ledger failed.
            val sentNightsKey = SentNights.versionOf(w.nightOf(night.uuid))
            w.h.syncState.updateSleepAsAndroidRequest { it.copy(sentNightsKey = sentNightsKey) }
            w.extendAndRun(night)

            assertTrue(
                w.messages(LogLevel.WARN).contains("could not compare the nights of the last request with the ledger"),
                w.messages(LogLevel.WARN).toString(),
            )
            assertEquals(emptyList<ChangedNight>(), w.notices.shown)
            w.h.clock.nowMs = NOON + 45 * MINUTE_MS
            w.h.syncState.markLateDataPass(NOON + 40 * MINUTE_MS)
            assertEquals(SleepAsAndroidRequest.SENT, w.requests.onWork(), "the request goes on")
        }
}

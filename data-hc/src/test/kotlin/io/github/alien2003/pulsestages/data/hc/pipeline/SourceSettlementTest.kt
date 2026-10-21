package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.BackgroundRule
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MIDNIGHT
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/** Sleep as Android's package: it never imports its own sessions, so the request ignores them. */
private const val SLEEP_AS_ANDROID = "com.urbandroid.sleep"

/** An app that writes heart rate but no sleep. */
const val WATCH = "com.example.watch"

/** The heart-rate margin after a session's end: the default `mergeGapMin` (30 min) + [SourceSettlement.DATA_MARGIN_EXTRA_MS]. */
private const val MARGIN_MS = 60 * MINUTE_MS

/** [hours]:[minutes] on the day of [MIDNIGHT]. */
fun time(
    hours: Int,
    minutes: Int,
): Long = MIDNIGHT + hours * HOUR_MS + minutes * MINUTE_MS

/** The check as the Sleep as Android request runs it: Sleep as Android's 30-day window, its own sessions ignored. */
suspend fun PipelineHarness.settle(ignored: Set<String> = setOf(SLEEP_AS_ANDROID)): Settlement =
    settlement.check(settings.current, ignored, 30 * DAY_MS)

/** Enables exactly [packages] as sleep sources, in this priority order. */
suspend fun PipelineHarness.enableSources(vararg packages: String) {
    settings.set { it.copy(sources = it.sources.copy(sourcePackages = packages.toList(), sourcePriority = packages.toList())) }
}

/**
 * A walk of [packageName] as OHealth writes it: a one-minute step record of [count] steps every 5 minutes from [fromMs]
 * until before [untilMs] (it writes a record only for a minute with steps).
 */
fun PipelineHarness.walk(
    fromMs: Long,
    untilMs: Long,
    packageName: String = OHEALTH,
    count: Long = 40,
) {
    (fromMs until untilMs step 5 * MINUTE_MS).forEach { repository.addSteps(packageName, it, it + MINUTE_MS, count) }
}

/** OHealth's steps of the evening before the nights of these tests: it writes fine-grained steps. */
fun PipelineHarness.eveningWalk() = walk(time(-3, 0), time(-2, 0))

/** Runs a sync at [timeMs]. */
suspend fun PipelineHarness.syncAt(timeMs: Long): SyncReport {
    clock.nowMs = timeMs
    return sync.run(inBackground = false)
}

/**
 * OHealth publishes last night (23:00–07:00) at 07:05 with heart rate up to 07:28, more heart rate up to 08:08 follows,
 * and a sync at 08:15 writes the night: settled.
 */
suspend fun PipelineHarness.settledNight(): HcSleepSession {
    clock.nowMs = time(7, 5)
    val night = repository.addNight(OHEALTH, nightStart(0))
    repository.addHeartRate(OHEALTH, time(7, 30), time(8, 10))
    syncAt(time(8, 15))
    return night
}

/** Asserts that this is [Settlement.Unsettled] for [reason]. */
fun Settlement.unsettled(reason: UnsettledReason): Settlement.Unsettled {
    val unsettled = assertInstanceOf(Settlement.Unsettled::class.java, this)
    assertEquals(reason, unsettled.reason, "$unsettled")
    return unsettled
}

/** Asserts that this is [Settlement.Settled]. */
fun Settlement.settled(): Settlement.Settled = assertInstanceOf(Settlement.Settled::class.java, this, "$this")

/**
 * [SourceSettlement]: the source sessions that Sleep as Android imports are settled once PulseStages has processed every
 * one of them as it is now and the sources' heart rate shows that the recent ones are final (and their steps, for a
 * source that writes fine-grained steps: [SourceSettlementStepsTest]).
 */
class SourceSettlementTest {
    @TempDir
    lateinit var dir: File

    /**
     * 18 Oct 2026, after PulseStages' log on the development phone: at about 07:30 OHealth publishes the night cut at
     * 07:29 (in two fragments, as Sleep as Android's copy of it suggests); at about 09:37 an intermediate version ends at
     * 08:15, with heart rate to 09:35 already in Health Connect; at 12:16 the final version ends at the wake, 12:15; heart
     * rate from an hour after the wake has arrived by 13:19. With [walks], OHealth also writes one-minute steps: the
     * evening before, and from the wake on in the upload at 13:19; the rule for steps then holds nothing longer.
     */
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `18 Oct - provisional and intermediate versions wait, the final one settles with heart rate an hour after the wake`(
        walks: Boolean,
    ) = harnessTest(dir, now = time(7, 30)) { h ->
        if (walks) h.eveningWalk()
        val first = h.repository.addSleepSession(OHEALTH, time(4, 21), time(6, 8))
        val last = h.repository.addSleepSession(OHEALTH, time(6, 31), time(7, 29))
        h.repository.addHeartRate(OHEALTH, time(3, 51), time(7, 29))
        assertEquals(1, h.syncAt(time(7, 34)).pipeline.estimated)

        val provisional = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
        assertEquals(SourceVersion.of(first), provisional.version, "the earlier fragment needs heart rate 5 min after its publication")
        assertEquals(time(7, 35), provisional.thresholdMs)
        assertEquals(time(7, 27), provisional.latestHeartRateMs)
        h.syncAt(time(9, 1))
        assertEquals(provisional, h.settle())

        h.clock.nowMs = time(9, 36)
        h.repository.addHeartRate(OHEALTH, time(7, 29), time(9, 36))
        h.clock.nowMs = time(9, 37)
        val intermediate = h.repository.updateSleepSession(last.uuid) { it.copy(endMs = time(8, 15)) }
        assertEquals(1, h.syncAt(time(9, 39)).pipeline.estimated)
        val waiting = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
        assertEquals(SourceVersion.of(intermediate), waiting.version)
        assertEquals(time(9, 35), waiting.latestHeartRateMs, "already past the end + margin, 09:15 ...")
        assertEquals(intermediate.lastModifiedMs + 5 * MINUTE_MS, waiting.thresholdMs, "... but stamped before the change")
        h.syncAt(time(11, 13))
        assertEquals(waiting, h.settle())

        h.clock.nowMs = time(12, 16)
        h.repository.addHeartRate(OHEALTH, time(9, 37), time(12, 16))
        h.repository.updateSleepSession(last.uuid) { it.copy(endMs = time(12, 15)) }
        assertEquals(1, h.syncAt(time(12, 17)).pipeline.estimated)
        assertEquals(time(13, 15), h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA).thresholdMs)
        // OHealth writes the session again with unchanged times; nothing is written, the threshold stays.
        h.clock.nowMs = time(12, 33)
        val finalVersion = h.repository.updateSleepSession(last.uuid) { it }
        assertEquals(0, h.syncAt(time(12, 33)).pipeline.estimated)
        val rewritten = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
        assertEquals(SourceVersion.of(finalVersion), rewritten.version)
        assertEquals(time(13, 15), rewritten.thresholdMs)

        h.clock.nowMs = time(13, 19)
        h.repository.addHeartRate(OHEALTH, time(12, 17), time(13, 19))
        if (walks) h.walk(time(12, 20), time(13, 19))
        h.syncAt(time(13, 19))
        val settled = h.settle().settled()
        assertEquals(listOf(SourceVersion.of(first), SourceVersion.of(finalVersion)), settled.versions)
        assertEquals(mapOf(OHEALTH to time(13, 17)), settled.latestHeartRateMs)
    }

    /**
     * 19 Oct 2026, after PulseStages' log on the development phone: OHealth publishes the night cut at 07:29 at
     * about 07:30 and the final version, ending at the wake (08:03), at 08:05 with heart rate to 07:58; it writes the
     * session again with unchanged times at 08:20; its heart rate reaches 08:56 by 08:57 (the late-data pass rewrites the
     * night) and an hour after the wake by 09:12. With [walks], OHealth also writes one-minute steps: the evening before,
     * and from the wake on in the uploads at 08:57 and 09:12; the rule for steps then holds nothing longer.
     */
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `19 Oct - the night settles once heart rate an hour after the wake arrives, not at the final version`(walks: Boolean) =
        harnessTest(dir, now = time(7, 30)) { h ->
            if (walks) h.eveningWalk()
            val source = h.repository.addSleepSession(OHEALTH, time(2, 20), time(7, 29))
            h.repository.addHeartRate(OHEALTH, time(1, 50), time(7, 29))
            h.syncAt(time(7, 55))
            assertEquals(time(8, 29), h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA).thresholdMs)

            h.clock.nowMs = time(8, 5)
            h.repository.addHeartRate(OHEALTH, time(7, 30), time(8, 0))
            h.repository.updateSleepSession(source.uuid) { it.copy(endMs = time(8, 3)) }
            assertEquals(1, h.syncAt(time(8, 10)).pipeline.estimated)
            val finalVersion = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
            assertEquals(time(9, 3), finalVersion.thresholdMs)
            assertEquals(time(7, 58), finalVersion.latestHeartRateMs)

            h.clock.nowMs = time(8, 20)
            h.repository.updateSleepSession(source.uuid) { it }
            assertEquals(0, h.syncAt(time(8, 25)).pipeline.estimated)
            assertEquals(time(9, 3), h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA).thresholdMs)

            h.clock.nowMs = time(8, 57)
            h.repository.addHeartRate(OHEALTH, time(8, 0), time(8, 57))
            if (walks) h.walk(time(8, 10), time(8, 57))
            assertEquals(1, h.syncAt(time(8, 57)).pipeline.refreshed)
            assertEquals(1002L, h.singleRecord().clientRecordVersion)
            assertEquals(time(8, 56), h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA).latestHeartRateMs)

            h.clock.nowMs = time(9, 12)
            h.repository.addHeartRate(OHEALTH, time(8, 58), time(9, 11))
            if (walks) h.walk(time(9, 0), time(9, 12))
            assertEquals(mapOf(OHEALTH to time(9, 10)), h.settle().settled().latestHeartRateMs)
        }

    @Test
    fun `a back-to-sleep fragment is not processed until a run merges it, then the margin counts from its end`() =
        harnessTest(dir, now = time(7, 10)) { h ->
            h.repository.addSleepSession(OHEALTH, nightStart(0), time(7, 0))
            h.repository.addHeartRate(OHEALTH, nightStart(0) - 30 * MINUTE_MS, time(7, 10))
            h.syncAt(time(7, 10))

            h.clock.nowMs = time(8, 15)
            h.repository.addHeartRate(OHEALTH, time(7, 10), time(8, 15))
            val fragment = h.repository.addSleepSession(OHEALTH, time(7, 20), time(8, 10))
            assertEquals(SourceVersion.of(fragment), h.settle().unsettled(UnsettledReason.NOT_PROCESSED).version)

            h.syncAt(time(8, 16))
            val merged = h.ledger.allNights().single { it.status == NightStatus.WRITTEN }
            assertEquals(time(8, 10), merged.sessionEndMs)
            val waiting = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
            assertEquals(SourceVersion.of(fragment), waiting.version)
            assertEquals(merged.clientRecordId, waiting.nightClientRecordId)
            assertEquals(fragment.endMs + MARGIN_MS, waiting.thresholdMs)

            h.clock.nowMs = time(9, 12)
            h.repository.addHeartRate(OHEALTH, time(8, 15), time(9, 12))
            h.settle().settled()
        }

    @Test
    fun `a session changed in Health Connect is not processed until a run processes it`() =
        harnessTest(dir) { h ->
            val night = h.settledNight()
            h.settle().settled()

            val changed = h.repository.updateSleepSession(night.uuid) { it.copy(endMs = time(7, 30)) }
            val unprocessed = h.settle().unsettled(UnsettledReason.NOT_PROCESSED)
            assertEquals(SourceVersion.of(changed), unprocessed.version)
            assertEquals(NightKeys.clientRecordId(listOf(night.uuid)), unprocessed.nightClientRecordId)

            h.syncAt(time(8, 16))
            h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
        }

    @Test
    fun `a deleted source session holds the request until a run has removed our record of it`() =
        harnessTest(dir) { h ->
            val night = h.settledNight()
            h.repository.deleteSleepSession(night.uuid)

            val pending = h.settle().unsettled(UnsettledReason.DELETION_PENDING)
            assertNull(pending.version)
            assertEquals(NightKeys.clientRecordId(listOf(night.uuid)), pending.nightClientRecordId)
            assertTrue(h.repository.ownSessions().isNotEmpty())

            h.syncAt(time(8, 16))
            assertTrue(h.repository.ownSessions().isEmpty())
            assertEquals(Settlement.Settled(emptyList(), emptyMap()), h.settle())

            // The source writes it again under its clientRecordId, from which Health Connect derives the same id.
            val back = h.repository.addSleepSession(OHEALTH, night.startMs, night.endMs, uuid = night.uuid)
            assertEquals(SourceVersion.of(back), h.settle().unsettled(UnsettledReason.NOT_PROCESSED).version)
        }

    @Test
    fun `an older output left by a failed supersede holds the request until the next sync repairs it`() =
        harnessTest(dir, now = time(3, 5)) { h ->
            val early = h.repository.addSleepSession(OHEALTH, nightStart(0), time(3, 0))
            h.repository.addHeartRate(OHEALTH, nightStart(0) - 30 * MINUTE_MS, time(8, 10))
            h.syncAt(time(3, 5))
            // The late-data pass of this run is done, so the next run's does not supersede the older output either.
            h.syncAt(time(7, 0))
            h.clock.nowMs = time(7, 5)
            val late = h.repository.addSleepSession(OHEALTH, time(3, 20), time(7, 0))
            h.repository.failOn(Operation.DELETE, HealthDataException.Transient("fake: delete failed"))
            h.syncAt(time(7, 10))
            val merged = NightKeys.clientRecordId(listOf(early.uuid, late.uuid))
            assertEquals(2, h.ledger.allNights().count { it.status == NightStatus.WRITTEN })

            val leftover = h.settle().unsettled(UnsettledReason.LEFTOVER_OUTPUT)
            assertEquals(SourceVersion.of(early), leftover.version)
            assertEquals(merged, leftover.nightClientRecordId)

            h.syncAt(time(7, 11))
            assertEquals(listOf(merged), h.repository.ownSessions().map { it.clientRecordId })
            h.clock.nowMs = time(8, 15)
            h.settle().settled()
            // The early fragment is out of scope, its night is not: all its sessions are still in Health Connect.
            h.clock.nowMs = time(15, 5)
            assertEquals(listOf(SourceVersion.of(late)), h.settle().settled().versions)
        }

    @Test
    fun `a written night that could not be estimated again after its session grew holds the request`() =
        harnessTest(dir) { h ->
            val night = h.settledNight()
            h.estimator.declineWhen = { EstimationOutcome.NotEstimated(NotEstimatedReason.LOW_COVERAGE, 0.1, "declined by the test") }
            h.repository.updateSleepSession(night.uuid) { it.copy(endMs = time(7, 40)) }
            h.syncAt(time(8, 16))
            assertEquals(time(7, 0), h.singleNight().sessionEndMs, "the previous output is kept")

            val behind = h.settle().unsettled(UnsettledReason.OUTPUT_BEHIND)
            assertEquals(time(7, 40), behind.version?.endMs)
            assertEquals(NightKeys.clientRecordId(listOf(night.uuid)), behind.nightClientRecordId)
        }

    @Test
    fun `a recent night without an estimate holds the request until it is estimated`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            val night = h.repository.addNight(OHEALTH, nightStart(0), withHeartRate = false)
            h.syncAt(time(8, 15))
            assertEquals(NightStatus.NOT_ESTIMATED, h.singleNight().status)
            val awaiting = h.settle().unsettled(UnsettledReason.AWAITING_ESTIMATE)
            assertEquals(NightKeys.clientRecordId(listOf(night.uuid)), awaiting.nightClientRecordId)

            h.repository.addHeartRate(OHEALTH, nightStart(0) - 30 * MINUTE_MS, time(8, 10))
            h.estimator.failWhen = { IllegalStateException("fake: estimation failed") }
            h.clock.nowMs = time(8, 20)
            h.sync.run(inBackground = false, userRequested = true)
            assertEquals(NightStatus.FAILED, h.singleNight().status)
            h.settle().unsettled(UnsettledReason.AWAITING_ESTIMATE)

            h.estimator.failWhen = { null }
            h.clock.nowMs = time(8, 25)
            h.sync.run(inBackground = false, userRequested = true)
            assertEquals(NightStatus.WRITTEN, h.singleNight().status)
            h.settle().settled()
        }

    @Test
    fun `a nap skipped by the nap policy does not await an estimate`() =
        harnessTest(dir, now = time(16, 5)) { h ->
            h.settings.set { s ->
                s.copy(
                    sources = s.sources.copy(minSessionMin = 30),
                    algo = s.algo.copy(napMode = s.algo.napMode.copy(outputPolicy = NapOutputPolicy.SKIP)),
                )
            }
            h.repository.addSleepSession(OHEALTH, time(14, 0), time(16, 0))
            h.repository.addHeartRate(OHEALTH, time(13, 30), time(17, 10))
            h.syncAt(time(17, 15))
            assertTrue(h.singleNight().skippedNap)

            h.settle().settled()
        }

    @Test
    fun `under NEVER the sessions of a written night count as processed and wait for heart rate after their ends`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.NEVER)) }
            val night = h.repository.addNight(OHEALTH, nightStart(0))
            h.syncAt(time(7, 10))
            h.clock.nowMs = time(7, 45)
            h.repository.addHeartRate(OHEALTH, time(7, 30), time(7, 45))
            h.repository.updateSleepSession(night.uuid) { it.copy(endMs = time(7, 40)) }
            h.syncAt(time(7, 45))
            assertEquals(time(7, 0), h.singleRecord().endMs, "NEVER keeps the output")
            assertEquals(time(8, 40), h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA).thresholdMs)

            // A session overlapping the written night: NEVER writes no night over the output, and records no row.
            h.clock.nowMs = time(7, 50)
            val overlapping = h.repository.addSleepSession(OHEALTH, time(6, 30), time(7, 50))
            h.syncAt(time(7, 50))
            assertNull(h.ledger.allSources().find { it.uuid == overlapping.uuid })
            h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)

            h.clock.nowMs = time(8, 55)
            h.repository.addHeartRate(OHEALTH, time(7, 46), time(8, 55))
            assertEquals(
                listOf(night.uuid, overlapping.uuid),
                h
                    .settle()
                    .settled()
                    .versions
                    .map { it.uuid },
            )
        }

    @Test
    fun `a changed session of a night the user deleted counts as processed`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            val night = h.repository.addNight(OHEALTH, nightStart(0))
            h.syncAt(time(7, 10))
            assertEquals(DeleteNightResult.DELETED, h.eraser.deleteNight(h.singleNight().clientRecordId))
            h.clock.nowMs = time(7, 45)
            h.repository.addHeartRate(OHEALTH, time(7, 30), time(7, 45))
            h.repository.updateSleepSession(night.uuid) { it.copy(endMs = time(7, 40)) }
            h.syncAt(time(7, 45))
            assertEquals(NightStatus.DELETED, h.singleNight().status)

            assertEquals(time(8, 40), h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA).thresholdMs)
            h.clock.nowMs = time(8, 45)
            h.repository.addHeartRate(OHEALTH, time(7, 46), time(8, 45))
            h.settle().settled()
        }

    @Test
    fun `with hrSources ANY another app's heart rate does not settle a session`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.settings.set { it.copy(sources = it.sources.copy(hrSources = HrSourcesMode.ANY)) }
            h.repository.addNight(OHEALTH, nightStart(0), withHeartRate = false)
            h.repository.addHeartRate(OHEALTH, nightStart(0) - 30 * MINUTE_MS, time(7, 0))
            h.repository.addHeartRate(WATCH, nightStart(0) - 30 * MINUTE_MS, time(8, 15))
            h.syncAt(time(8, 15))
            assertEquals(NightStatus.WRITTEN, h.singleNight().status)

            val waiting = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
            assertEquals(time(6, 58), waiting.latestHeartRateMs)
            assertEquals(time(8, 0), waiting.thresholdMs)
        }

    @Test
    fun `a session of a package without heart rate in it settles once it is unchanged for an hour past its end`() =
        harnessTest(dir, now = time(7, 40)) { h ->
            h.enableSources(OTHER_APP)
            h.repository.addSleepSession(OTHER_APP, nightStart(0), time(7, 0), stages = listOf(HcStage(nightStart(0), time(7, 0), 4)))
            h.syncAt(time(7, 40))
            assertTrue(h.repository.ownSessions().isEmpty(), "a session with its own stages is not estimated")

            h.clock.nowMs = time(8, 39)
            val quiet = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
            assertFalse(quiet.ownHeartRate)
            assertEquals(time(8, 40), quiet.thresholdMs, "unchanged for an hour since 07:40, later than an hour past the end")
            assertNull(quiet.latestHeartRateMs)

            h.clock.nowMs = time(8, 40)
            assertEquals(emptyMap<String, Long>(), h.settle().settled().latestHeartRateMs)
        }

    @Test
    fun `heart rate of the package only after the session, none in it, does not settle it either`() =
        harnessTest(dir, now = time(7, 40)) { h ->
            h.enableSources(OTHER_APP)
            // The band was put on after the wake: heart rate from 07:00 on, none in the session.
            h.repository.addSleepSession(OTHER_APP, nightStart(0), time(7, 0), stages = listOf(HcStage(nightStart(0), time(7, 0), 4)))
            h.repository.addHeartRate(OTHER_APP, time(7, 0), time(8, 15))
            h.syncAt(time(8, 15))

            val quiet = h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA)
            assertFalse(quiet.ownHeartRate, "heart rate counts only inside the session")
            assertEquals(time(8, 40), quiet.thresholdMs, "unchanged for an hour since 07:40")
            assertEquals(time(8, 14), quiet.latestHeartRateMs)
        }

    @Test
    fun `Sleep as Android's own sessions are ignored`() =
        harnessTest(dir) { h ->
            h.enableSources(OHEALTH, SLEEP_AS_ANDROID)
            val night = h.settledNight()
            val own = h.repository.addSleepSession(SLEEP_AS_ANDROID, nightStart(0) + 5 * MINUTE_MS, time(6, 55))

            val settled = h.settle().settled()
            assertEquals(listOf(SourceVersion.of(night)), settled.versions)
            assertEquals(setOf(OHEALTH), settled.latestHeartRateMs.keys)
            assertEquals(SourceVersion.of(own), h.settle(ignored = emptySet()).unsettled(UnsettledReason.NOT_PROCESSED).version)
        }

    @Test
    fun `a session that ended and changed over 12 hours ago is out of scope, one that changed since must only be processed`() =
        harnessTest(dir, now = time(7, 1)) { h ->
            val session = h.repository.addSleepSession(OHEALTH, nightStart(0), time(7, 0))
            h.clock.nowMs = time(7, 0) + 12 * HOUR_MS + MINUTE_MS
            assertEquals(Settlement.Settled(emptyList(), emptyMap()), h.settle())

            val changed = h.repository.updateSleepSession(session.uuid) { it }
            h.clock.advanceBy(30 * MINUTE_MS)
            assertEquals(SourceVersion.of(changed), h.settle().unsettled(UnsettledReason.NOT_PROCESSED).version)

            h.sync.run(inBackground = false)
            assertEquals(NightStatus.NOT_ESTIMATED, h.singleNight().status)
            h.repository.clearCalls()
            // Changed 30 min ago and no heart rate in it: the rule for recent sessions would wait 30 min more.
            assertEquals(listOf(SourceVersion.of(changed)), h.settle().settled().versions, "only a recent night awaits an estimate")
            assertEquals(0, h.repository.callCount(Operation.READ_HEART_RATE), "only recent sessions need heart rate")
        }

    @Test
    fun `a written night that ended over 12 hours ago is not checked for a deletion, also one older than the window`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(35))
            h.backfill.run(inBackground = false)
            assertEquals(NightStatus.WRITTEN, h.singleNight().status)

            assertEquals(Settlement.Settled(emptyList(), emptyMap()), h.settle(), "its session starts before the 30 days read")
        }

    @Test
    fun `a recent night estimated from Sleep as Android's own session is not checked for a deletion`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.enableSources(OHEALTH, SLEEP_AS_ANDROID)
            h.repository.addNight(SLEEP_AS_ANDROID, nightStart(0))
            h.syncAt(time(8, 15))
            assertEquals(SLEEP_AS_ANDROID, h.singleNight().sourcePackage)
            assertEquals(NightStatus.WRITTEN, h.singleNight().status)

            assertEquals(Settlement.Settled(emptyList(), emptyMap()), h.settle(), "its sessions are not read")
        }

    @Test
    fun `zero-length sessions and sessions ending in the future are ignored`() =
        harnessTest(dir) { h ->
            val night = h.settledNight()
            val point = h.repository.addSleepSession(OHEALTH, time(7, 50), time(7, 51))
            h.repository.updateSleepSession(point.uuid) { it.copy(endMs = it.startMs) }
            h.repository.addSleepSession(OHEALTH, time(7, 55), time(8, 21))

            assertEquals(listOf(SourceVersion.of(night)), h.settle().settled().versions)
        }

    @Test
    fun `one heart-rate series record that starts before the session settles it with its samples after the margin`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.repository.addNight(OHEALTH, nightStart(0), withHeartRate = false)
            val samples = (nightStart(0) - 30 * MINUTE_MS until time(8, 10) step 2 * MINUTE_MS).map { HrSample(it, 60.0) }
            h.repository.addHeartRateSeries(OHEALTH, samples)
            h.syncAt(time(8, 15))
            assertEquals(NightStatus.WRITTEN, h.singleNight().status)

            assertEquals(mapOf(OHEALTH to time(8, 8)), h.settle().settled().latestHeartRateMs)
        }

    @Test
    fun `heart rate stamped more than 5 minutes after now is ignored`() =
        harnessTest(dir, now = time(7, 5)) { h ->
            h.repository.addNight(OHEALTH, nightStart(0))
            h.syncAt(time(7, 58))
            h.repository.addHeartRateSeries(OHEALTH, listOf(HrSample(time(8, 8), 60.0)))
            assertEquals(time(7, 28), h.settle().unsettled(UnsettledReason.WAITING_FOR_DATA).latestHeartRateMs)

            h.repository.addHeartRateSeries(OHEALTH, listOf(HrSample(time(8, 2), 60.0)))
            assertEquals(mapOf(OHEALTH to time(8, 2)), h.settle().settled().latestHeartRateMs)
        }

    @Test
    fun `in the background without READ_HEALTH_DATA_IN_BACKGROUND nothing is read and the sessions are never settled`() =
        harnessTest(dir) { h ->
            h.settledNight()
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            h.repository.inBackground = true
            h.repository.clearCalls()

            assertInstanceOf(Settlement.Unreadable::class.java, h.settle())
            h.repository.backgroundRule = BackgroundRule.SELF_ONLY
            assertInstanceOf(Settlement.Unreadable::class.java, h.settle())
            assertEquals(0, h.repository.callCount(Operation.READ_SLEEP_SESSIONS), "refused before the call")

            h.repository.grant(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            h.settle().settled()
        }

    @Test
    fun `Health Connect errors and missing permissions fail the check`() =
        harnessTest(dir) { h ->
            h.settledNight()
            val sessions = HealthDataException.Transient("fake: sleep sessions")
            h.repository.failOn(Operation.READ_SLEEP_SESSIONS, sessions)
            assertSame(sessions, assertInstanceOf(Settlement.Failed::class.java, h.settle()).error)
            val heartRate = HealthDataException.QuotaExceeded("fake: heart rate")
            h.repository.failOn(Operation.READ_HEART_RATE, heartRate)
            assertSame(heartRate, assertInstanceOf(Settlement.Failed::class.java, h.settle()).error)

            h.repository.revoke(HealthPermissions.READ_HEART_RATE)
            val denied = assertInstanceOf(Settlement.Failed::class.java, h.settle()).error
            assertInstanceOf(HealthDataException.PermissionDenied::class.java, denied)
        }

    @Test
    fun `without an enabled source other than Sleep as Android the sessions are settled without a read`() =
        harnessTest(dir) { h ->
            h.enableSources(SLEEP_AS_ANDROID)
            h.repository.addNight(SLEEP_AS_ANDROID, nightStart(0))

            assertEquals(Settlement.Settled(emptyList(), emptyMap()), h.settle())
            assertTrue(h.repository.calls.isEmpty())
        }

    @Test
    fun `the key of the versions does not depend on their order`() {
        val a = SourceVersion("a", OHEALTH, startMs = 1, endMs = 2, lastModifiedMs = 3)
        val b = SourceVersion("b", OTHER_APP, startMs = 4, endMs = 5, lastModifiedMs = 6)

        assertEquals("a:1:2:3", a.key)
        assertEquals("a:1:2:3;b:4:5:6", Settlement.Settled(listOf(b, a), emptyMap()).keyOf())
        assertEquals(
            Settlement.Settled(listOf(a, b), emptyMap()).keyOf(),
            Settlement.Unsettled(listOf(b, a), UnsettledReason.WAITING_FOR_DATA, a, nightClientRecordId = null).keyOf(),
        )
        assertEquals("", Settlement.Unreadable("not in the foreground").keyOf())
        assertEquals("", Settlement.Failed(HealthDataException.Transient("fake")).keyOf())
    }
}

package io.github.alien2003.pulsestages.data.hc.pipeline

import androidx.health.connect.client.records.SleepSessionRecord
import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.SourceOutcome
import io.github.alien2003.pulsestages.data.local.db.SourceSessionEntity
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Sessions that change mode, and the nap output policies against the bookkeeping of the pipeline (ALGORITHM.md section
 * 18, docs/INTERCHANGE.md section 5): every case checks the ledger statuses, the source rows (outcome and link), our
 * records in Health Connect (one per sleep, none for a skipped nap) and the mode and sleep / wake columns.
 */
class NapModeTransitionsTest {
    @TempDir
    lateinit var dir: File

    private suspend fun PipelineHarness.policy(policy: NapOutputPolicy) {
        settings.set { s -> s.copy(algo = s.algo.copy(napMode = s.algo.napMode.copy(outputPolicy = policy))) }
    }

    /** OHealth first, then the other app. */
    private suspend fun PipelineHarness.twoSources() {
        settings.set {
            it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OHEALTH, OTHER_APP)))
        }
    }

    private fun PipelineHarness.rowOf(vararg sessions: HcSleepSession): EstimatedNightEntity =
        ledger.allNights().single { it.clientRecordId == NightKeys.clientRecordId(sessions.map { s -> s.uuid }) }

    private fun PipelineHarness.source(session: HcSleepSession): SourceSessionEntity =
        ledger.allSources().single { it.uuid == session.uuid }

    private fun PipelineHarness.ownIds(): Set<String?> = repository.ownSessions().map { it.clientRecordId }.toSet()

    private fun cid(vararg sessions: HcSleepSession): String = NightKeys.clientRecordId(sessions.map { it.uuid })

    private fun assertSkippedNap(row: EstimatedNightEntity) {
        assertEquals(NightStatus.NOT_ESTIMATED, row.status)
        assertEquals(SessionMode.NAP, row.mode)
        assertTrue(checkNotNull(row.statusReason).startsWith("${NotEstimatedReason.NAP_SKIPPED}:"), row.statusReason)
        assertTrue(row.skippedNap)
        assertNull(row.hcRecordId)
    }

    // ---------------------------------------------------------------------------- SKIP and other packages' nights

    /** A 240-min night of the other app (lower priority), and OHealth's 170-min record of the same sleep (a nap). */
    private fun PipelineHarness.sleepOfTwoSources(): Pair<HcSleepSession, HcSleepSession> {
        val start = nightStart(1)
        val night = repository.addNight(OTHER_APP, start + 30 * MINUTE_MS, durationMs = 240 * MINUTE_MS)
        val nap = repository.addNight(OHEALTH, start, durationMs = 170 * MINUTE_MS)
        return night to nap
    }

    @Test
    fun `SKIP - a skipped nap does not retire the written night of a lower-priority source`() =
        harnessTest(dir) { h ->
            h.twoSources()
            h.policy(NapOutputPolicy.SKIP)
            val night = h.repository.addNight(OTHER_APP, nightStart(1) + 30 * MINUTE_MS, durationMs = 240 * MINUTE_MS)
            h.sync.run(inBackground = false)
            assertEquals(setOf(cid(night)), h.ownIds())

            val nap = h.repository.addNight(OHEALTH, nightStart(1), durationMs = 170 * MINUTE_MS)
            h.sync.run(inBackground = false)

            assertEquals(setOf(cid(night)), h.ownIds(), "the night keeps its output")
            assertEquals(NightStatus.WRITTEN, h.rowOf(night).status)
            assertNotNull(h.ledger.baselineDao.find(cid(night)), "and its nadir")
            assertEquals(SourceOutcome.ESTIMATED, h.source(night).outcome)
            assertSkippedNap(h.rowOf(nap))
            assertEquals(SourceOutcome.NOT_ESTIMATED, h.source(nap).outcome)
            assertEquals(cid(nap), h.source(nap).nightClientRecordId)
            // Recomputing everything keeps that state.
            h.processRecent(PipelineTrigger.RECOMPUTE)
            assertEquals(setOf(cid(night)), h.ownIds())
            assertEquals(NightStatus.WRITTEN, h.rowOf(night).status)
        }

    @Test
    fun `SKIP - a skipped nap does not make the night of a lower-priority source its duplicate`() =
        harnessTest(dir) { h ->
            h.twoSources()
            h.policy(NapOutputPolicy.SKIP)
            val (night, nap) = h.sleepOfTwoSources()

            val report = h.processRecent()

            assertEquals(1, report.estimated, "$report")
            assertEquals(setOf(cid(night)), h.ownIds())
            assertEquals(NightStatus.WRITTEN, h.rowOf(night).status)
            assertEquals(SessionMode.NIGHT, h.rowOf(night).mode)
            assertEquals(SourceOutcome.ESTIMATED, h.source(night).outcome)
            assertSkippedNap(h.rowOf(nap))
        }

    @Test
    fun `STAGES - the higher-priority nap replaces the night of the other source`() =
        harnessTest(dir) { h ->
            h.twoSources()
            val night = h.repository.addNight(OTHER_APP, nightStart(1) + 30 * MINUTE_MS, durationMs = 240 * MINUTE_MS)
            h.sync.run(inBackground = false)
            val nap = h.repository.addNight(OHEALTH, nightStart(1), durationMs = 170 * MINUTE_MS)
            h.sync.run(inBackground = false)

            assertEquals(setOf(cid(nap)), h.ownIds())
            assertEquals(NightStatus.SUPERSEDED, h.rowOf(night).status)
            assertEquals(NightStatus.WRITTEN, h.rowOf(nap).status)
            assertEquals(SessionMode.NAP, h.rowOf(nap).mode)
        }

    // --------------------------------------------------------------------------- a night becomes a nap, and back

    /** A 120-min fragment, a 10-min gap and a 6-h session of OHealth: one night of 8 h 10 min. */
    private fun PipelineHarness.fragmentAndMain(): Pair<HcSleepSession, HcSleepSession> {
        val start = nightStart(1)
        val fragment = repository.addNight(OHEALTH, start, durationMs = 120 * MINUTE_MS)
        val main = repository.addNight(OHEALTH, start + 130 * MINUTE_MS, durationMs = 6 * HOUR_MS)
        return fragment to main
    }

    @Test
    fun `SKIP - a night that becomes a nap when a fragment is deleted leaves no output and links the fragment to the nap`() =
        harnessTest(dir) { h ->
            h.policy(NapOutputPolicy.SKIP)
            val (fragment, main) = h.fragmentAndMain()
            h.sync.run(inBackground = false)
            assertEquals(setOf(cid(fragment, main)), h.ownIds())
            assertEquals(SessionMode.NIGHT, h.rowOf(fragment, main).mode)

            h.repository.deleteSleepSession(main.uuid)
            h.sync.run(inBackground = false)

            assertTrue(h.repository.ownSessions().isEmpty())
            assertEquals(NightStatus.SUPERSEDED, h.rowOf(fragment, main).status)
            assertSkippedNap(h.rowOf(fragment))
            assertEquals(SourceOutcome.NOT_ESTIMATED, h.source(fragment).outcome, "not counted as estimated")
            assertEquals(cid(fragment), h.source(fragment).nightClientRecordId, "linked to the nap, not to the retired night")
            assertEquals(SourceOutcome.DELETED, h.source(main).outcome)
            val counters =
                h.ledger.sourceDao
                    .observeHomeCounters(1, "any")
                    .first()
            assertEquals(0, counters.estimated)
            assertEquals(0, counters.nights + counters.naps)
        }

    @Test
    fun `STAGES - a night that becomes a nap when a fragment is deleted is rewritten as a nap`() =
        harnessTest(dir) { h ->
            h.policy(NapOutputPolicy.STAGES)
            val (fragment, main) = h.fragmentAndMain()
            h.sync.run(inBackground = false)

            h.repository.deleteSleepSession(main.uuid)
            h.sync.run(inBackground = false)

            assertEquals(setOf(cid(fragment)), h.ownIds())
            assertEquals(NightStatus.SUPERSEDED, h.rowOf(fragment, main).status)
            val nap = h.rowOf(fragment)
            assertEquals(NightStatus.WRITTEN, nap.status)
            assertEquals(SessionMode.NAP, nap.mode)
            assertFalse(nap.sleepWakeOnly)
            assertEquals(SourceOutcome.ESTIMATED, h.source(fragment).outcome)
            assertEquals(cid(fragment), h.source(fragment).nightClientRecordId)
        }

    @Test
    fun `a nap that becomes a night through a new fragment is replaced by the merged night, under STAGES and SKIP`() {
        for (policy in listOf(NapOutputPolicy.STAGES, NapOutputPolicy.SKIP)) {
            harnessTest(File(dir, policy.name).apply { mkdirs() }) { h ->
                h.policy(policy)
                val start = nightStart(1)
                val nap = h.repository.addNight(OHEALTH, start, durationMs = 100 * MINUTE_MS)
                h.sync.run(inBackground = false)
                assertEquals(SessionMode.NAP, h.rowOf(nap).mode)
                assertEquals(if (policy == NapOutputPolicy.SKIP) emptySet() else setOf(cid(nap)), h.ownIds(), "$policy")

                val rest = h.repository.addNight(OHEALTH, start + 110 * MINUTE_MS, durationMs = 3 * HOUR_MS)
                h.sync.run(inBackground = false)

                assertEquals(setOf(cid(nap, rest)), h.ownIds(), "$policy")
                val merged = h.rowOf(nap, rest)
                assertEquals(NightStatus.WRITTEN, merged.status)
                assertEquals(SessionMode.NIGHT, merged.mode)
                assertFalse(merged.sleepWakeOnly)
                assertEquals(NightStatus.SUPERSEDED, h.rowOf(nap).status, "$policy")
                for (session in listOf(nap, rest)) {
                    assertEquals(SourceOutcome.ESTIMATED, h.source(session).outcome)
                    assertEquals(cid(nap, rest), h.source(session).nightClientRecordId)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------ settings that flip the mode

    @Test
    fun `a written session flips between nap and night with napMode maxSessionMin, keeping one record`() =
        harnessTest(dir) { h ->
            val session = h.repository.addNight(OHEALTH, nightStart(1), durationMs = 150 * MINUTE_MS)
            h.processRecent()
            assertEquals(SessionMode.NAP, h.rowOf(session).mode)
            val firstVersion =
                h.repository
                    .ownSessions()
                    .single()
                    .clientRecordVersion

            h.settings.set { it.copy(algo = it.algo.copy(napMode = it.algo.napMode.copy(maxSessionMin = 120.0))) }
            h.processRecent(PipelineTrigger.RECOMPUTE)

            val row = h.rowOf(session)
            assertEquals(NightStatus.WRITTEN, row.status)
            assertEquals(SessionMode.NIGHT, row.mode)
            assertEquals(setOf(cid(session)), h.ownIds(), "the same record, rewritten")
            assertTrue(
                h.repository
                    .ownSessions()
                    .single()
                    .clientRecordVersion > firstVersion,
            )
            assertEquals(1, h.ledger.allNights().size)

            h.settings.set { it.copy(algo = it.algo.copy(napMode = it.algo.napMode.copy(maxSessionMin = 180.0))) }
            h.processRecent(PipelineTrigger.RECOMPUTE)
            assertEquals(SessionMode.NAP, h.rowOf(session).mode)
            assertEquals(setOf(cid(session)), h.ownIds())
        }

    @Test
    fun `SLEEP_WAKE to STAGES - recomputing writes the stages and clears the sleep-wake flag`() =
        harnessTest(dir) { h ->
            h.policy(NapOutputPolicy.SLEEP_WAKE)
            val nap = h.repository.addNight(OHEALTH, nightStart(1), durationMs = 150 * MINUTE_MS)
            h.processRecent()
            assertTrue(h.rowOf(nap).sleepWakeOnly)
            assertTrue(
                h.repository
                    .ownSessions()
                    .single()
                    .stages
                    .none { it.type == SleepSessionRecord.STAGE_TYPE_DEEP },
            )

            h.policy(NapOutputPolicy.STAGES)
            h.processRecent(PipelineTrigger.RECOMPUTE)

            assertFalse(h.rowOf(nap).sleepWakeOnly)
            val written = h.repository.ownSessions().single()
            assertEquals(cid(nap), written.clientRecordId)
            assertTrue(written.stages.none { it.type == SleepSessionRecord.STAGE_TYPE_SLEEPING }, "${written.stages}")
        }

    // ------------------------------------------------------------------------------------------- SKIP bookkeeping

    @Test
    fun `SKIP after a reinstall - our earlier records of the nap are deleted, another night's record stays`() =
        harnessTest(dir) { h ->
            h.policy(NapOutputPolicy.SKIP)
            val nap = h.repository.addNight(OHEALTH, nightStart(1), durationMs = 150 * MINUTE_MS)
            val notes =
                SessionText.notes(EstimatorType.HYBRID, SessionMode.NAP, 1, "old", 1.0, 0.5, listOf(nap.uuid), 120, calibrated = false)
            // Written before the reinstall: the nap's own record, an older record of it, and another night's record.
            h.repository.preloadOwnSession(ownWrite(cid(nap), nap.startMs, nap.endMs, notes))
            h.repository.preloadOwnSession(ownWrite("ps:older", nap.startMs, nap.endMs - 10 * MINUTE_MS, notes))
            h.repository.preloadOwnSession(ownWrite("ps:other-night", nap.startMs + 20 * MINUTE_MS, nap.endMs + HOUR_MS, "{}"))

            val report = h.processRecent()

            assertEquals(setOf("ps:other-night"), h.ownIds())
            assertEquals(1, report.superseded, "$report")
            assertSkippedNap(h.rowOf(nap))
            assertEquals(0, h.processRecent().superseded, "a second run finds nothing to delete")
        }

    @Test
    fun `SKIP - a skipped nap is not retried for late heart rate, and Recompute stale writes it after switching back`() =
        harnessTest(dir) { h ->
            h.policy(NapOutputPolicy.SKIP)
            val nap = h.repository.addNight(OHEALTH, nightStart(0) + 2 * HOUR_MS, durationMs = 100 * MINUTE_MS)
            h.sync.run(inBackground = false)
            assertSkippedNap(h.rowOf(nap))
            assertTrue(h.pipeline.unfinishedNights(Long.MIN_VALUE, Long.MAX_VALUE).isEmpty(), "never retried")
            val updated = h.rowOf(nap).updatedAt
            h.clock.advanceBy(HOUR_MS)
            h.sync.run(inBackground = false)
            assertEquals(updated, h.rowOf(nap).updatedAt, "the next sync leaves it alone")

            h.policy(NapOutputPolicy.STAGES)
            val hash = h.pipeline.currentParamsHash(h.settings.current, h.repository.granted.toSet())
            assertTrue(h.rowOf(nap).isStale(ALGO_VERSION, hash), "stale with the new policy")
            h.recomputer.recomputeStale(inBackground = false)

            assertEquals(NightStatus.WRITTEN, h.rowOf(nap).status)
            assertEquals(setOf(cid(nap)), h.ownIds())
        }
}

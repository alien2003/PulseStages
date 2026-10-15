package io.github.alien2003.pulsestages.data.hc.pipeline

import androidx.health.connect.client.records.SleepSessionRecord
import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.InputChange
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What the refresh of written nights never does, whatever heart rate arrives later: bring back a night the user
 * deleted, read or write anything under OverwritePolicy.NEVER, touch a stale night, a skipped nap, a night retired for
 * a staged session or superseded by a merged night, recreate a record deleted outside PulseStages, or drop an output it
 * cannot estimate again.
 */
class RefreshWrittenNightsRulesTest {
    @TempDir
    lateinit var dir: File

    /** Naps are processed from 30 min (the source setting) and written as [policy]. */
    private suspend fun PipelineHarness.withNaps(policy: NapOutputPolicy) {
        settings.set { s ->
            s.copy(sources = s.sources.copy(minSessionMin = 30), algo = s.algo.copy(napMode = s.algo.napMode.copy(outputPolicy = policy)))
        }
    }

    /** Syncs [times] times an hour apart, starting an hour from now. */
    private suspend fun PipelineHarness.syncHourly(times: Int) {
        repeat(times) {
            clock.advanceBy(HOUR_MS)
            sync.run(inBackground = false)
        }
    }

    @Test
    fun `a night the user deleted is not brought back by heart rate that arrives later`() =
        harnessTest(dir) { h ->
            val source = h.repository.addPartialNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val cid = NightKeys.clientRecordId(listOf(source.uuid))
            assertEquals(DeleteNightResult.DELETED, h.eraser.deleteNight(cid))

            h.repository.addRestOfHeartRate(source)
            h.repository.clearCalls()
            h.syncHourly(3)

            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertEquals(0, h.repository.callCount(Operation.READ_HEART_RATE))
            assertTrue(h.repository.ownSessions().isEmpty())
            assertEquals(NightStatus.DELETED, h.singleNight().status)
        }

    @Test
    fun `nights deleted with delete all my sessions are not brought back by heart rate that arrives later`() =
        harnessTest(dir) { h ->
            val sources = listOf(h.repository.addPartialNight(OHEALTH, nightStart(0)), h.repository.addPartialNight(OHEALTH, nightStart(1)))
            h.sync.run(inBackground = false)
            assertEquals(2, h.eraser.eraseAll().markedDeleted)

            sources.forEach { h.repository.addRestOfHeartRate(it) }
            h.repository.clearCalls()
            h.syncHourly(3)

            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertEquals(0, h.repository.callCount(Operation.READ_HEART_RATE))
            assertTrue(h.repository.ownSessions().isEmpty())
            assertEquals(listOf(NightStatus.DELETED, NightStatus.DELETED), h.ledger.allNights().map { it.status })
        }

    @Test
    fun `a nap skipped by the nap policy is neither read nor written when its heart rate arrives`() =
        harnessTest(dir) { h ->
            h.withNaps(NapOutputPolicy.SKIP)
            val nap = h.repository.addPartialNight(OHEALTH, NAP_START, durationMs = 90 * MINUTE_MS, untilMin = 59)
            h.sync.run(inBackground = false)
            val skipped = h.singleNight()
            assertEquals(NightStatus.NOT_ESTIMATED, skipped.status)
            assertTrue(skipped.skippedNap)

            h.repository.addRestOfHeartRate(nap, fromMin = 59)
            h.repository.clearCalls()
            h.syncHourly(3)

            assertEquals(0, h.repository.callCount(Operation.READ_HEART_RATE))
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertTrue(h.estimator.inputs.isEmpty())
            assertTrue(h.repository.ownSessions().isEmpty())
            assertEquals(skipped, h.singleNight())
        }

    @Test
    fun `heart rate that changes only the estimated stages of a sleep-wake nap updates the ledger, not its record`() =
        harnessTest(dir) { h ->
            h.withNaps(NapOutputPolicy.SLEEP_WAKE)
            val nap = h.repository.addNight(OHEALTH, NAP_START, durationMs = 90 * MINUTE_MS)
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            val record = h.singleRecord()
            assertTrue(written.sleepWakeOnly)
            assertEquals(SleepStage.REM, written.stages.first().stage)

            // One more sample just before the nap: its first two estimated minutes become LIGHT instead of REM, which a
            // sleep/wake record writes as SLEEPING either way; the coverage and the samples inside the nap do not change.
            h.repository.addHeartRateSeries(OHEALTH, listOf(HrSample(nap.startMs - 3 * MINUTE_MS, 40.0)))
            h.clock.advanceBy(HOUR_MS)
            h.repository.clearCalls()
            val report = h.sync.run(inBackground = false)

            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertEquals(listOf(record), h.repository.ownSessions())
            assertSleepWakeOnly(record.stages)
            assertEquals(1, report.pipeline.inputChecks)
            assertEquals(0, report.pipeline.refreshed)
            val night = h.singleNight()
            assertEquals(SleepStage.LIGHT, night.stages.first().stage, "the ledger keeps the new estimated stages")
            assertEquals(written.remMin - 2.0, night.remMin, 1e-9)
            assertEquals(written.lightMin + 2.0, night.lightMin, 1e-9)
            assertEquals(written.clientRecordVersion, night.clientRecordVersion)
            assertEquals(written.updatedAt, night.updatedAt)
            assertEquals(written.hrSamples, night.hrSamples)
            assertNull(night.statusReason)
            assertTrue(night.sleepWakeOnly)
            assertNotEquals(written.inputFingerprint, night.inputFingerprint)
            val expected =
                "refresh: the input changed (the heart rate changed (45 -> 45 heart-rate samples)); the written session stays the same"
            assertTrue(h.logger.lines.any { it.message == expected && it.nightKey == night.clientRecordId }, "${h.logger.lines}")
        }

    @Test
    fun `a sleep-wake nap whose heart rate arrives later is rewritten with sleep and wake stages only`() =
        harnessTest(dir) { h ->
            h.withNaps(NapOutputPolicy.SLEEP_WAKE)
            val nap = h.repository.addPartialNight(OHEALTH, NAP_START, durationMs = 90 * MINUTE_MS, untilMin = 59)
            h.sync.run(inBackground = false)
            assertEquals(64.0 / 90, h.singleNight().coverage, 1e-9)

            h.clock.advanceBy(HOUR_MS)
            h.repository.addRestOfHeartRate(nap, fromMin = 59)
            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.pipeline.refreshed)
            val record = h.singleRecord()
            assertEquals(1001L, record.clientRecordVersion)
            assertSleepWakeOnly(record.stages)
            assertEquals("NAP", record.noteValue("mode"))
            val night = h.singleNight()
            assertTrue(night.sleepWakeOnly)
            assertEquals(1.0, night.coverage, 1e-9)
            assertTrue(night.stages.any { it.stage == SleepStage.DEEP }, "the ledger keeps the estimated stages: ${night.stages}")
            assertEquals(InputChange.MORE_HEART_RATE, night.inputUpdate?.change)
            h.assertLedgerMatchesHealthConnect()
        }

    @Test
    fun `under the NEVER policy nothing is read again and nothing is rewritten`() =
        harnessTest(dir) { h ->
            val source = h.repository.addPartialNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.NEVER)) }

            h.clock.advanceBy(HOUR_MS)
            h.repository.addRestOfHeartRate(source)
            h.repository.clearCalls()
            val report = h.sync.run(inBackground = false)

            assertEquals(0, h.repository.callCount(Operation.READ_HEART_RATE))
            assertEquals(0, h.repository.callCount(Operation.READ_SLEEP_SESSIONS))
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertEquals(0, report.pipeline.inputChecks)
            assertEquals(written, h.singleNight())
            assertEquals(1000L, h.singleRecord().clientRecordVersion)
        }

    @Test
    fun `a stale night is left to an explicit recompute, also when its heart rate grows`() =
        harnessTest(dir) { h ->
            val source = h.repository.addPartialNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            h.settings.set { it.copy(algo = it.algo.copy(preprocess = it.algo.preprocess.copy(smoothWindowEpochs = 7))) }
            val hash = h.pipeline.currentParamsHash(h.settings.current, h.repository.granted)
            assertTrue(written.isStale(ALGO_VERSION, hash))

            h.repository.addRestOfHeartRate(source)
            h.repository.clearCalls()
            h.syncHourly(3)

            assertEquals(0, h.repository.callCount(Operation.READ_SLEEP_SESSIONS), "not even a candidate")
            assertEquals(0, h.repository.callCount(Operation.READ_HEART_RATE))
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            val night = h.singleNight()
            assertEquals(written, night)
            assertTrue(night.isStale(ALGO_VERSION, hash), "still stale")
            assertEquals(1000L, h.singleRecord().clientRecordVersion)
        }

    @Test
    fun `a record deleted outside PulseStages is not recreated when the input changes`() =
        harnessTest(dir) { h ->
            val source = h.repository.addPartialNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            val cid = written.clientRecordId
            // Another tool deletes our record; the ledger does not know.
            h.repository.deleteOwnSessions(listOf(cid))

            h.clock.advanceBy(HOUR_MS)
            h.repository.addRestOfHeartRate(source)
            h.repository.clearCalls()
            val report = h.sync.run(inBackground = false)

            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertTrue(h.repository.ownSessions().isEmpty())
            assertEquals(0, report.pipeline.refreshed)
            assertEquals(1, report.pipeline.inputChecks)
            val night = h.singleNight()
            assertEquals(NightStatus.WRITTEN, night.status)
            assertEquals(written.withoutInputCheck(), night.withoutInputCheck(), "the earlier estimate stays in the ledger")
            assertNotEquals(written.inputFingerprint, night.inputFingerprint, "the current input is marked as checked")
            assertEquals(written.updatedAt + HOUR_MS, night.inputCheckedAt)
            assertTrue(
                h.logger.atLeast(LogLevel.WARN).any { it.nightKey == cid && "no longer in Health Connect" in it.message },
                "${h.logger.lines}",
            )

            val estimates = h.estimator.inputs.size
            h.syncHourly(2)
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertEquals(estimates, h.estimator.inputs.size, "the input is unchanged since that check")
        }

    @Test
    fun `a staged session of a higher-priority source retires our output, heart rate that arrives later does not bring it back`() =
        harnessTest(dir) { h ->
            h.settings.set {
                it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OTHER_APP, OHEALTH)))
            }
            val start = nightStart(0)
            val ours = h.repository.addPartialNight(OHEALTH, start)
            h.sync.run(inBackground = false)
            assertEquals(1, h.repository.ownSessions().size)

            h.repository.addNight(
                OTHER_APP,
                start + 10 * MINUTE_MS,
                stages = listOf(HcStage(start + 10 * MINUTE_MS, start + 8 * HOUR_MS + 10 * MINUTE_MS, SleepSessionRecord.STAGE_TYPE_LIGHT)),
                withHeartRate = false,
            )
            h.repository.addRestOfHeartRate(ours)
            h.clock.advanceBy(HOUR_MS)
            h.repository.clearCalls()
            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.pipeline.superseded)
            assertEquals(0, report.pipeline.inputChecks)
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertTrue(h.repository.ownSessions().isEmpty(), "one staged session per night: the preferred app's")
            assertEquals(NightStatus.SUPERSEDED, h.singleNight().status)

            h.syncHourly(2)
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertEquals(NightStatus.SUPERSEDED, h.singleNight().status)
        }

    @Test
    fun `a night superseded by a merged night is not brought back, the merged night is refreshed`() =
        harnessTest(dir) { h ->
            val start = nightStart(0)
            val first = h.repository.addPartialNight(OHEALTH, start, durationMs = 4 * HOUR_MS, untilMin = 119)
            h.sync.run(inBackground = false)
            val firstCid = NightKeys.clientRecordId(listOf(first.uuid))
            assertEquals(listOf(firstCid), h.repository.ownSessions().map { it.clientRecordId })

            h.clock.advanceBy(HOUR_MS)
            val second = h.repository.addNight(OHEALTH, start + 4 * HOUR_MS + 15 * MINUTE_MS, durationMs = 3 * HOUR_MS)
            h.sync.run(inBackground = false)
            val mergedCid = NightKeys.clientRecordId(listOf(first.uuid, second.uuid))
            assertEquals(listOf(mergedCid), h.repository.ownSessions().map { it.clientRecordId })

            h.clock.advanceBy(HOUR_MS)
            h.repository.addRestOfHeartRate(first, fromMin = 119)
            h.repository.clearCalls()
            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.pipeline.refreshed)
            assertEquals(1, h.repository.callCount(Operation.WRITE), "the merged night only")
            assertEquals(listOf(mergedCid), h.repository.ownSessions().map { it.clientRecordId })
            val nights = h.ledger.allNights().associateBy { it.clientRecordId }
            assertEquals(NightStatus.SUPERSEDED, nights.getValue(firstCid).status)
            assertEquals("superseded by $mergedCid", nights.getValue(firstCid).statusReason)
            assertEquals(InputChange.MORE_HEART_RATE, nights.getValue(mergedCid).inputUpdate?.change)
            h.assertLedgerMatchesHealthConnect()
        }

    @Test
    fun `written nights of a source that is no longer enabled are not refreshed and take no place of the per-run limit`() =
        harnessTest(dir) { h ->
            h.settings.set {
                it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OHEALTH, OTHER_APP)))
            }
            h.sync.run(inBackground = false)
            // Eight late nights of the other app and one of OHealth, all first seen by the same sync.
            val others = (3..10).map { h.repository.addPartialNight(OTHER_APP, nightStart(it)) }
            val ours = h.repository.addPartialNight(OHEALTH, nightStart(2))
            h.sync.run(inBackground = false)
            assertEquals(9, h.repository.ownSessions().size)
            h.settings.set { it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH), sourcePriority = listOf(OHEALTH))) }
            (others + ours).forEach { h.repository.addRestOfHeartRate(it) }

            h.syncHourly(1)

            val nights = h.ledger.allNights().groupBy { it.sourcePackage }
            assertEquals(listOf(1001L), nights.getValue(OHEALTH).map { it.clientRecordVersion }, "refreshed despite 8 older due nights")
            assertTrue(nights.getValue(OTHER_APP).all { it.clientRecordVersion == 1000L && it.status == NightStatus.WRITTEN })
            assertEquals(9, h.repository.ownSessions().size, "disabling a source does not remove its outputs")
        }

    @Test
    fun `a night whose changed input can no longer be estimated keeps its output`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            val record = h.singleRecord()

            h.repository.addSteps(OHEALTH, source.startMs + HOUR_MS, source.startMs + HOUR_MS + 5 * MINUTE_MS, 25)
            h.estimator.declineWhen = { EstimationOutcome.NotEstimated(NotEstimatedReason.LOW_COVERAGE, 0.3, "declined by the test") }
            h.clock.advanceBy(HOUR_MS)
            h.repository.clearCalls()
            val report = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.SUCCESS, report.outcome)
            assertEquals(0, report.pipeline.failed)
            assertEquals(1, report.pipeline.inputChecks)
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertEquals(0, h.repository.callCount(Operation.DELETE))
            assertEquals(listOf(record), h.repository.ownSessions())
            val night = h.singleNight()
            assertEquals(written.withoutInputCheck(), night.withoutInputCheck())
            assertNotEquals(written.inputFingerprint, night.inputFingerprint)
            val warnings = h.logger.atLeast(LogLevel.WARN)
            assertTrue(warnings.any { "can no longer be estimated" in it.message && it.nightKey == night.clientRecordId }, "$warnings")

            val estimates = h.estimator.inputs.size
            h.syncHourly(1)
            assertEquals(estimates, h.estimator.inputs.size, "the same input is not estimated again")
        }

    @Test
    fun `an estimator failure during the refresh keeps the output and the next sync tries again`() =
        harnessTest(dir) { h ->
            val source = h.repository.addPartialNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            val record = h.singleRecord()

            h.repository.addRestOfHeartRate(source)
            h.estimator.failWhen = { IllegalStateException("estimator bug") }
            h.clock.advanceBy(HOUR_MS)
            val failed = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.PARTIAL_FAILURE, failed.outcome)
            assertEquals(1, failed.pipeline.failed)
            assertEquals(listOf(record), h.repository.ownSessions())
            assertEquals(written, h.singleNight(), "still WRITTEN, not marked as checked")

            h.estimator.failWhen = { null }
            h.clock.advanceBy(HOUR_MS)
            val retried = h.sync.run(inBackground = false)
            assertEquals(1, retried.pipeline.refreshed)
            h.assertLedgerMatchesHealthConnect()
        }

    private fun assertSleepWakeOnly(stages: List<HcStage>) {
        assertTrue(stages.isNotEmpty())
        val types = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_SLEEPING)
        assertTrue(stages.all { it.type in types }, "only AWAKE and SLEEPING: $stages")
        assertTrue(stages.any { it.type == SleepSessionRecord.STAGE_TYPE_SLEEPING })
        stages.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs, "contiguous and sorted") }
    }

    @Test
    fun `heart rate that the source removes for a while never makes a written night worse`() =
        harnessTest(dir) { h ->
            val start = nightStart(0)
            val source = h.repository.addNight(OHEALTH, start)
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            val record = h.singleRecord()
            assertEquals(240, written.hrSamples)

            // The source replaces its records: half of the night's heart rate is gone for a while.
            h.repository.removeHeartRate(OHEALTH, start + 4 * HOUR_MS, source.endMs + HOUR_MS)
            h.repository.clearCalls()
            h.clock.advanceBy(HOUR_MS)
            val partial = h.sync.run(inBackground = false)

            assertEquals(1, partial.pipeline.inputChecks)
            assertEquals(0, partial.pipeline.refreshed)
            assertEquals(0, h.repository.callCount(Operation.WRITE), "fewer samples never rewrite the record")
            assertEquals(listOf(record), h.repository.ownSessions())
            assertEquals(written.withoutInputCheck(), h.singleNight().withoutInputCheck())
            assertTrue(h.logger.lines.any { "fewer heart-rate samples than at the last write (240 -> 120)" in it.message })

            // The records are back: the input matches the record again, nothing is written either.
            h.repository.addRestOfHeartRate(source, fromMin = 240)
            h.clock.advanceBy(HOUR_MS)
            val back = h.sync.run(inBackground = false)

            assertEquals(1, back.pipeline.inputChecks)
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertEquals(listOf(record), h.repository.ownSessions())
            assertEquals(written.inputFingerprint, h.singleNight().inputFingerprint, "the same input as at the write")
        }

    @Test
    fun `a due night that is no longer a night of its window is marked as checked without reading its input`() =
        harnessTest(dir) { h ->
            val start = nightStart(1) + 14 * HOUR_MS
            h.repository.addNight(OHEALTH, start, durationMs = 2 * HOUR_MS)
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            assertEquals(NightStatus.WRITTEN, written.status)

            // A minimum session length above the session's makes it no candidate any more; its output stays.
            h.settings.set { it.copy(sources = it.sources.copy(minSessionMin = 150)) }
            h.repository.clearCalls()
            h.clock.advanceBy(HOUR_MS)
            val report = h.sync.run(inBackground = false)

            assertEquals(0, h.repository.callCount(Operation.READ_HEART_RATE), "nothing to estimate, nothing read")
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertEquals(0, report.pipeline.inputChecks)
            assertEquals(0, report.pipeline.found, "a night that is no candidate any more is not found again")
            assertEquals(0, report.pipeline.skipped)
            val night = h.singleNight()
            assertEquals(NightStatus.WRITTEN, night.status)
            assertEquals(h.clock.millis(), night.inputCheckedAt, "checked, so it moves back in the refresh order")
            assertEquals(written.inputFingerprint, night.inputFingerprint)
            assertEquals(written.updatedAt, night.updatedAt)
        }

    private companion object {
        /** A 90-min afternoon nap the day before [nightStart] (0): it ended 19.5 h before the first sync. */
        val NAP_START: Long = nightStart(1) + 16 * HOUR_MS
    }
}

package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * `write.startBeforeSource` (Sleep as Android compatibility) in the pipeline: the record starts one second before its
 * night with the first stage extended back, the ledger keeps the night's times, the option is part of the params hash,
 * and the stages are still validated against the night.
 */
class StartBeforeSourcePipelineTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `startBeforeSource writes the record one second before its source and keeps the ledger in the night's times`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(1))
            val plainHash = h.pipeline.currentParamsHash(h.settings.current, h.repository.granted)
            h.settings.set { it.copy(write = it.write.copy(startBeforeSource = true)) }

            h.processRecent()

            val own = h.repository.ownSessions().single()
            assertValidStages(own)
            assertEquals(source.startMs - OutputOptions.START_BEFORE_SOURCE_MS, own.startMs)
            assertEquals(source.endMs, own.endMs)
            assertEquals(NightKeys.clientRecordId(listOf(source.uuid)), own.clientRecordId)
            val night = h.ledger.allNights().single()
            assertEquals(source.startMs, night.sessionStartMs)
            assertEquals(source.startMs, night.stages.first().startMs, "the ledger keeps the estimate in the night's times")
            val estimated = StageMapping.toHcStages(night.stages, awakeAsLight = false)
            assertEquals(StageMapping.startingAt(estimated, own.startMs), own.stages, "only the first stage is extended")
            assertNotEquals(plainHash, night.paramsHash)
            assertTrue(own.notes.orEmpty().contains(night.paramsHash))
            assertTrue(h.logger.lines.any { "starting 1 s before its source" in it.message })

            assertEquals(PipelineReport(found = 1, skipped = 1, upToDate = 1), h.processRecent())
            assertEquals(1, h.repository.callCount(Operation.WRITE), "the shifted record is up to date on the next run")
        }

    @Test
    fun `turning startBeforeSource on or off makes written nights stale and a recompute moves the record in place`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(1))
            h.processRecent()
            val before = h.repository.ownSessions().single()
            val hashOff = h.pipeline.currentParamsHash(h.settings.current, h.repository.granted)
            h.settings.set { it.copy(write = it.write.copy(startBeforeSource = true)) }

            assertEquals(PipelineReport(found = 1, skipped = 1, stale = 1), h.processRecent())
            assertEquals(before, h.repository.ownSessions().single(), "a stale night is not rewritten by a sync")
            assertEquals(1, h.recomputer.recomputeStale(inBackground = false).estimated)

            val shifted = h.repository.ownSessions().single()
            assertEquals(before.uuid, shifted.uuid, "rewritten in place")
            assertEquals(before.clientRecordId, shifted.clientRecordId)
            assertEquals(1001L, shifted.clientRecordVersion)
            assertEquals(source.startMs - OutputOptions.START_BEFORE_SOURCE_MS, shifted.startMs)
            assertEquals(before.endMs, shifted.endMs)
            assertValidStages(shifted)
            assertEquals(
                shifted.uuid,
                h.ledger
                    .allNights()
                    .single()
                    .hcRecordId,
            )
            assertEquals(
                source.startMs,
                h.ledger
                    .allNights()
                    .single()
                    .sessionStartMs,
            )

            h.settings.set { it.copy(write = it.write.copy(startBeforeSource = false)) }
            assertEquals(hashOff, h.pipeline.currentParamsHash(h.settings.current, h.repository.granted), "off: the hash of before")
            assertEquals(1, h.recomputer.recomputeStale(inBackground = false).estimated)

            val back = h.repository.ownSessions().single()
            assertEquals(before.uuid, back.uuid)
            assertEquals(1002L, back.clientRecordVersion)
            assertEquals(source.startMs, back.startMs)
            assertEquals(before.stages, back.stages)
        }

    @Test
    fun `with startBeforeSource an estimate that does not start with its night still fails and nothing is written`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.settings.set { it.copy(write = it.write.copy(startBeforeSource = true)) }
            h.estimator.stagesOverride = { input, _ ->
                listOf(StageInterval(input.sessionStartMs + 2 * MINUTE_MS, input.sessionEndMs, SleepStage.LIGHT))
            }

            val report = h.processRecent()

            assertEquals(1, report.failed)
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertTrue(
                h.ledger
                    .allNights()
                    .single()
                    .statusReason
                    .orEmpty()
                    .contains("first stage starts at"),
                "the gap is reported, not covered by the extended first stage",
            )
        }
}

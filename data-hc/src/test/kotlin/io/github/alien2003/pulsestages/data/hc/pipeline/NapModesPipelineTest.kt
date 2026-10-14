package io.github.alien2003.pulsestages.data.hc.pipeline

import androidx.health.connect.client.records.SleepSessionRecord
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.hc.usecase.UseCaseResult
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.SourceOutcome
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The night / nap modes in the pipeline (ALGORITHM.md section 18): the mode is decided per merged session by
 * `napMode.maxSessionMin`; the source setting `minSessionMin` stays the global minimum below which sessions are
 * ignored; a nap is written with all stages (STAGES), as sleep / wake only (SLEEP_WAKE) or not at all (SKIP), and the
 * ledger row and the notes say which mode the session was processed in.
 */
class NapModesPipelineTest {
    @TempDir
    lateinit var dir: File

    /** Naps are estimated from 30 min (the source setting), the nap threshold is the default 180 min. */
    private suspend fun PipelineHarness.withNaps(policy: NapOutputPolicy = NapOutputPolicy.STAGES) {
        settings.set { s ->
            s.copy(sources = s.sources.copy(minSessionMin = 30), algo = s.algo.copy(napMode = s.algo.napMode.copy(outputPolicy = policy)))
        }
    }

    /** A night (8 h) two days ago and a 90-min nap yesterday afternoon; returns (night, nap). */
    private fun PipelineHarness.nightAndNap(napMinutes: Long = 90): Pair<HcSleepSession, HcSleepSession> {
        val night = repository.addNight(OHEALTH, nightStart(2))
        val nap = repository.addNight(OHEALTH, nightStart(1) + 16 * HOUR_MS, durationMs = napMinutes * MINUTE_MS)
        return night to nap
    }

    private fun PipelineHarness.row(session: HcSleepSession): EstimatedNightEntity =
        ledger.allNights().single { it.sessionStartMs == session.startMs }

    private fun PipelineHarness.output(session: HcSleepSession): HcSleepSession? =
        repository.ownSessions().singleOrNull { it.startMs == session.startMs }

    private fun notesMode(own: HcSleepSession): String =
        Json
            .parseToJsonElement(checkNotNull(own.notes))
            .jsonObject
            .getValue("mode")
            .jsonPrimitive.content

    @Test
    fun `by default a nap is written with every stage and recorded as a nap, the night as a night`() =
        harnessTest(dir) { h ->
            h.withNaps()
            val (night, nap) = h.nightAndNap()

            val report = h.processRecent()

            assertEquals(2, report.estimated, "$report")
            assertEquals(SessionMode.NIGHT, h.row(night).mode)
            assertEquals(SessionMode.NAP, h.row(nap).mode)
            assertFalse(h.row(nap).sleepWakeOnly)
            val napOutput = checkNotNull(h.output(nap))
            assertValidStages(napOutput)
            assertTrue(napOutput.stages.any { it.type == SleepSessionRecord.STAGE_TYPE_DEEP }, "${napOutput.stages}")
            assertEquals("NAP", notesMode(napOutput))
            assertEquals("NIGHT", notesMode(checkNotNull(h.output(night))))
            assertTrue(h.logger.lines.any { "wrote HYBRID nap" in it.message }, "the log says it was a nap")
        }

    @Test
    fun `SLEEP_WAKE writes a nap with AWAKE and SLEEPING only, the ledger keeps the estimated stages`() =
        harnessTest(dir) { h ->
            h.withNaps(NapOutputPolicy.SLEEP_WAKE)
            val (night, nap) = h.nightAndNap()
            // The fake's REM bouts of the nap become awakenings, so that both written types occur.
            h.estimator.stagesOverride = { input, computed ->
                if (input.sessionStartMs !=
                    nap.startMs
                ) {
                    computed
                } else {
                    computed.map { if (it.stage == SleepStage.REM) it.copy(stage = SleepStage.AWAKE) else it }
                }
            }

            h.processRecent()

            val napOutput = checkNotNull(h.output(nap))
            val types = napOutput.stages.map { it.type }.toSet()
            assertEquals(setOf(SleepSessionRecord.STAGE_TYPE_SLEEPING, SleepSessionRecord.STAGE_TYPE_AWAKE), types)
            assertEquals(napOutput.startMs, napOutput.stages.first().startMs)
            assertEquals(napOutput.endMs, napOutput.stages.last().endMs)
            napOutput.stages.zipWithNext().forEach { (a, b) ->
                assertEquals(a.endMs, b.startMs)
                assertTrue(a.type != b.type, "adjacent equal types are merged: $a $b")
            }
            val row = h.row(nap)
            assertTrue(row.sleepWakeOnly)
            assertEquals(SessionMode.NAP, row.mode)
            assertTrue(row.stages.any { it.stage == SleepStage.DEEP }, "the row keeps the estimated stages: ${row.stages}")
            assertTrue(row.deepMin > 0.0 && row.awakeMin > 0.0)
            // Nights are always written with every stage.
            val nightOutput = checkNotNull(h.output(night))
            assertTrue(nightOutput.stages.none { it.type == SleepSessionRecord.STAGE_TYPE_SLEEPING })
            assertFalse(h.row(night).sleepWakeOnly)
        }

    @Test
    fun `SKIP writes nothing for a nap, reads nothing for it and records why`() =
        harnessTest(dir) { h ->
            h.withNaps(NapOutputPolicy.SKIP)
            val (night, nap) = h.nightAndNap()

            val report = h.processRecent()

            assertEquals(1, report.estimated, "$report")
            assertEquals(1, report.notEstimated, "$report")
            assertNull(h.output(nap))
            val row = h.row(nap)
            assertEquals(NightStatus.NOT_ESTIMATED, row.status)
            assertEquals(SessionMode.NAP, row.mode)
            assertTrue(checkNotNull(row.statusReason).startsWith("${NotEstimatedReason.NAP_SKIPPED}:"), row.statusReason)
            assertTrue(h.estimator.inputs.none { it.sessionStartMs == nap.startMs }, "no input is read for a skipped nap")
            assertEquals(
                SourceOutcome.NOT_ESTIMATED,
                h.ledger
                    .allSources()
                    .single { it.uuid == nap.uuid }
                    .outcome,
            )
            assertEquals(NightStatus.WRITTEN, h.row(night).status)
        }

    @Test
    fun `switching to SKIP and recomputing removes an earlier nap output`() =
        harnessTest(dir) { h ->
            h.withNaps(NapOutputPolicy.STAGES)
            val (night, nap) = h.nightAndNap()
            h.processRecent()
            assertTrue(h.output(nap) != null)

            h.withNaps(NapOutputPolicy.SKIP)
            h.processRecent(PipelineTrigger.RECOMPUTE)

            assertNull(h.output(nap), "the nap output is deleted")
            assertEquals(NightStatus.NOT_ESTIMATED, h.row(nap).status)
            assertNull(h.row(nap).hcRecordId)
            assertTrue(h.output(night) != null, "the night stays")
            assertEquals(NightStatus.WRITTEN, h.row(night).status)
        }

    @Test
    fun `sessions below the source minimum are ignored, the nap threshold decides night or nap`() =
        harnessTest(dir) { h ->
            h.withNaps()
            val tooShort = h.repository.addNight(OHEALTH, nightStart(3) + 14 * HOUR_MS, durationMs = 20 * MINUTE_MS)
            val justNap = h.repository.addNight(OHEALTH, nightStart(2) + 14 * HOUR_MS, durationMs = 179 * MINUTE_MS)
            val justNight = h.repository.addNight(OHEALTH, nightStart(1) + 14 * HOUR_MS, durationMs = 180 * MINUTE_MS)

            h.processRecent()

            assertEquals(
                SourceOutcome.NAP,
                h.ledger
                    .allSources()
                    .single { it.uuid == tooShort.uuid }
                    .outcome,
                "ignored",
            )
            assertTrue(h.ledger.allNights().none { it.sessionStartMs == tooShort.startMs })
            assertEquals(SessionMode.NAP, h.row(justNap).mode)
            assertEquals(SessionMode.NIGHT, h.row(justNight).mode)
        }

    @Test
    fun `with napMode maxSessionMin 0 every session is a night`() =
        harnessTest(dir) { h ->
            h.withNaps(NapOutputPolicy.SKIP)
            h.settings.set { it.copy(algo = it.algo.copy(napMode = it.algo.napMode.copy(maxSessionMin = 0.0))) }
            val (_, nap) = h.nightAndNap(napMinutes = 45)

            h.processRecent()

            assertEquals(SessionMode.NIGHT, h.row(nap).mode)
            assertEquals(NightStatus.WRITTEN, h.row(nap).status)
        }

    @Test
    fun `home counters count written nights and naps apart`() =
        harnessTest(dir) { h ->
            h.withNaps()
            h.nightAndNap()
            h.processRecent()

            val counters =
                h.ledger.sourceDao
                    .observeHomeCounters(1, "any")
                    .first()

            assertEquals(1, counters.nights)
            assertEquals(1, counters.naps)
        }

    @Test
    fun `the night detail re-estimates a nap as a nap`() =
        harnessTest(dir) { h ->
            h.withNaps()
            val (night, nap) = h.nightAndNap()
            h.processRecent()

            val napInspection = h.inspector.inspect(h.row(nap).clientRecordId)
            val nightInspection = h.inspector.inspect(h.row(night).clientRecordId)

            val napValue = (napInspection as UseCaseResult.Success).value
            assertEquals(SessionMode.NAP, napValue.mode)
            assertEquals(SessionMode.NAP, napValue.estimate?.mode)
            val nightValue = (nightInspection as UseCaseResult.Success).value
            assertEquals(SessionMode.NIGHT, nightValue.mode)
        }
}

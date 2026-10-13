package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.data.hc.EstimatedSessionWrite
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OWN
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.SourceOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** One of our records as a previous install would have left it. */
fun ownWrite(
    clientRecordId: String,
    startMs: Long,
    endMs: Long,
    notes: String,
) = EstimatedSessionWrite(clientRecordId, 1000, startMs, endMs, 0, 0, listOf(HcStage(startMs, endMs, 4)), "old", notes)

/** The pipeline keeps one output per night when earlier work was lost or the sources changed under it. */
class NightPipelineRepairTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `reinstall - our record of the night under another clientRecordId is deleted when the merged night is written`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            val first = h.repository.addNight(OHEALTH, start, durationMs = 4 * HOUR_MS)
            val oldCid = NightKeys.clientRecordId(listOf(first.uuid))
            val notes = SessionText.notes(EstimatorType.HYBRID, 1, "old", 1.0, 0.5, listOf(first.uuid), 120, calibrated = false)
            h.repository.preloadOwnSession(ownWrite(oldCid, first.startMs, first.endMs, notes))
            // Another night of ours that only touches this one (less than half of the shorter overlaps) stays.
            val neighbour = ownWrite("ps:neighbour", start + 6 * HOUR_MS, start + 14 * HOUR_MS, "{}")
            h.repository.preloadOwnSession(neighbour)
            val second = h.repository.addNight(OHEALTH, start + 4 * HOUR_MS + 15 * MINUTE_MS, durationMs = 3 * HOUR_MS)

            val report = h.processRecent()

            assertEquals(1, report.estimated)
            val mergedCid = NightKeys.clientRecordId(listOf(first.uuid, second.uuid))
            assertEquals(
                setOf(mergedCid, "ps:neighbour"),
                h.repository
                    .ownSessions()
                    .map { it.clientRecordId }
                    .toSet(),
            )
            assertEquals(1, h.processRecent().upToDate, "a second run changes nothing")
            assertEquals(2, h.repository.ownSessions().size)
        }

    @Test
    fun `reinstall - an older record that overlaps the night mostly is deleted even without notes`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.preloadOwnSession(ownWrite("ps:other-settings", source.startMs + 30 * MINUTE_MS, source.endMs, "{}"))

            h.processRecent()

            assertEquals(listOf(NightKeys.clientRecordId(listOf(source.uuid))), h.repository.ownSessions().map { it.clientRecordId })
        }

    @Test
    fun `a night whose supersede failed loses the older output when it is processed again`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addNight(OHEALTH, start, durationMs = 4 * HOUR_MS)
            h.processRecent()
            h.repository.addNight(OHEALTH, start + 4 * HOUR_MS + 15 * MINUTE_MS, durationMs = 3 * HOUR_MS)
            h.repository.failOn(Operation.DELETE, HealthDataException.Transient("binder died"))
            assertEquals(1, h.processRecent().failed)
            assertEquals(2, h.repository.ownSessions().size)

            val again = h.processRecent(PipelineTrigger.BACKFILL)

            assertEquals(1, again.upToDate)
            assertEquals(1, again.superseded)
            assertEquals(1, h.repository.ownSessions().size)
            assertEquals(listOf(NightStatus.SUPERSEDED, NightStatus.WRITTEN), h.ledger.allNights().map { it.status })
        }

    @Test
    fun `a source that later adds its own stages retires our estimate`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(1))
            h.processRecent()
            h.clock.advanceBy(MINUTE_MS)
            h.repository.updateSleepSession(source.uuid) { it.copy(stages = listOf(HcStage(it.startMs, it.startMs + HOUR_MS, 5))) }

            val report = h.processRecent()

            assertEquals(1, report.superseded)
            assertTrue(h.repository.ownSessions().isEmpty(), "Health Connect must not hold two staged sessions for one night")
            assertEquals(
                NightStatus.SUPERSEDED,
                h.ledger
                    .allNights()
                    .single()
                    .status,
            )
            assertEquals(
                SourceOutcome.HAS_STAGES,
                h.ledger
                    .allSources()
                    .single()
                    .outcome,
            )
        }

    @Test
    fun `missing zone offsets keep the offset first used - a later zone change rewrites nothing`() =
        harnessTest(dir) { h ->
            var zone = 10_800
            val reader = NightWindowReader(h.repository, NightCandidateBuilder(OWN) { zone }, OWN)
            h.repository.addNight(OHEALTH, nightStart(1), startZoneOffsetSec = null, endZoneOffsetSec = null)

            suspend fun process(trigger: PipelineTrigger): PipelineReport {
                val nights = reader.nightsInWindow(NOON - 30 * DAY_MS, NOON, h.settings.current)
                return h.pipeline.process(nights, h.settings.current, trigger, emptySet())
            }

            assertEquals(1, process(PipelineTrigger.BACKFILL).estimated)
            zone = -18_000
            assertEquals(1, process(PipelineTrigger.BACKFILL).upToDate, "a guessed offset is not a change of the source")

            assertEquals(1, process(PipelineTrigger.RECOMPUTE).estimated)
            val own = h.repository.ownSessions().single()
            assertEquals(10_800, own.startZoneOffsetSec, "a recompute keeps the night's historical offset")
            assertEquals(10_800, own.endZoneOffsetSec)
            assertEquals(1001L, own.clientRecordVersion)
        }
}

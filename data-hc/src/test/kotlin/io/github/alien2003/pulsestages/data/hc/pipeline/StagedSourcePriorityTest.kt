package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.SourceOutcome
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * "If two sources recorded one night, take the one higher in the priority order", also when the higher-priority one
 * recorded the night with its own sleep stages (which PulseStages never estimates): through the Changes API, from the
 * first sync on, and when the staged session is deleted again.
 */
class StagedSourcePriorityTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a staged session of a higher-priority source replaces our estimate of the night, and its deletion brings it back`() =
        harnessTest(dir) { h ->
            h.settings.set {
                it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OTHER_APP, OHEALTH)))
            }
            val start = nightStart(1)
            val oh = h.repository.addNight(OHEALTH, start)
            h.sync.run(inBackground = false)
            val estimate = h.ledger.allNights().single()
            assertEquals(NightStatus.WRITTEN, estimate.status)

            // Later, the preferred app syncs its own night with real stages.
            val staged =
                h.repository.addNight(
                    OTHER_APP,
                    start + 10 * MINUTE_MS,
                    durationMs = 8 * HOUR_MS,
                    stages = listOf(HcStage(start + 10 * MINUTE_MS, start + 8 * HOUR_MS + 10 * MINUTE_MS, 4)),
                )
            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.pipeline.superseded)
            assertTrue(h.repository.ownSessions().isEmpty(), "one staged session per night: the preferred app's")
            assertEquals(
                NightStatus.SUPERSEDED,
                h.ledger
                    .allNights()
                    .single()
                    .status,
            )
            val rows = h.ledger.allSources().associateBy { it.uuid }
            assertEquals(SourceOutcome.LOWER_PRIORITY_DUPLICATE, rows.getValue(oh.uuid).outcome)
            assertEquals(SourceOutcome.HAS_STAGES, rows.getValue(staged.uuid).outcome)

            h.repository.deleteSleepSession(staged.uuid)
            h.sync.run(inBackground = false)

            assertEquals(listOf(estimate.clientRecordId), h.repository.ownSessions().map { it.clientRecordId })
            assertEquals(
                NightStatus.WRITTEN,
                h.ledger
                    .allNights()
                    .single()
                    .status,
            )
        }

    @Test
    fun `a night first seen together with a staged session of a higher-priority source is not estimated, NEVER keeps old outputs`() =
        harnessTest(dir) { h ->
            h.settings.set {
                it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OTHER_APP, OHEALTH)))
            }
            val start = nightStart(2)
            h.repository.addNight(OHEALTH, start)
            h.repository.addNight(OTHER_APP, start, stages = listOf(HcStage(start, start + 8 * HOUR_MS, 5)))
            val first = h.sync.run(inBackground = false)
            assertEquals(0, first.pipeline.estimated)
            assertTrue(h.repository.ownSessions().isEmpty())
            assertTrue(h.ledger.allNights().isEmpty())

            val later = nightStart(1)
            h.repository.addNight(OHEALTH, later)
            h.sync.run(inBackground = false)
            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.NEVER)) }
            h.repository.addNight(OTHER_APP, later, stages = listOf(HcStage(later, later + 8 * HOUR_MS, 5)))
            h.sync.run(inBackground = false)
            assertEquals(1, h.repository.ownSessions().size, "NEVER does not touch existing outputs")
        }
}

package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MIDNIGHT
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A source session extended in place, through the Changes API ([NewNightsSync]): OHealth publishes a provisional
 * session while the user still sleeps and later updates the same session (same uuid) with a later end. The night keeps
 * its clientRecordId, and its record is estimated again and replaced in place as a new write, not as a refresh.
 */
class SourceExtendedInPlaceTest {
    @TempDir
    lateinit var dir: File

    /** As on 19 Oct: the provisional session is cut at 07:29, and by 08:10 the same session ends at the wake, 08:03. */
    @Test
    fun `a source session extended in place is estimated again into the same record and counted as written`() =
        harnessTest(dir, now = MIDNIGHT + 7 * HOUR_MS + 55 * MINUTE_MS) { h ->
            val start = nightStart(0)
            val source = h.repository.addNight(OHEALTH, start, durationMs = 8 * HOUR_MS + 29 * MINUTE_MS, withHeartRate = false)
            h.repository.addHeartRate(OHEALTH, start - 30 * MINUTE_MS, source.endMs)
            h.sync.run(inBackground = false)
            val provisional = h.singleRecord()
            assertEquals(1000L, provisional.clientRecordVersion)

            h.clock.advanceBy(15 * MINUTE_MS)
            val wakeMs = source.endMs + 34 * MINUTE_MS
            h.repository.addHeartRate(OHEALTH, source.endMs, wakeMs)
            h.repository.updateSleepSession(source.uuid) { it.copy(endMs = wakeMs) }
            val writes = WriteCount()
            val report = h.sync.run(inBackground = false, writes = writes)

            assertEquals(1, report.pipeline.estimated)
            assertEquals(0, report.pipeline.refreshed)
            assertEquals(0, report.pipeline.superseded, "no other night: the record is replaced in place")
            assertEquals(1, writes.written, "a changed source is estimated again, which counts as a write")
            assertEquals(0, writes.rewritten)
            val record = h.singleRecord()
            assertEquals(NightKeys.clientRecordId(listOf(source.uuid)), record.clientRecordId)
            assertEquals(provisional.uuid, record.uuid)
            assertEquals(wakeMs, record.endMs)
            assertEquals(1001L, record.clientRecordVersion)
            val night = h.singleNight()
            assertEquals(NightStatus.WRITTEN, night.status)
            assertEquals(1, night.recomputeCount)
            assertEquals(1001L, night.clientRecordVersion)
            assertEquals(wakeMs, night.sessionEndMs)
            h.assertLedgerMatchesHealthConnect()
        }
}

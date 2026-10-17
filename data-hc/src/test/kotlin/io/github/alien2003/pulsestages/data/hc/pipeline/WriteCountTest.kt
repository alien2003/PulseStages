package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** [WriteCount]: a session is counted when Health Connect accepted its insert, not when its night's step ends. */
class WriteCountTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a session is counted as written at its insert, also when superseding the older output then stops the run`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addNight(OHEALTH, start, durationMs = 4 * HOUR_MS)
            h.processRecent()
            h.repository.addNight(OHEALTH, start + 4 * HOUR_MS + 20 * MINUTE_MS, durationMs = 3 * HOUR_MS + 40 * MINUTE_MS)
            h.repository.failOn(Operation.DELETE, HealthDataException.QuotaExceeded("quota"))

            val writes = WriteCount()
            val now = h.clock.millis()
            val current = h.settings.current
            val report =
                h.pipeline.process(
                    h.windowReader.nightsInWindow(now - 30 * DAY_MS, now, current),
                    current,
                    PipelineTrigger.NEW_NIGHTS,
                    h.repository.granted.toSet(),
                    writes = writes,
                )

            assertTrue(report.quotaHit, "the delete of the older output stopped the run")
            assertEquals(0, report.estimated, "the step did not end as WRITTEN")
            assertEquals(1, writes.written, "the merged night is in Health Connect, so it is counted")
            assertEquals(0, writes.rewritten)
            assertEquals(2, h.repository.ownSessions().size, "the older output is still there")
        }
}

package io.github.alien2003.pulsestages.ui.nights

import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.testutil.MainDispatcherExtension
import io.github.alien2003.pulsestages.testutil.harnessTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NightLabelsTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    @TempDir
    lateinit var dir: File

    @Test
    fun `the reason of a night the pipeline could not estimate is shown localized, not as the ledger's English text`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1), withHeartRate = false)
            h.sync.run(inBackground = false)
            val night = h.ledger.allNights().single()
            assertEquals(NightStatus.NOT_ESTIMATED, night.status)
            assertNotNull(night.statusReason, "the ledger keeps its English diagnostic")

            assertEquals(NotEstimatedReason.NO_HR_DATA, notEstimatedReasonOf(night.statusReason))
            assertEquals(R.string.not_estimated_no_hr, statusReasonRes(night.status, night.statusReason))
        }

    @Test
    fun `every status without output has a localized explanation, a written night has none`() {
        assertNull(statusReasonRes(NightStatus.WRITTEN, "anything"))
        assertEquals(R.string.not_estimated_too_short, statusReasonRes(NightStatus.NOT_ESTIMATED, "SESSION_TOO_SHORT: 12.0 min < 30 min"))
        assertEquals(R.string.not_estimated_low_coverage, statusReasonRes(NightStatus.NOT_ESTIMATED, "LOW_COVERAGE"))
        assertEquals(R.string.night_reason_not_estimated, statusReasonRes(NightStatus.NOT_ESTIMATED, "something else: detail"))
        assertEquals(R.string.night_reason_not_estimated, statusReasonRes(NightStatus.NOT_ESTIMATED, null))
        assertEquals(R.string.night_reason_failed, statusReasonRes(NightStatus.FAILED, "IllegalStateException: boom"))
        assertEquals(R.string.night_reason_superseded, statusReasonRes(NightStatus.SUPERSEDED, null))
        assertEquals(R.string.night_reason_deleted, statusReasonRes(NightStatus.DELETED, null))
        assertEquals(R.string.night_reason_source_deleted, statusReasonRes(NightStatus.SOURCE_DELETED, "some source sessions were deleted"))
    }

    @Test
    fun `the hypnogram rows use the short stage names`() {
        assertEquals(
            listOf(R.string.stage_short_awake, R.string.stage_short_rem, R.string.stage_short_light, R.string.stage_short_deep),
            HYPNOGRAM_ROWS.map { it.shortLabelRes() },
        )
        assertEquals(R.string.stage_unknown, SleepStage.UNKNOWN.shortLabelRes())
    }
}

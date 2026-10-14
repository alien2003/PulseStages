// detekt treats only src/test as tests; this is the test source set of the debug variant (the demo code is debug-only).
@file:Suppress("FunctionNaming", "MagicNumber")

package io.github.alien2003.pulsestages.demo

import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.data.hc.fake.FakeSettingsRepository
import io.github.alien2003.pulsestages.data.hc.fake.TestClock
import io.github.alien2003.pulsestages.data.hc.pipeline.DeleteNightResult
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.usecase.CalibrationOutcome
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.hc.usecase.UseCaseResult
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.work.schedule.JobState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The demo data of debug builds: every variant shows what docs/SCREENSHOTS.md promises, all in memory. */
class DemoSourcesTest {
    private val settings = FakeSettingsRepository()
    private val noBundle = HybridModelProvider { null }

    @Test
    fun `the demo nights show every state of the Nights screen`() =
        runTest {
            val source = DemoNightsSource(settings, noBundle, StandardTestDispatcher(testScheduler), TestClock(NOON))
            val nights = source.nights().first()
            val hash = source.currentParamsHash().first()

            assertEquals(DemoData.SESSION_COUNT, nights.size)
            assertEquals(nights.sortedByDescending { it.sessionStartMs }, nights, "newest first")
            assertEquals(nights.size, nights.map { it.id }.distinct().size, "unique list keys")
            assertTrue(nights.all { it.sourcePackage == DEMO_SOURCE_PACKAGE })
            val (naps, whole) = nights.partition { it.mode == SessionMode.NAP }
            assertEquals(
                DemoNaps.LENGTHS.keys
                    .map { DemoData.clientRecordId(it, nap = true) }
                    .toSet(),
                naps.map { it.clientRecordId }.toSet(),
            )
            assertEquals(DemoData.NIGHT_COUNT, whole.size)
            assertTrue(naps.all { it.status == NightStatus.WRITTEN }, "the demo naps are estimated")
            val byDaysAgo = whole.associateBy { it.clientRecordId.removePrefix("ps:demo-").toInt() }
            assertTrue(checkNotNull(byDaysAgo[2]).elevatedHr, "elevated heart rate")
            assertTrue(checkNotNull(byDaysAgo[5]).isStale(1, hash), "stale")
            assertEquals(NightStatus.NOT_ESTIMATED, byDaysAgo[9]?.status, "not estimated")
            val gap = checkNotNull(source.night("ps:demo-3").first())
            assertTrue(gap.unknownMin > 0.0 || WarningCode.DATA_GAPS in gap.warnings, "data gap")
            val regular = checkNotNull(source.night("ps:demo-1").first())
            assertEquals(NightStatus.WRITTEN, regular.status)
            assertTrue(regular.stages.any { it.stage == SleepStage.DEEP })
            assertTrue(!regular.isStale(1, hash))
        }

    @Test
    fun `inspect, compare, export, delete and recompute work on the in-memory nights`() =
        runTest {
            val source = DemoNightsSource(settings, noBundle, StandardTestDispatcher(testScheduler), TestClock(NOON))
            val id = "ps:demo-1"

            val inspection = (source.inspect(id) as UseCaseResult.Success).value
            assertNotNull(inspection.estimate)
            assertInstanceOf(UseCaseResult.Success::class.java, source.compareWithRules(inspection))
            val export = (source.exportCsv(inspection) as UseCaseResult.Success).value
            assertTrue(export.content.startsWith("epoch,start_ms"))
            assertEquals(UseCaseResult.NotFound, source.inspect("ps:unknown"))

            assertEquals(DeleteNightResult.DELETED, source.deleteNight(id))
            assertEquals(NightStatus.DELETED, source.night(id).first()?.status)
            assertEquals(DeleteNightResult.NO_OUTPUT, source.deleteNight(id))

            source.recomputeNight(id)
            val status = source.recomputeStatus(id).first()
            assertEquals(JobState.SUCCEEDED, status.state)
            assertEquals(1, status.lastOutput?.summary?.estimated)
            val recomputed = checkNotNull(source.night(id).first())
            assertEquals(NightStatus.WRITTEN, recomputed.status)
            assertEquals(1, recomputed.recomputeCount)
        }

    @Test
    fun `the demo references are scored and calibrated in memory`() =
        runTest {
            val source = DemoReferenceSource(settings, noBundle, StandardTestDispatcher(testScheduler), TestClock(NOON))
            val search = (source.findReferenceNights(0, NOON) as UseCaseResult.Success).value

            assertEquals(DemoData.REFERENCE_SESSION_COUNT, search.nights.size)
            assertTrue(search.nights.all { it.session.packageName == DEMO_REFERENCE_PACKAGE && it.truth.isNotEmpty() })
            val report = source.compare(search.nights)
            assertEquals(DemoData.REFERENCE_SESSION_COUNT, report.scoredNights)
            assertNotNull(report.kappa)
            assertTrue(report.mixesModes, "the short reference session is compared as a nap")
            assertEquals(1, report.forMode(SessionMode.NAP).scoredNights)
            assertNull(source.activeModel().first())

            assertInstanceOf(CalibrationOutcome.Calibrated::class.java, source.calibrate(search.nights))
            assertNotNull(source.activeModel().first())
            source.resetToGlobal()
            assertNull(source.activeModel().first())
        }
}

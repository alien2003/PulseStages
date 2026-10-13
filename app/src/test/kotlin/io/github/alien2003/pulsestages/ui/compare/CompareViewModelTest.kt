package io.github.alien2003.pulsestages.ui.compare

import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.metrics.ConfusionMatrix
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.personal.CalibrationResult
import io.github.alien2003.pulsestages.algo.personal.PersonalModel
import io.github.alien2003.pulsestages.algo.personal.PersonalProvenance
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.hc.usecase.CalibrationOutcome
import io.github.alien2003.pulsestages.data.local.db.PersonalModelKind
import io.github.alien2003.pulsestages.source.HealthConnectReferenceSource
import io.github.alien2003.pulsestages.testutil.FakeAppInfoResolver
import io.github.alien2003.pulsestages.testutil.MainDispatcherExtension
import io.github.alien2003.pulsestages.testutil.clear
import io.github.alien2003.pulsestages.testutil.harnessTest
import io.github.alien2003.pulsestages.ui.common.UiText
import io.github.alien2003.pulsestages.ui.common.formatDecimal
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File
import io.github.alien2003.pulsestages.algo.personal.PersonalModelKind as AlgoPersonalKind

class CompareViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    @TempDir
    lateinit var dir: File

    private val stageAwake = 1
    private val stageSleeping = 2
    private val stageLight = 4
    private val stageDeep = 5
    private val stageRem = 6

    private fun viewModel(h: PipelineHarness) =
        CompareViewModel(
            HealthConnectReferenceSource(h.referenceComparison, h.personalization, h.homeStatus, h.settings),
            FakeAppInfoResolver(mapOf(OTHER_APP to "Other tracker")),
            h.clock,
            h.logger,
        )

    private suspend fun CompareViewModel.awaitState(predicate: (CompareUiState) -> Boolean): CompareUiState = state.first(predicate)

    private suspend fun CompareViewModel.awaitReady(): CompareUiState =
        awaitState { it.phase == ComparePhase.READY || it.phase == ComparePhase.ERROR }

    /** Stages that follow the harness's 90-minute heart-rate pattern (what a perfect reference would say). */
    private fun cyclingStages(start: Long): List<HcStage> {
        val pattern = listOf(15 to stageLight, 25 to stageDeep, 25 to stageLight, 20 to stageRem, 5 to stageAwake)
        val stages = mutableListOf<HcStage>()
        var t = start
        val end = start + 8 * HOUR_MS
        while (t < end) {
            for ((minutes, type) in pattern) {
                val to = minOf(t + minutes * MINUTE_MS, end)
                if (t < to) stages += HcStage(t, to, type)
                t = to
            }
        }
        return stages
    }

    private fun PipelineHarness.addReferenceNights(count: Int) {
        (1..count).forEach { daysAgo -> repository.addNight(OTHER_APP, nightStart(daysAgo), stages = cyclingStages(nightStart(daysAgo))) }
    }

    @Test
    fun `reference nights are scored with pooled metrics, per stage, matrix and per night`() =
        harnessTest(main, dir) { h ->
            h.addReferenceNights(3)
            val vm = viewModel(h)

            val state = vm.awaitReady()

            assertEquals(ComparePhase.READY, state.phase)
            assertFalse(state.empty)
            assertEquals(3, state.referenceCount)
            assertFalse(state.historyLimited)
            val metrics = checkNotNull(state.metrics)
            assertEquals(3, metrics.scoredNights)
            assertTrue(checkNotNull(metrics.kappa) > 0.5, "the fake estimator follows the same pattern")
            assertEquals(SleepStage.SCORED, metrics.stages.map { it.stage })
            assertEquals(SleepStage.SCORED, metrics.matrix.map { it.truth })
            metrics.matrix.filter { it.total > 0 }.forEach { row -> assertEquals(1.0, row.shares.sum(), 1e-9) }
            assertEquals(listOf(nightStart(1), nightStart(2), nightStart(3)), state.nights.map { it.startMs }, "newest first")
            state.nights.forEach { night ->
                assertEquals(OTHER_APP, night.packageName)
                assertEquals("Other tracker", night.label)
                assertNotNull(night.kappa)
                assertNotNull(night.estimator)
            }
            assertTrue(state.canCalibrate)
            assertNull(state.model)
            clear(vm)
        }

    @Test
    fun `with the history permission a year or the whole history can be searched`() =
        harnessTest(main, dir) { h ->
            listOf(1, 200, 500).forEach { daysAgo ->
                h.repository.addNight(OTHER_APP, nightStart(daysAgo), stages = cyclingStages(nightStart(daysAgo)))
            }
            val vm = viewModel(h)
            val recent = vm.awaitReady()
            assertEquals(CompareRange.RECENT, recent.range)
            assertEquals(1, recent.referenceCount, "the last 90 days by default")

            vm.selectRange(CompareRange.YEAR)
            assertEquals(2, vm.awaitState { it.range == CompareRange.YEAR && it.phase == ComparePhase.READY }.referenceCount)

            vm.selectRange(CompareRange.ALL)
            val all = vm.awaitState { it.range == CompareRange.ALL && it.phase == ComparePhase.READY }
            assertEquals(3, all.referenceCount)
            assertEquals(listOf(nightStart(1), nightStart(200), nightStart(500)), all.nights.map { it.startMs })
            assertTrue(all.canCalibrate, "every reference night found can be used for the calibration")
            clear(vm)
        }

    @Test
    fun `without the history permission only the last 90 days are offered`() =
        harnessTest(main, dir) { h ->
            h.addReferenceNights(1)
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_HISTORY)
            val vm = viewModel(h)
            val state = vm.awaitReady()
            assertTrue(state.historyLimited)
            assertFalse(state.canSelect(CompareRange.ALL))

            vm.selectRange(CompareRange.ALL)

            assertEquals(CompareRange.RECENT, vm.state.value.range)
            assertEquals(ComparePhase.READY, vm.state.value.phase, "nothing was searched again")
            clear(vm)
        }

    @Test
    fun `without reference nights the empty state counts what was skipped`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(
                OTHER_APP,
                nightStart(1),
                stages = listOf(HcStage(nightStart(1), nightStart(1) + 8 * HOUR_MS, stageSleeping)),
            )
            h.repository.addNight(
                OTHER_APP,
                nightStart(2),
                stages =
                    listOf(
                        HcStage(nightStart(2), nightStart(2) + HOUR_MS, stageAwake),
                        HcStage(nightStart(2) + HOUR_MS, nightStart(2) + 8 * HOUR_MS, stageSleeping),
                    ),
            )
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_HISTORY)
            val vm = viewModel(h)

            val state = vm.awaitReady()

            assertTrue(state.empty)
            assertEquals(1, state.withoutSleepStages, "awake / asleep only")
            assertTrue(state.historyLimited)
            assertNull(state.metrics)
            assertFalse(state.canCalibrate)
            clear(vm)
        }

    @Test
    fun `Health Connect problems are errors with a reason`() =
        harnessTest(main, dir) { h ->
            h.repository.availability = HcAvailability.NOT_SUPPORTED
            val vm = viewModel(h)
            val state = vm.awaitReady()
            assertEquals(ComparePhase.ERROR, state.phase)
            assertEquals(UiText.of(R.string.error_hc_unavailable), state.error)

            h.repository.availability = HcAvailability.AVAILABLE
            h.repository.revoke(HealthPermissions.READ_SLEEP)
            vm.refresh()
            val blocked = vm.awaitState { it.phase == ComparePhase.ERROR && it.error != state.error }
            assertEquals(UiText.of(R.string.result_blocked_permission), blocked.error)
            clear(vm)
        }

    @Test
    fun `calibrate stores a personal model, reports kappa before and after and scores again`() =
        harnessTest(main, dir) { h ->
            h.addReferenceNights(4)
            val vm = viewModel(h)
            vm.awaitReady()

            vm.calibrate()
            val state = vm.awaitState { it.busy == null && it.model != null && it.messages.isNotEmpty() }

            assertEquals(PersonalModelKind.CALIBRATED, state.model?.kind)
            assertEquals(PersonalModelKind.CALIBRATED, h.modelStore.active()?.kind)
            assertEquals(1, state.messages.size)
            assertNotNull(state.metrics, "scored again")
            clear(vm)
        }

    @Test
    fun `reset needs a confirmation and goes back to the global model`() =
        harnessTest(main, dir) { h ->
            h.addReferenceNights(4)
            val vm = viewModel(h)
            vm.awaitReady()
            vm.calibrate()
            vm.awaitState { it.model != null && it.busy == null }

            vm.requestReset()
            assertTrue(vm.state.value.confirmReset)
            vm.dismissReset()
            assertFalse(vm.state.value.confirmReset)
            assertNotNull(h.modelStore.active(), "nothing reset without confirmation")

            vm.requestReset()
            vm.confirmReset()
            val state = vm.awaitState { it.model == null && it.busy == null }

            assertNull(h.modelStore.active())
            assertEquals(UiText.of(R.string.compare_reset_done), state.messages.last().text)
            clear(vm)
        }

    @Test
    fun `calibration is not offered while personalization is off`() =
        harnessTest(main, dir) { h ->
            h.addReferenceNights(2)
            h.settings.set { it.copy(algo = it.algo.copy(personal = it.algo.personal.copy(enabled = false))) }
            val vm = viewModel(h)
            vm.awaitReady()
            val state = vm.awaitState { !it.personalizationEnabled }

            assertFalse(state.canCalibrate)
            vm.calibrate()
            assertNull(h.modelStore.active())
            clear(vm)
        }

    @Test
    fun `metrics map a confusion matrix into per-stage scores and row shares`() {
        val counts = Array(SleepStage.COUNT) { LongArray(SleepStage.COUNT) }
        counts[SleepStage.LIGHT.index][SleepStage.LIGHT.index] = 6
        counts[SleepStage.LIGHT.index][SleepStage.DEEP.index] = 2
        counts[SleepStage.DEEP.index][SleepStage.DEEP.index] = 2
        val matrix = ConfusionMatrix(counts)

        val stages = CompareMetrics.stagesOf(matrix).associateBy { it.stage }
        val rows = CompareMetrics.rowsOf(matrix).associateBy { it.truth }

        assertEquals(0.75, stages.getValue(SleepStage.LIGHT).recall, 1e-9)
        assertEquals(1.0, stages.getValue(SleepStage.LIGHT).precision, 1e-9)
        assertEquals(0.5, stages.getValue(SleepStage.DEEP).precision, 1e-9)
        assertEquals(1.0, stages.getValue(SleepStage.DEEP).recall, 1e-9)
        assertEquals(8L, stages.getValue(SleepStage.LIGHT).support)
        assertEquals(listOf(0L, 6L, 2L, 0L), rows.getValue(SleepStage.LIGHT).counts)
        assertEquals(listOf(0.0, 0.75, 0.25, 0.0), rows.getValue(SleepStage.LIGHT).shares)
        assertEquals(0L, rows.getValue(SleepStage.REM).total)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), rows.getValue(SleepStage.REM).shares, "empty rows have no shares")
    }

    @Test
    fun `calibration outcomes are worded`() {
        val model = PersonalModel(provenance = PersonalProvenance(AlgoPersonalKind.CALIBRATED, nightsUsed = 3))
        val measured = CalibrationResult(model, trainingNights = 3, heldOutNights = 1, kappaBefore = 0.31, kappaAfter = 0.42, true, true)
        assertEquals(
            UiText.of(
                R.string.compare_calibrated,
                UiText.plural(R.plurals.compare_calibrated_nights, 3),
                formatDecimal(0.31, 2),
                formatDecimal(0.42, 2),
                UiText.plural(R.plurals.compare_held_out, 1),
            ),
            calibrationText(CalibrationOutcome.Calibrated(1, measured)),
        )
        val unmeasured = measured.copy(trainingNights = 1, heldOutNights = 0, kappaBefore = null, kappaAfter = null)
        assertEquals(
            UiText.of(R.string.compare_calibrated_unmeasured, UiText.plural(R.plurals.compare_calibrated_nights, 1)),
            calibrationText(CalibrationOutcome.Calibrated(1, unmeasured)),
        )
        assertEquals(UiText.of(R.string.compare_not_calibrated), calibrationText(CalibrationOutcome.NotCalibrated("none")))
        assertEquals(UiText.of(R.string.compare_personalization_off), calibrationText(CalibrationOutcome.PersonalizationDisabled))
    }
}

package io.github.alien2003.pulsestages.ui.nights

import androidx.lifecycle.SavedStateHandle
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeNightEstimator
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.hc.usecase.NightInspection
import io.github.alien2003.pulsestages.data.hc.usecase.UseCaseResult
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.source.LedgerNightsSource
import io.github.alien2003.pulsestages.source.NightsSource
import io.github.alien2003.pulsestages.testutil.FakeDocumentStore
import io.github.alien2003.pulsestages.testutil.FakeWorkScheduler
import io.github.alien2003.pulsestages.testutil.MainDispatcherExtension
import io.github.alien2003.pulsestages.testutil.clear
import io.github.alien2003.pulsestages.testutil.harnessTest
import io.github.alien2003.pulsestages.ui.common.JobPhase
import io.github.alien2003.pulsestages.ui.common.UiText
import io.github.alien2003.pulsestages.work.run.OutcomeKind
import io.github.alien2003.pulsestages.work.run.RunReport
import io.github.alien2003.pulsestages.work.run.RunSummary
import io.github.alien2003.pulsestages.work.schedule.JobState
import io.github.alien2003.pulsestages.work.schedule.WorkStatus
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NightDetailViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    @TempDir
    lateinit var dir: File

    private val scheduler = FakeWorkScheduler()
    private val documents = FakeDocumentStore()

    private fun source(h: PipelineHarness) =
        LedgerNightsSource(h.ledger.nightDao, h.homeStatus, h.inspector, h.eraser, scheduler, h.settings)

    /** The detail ViewModel showing [clientRecordId] in a visible pane. */
    private fun viewModel(
        h: PipelineHarness,
        clientRecordId: String,
        savedState: SavedStateHandle = SavedStateHandle(),
    ) = NightDetailViewModel(source(h), documents, h.logger, savedState).apply {
        show(clientRecordId)
        onPaneStarted()
    }

    /** One estimated night, synced into the ledger. */
    private suspend fun writtenNight(h: PipelineHarness): EstimatedNightEntity {
        h.repository.addNight(OHEALTH, nightStart(1))
        h.sync.run(inBackground = false)
        return h.ledger.allNights().single()
    }

    private suspend fun NightDetailViewModel.awaitState(predicate: (NightDetailUiState) -> Boolean): NightDetailUiState =
        state.first(predicate)

    private suspend fun NightDetailViewModel.awaitReady(): InspectionState.Ready =
        checkNotNull(awaitState { it.inspection !is InspectionState.Loading && it.load == DetailLoad.LOADED }.ready) {
            "inspection: ${state.value.inspection}"
        }

    @Test
    fun `the written night and its re-estimate are loaded`() =
        harnessTest(main, dir) { h ->
            val night = writtenNight(h)
            val vm = viewModel(h, night.clientRecordId)

            val ready = vm.awaitReady()

            val state = vm.state.value
            assertEquals(night.clientRecordId, state.night?.clientRecordId)
            assertFalse(state.stale)
            assertFalse(state.synthetic)
            assertEquals(60_000L, ready.epochMs)
            assertEquals(8 * 60, ready.estimate.perEpoch.size)
            assertEquals(ready.estimate.perEpoch.map { it.startMs }, ready.epochStarts)
            assertFalse(ready.inspection.writtenIsStale)
            clear(vm)
        }

    @Test
    fun `a tap selects the epoch under it with its posteriors, heart rate and features`() =
        harnessTest(main, dir) { h ->
            val night = writtenNight(h)
            val vm = viewModel(h, night.clientRecordId)
            val ready = vm.awaitReady()

            vm.selectAt(night.sessionStartMs + 20 * MINUTE_MS + 30_000)

            val selection = checkNotNull(vm.state.value.selection)
            val epoch = ready.estimate.perEpoch[20]
            assertEquals(20, selection.index)
            assertEquals(epoch.startMs, selection.startMs)
            assertEquals(epoch.startMs + 60_000, selection.endMs)
            assertEquals(epoch.posterior, selection.posterior)
            assertEquals(epoch.stage, selection.estimatedStage)
            assertEquals(stageAt(night.stages, epoch.startMs + 30_000), selection.writtenStage)
            assertEquals(epoch.hrRaw, selection.hrRaw)
            assertTrue(selection.hasData)
            assertEquals(listOf(FeatureValue(FakeNightEstimator.FEATURE_NAME, epoch.features.single())), selection.features)
            assertEquals(0.7, selection.posterior[selection.estimatedStage], 1e-9, "the fake's chosen stage")
            clear(vm)
        }

    @Test
    fun `the selection moves epoch by epoch, stays inside the night and can be cleared`() =
        harnessTest(main, dir) { h ->
            val night = writtenNight(h)
            val vm = viewModel(h, night.clientRecordId)
            val ready = vm.awaitReady()

            vm.moveSelection(0)
            assertEquals(
                0,
                vm.state.value.selection
                    ?.index,
                "\"Show epoch details\": the first epoch",
            )
            vm.clearSelection()
            vm.moveSelection(1)
            assertEquals(
                0,
                vm.state.value.selection
                    ?.index,
                "nothing selected: the first epoch",
            )
            vm.moveSelection(1)
            assertEquals(
                1,
                vm.state.value.selection
                    ?.index,
            )
            vm.moveSelection(-10)
            assertEquals(
                0,
                vm.state.value.selection
                    ?.index,
            )
            vm.moveSelection(100_000)
            assertEquals(
                ready.estimate.perEpoch.lastIndex,
                vm.state.value.selection
                    ?.index,
            )

            vm.selectAt(night.sessionEndMs + 60 * MINUTE_MS)
            assertEquals(
                ready.estimate.perEpoch.lastIndex,
                vm.state.value.selection
                    ?.index,
                "a tap outside keeps the selection",
            )

            vm.clearSelection()
            assertNull(vm.state.value.selection)
            clear(vm)
        }

    @Test
    fun `compare with RULES shows the second hypnogram and the agreement`() =
        harnessTest(main, dir) { h ->
            val night = writtenNight(h)
            val vm = viewModel(h, night.clientRecordId)
            vm.awaitReady()

            vm.compareWithRules()
            val comparison = vm.awaitState { it.comparison is ComparisonState.Ready }.comparison as ComparisonState.Ready

            assertEquals(EstimatorType.HYBRID, comparison.primaryEstimator)
            assertTrue(comparison.rules.isNotEmpty())
            assertTrue(comparison.primary != comparison.rules, "the fake RULES estimator disagrees on some epochs")
            val kappa = checkNotNull(comparison.kappa)
            assertTrue(kappa < 1.0)
            val accuracy = checkNotNull(comparison.accuracy)
            assertTrue(accuracy in 0.0..1.0)
            clear(vm)
        }

    @Test
    fun `export CSV asks for a destination and writes the file there`() =
        harnessTest(main, dir) { h ->
            val night = writtenNight(h)
            val vm = viewModel(h, night.clientRecordId)
            vm.awaitReady()

            vm.exportCsv()
            val export = checkNotNull(vm.state.value.pendingExport)
            assertTrue(export.fileName.startsWith("pulsestages-") && export.fileName.endsWith(".csv"))
            assertTrue(export.content.startsWith("epoch,start_ms,has_data,hr_raw,hr_smooth,${FakeNightEstimator.FEATURE_NAME}"))
            assertTrue(vm.claimExportPicker())
            assertTrue(vm.state.value.exportPickerOpen)
            assertFalse(vm.claimExportPicker(), "the picker is opened once, by one screen")

            vm.onExportDestination("content://documents/night.csv")
            val state = vm.awaitState { !it.exporting && it.messages.isNotEmpty() }

            assertNull(state.pendingExport)
            assertFalse(state.exportPickerOpen)
            assertEquals(export.content, documents.documents["content://documents/night.csv"])
            assertEquals(listOf(UiText.of(R.string.night_export_done, export.fileName)), state.messages.map { it.text })
            clear(vm)
        }

    @Test
    fun `a cancelled picker exports nothing, a failed write is reported`() =
        harnessTest(main, dir) { h ->
            val night = writtenNight(h)
            val vm = viewModel(h, night.clientRecordId)
            vm.awaitReady()

            vm.exportCsv()
            vm.onExportDestination(null)
            assertNull(vm.state.value.pendingExport)
            assertTrue(documents.documents.isEmpty())
            assertTrue(
                vm.state.value.messages
                    .isEmpty(),
            )

            documents.failWrites = true
            vm.exportCsv()
            vm.onExportDestination("content://documents/night.csv")
            val state = vm.awaitState { !it.exporting && it.messages.isNotEmpty() }
            assertEquals(listOf(UiText.of(R.string.night_export_failed)), state.messages.map { it.text })
            clear(vm)
        }

    @Test
    fun `a document picked after a process restart gets the CSV of the night, exported again`() =
        harnessTest(main, dir) { h ->
            val night = writtenNight(h)
            val savedState = SavedStateHandle()
            val before = viewModel(h, night.clientRecordId, savedState)
            before.awaitReady()
            before.exportCsv()
            val export = checkNotNull(before.state.value.pendingExport)
            assertTrue(before.claimExportPicker())
            clear(before)

            // The process died while the picker was open: a new ViewModel with the saved state gets the result first.
            val restored = NightDetailViewModel(source(h), documents, h.logger, savedState)
            restored.onExportDestination("content://documents/night.csv")
            val state = restored.awaitState { !it.exporting && it.messages.isNotEmpty() }

            assertEquals(export.content, documents.documents["content://documents/night.csv"], "the document is not left empty")
            assertEquals(listOf(UiText.of(R.string.night_export_done, export.fileName)), state.messages.map { it.text })
            clear(restored)
        }

    @Test
    fun `a picked document is never dropped silently`() =
        harnessTest(main, dir) { h ->
            val vm = NightDetailViewModel(source(h), documents, h.logger, SavedStateHandle())

            vm.onExportDestination("content://documents/night.csv")
            val state = vm.awaitState { !it.exporting && it.messages.isNotEmpty() }

            assertEquals(listOf(UiText.of(R.string.night_export_failed)), state.messages.map { it.text })
            assertTrue(documents.documents.isEmpty())
            clear(vm)
        }

    @Test
    fun `one ViewModel switches between nights and drops the previous night's state`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            val (older, newer) = h.ledger.allNights().sortedBy { it.sessionStartMs }
            val vm = viewModel(h, older.clientRecordId)
            vm.awaitReady()
            vm.moveSelection(0)
            vm.requestDelete()

            vm.show(newer.clientRecordId)
            assertEquals(newer.clientRecordId, vm.state.value.clientRecordId)
            assertNull(vm.state.value.selection)
            assertFalse(vm.state.value.confirmDelete)
            val ready = vm.awaitReady()

            assertEquals(
                newer.clientRecordId,
                vm.state.value.night
                    ?.clientRecordId,
            )
            assertEquals(newer.clientRecordId, ready.inspection.night.clientRecordId)
            vm.show(newer.clientRecordId)
            assertTrue(vm.state.value.ready === ready, "showing the same night again changes nothing")
            clear(vm)
        }

    @Test
    fun `a changed night is re-estimated only while a pane shows it`() =
        harnessTest(main, dir) { h ->
            val night = writtenNight(h)
            var inspections = 0
            val counting =
                object : NightsSource by source(h) {
                    override suspend fun inspect(clientRecordId: String): UseCaseResult<NightInspection> {
                        inspections++
                        return h.inspector.inspect(clientRecordId)
                    }
                }
            val vm = NightDetailViewModel(counting, documents, h.logger, SavedStateHandle())
            vm.show(night.clientRecordId)
            vm.onPaneStarted()
            vm.awaitReady()
            assertEquals(1, inspections)

            vm.onPaneStopped()
            h.ledger.putNight(night.copy(updatedAt = night.updatedAt + 1))
            vm.awaitState { it.night?.updatedAt == night.updatedAt + 1 }
            assertEquals(1, inspections, "the hidden pane does not read Health Connect")

            vm.onPaneStarted()
            vm.awaitReady()
            assertEquals(2, inspections, "re-estimated when the pane is visible again")
            clear(vm)
        }

    @Test
    fun `under the never-overwrite policy a written night is not offered for recompute`() =
        harnessTest(main, dir) { h ->
            val night = writtenNight(h)
            val vm = viewModel(h, night.clientRecordId)
            assertFalse(vm.awaitState { it.load == DetailLoad.LOADED }.recomputeBlockedByPolicy)

            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.NEVER)) }

            assertTrue(vm.awaitState { it.overwriteNever }.recomputeBlockedByPolicy)
            clear(vm)
        }

    @Test
    fun `delete my session needs a confirmation and deletes only our session`() =
        harnessTest(main, dir) { h ->
            val night = writtenNight(h)
            val vm = viewModel(h, night.clientRecordId)
            vm.awaitReady()

            vm.requestDelete()
            assertTrue(vm.state.value.confirmDelete)
            vm.dismissDelete()
            assertFalse(vm.state.value.confirmDelete)
            assertEquals(1, h.repository.ownSessions().size, "nothing deleted without confirmation")

            vm.requestDelete()
            vm.confirmDelete()
            val state = vm.awaitState { !it.deleting && it.messages.isNotEmpty() && it.night?.status == NightStatus.DELETED }

            assertTrue(h.repository.ownSessions().isEmpty())
            assertEquals(1, h.repository.allSessions().size, "the source session stays")
            assertEquals(listOf(UiText.of(R.string.night_delete_done)), state.messages.map { it.text })
            clear(vm)
        }

    @Test
    fun `recompute runs the night's job and reports when it finished`() =
        harnessTest(main, dir) { h ->
            val night = writtenNight(h)
            val vm = viewModel(h, night.clientRecordId)
            vm.awaitReady()
            val status = scheduler.nightStatus(night.clientRecordId)

            vm.recompute()
            scheduler.awaitCall("recomputeNight:${night.clientRecordId}")
            assertEquals(listOf("recomputeNight:${night.clientRecordId}"), scheduler.calls)
            status.value = WorkStatus(JobState.RUNNING)
            assertEquals(JobPhase.RUNNING, vm.awaitState { it.recompute.active }.recompute.phase)

            val summary = RunSummary(found = 1, estimated = 1)
            status.value = WorkStatus(JobState.SUCCEEDED, lastOutput = RunReport(OutcomeKind.COMPLETED, summary, null, null, null))
            val state = vm.awaitState { !it.recompute.active && it.messages.size == 2 }

            assertEquals(
                listOf(UiText.of(R.string.night_recompute_started), UiText.of(R.string.result_night_recomputed)),
                state.messages.map { it.text },
            )
            clear(vm)
        }

    @Test
    fun `stale nights are marked`() =
        harnessTest(main, dir) { h ->
            val night = writtenNight(h)
            h.settings.set { it.copy(write = it.write.copy(awakeAsLight = true)) }
            val vm = viewModel(h, night.clientRecordId)

            assertTrue(vm.awaitState { it.stale }.stale)
            assertTrue(vm.awaitReady().inspection.writtenIsStale)
            clear(vm)
        }

    @Test
    fun `a missing permission blocks the re-estimate, the written night is still shown`() =
        harnessTest(main, dir) { h ->
            val night = writtenNight(h)
            h.repository.revoke(HealthPermissions.READ_HEART_RATE)
            val vm = viewModel(h, night.clientRecordId)

            val state = vm.awaitState { it.inspection !is InspectionState.Loading }

            assertEquals(DetailLoad.LOADED, state.load)
            assertEquals(InspectionState.Blocked(BlockedReason.PERMISSION_MISSING), state.inspection)
            assertNull(state.ready)
            vm.compareWithRules()
            assertEquals(ComparisonState.None, vm.state.value.comparison, "nothing to compare")
            clear(vm)
        }

    @Test
    fun `a night that is not estimable now says why`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1), withHeartRate = false)
            h.sync.run(inBackground = false)
            val night = h.ledger.allNights().single()
            val vm = viewModel(h, night.clientRecordId)

            val inspection = vm.awaitState { it.inspection !is InspectionState.Loading }.inspection

            assertInstanceOf(InspectionState.NotEstimated::class.java, inspection)
            assertNotNull((inspection as InspectionState.NotEstimated).inspection)
            clear(vm)
        }

    @Test
    fun `an unknown night is not found`() =
        harnessTest(main, dir) { h ->
            val vm = viewModel(h, "ps:unknown")
            assertEquals(DetailLoad.NOT_FOUND, vm.awaitState { it.load != DetailLoad.LOADING }.load)
            clear(vm)
        }
}

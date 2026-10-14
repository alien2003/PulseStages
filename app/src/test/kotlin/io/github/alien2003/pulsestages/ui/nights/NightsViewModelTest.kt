package io.github.alien2003.pulsestages.ui.nights

import androidx.lifecycle.SavedStateHandle
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.NightSummary
import io.github.alien2003.pulsestages.data.local.db.SKIPPED_NAP_REASON_PREFIX
import io.github.alien2003.pulsestages.source.LedgerNightsSource
import io.github.alien2003.pulsestages.source.NightsSource
import io.github.alien2003.pulsestages.testutil.FakeWorkScheduler
import io.github.alien2003.pulsestages.testutil.MainDispatcherExtension
import io.github.alien2003.pulsestages.testutil.TestScreenSources
import io.github.alien2003.pulsestages.testutil.clear
import io.github.alien2003.pulsestages.testutil.harnessTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NightsViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    @TempDir
    lateinit var dir: File

    private val scheduler = FakeWorkScheduler()

    private fun source(h: PipelineHarness): NightsSource =
        LedgerNightsSource(h.ledger.nightDao, h.homeStatus, h.inspector, h.eraser, scheduler, h.settings)

    private suspend fun NightsViewModel.awaitLoaded(predicate: (NightsUiState.Loaded) -> Boolean = { true }): NightsUiState.Loaded =
        state.first { it is NightsUiState.Loaded && predicate(it) } as NightsUiState.Loaded

    @Test
    fun `nights are listed newest first with status, shares and markers`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(3))
            h.repository.addNight(OHEALTH, nightStart(2), withHeartRate = false)
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            val elevated = h.ledger.allNights().first { it.sessionStartMs == nightStart(3) }
            h.ledger.putNight(elevated.copy(elevatedHr = true, lowConfidence = true))
            val vm = NightsViewModel(TestScreenSources(source(h)), h.logger, SavedStateHandle())

            val nights = vm.awaitLoaded { it.nights.size == 3 }.nights

            assertEquals(listOf(nightStart(1), nightStart(2), nightStart(3)), nights.map { it.summary.sessionStartMs })
            val (latest, unestimated, oldest) = nights
            assertTrue(latest.estimated)
            assertFalse(latest.problem)
            assertTrue(latest.summary.deepMin > 0.0)
            assertEquals(NightStatus.NOT_ESTIMATED, unestimated.summary.status)
            assertTrue(unestimated.problem)
            assertFalse(unestimated.estimated)
            assertTrue(oldest.summary.elevatedHr)
            assertTrue(oldest.summary.lowConfidence)
            assertTrue(nights.none { it.stale })
            // A nap skipped by the nap policy ("Written for a nap": Nothing) is the chosen outcome, not a problem.
            val skipped = unestimated.copy(summary = unestimated.summary.copy(statusReason = "$SKIPPED_NAP_REASON_PREFIX nap of 90 min"))
            assertFalse(skipped.problem)
            assertTrue(unestimated.copy(summary = unestimated.summary.copy(status = NightStatus.FAILED)).problem)
            clear(vm)
        }

    @Test
    fun `naps carry the badge and the filter shows all, nights or naps and survives in the saved state`() =
        harnessTest(main, dir) { h ->
            h.settings.set { it.copy(sources = it.sources.copy(minSessionMin = 30)) }
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1) + 16 * HOUR_MS, durationMs = 90 * MINUTE_MS)
            h.sync.run(inBackground = false)
            val saved = SavedStateHandle()
            val vm = NightsViewModel(TestScreenSources(source(h)), h.logger, saved)

            val all = vm.awaitLoaded { it.nights.size == 2 }
            assertEquals(NightsFilter.ALL, all.filter)
            assertEquals(listOf(true, false), all.nights.map { it.nap }, "newest first: the nap, then the night")
            assertEquals(
                SessionMode.NAP,
                all.nights
                    .first()
                    .summary.mode,
            )

            vm.setFilter(NightsFilter.NAPS)
            val naps = vm.awaitLoaded { it.filter == NightsFilter.NAPS }
            assertEquals(listOf(true), naps.nights.map { it.nap })
            assertEquals(2, naps.total)
            vm.setFilter(NightsFilter.NIGHTS)
            val nights = vm.awaitLoaded { it.filter == NightsFilter.NIGHTS }
            assertEquals(listOf(false), nights.nights.map { it.nap })

            // The filter is restored with the saved state (rotation, process death).
            val restored = NightsViewModel(TestScreenSources(source(h)), h.logger, saved)
            assertEquals(NightsFilter.NIGHTS, restored.awaitLoaded().filter)
            clear(vm)
            clear(restored)
        }

    @Test
    fun `the filter accepts nights and naps by mode`() {
        val night = NightListItem(summary(SessionMode.NIGHT), stale = false)
        val nap = NightListItem(summary(SessionMode.NAP), stale = false)
        assertEquals(listOf(true, true), listOf(night, nap).map(NightsFilter.ALL::accepts))
        assertEquals(listOf(true, false), listOf(night, nap).map(NightsFilter.NIGHTS::accepts))
        assertEquals(listOf(false, true), listOf(night, nap).map(NightsFilter.NAPS::accepts))
    }

    private fun summary(mode: SessionMode): NightSummary =
        NightSummary(
            id = 1,
            clientRecordId = "ps:x",
            sourcePackage = OHEALTH,
            sessionStartMs = 0,
            sessionEndMs = 60_000,
            startZoneOffsetSec = 0,
            endZoneOffsetSec = 0,
            status = NightStatus.WRITTEN,
            statusReason = null,
            estimator = EstimatorType.HYBRID,
            algoVersion = 1,
            paramsHash = "h",
            coverage = 1.0,
            confidence = 0.5,
            awakeMin = 0.0,
            lightMin = 1.0,
            deepMin = 0.0,
            remMin = 0.0,
            unknownMin = 0.0,
            elevatedHr = false,
            lowConfidence = false,
            calibrated = false,
            rulesFallback = false,
            updatedAt = 0,
            mode = mode,
        )

    @Test
    fun `written nights become stale when the settings change, other nights never`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(2), withHeartRate = false)
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            val vm = NightsViewModel(TestScreenSources(source(h)), h.logger, SavedStateHandle())
            vm.awaitLoaded { it.nights.size == 2 }

            h.settings.set { it.copy(write = it.write.copy(awakeAsLight = true)) }
            val nights = vm.awaitLoaded { loaded -> loaded.nights.any { it.stale } }.nights

            assertEquals(listOf(true, false), nights.map { it.stale })
            clear(vm)
        }

    @Test
    fun `superseded outputs are not listed`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            val replaced = h.ledger.allNights().first { it.sessionStartMs == nightStart(2) }
            h.ledger.putNight(replaced.copy(status = NightStatus.SUPERSEDED))
            val vm = NightsViewModel(TestScreenSources(source(h)), h.logger, SavedStateHandle())

            assertEquals(listOf(nightStart(1)), vm.awaitLoaded().nights.map { it.summary.sessionStartMs })
            clear(vm)
        }

    @Test
    fun `an empty ledger is an empty list`() =
        harnessTest(main, dir) { h ->
            val vm = NightsViewModel(TestScreenSources(source(h)), h.logger, SavedStateHandle())
            val loaded = vm.awaitLoaded()
            assertTrue(loaded.nights.isEmpty())
            assertFalse(loaded.synthetic)
            clear(vm)
        }

    @Test
    fun `a night selected in the wide pane is opened as its own destination once the window becomes compact`() =
        harnessTest(main, dir) { h ->
            val savedState = SavedStateHandle()
            val vm = NightsViewModel(TestScreenSources(source(h)), h.logger, savedState)
            assertEquals(null, vm.selectedId.value)
            assertEquals(null, vm.takeNightToOpen(), "nothing was selected")

            vm.select("ps:a", inPane = true)
            assertEquals("ps:a", vm.selectedId.value)
            clear(vm)

            // Rotated / folded (the ViewModel may even be recreated from the saved state).
            val restored = NightsViewModel(TestScreenSources(source(h)), h.logger, savedState)
            assertEquals("ps:a", restored.selectedId.value)
            assertEquals("ps:a", restored.takeNightToOpen())
            assertEquals(null, restored.takeNightToOpen(), "opened once: back from the detail shows the list")
            assertEquals("ps:a", restored.selectedId.value, "still highlighted in a wide window")

            restored.select("ps:b", inPane = false)
            assertEquals(null, restored.takeNightToOpen(), "opened from a compact list: already its own destination")

            restored.onDetailWidened("ps:c")
            assertEquals("ps:c", restored.selectedId.value, "the wide pane shows the night of the detail destination")
            assertEquals("ps:c", restored.takeNightToOpen())
            clear(restored)
        }

    @Test
    fun `replaced data are listed at once, and the list follows every switch`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            val real = source(h)
            val synthetic =
                object : NightsSource by real {
                    override val synthetic: Boolean = true

                    override fun nights(): Flow<List<NightSummary>> = flowOf(emptyList())
                }
            val sources = TestScreenSources(real)
            val vm = NightsViewModel(sources, h.logger, SavedStateHandle())
            vm.select("ps:selected", inPane = true)
            assertEquals(1, vm.awaitLoaded { it.nights.isNotEmpty() }.nights.size)

            sources.replace(synthetic)
            val demo = vm.awaitLoaded { it.synthetic }
            assertTrue(demo.nights.isEmpty(), "the nights of the new source")

            sources.replace(real)
            val back = vm.awaitLoaded { !it.synthetic }
            assertEquals(listOf(nightStart(1)), back.nights.map { it.summary.sessionStartMs })
            assertEquals("ps:selected", vm.selectedId.value, "the selection is kept for the data it belongs to")
            clear(vm)
        }

    @Test
    fun `a night selected in the pane before a source switch is not opened when the window becomes compact`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            val real = source(h)
            val demo =
                object : NightsSource by real {
                    override val synthetic: Boolean = true

                    override fun nights(): Flow<List<NightSummary>> =
                        real.nights().map { nights -> nights.map { it.copy(clientRecordId = "demo:" + it.clientRecordId) } }
                }
            val sources = TestScreenSources(demo)
            val vm = NightsViewModel(sources, h.logger, SavedStateHandle())
            val demoList = vm.awaitLoaded { it.synthetic }
            val demoNight = demoList.nights.single().clientRecordId
            vm.select(demoNight, inPane = true)

            // Demo mode off in Settings, back to the Nights list (the newest real night in the pane), then folded.
            sources.replace(real)
            val realList = vm.awaitLoaded { !it.synthetic }
            val realNight = realList.nights.single().clientRecordId
            assertEquals(null, vm.takeNightToOpen(), "the demo night is not in the real data")

            // Switched while the tab was hidden (the list still shows the old data): not opened either.
            sources.replace(demo)
            vm.select(demoNight, inPane = true)
            sources.replace(real)
            assertEquals(null, vm.takeNightToOpen())

            // A night of the current data is opened; one missing from the current list is not.
            vm.awaitLoaded { !it.synthetic }
            vm.select(realNight, inPane = true)
            assertEquals(realNight, vm.takeNightToOpen())
            vm.select("ps:deleted", inPane = true)
            assertEquals(null, vm.takeNightToOpen())
            clear(vm)
        }

    @Test
    fun `the list waits for its source`() =
        harnessTest(main, dir) { h ->
            val sources = TestScreenSources()
            val vm = NightsViewModel(sources, h.logger, SavedStateHandle())
            assertEquals(NightsUiState.Loading, vm.state.value)

            sources.replace(source(h))
            assertFalse(vm.awaitLoaded().synthetic)
            clear(vm)
        }

    @Test
    fun `an unreadable ledger is an error`() =
        harnessTest(main, dir) { h ->
            val failing =
                object : NightsSource by source(h) {
                    override fun nights(): Flow<List<NightSummary>> = flow { throw IllegalStateException("database closed") }
                }
            val vm = NightsViewModel(TestScreenSources(failing), h.logger, SavedStateHandle())

            assertEquals(NightsUiState.Error, vm.state.first { it != NightsUiState.Loading })
            clear(vm)
        }

    @Test
    fun `without a params hash the list is shown without stale markers`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            val failing =
                object : NightsSource by source(h) {
                    override fun currentParamsHash(): Flow<String> = flow { throw IllegalStateException("no hash") }
                }
            val vm = NightsViewModel(TestScreenSources(failing), h.logger, SavedStateHandle())

            val nights = vm.awaitLoaded { it.nights.isNotEmpty() }.nights
            assertFalse(nights.single().stale)
            clear(vm)
        }
}

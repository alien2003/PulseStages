package io.github.alien2003.pulsestages.ui.nights

import androidx.lifecycle.SavedStateHandle
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.NightSummary
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
            clear(vm)
        }

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

// detekt treats only src/test as tests; this is the test source set of the debug variant (the demo code is debug-only).
@file:Suppress("FunctionNaming", "MagicNumber")

package io.github.alien2003.pulsestages.demo

import androidx.lifecycle.SavedStateHandle
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.source.HealthConnectReferenceSource
import io.github.alien2003.pulsestages.source.LedgerNightsSource
import io.github.alien2003.pulsestages.testutil.FakeAppInfoResolver
import io.github.alien2003.pulsestages.testutil.FakeDocumentStore
import io.github.alien2003.pulsestages.testutil.FakeWorkScheduler
import io.github.alien2003.pulsestages.testutil.MainDispatcherExtension
import io.github.alien2003.pulsestages.testutil.clear
import io.github.alien2003.pulsestages.testutil.harnessTest
import io.github.alien2003.pulsestages.ui.compare.ComparePhase
import io.github.alien2003.pulsestages.ui.compare.CompareViewModel
import io.github.alien2003.pulsestages.ui.nights.DetailLoad
import io.github.alien2003.pulsestages.ui.nights.NightDetailViewModel
import io.github.alien2003.pulsestages.ui.nights.NightsUiState
import io.github.alien2003.pulsestages.ui.nights.NightsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The debug build's demo-mode switch swaps the data of the Nights list, the night detail and the Compare tab at
 * runtime: the ViewModels, created once with the real data, follow every switch without a restart, and nothing of the
 * demo data reaches the ledger.
 */
class DemoScreenSourcesTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    @TempDir
    lateinit var dir: File

    private val noBundle = HybridModelProvider { null }

    /** The sources of the debug build over [h], following [enabled] like the store's switch. */
    private class Sources(
        scope: TestScope,
        h: PipelineHarness,
        val enabled: MutableStateFlow<Boolean> = MutableStateFlow(false),
        noBundle: HybridModelProvider,
    ) {
        val ledger = LedgerNightsSource(h.ledger.nightDao, h.homeStatus, h.inspector, h.eraser, FakeWorkScheduler(), h.settings)
        val healthConnect = HealthConnectReferenceSource(h.referenceComparison, h.personalization, h.homeStatus, h.settings)
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val demoNights = DemoNightsSource(h.settings, noBundle, dispatcher, h.clock)
        val demoReferences = DemoReferenceSource(h.settings, noBundle, dispatcher, h.clock)
        val screens = DemoScreenSources(enabled, { ledger }, { healthConnect }, { demoNights }, { demoReferences })
    }

    @Test
    fun `the switch selects the real or the demo source of every screen`() =
        harnessTest(main, dir) { h ->
            val sources = Sources(this, h, noBundle = noBundle)

            assertSame(sources.ledger, sources.screens.nights.first())
            assertSame(sources.healthConnect, sources.screens.references.first())

            sources.enabled.value = true
            assertSame(sources.demoNights, sources.screens.nights.first())
            assertSame(sources.demoReferences, sources.screens.references.first())

            sources.enabled.value = false
            assertSame(sources.ledger, sources.screens.nights.first())
            assertSame(sources.healthConnect, sources.screens.references.first())
        }

    @Test
    fun `the Nights, night detail and Compare screens follow the switch without a restart`() =
        harnessTest(main, dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            val real = h.ledger.allNights().single()
            val sources = Sources(this, h, noBundle = noBundle)
            val nights = NightsViewModel(sources.screens, h.logger, SavedStateHandle())
            val detail = NightDetailViewModel(sources.screens, FakeDocumentStore(), h.logger, SavedStateHandle())
            val compare = CompareViewModel(sources.screens, FakeAppInfoResolver(), h.clock, h.logger)
            detail.show(real.clientRecordId)
            detail.onPaneStarted()
            compare.onScreenStarted()

            val realList = nights.awaitLoaded { !it.synthetic }
            assertEquals(listOf(real.clientRecordId), realList.nights.map { it.clientRecordId })
            assertEquals(DetailLoad.LOADED, detail.state.first { it.load != DetailLoad.LOADING }.load)
            assertFalse(compare.state.first { it.phase == ComparePhase.READY }.synthetic)

            // On: the demo nights, the night shown is not among them (the detail returns to the list), demo references.
            sources.enabled.value = true
            val demoList = nights.awaitLoaded { it.synthetic }
            assertEquals(DemoData.SESSION_COUNT, demoList.nights.size)
            assertTrue(demoList.nights.all { it.summary.sourcePackage == DEMO_SOURCE_PACKAGE })
            val gone = detail.state.first { it.synthetic && it.load != DetailLoad.LOADING }
            assertTrue(gone.goneWithReplacedData)
            detail.show(demoList.nights.first().clientRecordId)
            val demoNight = detail.state.first { it.load == DetailLoad.LOADED }
            assertTrue(demoNight.synthetic)
            val demoCompare = compare.state.first { it.synthetic && it.phase == ComparePhase.READY && it.metrics != null }
            assertEquals(DemoData.REFERENCE_SESSION_COUNT, demoCompare.referenceCount)
            assertEquals(2, demoCompare.metrics?.byMode?.size, "nights and naps apart")

            // Off: the real data again; the demo data never reached the ledger.
            sources.enabled.value = false
            val back = nights.awaitLoaded { !it.synthetic }
            assertEquals(listOf(real.clientRecordId), back.nights.map { it.clientRecordId })
            assertFalse(compare.state.first { !it.synthetic && it.phase == ComparePhase.READY }.synthetic)
            assertFalse(detail.state.first { !it.synthetic }.synthetic)
            assertEquals(listOf(real.clientRecordId), h.ledger.allNights().map { it.clientRecordId })
            assertEquals(
                NightStatus.WRITTEN,
                h.ledger
                    .allNights()
                    .single()
                    .status,
            )
            clear(nights)
            clear(detail)
            clear(compare)
        }

    private suspend fun NightsViewModel.awaitLoaded(predicate: (NightsUiState.Loaded) -> Boolean): NightsUiState.Loaded =
        state.first { it is NightsUiState.Loaded && predicate(it) } as NightsUiState.Loaded
}

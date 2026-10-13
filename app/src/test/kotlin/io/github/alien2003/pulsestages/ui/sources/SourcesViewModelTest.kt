package io.github.alien2003.pulsestages.ui.sources

import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.fake.FakeSettingsRepository
import io.github.alien2003.pulsestages.data.hc.fake.RecordingLogger
import io.github.alien2003.pulsestages.data.hc.fake.TestClock
import io.github.alien2003.pulsestages.data.hc.pipeline.SourceScanner
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.OHEALTH_PACKAGE
import io.github.alien2003.pulsestages.testutil.FakeAppInfoResolver
import io.github.alien2003.pulsestages.testutil.MainDispatcherExtension
import io.github.alien2003.pulsestages.ui.common.UiText
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class SourcesViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val clock = TestClock(NOW)
    private val healthConnect = FakeHealthRepository(OWN, now = { clock.nowMs })
    private val settings = FakeSettingsRepository()
    private val logger = RecordingLogger()

    private fun viewModel(labels: Map<String, String> = mapOf(OHEALTH_PACKAGE to "OHealth")) =
        SourcesViewModel(SourceScanner(healthConnect, settings, clock, OWN), settings, FakeAppInfoResolver(labels), logger, OWN)

    private fun addNights(
        packageName: String,
        count: Int,
        firstDaysAgo: Int = 1,
    ) {
        repeat(count) { i ->
            val start = NOW - (firstDaysAgo + i) * DAY_MS
            healthConnect.addSleepSession(packageName, start, start + 7 * HOUR_MS)
        }
    }

    private fun SourcesViewModel.packages(): List<String> = state.value.items.map { it.packageName }

    @BeforeEach
    fun sources() {
        addNights(OHEALTH_PACKAGE, 2)
        addNights(OTHER, 5, firstDaysAgo = 3)
        addNights(THIRD, 1)
        addNights(OWN, 1)
    }

    @Nested
    inner class Ordering {
        @Test
        fun `listed sources come first, then the others by session count, PulseStages itself last`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                val state = vm.state.value
                assertFalse(state.loading)
                assertNull(state.error)
                assertEquals(listOf(OHEALTH_PACKAGE, OTHER, THIRD, OWN), vm.packages())
                assertEquals(listOf(true, false, false, false), state.items.map { it.enabled })
                assertTrue(state.items.last().isOwn)
                assertEquals("OHealth", state.items.first().label)
                assertNull(state.items[1].label)
                assertEquals(5, state.items[1].sessionCount)
                assertEquals(NOW - 3 * DAY_MS + 7 * HOUR_MS, state.items[1].lastSessionEndMs)
                assertTrue(state.fullHistory)
            }

        @Test
        fun `without the history permission only the recent range is scanned`() =
            runTest(main.dispatcher) {
                healthConnect.revoke(HealthPermissions.READ_HEALTH_DATA_HISTORY)
                val vm = viewModel()
                assertFalse(vm.state.value.fullHistory)
                assertEquals(SourceScanner.RECENT_DAYS, vm.state.value.scannedDays)
            }

        @Test
        fun `moving a source changes the stored priority and never moves it below PulseStages`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.moveUp(THIRD)
                assertEquals(listOf(OHEALTH_PACKAGE, THIRD, OTHER, OWN), vm.packages())
                assertEquals(listOf(OHEALTH_PACKAGE, THIRD, OTHER), settings.current.sources.sourcePriority)
                vm.moveDown(OHEALTH_PACKAGE)
                assertEquals(listOf(THIRD, OHEALTH_PACKAGE, OTHER), settings.current.sources.sourcePriority)
                vm.moveUp(THIRD)
                vm.moveDown(OTHER)
                vm.moveUp(OWN)
                assertEquals(listOf(THIRD, OHEALTH_PACKAGE, OTHER, OWN), vm.packages())
                assertEquals(listOf(OHEALTH_PACKAGE), settings.current.sources.sourcePackages)
            }

        @Test
        fun `the stored priority is shown on the next scan`() =
            runTest(main.dispatcher) {
                viewModel().moveUp(THIRD)
                assertEquals(listOf(OHEALTH_PACKAGE, THIRD, OTHER, OWN), viewModel().packages())
            }
    }

    @Nested
    inner class Toggles {
        @Test
        fun `enabling a source stores it in priority order`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.setEnabled(THIRD, true)
                vm.setEnabled(OTHER, true)
                assertEquals(listOf(OHEALTH_PACKAGE, OTHER, THIRD), settings.current.sources.sourcePackages)
                assertEquals(
                    listOf(true, true, true, false),
                    vm.state.value.items
                        .map { it.enabled },
                )
                assertTrue(
                    vm.state.value.messages
                        .isEmpty(),
                )
            }

        @Test
        fun `disabling the last source warns that nothing will be estimated`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.setEnabled(OHEALTH_PACKAGE, false)
                assertEquals(emptyList<String>(), settings.current.sources.sourcePackages)
                assertEquals(
                    UiText.Res(R.string.sources_none_enabled),
                    vm.state.value.messages
                        .single()
                        .text,
                )
            }

        @Test
        fun `PulseStages itself cannot be enabled`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.setEnabled(OWN, true)
                assertFalse(
                    vm.state.value.items
                        .single { it.isOwn }
                        .enabled,
                )
                assertFalse(OWN in settings.current.sources.sourcePackages)
                assertFalse(OWN in settings.current.sources.sourcePriority)
            }

        @Test
        fun `heart rate and steps can come from any app`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.setHrSourcesAny(true)
                assertEquals(HrSourcesMode.ANY, settings.current.sources.hrSources)
                assertEquals(HrSourcesMode.ANY, vm.state.value.hrSources)
                vm.setHrSourcesAny(false)
                assertEquals(HrSourcesMode.SAME_AS_SLEEP, settings.current.sources.hrSources)
            }
    }

    @Nested
    inner class ManualAdd {
        private fun SourcesViewModel.add(name: String) {
            onManualInput(name)
            addManual()
        }

        private fun SourcesViewModel.error(): UiText? = state.value.manualError

        @Test
        fun `invalid names are rejected with a reason`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.add("  ")
                assertEquals(UiText.Res(R.string.sources_manual_error_blank), vm.error())
                vm.add("not a package")
                assertEquals(UiText.Res(R.string.sources_manual_error_invalid), vm.error())
                vm.add("nodots")
                assertEquals(UiText.Res(R.string.sources_manual_error_invalid), vm.error())
                vm.add(OWN)
                assertEquals(UiText.Res(R.string.sources_manual_error_own), vm.error())
                vm.add(OTHER)
                assertEquals(UiText.Res(R.string.sources_manual_error_duplicate), vm.error())
                assertEquals(listOf(OHEALTH_PACKAGE), settings.current.sources.manualPackages + settings.current.sources.sourcePackages)
            }

        @Test
        fun `typing clears the error`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.add("x")
                vm.onManualInput("com.")
                assertNull(vm.error())
            }

        @Test
        fun `a valid name is added enabled, before PulseStages, and stored`() =
            runTest(main.dispatcher) {
                val vm = viewModel(labels = mapOf(NEW to "New watch"))
                vm.add("  $NEW ")
                assertNull(vm.error())
                assertEquals("", vm.state.value.manualInput)
                assertEquals(listOf(OHEALTH_PACKAGE, OTHER, THIRD, NEW, OWN), vm.packages())
                val added =
                    vm.state.value.items
                        .single { it.packageName == NEW }
                assertTrue(added.enabled && added.manual)
                assertEquals("New watch", added.label)
                assertEquals(listOf(NEW), settings.current.sources.manualPackages)
                assertEquals(listOf(OHEALTH_PACKAGE, NEW), settings.current.sources.sourcePackages)
                assertEquals(listOf(OHEALTH_PACKAGE, OTHER, THIRD, NEW), settings.current.sources.sourcePriority)
                assertEquals(
                    UiText.Res(R.string.sources_added, listOf(NEW)),
                    vm.state.value.messages
                        .last()
                        .text,
                )
            }

        @Test
        fun `a manually added package without sessions can be removed`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.add(NEW)
                vm.removeManual(NEW)
                assertFalse(NEW in vm.packages())
                assertEquals(emptyList<String>(), settings.current.sources.manualPackages)
                assertFalse(NEW in settings.current.sources.sourcePriority)
                vm.removeManual(OTHER)
                assertTrue(OTHER in vm.packages())
            }
    }

    @Nested
    inner class Errors {
        @Test
        fun `Health Connect unavailable`() =
            runTest(main.dispatcher) {
                healthConnect.availability = HcAvailability.NOT_SUPPORTED
                assertEquals(SourcesError.UNAVAILABLE, viewModel().state.value.error)
            }

        @Test
        fun `sleep permission missing`() =
            runTest(main.dispatcher) {
                healthConnect.revoke(HealthPermissions.READ_SLEEP)
                assertEquals(SourcesError.PERMISSION, viewModel().state.value.error)
            }

        @Test
        fun `a failed scan still lists the configured sources from the settings, and they stay editable`() =
            runTest(main.dispatcher) {
                settings.set {
                    it.copy(
                        sources =
                            it.sources.copy(
                                sourcePriority = listOf(OHEALTH_PACKAGE, OTHER),
                                sourcePackages = listOf(OHEALTH_PACKAGE),
                                manualPackages = listOf(NEW),
                            ),
                    )
                }
                healthConnect.revoke(HealthPermissions.READ_SLEEP)
                val vm = viewModel()
                val state = vm.state.value
                assertEquals(SourcesError.PERMISSION, state.error)
                assertFalse(state.loading)
                assertFalse(state.scanned)
                assertEquals(listOf(OHEALTH_PACKAGE, OTHER, NEW), vm.packages(), "listed, then manual; nothing scanned")
                assertEquals(listOf(true, false, false), state.items.map { it.enabled })
                assertEquals(listOf(false, false, true), state.items.map { it.manual })
                assertTrue(state.items.all { it.sessionCount == null }, "session counts are unknown")
                assertEquals("OHealth", state.items.first().label)

                vm.setEnabled(OTHER, true)
                vm.moveUp(OTHER)
                vm.setHrSourcesAny(true)
                vm.onManualInput(THIRD)
                vm.addManual()
                assertEquals(listOf(OTHER, OHEALTH_PACKAGE, NEW, THIRD), settings.current.sources.sourcePriority)
                assertEquals(listOf(OTHER, OHEALTH_PACKAGE, THIRD), settings.current.sources.sourcePackages)
                assertEquals(listOf(NEW, THIRD), settings.current.sources.manualPackages)
                assertEquals(HrSourcesMode.ANY, settings.current.sources.hrSources)
                assertNull(
                    vm.state.value.items
                        .single { it.packageName == THIRD }
                        .sessionCount,
                )

                vm.removeManual(NEW)
                assertEquals(listOf(THIRD), settings.current.sources.manualPackages, "manual packages stay removable")
                assertEquals(SourcesError.PERMISSION, vm.state.value.error, "the error stays shown")
            }

        @Test
        fun `transient failure, then a successful retry`() =
            runTest(main.dispatcher) {
                healthConnect.failOn(FakeHealthRepository.Operation.SCAN_ORIGINS, HealthDataException.Transient("busy"))
                val vm = viewModel()
                assertEquals(SourcesError.FAILED, vm.state.value.error)
                assertEquals(listOf(OHEALTH_PACKAGE), vm.packages(), "the configured source is still listed")
                vm.refresh()
                assertNull(vm.state.value.error)
                assertTrue(vm.state.value.scanned)
                assertEquals(4, vm.state.value.items.size)
                assertEquals(
                    2,
                    vm.state.value.items
                        .first()
                        .sessionCount,
                )
            }
    }

    private companion object {
        const val OWN = FakeHealthRepository.DEFAULT_OWN_PACKAGE
        const val OTHER = "com.example.sleep"
        const val THIRD = "org.example.watch"
        const val NEW = "com.example.newwatch"
        const val HOUR_MS = 60L * 60 * 1000
        const val DAY_MS = 24 * HOUR_MS
        const val NOW = 1_760_000_000_000L
    }
}

package io.github.alien2003.pulsestages.ui.settings

import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.params.DurationModel
import io.github.alien2003.pulsestages.algo.params.EmissionTable
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.data.hc.fake.FakeSettingsRepository
import io.github.alien2003.pulsestages.data.hc.fake.RecordingLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsErrorCode
import io.github.alien2003.pulsestages.data.local.settings.SettingsJson
import io.github.alien2003.pulsestages.testutil.FakeDocumentStore
import io.github.alien2003.pulsestages.testutil.FakeStaleNightsSource
import io.github.alien2003.pulsestages.testutil.MainDispatcherExtension
import io.github.alien2003.pulsestages.ui.common.UiText
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class SettingsViewModelTest {
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val repository = FakeSettingsRepository()
    private val documents = FakeDocumentStore()
    private val logger = RecordingLogger()

    private val staleNights = FakeStaleNightsSource(repository)

    private fun viewModel() = SettingsViewModel(repository, documents, logger, staleNights)

    private fun SettingsViewModel.problemsOf(path: String): List<FieldProblem> = state.value.problems[path].orEmpty()

    private fun SettingsViewModel.lastMessage(): UiText? =
        state.value.messages
            .lastOrNull()
            ?.text

    @Test
    fun `the stored settings are loaded`() =
        runTest(main.dispatcher) {
            repository.set { it.copy(sources = it.sources.copy(minSessionMin = 120)) }
            val vm = viewModel()
            assertFalse(vm.state.value.loading)
            assertEquals(120, vm.state.value.settings.sources.minSessionMin)
        }

    @Nested
    inner class StalePrompt {
        private fun SettingsViewModel.prompts() = state.value.messages.filter { it.action != null }

        @Test
        fun `a change that makes nights stale offers to recompute them, and the action starts the recompute`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                assertTrue(vm.prompts().isEmpty())

                vm.onEdit(SettingsEdit.Switch("write.awakeAsLight", true))

                val prompt = vm.prompts().single()
                assertEquals(UiText.plural(R.plurals.settings_stale_nights, 3), prompt.text)
                assertEquals(UiText.of(R.string.settings_stale_recompute), prompt.action)
                assertEquals(0, staleNights.recomputes, "nothing is recomputed without the tap")

                vm.onMessageAction(prompt.id)
                vm.messageShown(prompt.id)
                assertEquals(1, staleNights.recomputes)
                assertEquals(UiText.of(R.string.home_recompute_stale_started), vm.lastMessage())
                vm.onMessageAction(prompt.id)
                assertEquals(1, staleNights.recomputes, "one tap, one recompute")
            }

        @Test
        fun `changes that keep the params hash offer nothing, undoing a change withdraws the offer`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Text("sources.minSessionMin", "120"))
                assertTrue(vm.prompts().isEmpty(), "not part of the params hash")

                vm.onEdit(SettingsEdit.Switch("write.awakeAsLight", true))
                vm.onEdit(SettingsEdit.Choice("algo.estimator", EstimatorType.RULES.name))
                assertEquals(1, vm.prompts().size, "a newer offer replaces the earlier one")

                vm.onEdit(SettingsEdit.Choice("algo.estimator", EstimatorType.HYBRID.name))
                vm.onEdit(SettingsEdit.Switch("write.awakeAsLight", false))
                assertTrue(vm.prompts().isEmpty(), "back to the parameters the nights were estimated with")
                assertEquals(0, staleNights.recomputes)
            }

        @Test
        fun `Sleep as Android compatibility makes nights stale and offers to recompute them`() =
            runTest(main.dispatcher) {
                val vm = viewModel()

                vm.onEdit(SettingsEdit.Switch("write.startBeforeSource", true))

                assertTrue(repository.current.write.startBeforeSource)
                assertEquals(UiText.of(R.string.settings_stale_recompute), vm.prompts().single().action)

                vm.onEdit(SettingsEdit.Switch("write.startBeforeSource", false))
                assertTrue(vm.prompts().isEmpty(), "off again: the nights are up to date")
            }
    }

    @Nested
    inner class Validation {
        @Test
        fun `a valid number is stored immediately and the typed text is kept`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Text("sources.minSessionMin", "120"))
                assertEquals(120, repository.current.sources.minSessionMin)
                assertEquals(emptyList<FieldProblem>(), vm.problemsOf("sources.minSessionMin"))
                assertEquals("120", vm.state.value.drafts["sources.minSessionMin"])
            }

        @Test
        fun `an out-of-range number is not stored and the validator's error is shown`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Text("sources.minSessionMin", "5"))
                assertEquals(90, repository.current.sources.minSessionMin)
                val problem = vm.problemsOf("sources.minSessionMin").single() as FieldProblem.Invalid
                assertEquals(SettingsErrorCode.OUT_OF_RANGE, problem.error.code)
                assertEquals("10..720", problem.error.constraint)
                assertEquals("5", vm.state.value.textOf(checkNotNull(SettingsCatalog.spec("sources.minSessionMin"))))
            }

        @Test
        fun `the nap threshold is minutes from 0 to 720 and makes nights stale`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Text("algo.napMode.maxSessionMin", "30"))
                assertEquals(30.0, repository.current.algo.napMode.maxSessionMin)
                assertTrue(
                    vm.state.value.messages
                        .any { it.action != null },
                    "part of the params hash: recompute offered",
                )

                vm.onEdit(SettingsEdit.Text("algo.napMode.maxSessionMin", "721"))

                assertEquals(30.0, repository.current.algo.napMode.maxSessionMin)
                val problem = vm.problemsOf("algo.napMode.maxSessionMin").single() as FieldProblem.Invalid
                assertEquals(SettingsErrorCode.OUT_OF_RANGE, problem.error.code)
                assertEquals("0.0..720.0", problem.error.constraint)
            }

        @Test
        fun `a nap override takes a number, an empty field means the night's value`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                val path = "algo.napMode.post.minDeepMin"
                val spec = checkNotNull(SettingsCatalog.spec(path)) as OptionalDoubleSpec
                assertEquals("", vm.state.value.textOf(spec), "no override: an empty field")
                assertEquals(repository.current.algo.post.minDeepMin, spec.inherited(repository.current))

                vm.onEdit(SettingsEdit.Text(path, "12,5"))
                assertEquals(12.5, repository.current.algo.napMode.post.minDeepMin)
                vm.onEdit(SettingsEdit.Text(path, "twelve"))
                assertEquals(listOf(FieldProblem.NotANumber(integer = false)), vm.problemsOf(path))
                assertEquals(12.5, repository.current.algo.napMode.post.minDeepMin, "an invalid text is not stored")
                vm.onEdit(SettingsEdit.Text(path, "-3"))
                assertEquals(SettingsErrorCode.OUT_OF_RANGE, (vm.problemsOf(path).single() as FieldProblem.Invalid).error.code)
                vm.onEdit(SettingsEdit.Text(path, "  "))
                assertEquals(null, repository.current.algo.napMode.post.minDeepMin, "empty again: the night's value")

                vm.onEdit(SettingsEdit.Choice("algo.napMode.hybrid.durationModel", "GEOMETRIC"))
                assertEquals(DurationModel.GEOMETRIC, repository.current.algo.napMode.hybrid.durationModel)
                vm.onEdit(SettingsEdit.Choice("algo.napMode.hybrid.durationModel", SAME_AS_NIGHTS_KEY))
                assertEquals(null, repository.current.algo.napMode.hybrid.durationModel)
                vm.onEdit(SettingsEdit.Choice("algo.napMode.outputPolicy", "SKIP"))
                assertEquals(NapOutputPolicy.SKIP, repository.current.algo.napMode.outputPolicy)
            }

        @Test
        fun `the nap context margin takes a whole number up to the night's margin, empty means the night's`() =
            runTest(main.dispatcher) {
                // Empty, whatever the trained default (TrainedDefaults.NAP_CONTEXT_MARGIN_MIN) is.
                repository.set { it.copy(algo = it.algo.copy(napMode = it.algo.napMode.copy(contextMarginMin = null))) }
                val vm = viewModel()
                val path = "algo.napMode.contextMarginMin"
                val spec = checkNotNull(SettingsCatalog.spec(path)) as OptionalIntSpec
                assertEquals("", vm.state.value.textOf(spec), "no override: an empty field")
                assertEquals(30, spec.inherited(repository.current))
                assertEquals(0..30, spec.range(repository.current))

                vm.onEdit(SettingsEdit.Text(path, "0"))
                assertEquals(0, repository.current.algo.napMode.contextMarginMin)
                assertEquals("0", vm.state.value.textOf(spec))
                vm.onEdit(SettingsEdit.Text(path, "12.5"))
                assertEquals(listOf(FieldProblem.NotANumber(integer = true)), vm.problemsOf(path))
                assertEquals(0, repository.current.algo.napMode.contextMarginMin, "an invalid text is not stored")
                vm.onEdit(SettingsEdit.Text(path, "31"))
                val above = (vm.problemsOf(path).single() as FieldProblem.Invalid).error
                assertEquals(SettingsErrorCode.MIN_ABOVE_MAX, above.code)
                assertEquals("algo.preprocess.contextMarginMin", above.constraint.substringAfter("<= ").substringBefore(' '))
                vm.onEdit(SettingsEdit.Text(path, "-1"))
                assertEquals(SettingsErrorCode.OUT_OF_RANGE, (vm.problemsOf(path).single() as FieldProblem.Invalid).error.code)
                vm.onEdit(SettingsEdit.Text(path, "30"))
                assertEquals(30, repository.current.algo.napMode.contextMarginMin)
                assertTrue(vm.problemsOf(path).isEmpty())
                // The night's margin cannot go below a nap margin that is set.
                vm.onEdit(SettingsEdit.Text("algo.preprocess.contextMarginMin", "20"))
                assertEquals(30, repository.current.algo.preprocess.contextMarginMin)
                vm.onEdit(SettingsEdit.Text(path, " "))
                assertEquals(null, repository.current.algo.napMode.contextMarginMin, "empty again: the night's value")
                vm.onEdit(SettingsEdit.Text("algo.preprocess.contextMarginMin", "20"))
                assertEquals(20, repository.current.algo.preprocess.contextMarginMin)
                assertEquals(0..20, spec.range(repository.current))
            }

        @Test
        fun `text that is not a number is rejected with the expected kind`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Text("sources.mergeGapMin", "ten"))
                vm.onEdit(SettingsEdit.Text("algo.preprocess.maxInterpGapMin", "1.2.3"))
                assertEquals(listOf(FieldProblem.NotANumber(integer = true)), vm.problemsOf("sources.mergeGapMin"))
                assertEquals(listOf(FieldProblem.NotANumber(integer = false)), vm.problemsOf("algo.preprocess.maxInterpGapMin"))
                assertEquals(AppSettings(), repository.current)
            }

        @Test
        fun `a decimal comma is accepted and a correction clears the problem`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Text("algo.preprocess.maxInterpGapMin", "61"))
                assertEquals(1, vm.problemsOf("algo.preprocess.maxInterpGapMin").size)
                vm.onEdit(SettingsEdit.Text("algo.preprocess.maxInterpGapMin", "12,5"))
                assertEquals(12.5, repository.current.algo.preprocess.maxInterpGapMin)
                assertTrue(vm.problemsOf("algo.preprocess.maxInterpGapMin").isEmpty())
            }

        @Test
        fun `a minimum moved above its maximum is rejected under the edited control`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.DoubleValue("algo.preprocess.minCoverage", 0.3))
                assertEquals(0.6, repository.current.algo.preprocess.minCoverage)
                val error = (vm.problemsOf("algo.preprocess.minCoverage").single() as FieldProblem.Invalid).error
                assertEquals("algo.preprocess.rulesMinCoverage", error.path)
                assertEquals(SettingsErrorCode.MIN_ABOVE_MAX, error.code)
            }

        @Test
        fun `values only the algorithm validator checks are rejected too`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Text("algo.preprocess.hampelMinScaleBpm", "-1"))
                assertEquals(1.0, repository.current.algo.preprocess.hampelMinScaleBpm)
                val error = (vm.problemsOf("algo.preprocess.hampelMinScaleBpm").single() as FieldProblem.Invalid).error
                assertEquals("algo.preprocess.hampelMinScaleBpm", error.path)
            }

        @Test
        fun `an even window length is rejected`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.IntValue("algo.preprocess.hampelWindow", 4))
                assertEquals(5, repository.current.algo.preprocess.hampelWindow)
                assertEquals(
                    SettingsErrorCode.NOT_ODD,
                    (vm.problemsOf("algo.preprocess.hampelWindow").single() as FieldProblem.Invalid).error.code,
                )
            }

        @Test
        fun `an emission sigma must be positive`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Text("algo.hmm.emissions.deep.hrVar.sd", "0"))
                assertEquals(AppSettings().algo.hmm.emissions, repository.current.algo.hmm.emissions)
                vm.onEdit(SettingsEdit.Text("algo.hmm.emissions.deep.hrVar.sd", "0.25"))
                assertEquals(0.25, repository.current.algo.hmm.emissions.deep.hrVar.sd)
            }
    }

    @Nested
    inner class Controls {
        @Test
        fun `switches, choices, sliders, times and ranges are stored`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Switch("write.awakeAsLight", true))
                vm.onEdit(SettingsEdit.Choice("algo.estimator", "RULES"))
                vm.onEdit(SettingsEdit.Choice("algo.preprocess.epochSec", "30"))
                vm.onEdit(SettingsEdit.IntValue("schedule.periodicHours", 3))
                vm.onEdit(SettingsEdit.DoubleValue("algo.post.lowConfidenceThreshold", 0.5))
                vm.onEdit(SettingsEdit.Time("schedule.dailyTimeMinutes", 7 * 60 + 30))
                vm.onEdit(SettingsEdit.Range("algo.post.targetDeepMin", 0.12, 0.3))
                val s = repository.current
                assertTrue(s.write.awakeAsLight)
                assertEquals(EstimatorType.RULES, s.algo.estimator)
                assertEquals(30, s.algo.preprocess.epochSec)
                assertEquals(3, s.schedule.periodicHours)
                assertEquals(0.5, s.algo.post.lowConfidenceThreshold)
                assertEquals(450, s.schedule.dailyTimeMinutes)
                assertEquals(0.12 to 0.3, s.algo.post.targetDeepMin to s.algo.post.targetDeepMax)
                assertEquals(s, vm.state.value.settings)
            }

        @Test
        fun `an inverted range is rejected`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Range("algo.post.targetRemMin", 0.4, 0.2))
                assertEquals(0.15, repository.current.algo.post.targetRemMin)
                assertEquals(
                    SettingsErrorCode.MIN_ABOVE_MAX,
                    (vm.problemsOf("algo.post.targetRemMin").single() as FieldProblem.Invalid).error.code,
                )
            }

        @Test
        fun `a blank title template means the default title`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Text("write.titleTemplate", "Stages {estimator}"))
                assertEquals("Stages {estimator}", repository.current.write.titleTemplate)
                vm.onEdit(SettingsEdit.Text("write.titleTemplate", "   "))
                assertNull(repository.current.write.titleTemplate)
            }

        @Test
        fun `edits of unknown paths are ignored`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Text("sources.sourcePackages", "x"))
                vm.onEdit(SettingsEdit.Switch("algo.estimator", true))
                assertEquals(AppSettings(), repository.current)
                assertTrue(
                    vm.state.value.problems
                        .isEmpty(),
                )
            }
    }

    @Nested
    inner class Reset {
        @Test
        fun `reset asks for confirmation and then restores every default`() =
            runTest(main.dispatcher) {
                repository.set { it.copy(sources = it.sources.copy(mergeGapMin = 10), schedule = it.schedule.copy(periodicHours = 6)) }
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Text("sources.minSessionMin", "5"))
                vm.requestReset()
                assertTrue(vm.state.value.confirmReset)
                assertEquals(10, repository.current.sources.mergeGapMin)
                vm.confirmReset()
                assertEquals(AppSettings(), repository.current)
                assertFalse(vm.state.value.confirmReset)
                assertTrue(
                    vm.state.value.drafts
                        .isEmpty(),
                )
                assertTrue(
                    vm.state.value.problems
                        .isEmpty(),
                )
                assertEquals(1, vm.state.value.revision)
                assertEquals(UiText.Res(R.string.settings_reset_done), vm.lastMessage())
            }

        @Test
        fun `dismissing the confirmation changes nothing`() =
            runTest(main.dispatcher) {
                repository.set { it.copy(sources = it.sources.copy(mergeGapMin = 10)) }
                val vm = viewModel()
                vm.requestReset()
                vm.dismissReset()
                assertFalse(vm.state.value.confirmReset)
                assertEquals(10, repository.current.sources.mergeGapMin)
            }

        @Test
        fun `the HMM emissions are reset to the physiology table only`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Text("algo.hmm.emissions.rem.hrNorm.mean", "2.0"))
                vm.onEdit(SettingsEdit.Text("sources.minSessionMin", "100"))
                assertEquals(2.0, repository.current.algo.hmm.emissions.rem.hrNorm.mean)
                vm.resetEmissions()
                assertEquals(EmissionTable.PHYSIOLOGY, repository.current.algo.hmm.emissions)
                assertEquals(100, repository.current.sources.minSessionMin)
                assertFalse("algo.hmm.emissions.rem.hrNorm.mean" in vm.state.value.drafts)
                assertEquals(UiText.Res(R.string.settings_emissions_reset_done), vm.lastMessage())
            }

        @Test
        fun `shown messages are removed`() =
            runTest(main.dispatcher) {
                val vm = viewModel()
                vm.resetEmissions()
                val id =
                    vm.state.value.messages
                        .single()
                        .id
                vm.messageShown(id)
                assertTrue(
                    vm.state.value.messages
                        .isEmpty(),
                )
            }
    }

    @Nested
    inner class ExportImport {
        private val uri = "content://documents/settings.json"

        @Test
        fun `export writes the complete settings JSON`() =
            runTest(main.dispatcher) {
                repository.set { it.copy(sources = it.sources.copy(minSessionMin = 75)) }
                val vm = viewModel()
                vm.exportTo(uri)
                assertEquals(SettingsJson.encode(repository.current), documents.documents[uri])
                assertEquals(UiText.Res(R.string.settings_export_done), vm.lastMessage())
                assertFalse(vm.state.value.busy)
            }

        @Test
        fun `a failed export is reported`() =
            runTest(main.dispatcher) {
                documents.failWrites = true
                val vm = viewModel()
                vm.exportTo(uri)
                assertEquals(UiText.Res(R.string.settings_export_failed), vm.lastMessage())
            }

        @Test
        fun `a valid file is imported and replaces the settings`() =
            runTest(main.dispatcher) {
                val imported =
                    AppSettings().let {
                        it.copy(
                            sources = it.sources.copy(mergeGapMin = 45),
                            algo = it.algo.copy(estimator = EstimatorType.HMM),
                        )
                    }
                documents.documents[uri] = SettingsJson.encode(imported)
                val vm = viewModel()
                vm.onEdit(SettingsEdit.Text("sources.minSessionMin", "5"))
                vm.importFrom(uri)
                assertEquals(imported, repository.current)
                assertEquals(imported, vm.state.value.settings)
                assertNull(vm.state.value.importErrors)
                assertTrue(
                    vm.state.value.drafts
                        .isEmpty(),
                )
                val texts =
                    vm.state.value.messages
                        .map { it.text }
                assertTrue(UiText.Res(R.string.settings_import_done) in texts, "$texts")
                assertTrue(UiText.plural(R.plurals.settings_stale_nights, 3) in texts, "the imported estimator makes nights stale: $texts")
            }

        @Test
        fun `a file that is not settings JSON is rejected with its error`() =
            runTest(main.dispatcher) {
                documents.documents[uri] = "{ not json"
                val vm = viewModel()
                vm.importFrom(uri)
                assertEquals(AppSettings(), repository.current)
                assertEquals(
                    SettingsErrorCode.MALFORMED_JSON,
                    vm.state.value.importErrors
                        ?.single()
                        ?.code,
                )
                assertEquals(UiText.Res(R.string.settings_import_rejected), vm.lastMessage())
                vm.dismissImportErrors()
                assertNull(vm.state.value.importErrors)
            }

        @Test
        fun `out-of-range values reject the whole import and list every problem`() =
            runTest(main.dispatcher) {
                val json =
                    SettingsJson
                        .encode(AppSettings())
                        .replace("\"minSessionMin\": 90", "\"minSessionMin\": 5")
                        .replace("\"periodicHours\": 1", "\"periodicHours\": 48")
                documents.documents[uri] = json
                val vm = viewModel()
                vm.importFrom(uri)
                assertEquals(AppSettings(), repository.current)
                assertEquals(
                    listOf("sources.minSessionMin", "schedule.periodicHours"),
                    vm.state.value.importErrors
                        ?.map { it.path },
                )
            }

        @Test
        fun `values only the algorithm validator rejects block the import as well`() =
            runTest(main.dispatcher) {
                val json = SettingsJson.encode(AppSettings()).replace("\"pseudoPosterior\": 0.85", "\"pseudoPosterior\": 3.0")
                documents.documents[uri] = json
                val vm = viewModel()
                vm.importFrom(uri)
                assertEquals(AppSettings(), repository.current)
                assertEquals(
                    listOf("algo.rules.pseudoPosterior"),
                    vm.state.value.importErrors
                        ?.map { it.path },
                )
            }

        @Test
        fun `an unreadable file is reported without changing anything`() =
            runTest(main.dispatcher) {
                documents.failReads = true
                val vm = viewModel()
                vm.importFrom(uri)
                assertEquals(AppSettings(), repository.current)
                assertNull(vm.state.value.importErrors)
                assertEquals(UiText.Res(R.string.settings_import_read_failed), vm.lastMessage())
            }
    }
}

// detekt treats only src/test as tests; this is the test source set of the debug variant (the demo code is debug-only).
@file:Suppress("FunctionNaming", "MagicNumber")

package io.github.alien2003.pulsestages.screenshots

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.data.hc.fake.FakeSettingsRepository
import io.github.alien2003.pulsestages.data.hc.fake.RecordingLogger
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.local.settings.SettingsError
import io.github.alien2003.pulsestages.data.local.settings.SettingsErrorCode
import io.github.alien2003.pulsestages.data.local.settings.SettingsLimits
import io.github.alien2003.pulsestages.demo.DemoModeCard
import io.github.alien2003.pulsestages.testutil.FakeDocumentStore
import io.github.alien2003.pulsestages.testutil.FakeStaleNightsSource
import io.github.alien2003.pulsestages.ui.TopLevelDestination
import io.github.alien2003.pulsestages.ui.about.AboutInfo
import io.github.alien2003.pulsestages.ui.about.AboutViewModel
import io.github.alien2003.pulsestages.ui.common.UiMessage
import io.github.alien2003.pulsestages.ui.common.UiText
import io.github.alien2003.pulsestages.ui.common.resolve
import io.github.alien2003.pulsestages.ui.settings.ChoiceSpec
import io.github.alien2003.pulsestages.ui.settings.ChoiceStyle
import io.github.alien2003.pulsestages.ui.settings.DoubleSpec
import io.github.alien2003.pulsestages.ui.settings.IntSpec
import io.github.alien2003.pulsestages.ui.settings.OptionalDoubleSpec
import io.github.alien2003.pulsestages.ui.settings.RangeSpec
import io.github.alien2003.pulsestages.ui.settings.SettingSpec
import io.github.alien2003.pulsestages.ui.settings.SettingsActions
import io.github.alien2003.pulsestages.ui.settings.SettingsCatalog
import io.github.alien2003.pulsestages.ui.settings.SettingsChecks
import io.github.alien2003.pulsestages.ui.settings.SettingsEdit
import io.github.alien2003.pulsestages.ui.settings.SettingsItem
import io.github.alien2003.pulsestages.ui.settings.SettingsRoute
import io.github.alien2003.pulsestages.ui.settings.SettingsScreen
import io.github.alien2003.pulsestages.ui.settings.SettingsSection
import io.github.alien2003.pulsestages.ui.settings.SettingsUiState
import io.github.alien2003.pulsestages.ui.settings.SettingsViewModel
import io.github.alien2003.pulsestages.ui.settings.SwitchSpec
import io.github.alien2003.pulsestages.ui.settings.TextSpec
import io.github.alien2003.pulsestages.ui.settings.TimeSpec
import io.github.alien2003.pulsestages.ui.settings.specs
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the Settings screen with every group expanded, in English and Russian, at font scale 1 and 2, in a compact
 * and a wide window: every field (also with a validation problem below it), every option of every choice (the
 * dropdown menus are opened), the time picker, the HMM emission table and transition weights, the debug build's
 * demo-mode card (switched on and off), the About card, the dialogs and the Snackbar. Any exception while the screen
 * is composed, measured or drawn fails the test, and so does a text that still shows a format placeholder.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "en-rUS-w411dp-h891dp-port-xhdpi")
class SettingsRenderTest(
    private val config: ScreenConfig,
) {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val host = ScreenHost(compose)
    private val logger = RecordingLogger()
    private val settings = FakeSettingsRepository()
    private val models = HybridModelProvider { HybridModel.parse(File(MODEL_FILE).readText()) }
    private val sections: List<SettingsSection> = SettingsCatalog.sections
    private var demoMode by mutableStateOf(false)

    /** The debug build's card, without its Hilt ViewModel. */
    private val demoCard: @Composable () -> Unit = {
        DemoModeCard(enabled = demoMode, onEnabledChange = { demoMode = it })
    }

    @Before
    fun configure() {
        config.apply()
    }

    @After
    fun tearDown() {
        host.close()
        config.restore()
    }

    @Test
    fun everyGroupExpandedWithTheStoredSettings() {
        val viewModel = SettingsViewModel(settings, FakeDocumentStore(), logger, FakeStaleNightsSource(settings))
        val about = AboutViewModel({ "1.0.0" }, models, logger)
        host.show(TopLevelDestination.SETTINGS) {
            SettingsRoute(onOpenSources = {}, onOpenLog = {}, viewModel = viewModel, aboutViewModel = about, debugTools = demoCard)
        }
        host.waitUntil { !viewModel.state.value.loading && about.about.value.modelChecked }
        config.assertAppliedTo(host.resources)
        expandEveryGroup()
        sections.forEachIndexed { index, section ->
            scrollToGroup(index)
            openEveryDropdown(section)
            openEveryTimePicker(section)
        }
        scrollThroughEverything("stored settings")
        switchDemoModeOnAndOff()
        scrollToAbout()
        compose
            .onNode(
                hasText(
                    about.about.value.modelId
                        .orEmpty(),
                    substring = true,
                ),
            ).assertExists()
    }

    @Test
    fun everyGroupExpandedWithAProblemBelowEveryField() {
        val viewModel = SettingsViewModel(settings, FakeDocumentStore(), logger, FakeStaleNightsSource(settings))
        host.show(TopLevelDestination.SETTINGS) {
            SettingsRoute(
                onOpenSources = {},
                onOpenLog = {},
                viewModel = viewModel,
                aboutViewModel = AboutViewModel({ "" }, models, logger),
                debugTools = demoCard,
            )
        }
        host.waitUntil { !viewModel.state.value.loading }
        expandEveryGroup()
        val edits = invalidEdits()
        compose.runOnIdle { edits.forEach(viewModel::onEdit) }
        host.waitUntil { viewModel.state.value.problems.size >= edits.size / 2 }
        val problems = viewModel.state.value.problems
        assertTrue("problems of ${problems.size} of ${edits.size} edits", problems.size >= edits.size / 2)
        scrollThroughEverything("a problem below every field") { section ->
            section.items.specs().forEach { spec ->
                problems[spec.path].orEmpty().forEach { problem ->
                    val text = SettingsChecks.describe(problem, spec.path).resolve(host.resources)
                    compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().let {
                        assertTrue("${spec.path}: \"$text\" is shown", it.isNotEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun loadingFailedBusyDialogsAndMessages() {
        var state by mutableStateOf(SettingsUiState())
        val about = AboutInfo(appVersion = "", modelChecked = false)
        host.show(TopLevelDestination.SETTINGS) {
            SettingsScreen(state = state, actions = SettingsActions(), about = about, debugTools = demoCard)
        }
        host.assertTexts("loading")
        val loaded = SettingsUiState(loading = false)
        val errors =
            SettingsErrorCode.entries.map { code -> SettingsError("algo.preprocess.minBpm", code, "250", "1..220") } +
                listOf(
                    SettingsError("$", SettingsErrorCode.MALFORMED_JSON, "", "Unexpected JSON token at offset 3: 100%"),
                    SettingsError("sources.sourcePackages[0]", SettingsErrorCode.INVALID_PACKAGE_NAME, "no package", "a package name"),
                    SettingsError(
                        "algo.features.normLowPercentile",
                        SettingsErrorCode.MIN_ABOVE_MAX,
                        "90.0",
                        "<= algo.features.normHighPercentile (80.0)",
                    ),
                )
        val variants =
            listOf(
                "failed load, busy" to loaded.copy(loadFailed = true, busy = true),
                "reset confirmation" to loaded.copy(confirmReset = true),
                "import errors" to loaded.copy(importErrors = errors),
                "stale nights message" to
                    loaded.copy(
                        messages =
                            listOf(
                                UiMessage(
                                    text = UiText.plural(R.plurals.settings_stale_nights, 3),
                                    action = UiText.of(R.string.settings_stale_recompute),
                                ),
                            ),
                    ),
            )
        variants.forEach { (name, variant) ->
            compose.runOnIdle { state = variant }
            host.idle()
            if (variant.confirmReset || variant.importErrors != null) compose.onNode(isDialog()).assertExists()
            host.assertTexts(name)
        }
        compose.runOnIdle { state = loaded }
        expandEveryGroup()
        scrollThroughEverything("defaults without a ViewModel")
    }

    private fun grid(): SemanticsNodeInteraction = compose.onNode(hasScrollToIndexAction())

    /** Grid items: the actions card, one card per group, the debug build's card, the About card. */
    private fun scrollToGroup(index: Int) {
        grid().performScrollToIndex(index + 1)
        host.idle()
    }

    private fun scrollToDebugTools() {
        grid().performScrollToIndex(sections.size + 1)
        host.idle()
        compose.onNode(hasText(host.resources.getString(R.string.demo_settings_title))).assertExists()
    }

    private fun scrollToAbout() {
        grid().performScrollToIndex(sections.size + 2)
        host.idle()
        compose.onNode(hasText(host.resources.getString(R.string.about_title))).assertExists()
    }

    /** The whole row of the demo-mode card toggles its switch (as TalkBack does, through the row's click action). */
    private fun switchDemoModeOnAndOff() {
        scrollToDebugTools()
        val row = compose.onNode(isToggleable() and hasText(host.resources.getString(R.string.demo_mode_title), substring = true))
        compose.onNode(hasText(host.resources.getString(R.string.demo_mode_hint))).assertExists()
        row.assertIsOff()
        row.performSemanticsAction(SemanticsActions.OnClick)
        host.idle()
        row.assertIsOn()
        assertTrue("the card switched demo mode on", demoMode)
        host.assertTexts("demo mode on")
        row.performSemanticsAction(SemanticsActions.OnClick)
        host.idle()
        row.assertIsOff()
        assertFalse("the card switched demo mode off", demoMode)
    }

    /**
     * Opens every collapsed group through its expand button's click action (as TalkBack does: after the programmatic
     * jumps of performScrollToIndex, injected taps in the two-column grid are not reliable in Robolectric); every group's
     * content is then composed.
     */
    private fun expandEveryGroup() {
        val resources = host.resources
        sections.forEachIndexed { index, section ->
            scrollToGroup(index)
            val title = section.title.resolve(resources)
            if (!section.expandedByDefault) {
                compose
                    .onNodeWithContentDescription(resources.getString(R.string.cd_expand_section, title))
                    .performSemanticsAction(SemanticsActions.OnClick)
                host.idle()
                scrollToGroup(index)
            }
            compose.onNodeWithContentDescription(resources.getString(R.string.cd_collapse_section, title)).assertExists()
            assertFieldsShown(section)
        }
    }

    /** The title of every setting of [section] (or its description, for the compact table cells) is on screen. */
    private fun assertFieldsShown(section: SettingsSection) {
        val resources = host.resources
        section.items.specs().forEach { spec ->
            val title = spec.title.resolve(resources)
            val nodes = compose.onAllNodes(hasText(title) or hasContentDescription(title), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("${section.id}: ${spec.path} (\"$title\") is shown", nodes.isNotEmpty())
            unitOf(spec)?.resolve(resources)?.let { unit ->
                val units = compose.onAllNodes(hasText(unit, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
                assertTrue("${section.id}: the unit \"$unit\" of ${spec.path} is shown", units.isNotEmpty())
            }
        }
        section.items.filterIsInstance<SettingsItem.EmissionTable>().forEach { table ->
            val hint = table.hint.resolve(resources)
            assertTrue("emission table hint", compose.onAllNodes(hasText(hint), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
    }

    private fun unitOf(spec: SettingSpec): UiText? =
        when (spec) {
            is IntSpec -> spec.unit
            is DoubleSpec -> spec.unit
            is OptionalDoubleSpec -> spec.unit
            else -> null
        }

    /** Opens the menu of every dropdown of [section], checks that it lists every option and closes it again. */
    private fun openEveryDropdown(section: SettingsSection) {
        val resources = host.resources
        section.items.specs().filterIsInstance<ChoiceSpec>().filter { it.style == ChoiceStyle.DROPDOWN }.forEach { spec ->
            val current =
                spec.options
                    .first { it.key == spec.read(settings.current) }
                    .label
                    .resolve(resources)
            // Several dropdowns may show the same option ("Same as nights"): the field is found by its label too.
            val title = spec.title.resolve(resources)
            compose.onNode(hasText(current) and hasText(title) and !hasAnyAncestor(isPopup())).performScrollTo().performClick()
            host.idle()
            spec.options.forEach { option ->
                compose.onNode(hasText(option.label.resolve(resources)) and hasAnyAncestor(isPopup())).assertExists()
            }
            host.assertTexts("${spec.path} menu")
            compose.onNode(hasText(current) and hasAnyAncestor(isPopup())).performClick()
            host.idle()
        }
    }

    /** Opens the time picker of every time setting of [section] and cancels it. */
    private fun openEveryTimePicker(section: SettingsSection) {
        val resources = host.resources
        section.items.specs().filterIsInstance<TimeSpec>().forEach { spec ->
            val title = spec.title.resolve(resources)
            compose.onNode(hasContentDescription(title, substring = true) and hasClickAction()).performScrollTo().performClick()
            host.idle()
            compose.onNode(isDialog()).assertExists()
            host.assertTexts("${spec.path} picker")
            compose.onNode(hasText(resources.getString(R.string.action_cancel)) and hasAnyAncestor(isDialog())).performClick()
            host.idle()
        }
    }

    /** Scrolls to every item of the grid, checks the texts on screen and runs [check] for each group. */
    private fun scrollThroughEverything(
        what: String,
        check: (SettingsSection) -> Unit = {},
    ) {
        grid().performScrollToIndex(0)
        host.idle()
        host.assertTexts("$what: actions")
        sections.forEachIndexed { index, section ->
            scrollToGroup(index)
            host.assertTexts("$what: ${section.id}")
            check(section)
        }
        scrollToDebugTools()
        host.assertTexts("$what: debug build")
        scrollToAbout()
        host.assertTexts("$what: about")
    }

    /** An invalid edit of every setting that can hold an invalid value: not a number, out of range, too long, ... */
    private fun invalidEdits(): List<SettingsEdit> =
        sections.flatMap { it.items.specs() }.mapIndexedNotNull { index, spec ->
            val typo = index % 2 == 0
            when (spec) {
                is IntSpec -> {
                    invalidInt(spec, typo)
                }

                is DoubleSpec -> {
                    invalidDouble(spec, typo)
                }

                is OptionalDoubleSpec -> {
                    SettingsEdit.Text(spec.path, if (typo) "0,5," else "-1000000")
                }

                is TextSpec -> {
                    SettingsEdit.Text(spec.path, "x".repeat(SettingsLimits.TITLE_TEMPLATE_MAX_LENGTH + 1))
                }

                is RangeSpec -> {
                    SettingsEdit.Range(spec.path, 0.9, 0.1)
                }

                is TimeSpec -> {
                    SettingsEdit.Time(spec.path, -1)
                }

                is ChoiceSpec -> {
                    // Only the epoch length is a number; enum options cannot be invalid.
                    SettingsEdit.Choice(spec.path, "7").takeIf { spec.path.endsWith(".epochSec") }
                }

                is SwitchSpec -> {
                    null
                }
            }
        }

    private fun invalidInt(
        spec: IntSpec,
        typo: Boolean,
    ): SettingsEdit {
        val above = (spec.range?.last ?: 0) + 1
        return when {
            spec.slider -> SettingsEdit.IntValue(spec.path, above)
            typo -> SettingsEdit.Text(spec.path, "1x")
            else -> SettingsEdit.Text(spec.path, above.toString())
        }
    }

    private fun invalidDouble(
        spec: DoubleSpec,
        typo: Boolean,
    ): SettingsEdit =
        when {
            spec.sliderRange != null -> SettingsEdit.DoubleValue(spec.path, 1.5)
            typo -> SettingsEdit.Text(spec.path, "0,5,")
            else -> SettingsEdit.Text(spec.path, "-1000000")
        }

    private companion object {
        const val MODEL_FILE = "src/main/assets/models/hybrid_model.json"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = ScreenConfig.ALL.map { arrayOf(it) }
    }
}

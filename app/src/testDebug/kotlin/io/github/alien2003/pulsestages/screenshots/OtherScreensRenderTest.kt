// detekt treats only src/test as tests; this is the test source set of the debug variant (the demo code is debug-only).
@file:Suppress("FunctionNaming", "MagicNumber")

package io.github.alien2003.pulsestages.screenshots

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.github.alien2003.pulsestages.PermissionsRationaleActivity
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.fake.FakeSettingsRepository
import io.github.alien2003.pulsestages.data.hc.fake.RecordingLogger
import io.github.alien2003.pulsestages.data.hc.pipeline.SourceScanner
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.local.db.LogEntryEntity
import io.github.alien2003.pulsestages.data.local.db.PersonalModelKind
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.settings.OHEALTH_PACKAGE
import io.github.alien2003.pulsestages.demo.DemoReferenceSource
import io.github.alien2003.pulsestages.testutil.FakeAppInfoResolver
import io.github.alien2003.pulsestages.testutil.TestScreenSources
import io.github.alien2003.pulsestages.ui.TopLevelDestination
import io.github.alien2003.pulsestages.ui.common.UiMessage
import io.github.alien2003.pulsestages.ui.common.UiText
import io.github.alien2003.pulsestages.ui.compare.CompareActions
import io.github.alien2003.pulsestages.ui.compare.CompareBusy
import io.github.alien2003.pulsestages.ui.compare.ComparePhase
import io.github.alien2003.pulsestages.ui.compare.CompareRange
import io.github.alien2003.pulsestages.ui.compare.CompareRoute
import io.github.alien2003.pulsestages.ui.compare.CompareScreen
import io.github.alien2003.pulsestages.ui.compare.CompareUiState
import io.github.alien2003.pulsestages.ui.compare.CompareViewModel
import io.github.alien2003.pulsestages.ui.compare.ModelInfo
import io.github.alien2003.pulsestages.ui.log.LogScreen
import io.github.alien2003.pulsestages.ui.log.LogUiState
import io.github.alien2003.pulsestages.ui.sources.SourceItem
import io.github.alien2003.pulsestages.ui.sources.SourcesActions
import io.github.alien2003.pulsestages.ui.sources.SourcesError
import io.github.alien2003.pulsestages.ui.sources.SourcesRoute
import io.github.alien2003.pulsestages.ui.sources.SourcesScreen
import io.github.alien2003.pulsestages.ui.sources.SourcesUiState
import io.github.alien2003.pulsestages.ui.sources.SourcesViewModel
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Renders Compare (the debug build's synthetic reference nights, a calibration, empty, searching, scoring, errors, the
 * busy states, the models and the reset confirmation), Sources (a scan with data, every error banner, a manual source),
 * the Log (entries of every level, empty, filtered out, failed) and PermissionsRationaleActivity, in English and Russian
 * (also at font scale 2 and in a wide window). Any exception fails the test, and so does a text that still shows a
 * format placeholder.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "en-rUS-w411dp-h891dp-port-xhdpi")
class OtherScreensRenderTest(
    private val config: ScreenConfig,
) {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val host = ScreenHost(compose)
    private val clock: Clock = Clock.fixed(NOW, ZoneId.of("UTC"))
    private val logger = RecordingLogger()
    private val settings = FakeSettingsRepository()
    private val model: HybridModel = HybridModel.parse(File(MODEL_FILE).readText())
    private val models = HybridModelProvider { model }
    private val appInfo = FakeAppInfoResolver(LABELS)

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
    fun compareWithDataCalibrationEmptyAndErrors() {
        val viewModel =
            CompareViewModel(
                TestScreenSources(references = DemoReferenceSource(settings, models, Dispatchers.Default, clock)),
                appInfo,
                clock,
                logger,
            )
        host.show(TopLevelDestination.COMPARE) { CompareRoute(viewModel = viewModel) }
        host.waitUntil { viewModel.state.value.let { it.phase == ComparePhase.READY && it.metrics != null } }
        config.assertAppliedTo(host.resources)
        host.assertNoUnresolvedPlaceholders("demo reference nights")
        val data = viewModel.state.value
        // A calibration on the demo nights: its result message is shown in the Snackbar.
        compose.runOnIdle { viewModel.calibrate() }
        host.waitUntil { viewModel.state.value.let { it.busy == null && it.model != null } }
        host.assertNoUnresolvedPlaceholders("calibrated")

        val variants =
            listOf(
                "searching" to CompareUiState(),
                "scoring" to CompareUiState(phase = ComparePhase.SCORING, referenceCount = 7),
                "error" to CompareUiState(phase = ComparePhase.ERROR, error = UiText.of(R.string.compare_error_generic)),
                "empty with skipped nights" to CompareUiState(phase = ComparePhase.READY, withoutHeartRate = 2, withoutSleepStages = 3),
                "data" to data,
                "reset confirmation" to data.copy(confirmReset = true),
                "personalization off, history limited" to
                    data.copy(personalizationEnabled = false, historyLimited = true, synthetic = false),
                "model without scores" to data.copy(model = ModelInfo(PersonalModelKind.CALIBRATED, NOW.toEpochMilli(), 1, null, null)),
                "skipped nights" to data.copy(withoutHeartRate = 1, withoutSleepStages = 2),
                "message" to data.copy(messages = listOf(UiMessage(UiText.of(R.string.compare_reset_done)))),
            ) +
                CompareRange.entries.map { range ->
                    "empty, range $range" to
                        CompareUiState(phase = ComparePhase.READY, range = range, historyLimited = range != CompareRange.RECENT)
                } +
                CompareBusy.entries.map { busy -> "busy $busy" to data.copy(busy = busy) } +
                PersonalModelKind.entries.map { kind ->
                    "model $kind" to data.copy(model = ModelInfo(kind, NOW.toEpochMilli(), 5, 0.31, 0.38))
                }
        host.showEach(
            TopLevelDestination.COMPARE,
            variants,
            check = { name, state -> if (state.confirmReset) compose.onNode(isDialog()).assertExists("$name: dialog") },
        ) { state -> CompareScreen(state = state, actions = CompareActions()) }
    }

    @Test
    fun sourcesWithDataEveryErrorBannerAndManualSources() {
        val health = FakeHealthRepository(OWN_PACKAGE) { NOW.toEpochMilli() }
        val nightOf = { daysAgo: Int -> NOW.toEpochMilli() - daysAgo * DAY_MS - 10 * HOUR_MS }
        for (daysAgo in 1..30) health.addNight(OHEALTH_PACKAGE, nightOf(daysAgo))
        for (daysAgo in 2..20 step 2) health.addNight(SLEEP_APP, nightOf(daysAgo) + 20 * MINUTE_MS)
        for (daysAgo in 1..25) health.addNight(OWN_PACKAGE, nightOf(daysAgo), withHeartRate = false)
        val sourceSettings = FakeSettingsRepository(AppSettings())
        val viewModel =
            SourcesViewModel(SourceScanner(health, sourceSettings, clock, OWN_PACKAGE), sourceSettings, appInfo, logger, OWN_PACKAGE)
        host.show(TopLevelDestination.SETTINGS) { SourcesRoute(onBack = {}, viewModel = viewModel) }
        host.waitUntil { !viewModel.state.value.loading && viewModel.state.value.items.size == 3 }
        config.assertAppliedTo(host.resources)
        host.assertNoUnresolvedPlaceholders("scanned sources")

        val data = viewModel.state.value
        val fromSettings = data.items.map { it.copy(sessionCount = null, lastSessionEndMs = null) }
        val manual = SourceItem("com.example.manual", null, null, 0, null, enabled = true, manual = true, isOwn = false)
        val variants =
            listOf(
                "loading" to SourcesUiState(),
                "nothing found" to SourcesUiState(loading = false),
                "data" to data,
                "full history, heart rate from any app" to data.copy(fullHistory = true, hrSources = HrSourcesMode.ANY),
                "manual source" to data.copy(items = listOf(manual) + data.items, manualInput = "com.example.manual"),
                "manual error" to data.copy(manualInput = "not a package", manualError = UiText.of(R.string.sources_manual_error_invalid)),
                "message" to data.copy(messages = listOf(UiMessage(UiText.of(R.string.sources_added, "Sleep as Android")))),
            ) +
                SourcesError.entries.flatMap { error ->
                    listOf(
                        "error $error" to data.copy(loading = false, error = error, items = fromSettings),
                        "error $error, retrying" to data.copy(loading = true, error = error, items = fromSettings),
                    )
                }
        host.showEach(TopLevelDestination.SETTINGS, variants, lazyItems = { if (it.items.isEmpty()) 0 else it.items.size + 2 }) { state ->
            SourcesScreen(state = state, actions = SourcesActions())
        }
    }

    @Test
    fun logWithEntriesEmptyFilteredAndFailed() {
        val entries =
            LogLevel.entries.flatMapIndexed { index, level ->
                listOf(
                    LogEntryEntity(
                        id = 2L * index + 1,
                        timestampMs = NOW.toEpochMilli() - index * MINUTE_MS,
                        level = level,
                        tag = "Pipeline",
                        message = "night ps:x estimated: coverage 93% (a long message that wraps over several lines on a phone)",
                        nightKey = "ps:night-$index",
                    ),
                    LogEntryEntity(
                        id = 2L * index + 2,
                        timestampMs = NOW.toEpochMilli() - index * HOUR_MS,
                        level = level,
                        tag = "Ui",
                        message = "short",
                    ),
                )
            }
        val loaded = LogUiState(loading = false)
        val variants =
            listOf(
                "loading" to LogUiState(),
                "failed" to loaded.copy(failed = true),
                "empty" to loaded,
                "every level filtered out" to loaded.copy(levels = emptySet()),
                "entries" to loaded.copy(entries = entries),
                "sharing" to loaded.copy(entries = entries, sharing = true),
                "message" to loaded.copy(entries = entries, messages = listOf(UiMessage(UiText.of(R.string.log_share_empty)))),
            )
        host.showEach(null, variants, lazyItems = { if (it.entries.isEmpty()) 0 else it.entries.size + 1 }) { state ->
            LogScreen(state = state, onBack = {}, onToggleLevel = {}, onShare = {}, onMessageShown = {})
        }
        config.assertAppliedTo(host.resources)
    }

    @Test
    fun permissionsRationaleActivityFromHealthConnectAndFromTheApp() {
        val application = RuntimeEnvironment.getApplication()
        listOf(false, true).forEach { fromApp ->
            val intent =
                Intent(application, PermissionsRationaleActivity::class.java).putExtra(PermissionsRationaleActivity.EXTRA_FROM_APP, fromApp)
            val activity = host.launch(PermissionsRationaleActivity::class.java, intent)
            config.assertAppliedTo(activity.resources)
            compose.onNode(hasText(activity.getString(R.string.rationale_title))).assertExists()
            host.assertNoUnresolvedPlaceholders("rationale, from the app: $fromApp")
        }
    }

    private companion object {
        const val MODEL_FILE = "src/main/assets/models/hybrid_model.json"
        const val MINUTE_MS = 60_000L
        const val OWN_PACKAGE = "io.github.alien2003.pulsestages"
        const val SLEEP_APP = "com.urbandroid.sleep"

        /** 2026-10-13 09:30 UTC: the morning after the newest demo night. */
        val NOW: Instant = Instant.parse("2026-10-13T09:30:00Z")

        val LABELS: Map<String, String> =
            mapOf(
                OHEALTH_PACKAGE to "OHealth",
                SLEEP_APP to "Sleep as Android",
                OWN_PACKAGE to "PulseStages",
                "io.github.alien2003.pulsestages.demo.watch" to "Demo watch",
                "io.github.alien2003.pulsestages.demo.tracker" to "Demo sleep tracker",
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = ScreenConfig.ALL.map { arrayOf(it) }
    }
}

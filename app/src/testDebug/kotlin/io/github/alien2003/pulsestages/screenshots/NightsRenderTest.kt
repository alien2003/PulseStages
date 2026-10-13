// detekt treats only src/test as tests; this is the test source set of the debug variant (the demo code is debug-only).
@file:Suppress("FunctionNaming", "MagicNumber")

package io.github.alien2003.pulsestages.screenshots

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.data.hc.fake.FakeSettingsRepository
import io.github.alien2003.pulsestages.data.hc.fake.RecordingLogger
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.demo.DemoNightsSource
import io.github.alien2003.pulsestages.testutil.FakeDocumentStore
import io.github.alien2003.pulsestages.ui.TopLevelDestination
import io.github.alien2003.pulsestages.ui.common.JobPhase
import io.github.alien2003.pulsestages.ui.common.JobUi
import io.github.alien2003.pulsestages.ui.common.UiMessage
import io.github.alien2003.pulsestages.ui.common.UiText
import io.github.alien2003.pulsestages.ui.nights.ComparisonState
import io.github.alien2003.pulsestages.ui.nights.DetailLoad
import io.github.alien2003.pulsestages.ui.nights.InspectionState
import io.github.alien2003.pulsestages.ui.nights.NightDetailActions
import io.github.alien2003.pulsestages.ui.nights.NightDetailContent
import io.github.alien2003.pulsestages.ui.nights.NightDetailRoute
import io.github.alien2003.pulsestages.ui.nights.NightDetailUiState
import io.github.alien2003.pulsestages.ui.nights.NightDetailViewModel
import io.github.alien2003.pulsestages.ui.nights.NightListItem
import io.github.alien2003.pulsestages.ui.nights.NightsRoute
import io.github.alien2003.pulsestages.ui.nights.NightsScreen
import io.github.alien2003.pulsestages.ui.nights.NightsUiState
import io.github.alien2003.pulsestages.ui.nights.NightsViewModel
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Renders the Nights list (loading, error, empty, the debug build's synthetic demo nights and a night in every status)
 * and the night detail (the demo night with the epoch details and the RULES comparison open, every inspection state,
 * every status, all warnings and flags, the jobs, the delete confirmation and "not found"), in English and Russian (also
 * at font scale 2 and in a wide window, where the list shows the detail beside it). Any exception fails the test, and
 * so does a text that still shows a format placeholder.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "en-rUS-w411dp-h891dp-port-xhdpi")
class NightsRenderTest(
    private val config: ScreenConfig,
) {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val host = ScreenHost(compose)
    private val clock: Clock = Clock.fixed(NOW, ZoneId.of("UTC"))
    private val logger = RecordingLogger()
    private val settings = FakeSettingsRepository()
    private val model: HybridModel = HybridModel.parse(File(MODEL_FILE).readText())
    private val source = DemoNightsSource(settings, { model }, Dispatchers.Default, clock)

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
    fun nightsListLoadingErrorEmptyDemoAndEveryStatus() {
        val viewModel = NightsViewModel(source, logger, SavedStateHandle())
        val detail = NightDetailViewModel(source, FakeDocumentStore(), logger, SavedStateHandle())
        host.show(TopLevelDestination.NIGHTS) { NightsRoute(onOpenNight = {}, viewModel = viewModel, detailViewModel = detail) }
        host.waitUntil {
            val loaded = (viewModel.state.value as? NightsUiState.Loaded)?.nights?.isNotEmpty() == true
            loaded && (!config.wide || detail.state.value.let { it.load == DetailLoad.LOADED && it.ready != null })
        }
        config.assertAppliedTo(host.resources)
        val nights = (viewModel.state.value as NightsUiState.Loaded).nights
        host.assertNoUnresolvedPlaceholders("demo nights")
        host.scrollLazyList("demo nights", nights.size)

        val everyStatus = statusItems(nights.first())
        host.showEach(
            TopLevelDestination.NIGHTS,
            listOf(
                "loading" to NightsUiState.Loading,
                "error" to NightsUiState.Error,
                "empty" to NightsUiState.Loaded(emptyList()),
                "empty demo" to NightsUiState.Loaded(emptyList(), synthetic = true),
                "every status" to NightsUiState.Loaded(everyStatus + nights),
            ),
            lazyItems = { (it as? NightsUiState.Loaded)?.nights?.size ?: 0 },
        ) { state ->
            NightsScreen(
                state = state,
                wide = config.wide,
                selectedId = null,
                onSelect = {},
                detailPane = { id, modifier -> Text(id, modifier) },
            )
        }
    }

    /** A copy of [item] in every status (every not-estimated reason, too), with the flags and the stale marker. */
    private fun statusItems(item: NightListItem): List<NightListItem> {
        val statuses =
            NightStatus.entries.map { it to reasonOf(it) } + NotEstimatedReason.entries.map { NightStatus.NOT_ESTIMATED to "$it: detail" }
        return statuses.mapIndexed { index, (status, reason) ->
            item.copy(
                summary =
                    item.summary.copy(
                        id = 10_000L + index,
                        clientRecordId = "ps:status-$index",
                        status = status,
                        statusReason = reason,
                        elevatedHr = index % 2 == 0,
                        lowConfidence = index % 3 == 0,
                        calibrated = index % 2 == 1,
                        rulesFallback = index % 4 == 0,
                    ),
                stale = index % 2 == 0,
            )
        }
    }

    private fun reasonOf(status: NightStatus): String? =
        when (status) {
            NightStatus.WRITTEN -> null
            NightStatus.NOT_ESTIMATED -> "SESSION_TOO_SHORT: session of 12.0 min is shorter than 180 min"
            else -> "an English diagnostic with 100% in it"
        }

    @Test
    fun nightDetailWithEpochDetailsRulesComparisonAndWarnings() {
        val viewModel = NightDetailViewModel(source, FakeDocumentStore(), logger, SavedStateHandle())
        host.show(TopLevelDestination.NIGHTS) {
            NightDetailRoute(clientRecordId = DETAIL_NIGHT, viewModel = viewModel, onBack = {}, onWideLayout = {})
        }
        host.waitUntil { viewModel.state.value.let { it.load == DetailLoad.LOADED && it.ready != null } }
        config.assertAppliedTo(host.resources)
        val night = checkNotNull(viewModel.state.value.night)
        compose.runOnIdle { viewModel.selectAt(night.sessionStartMs + 150 * MINUTE_MS) }
        host.waitUntil { viewModel.state.value.selection != null }
        compose.runOnIdle { viewModel.compareWithRules() }
        host.waitUntil { viewModel.state.value.comparison is ComparisonState.Ready }
        val resources = host.resources
        compose.onNode(hasText(resources.getString(R.string.night_epoch_title).substringBefore('%'), substring = true)).assertExists()
        compose.onNode(hasText(resources.getString(R.string.night_compare_title))).assertExists()
        host.assertNoUnresolvedPlaceholders("demo night with an epoch and the comparison")
        compose.runOnIdle { viewModel.requestDelete() }
        host.idle()
        compose.onNode(isDialog()).assertExists()
        host.assertNoUnresolvedPlaceholders("delete confirmation")
        compose.runOnIdle { viewModel.dismissDelete() }
        host.idle()

        val loaded = viewModel.state.value
        host.showEach(TopLevelDestination.NIGHTS, detailVariants(loaded), check = {
            name,
            variant,
            ->
            checkWarnings(name, variant)
        }) { state ->
            NightDetailContent(state = state, actions = NightDetailActions())
        }

        val missing = NightDetailViewModel(source, FakeDocumentStore(), logger, SavedStateHandle())
        host.show(
            TopLevelDestination.NIGHTS,
        ) { NightDetailRoute(clientRecordId = "ps:none", viewModel = missing, onBack = {}, onWideLayout = {}) }
        host.waitUntil { missing.state.value.load == DetailLoad.NOT_FOUND }
        compose.onNode(hasText(host.resources.getString(R.string.night_not_found_title))).assertExists()
        host.assertNoUnresolvedPlaceholders("night not found")
    }

    private fun checkWarnings(
        name: String,
        state: NightDetailUiState,
    ) {
        val night = state.night ?: return
        if (night.warnings.isNotEmpty()) {
            compose.onNode(hasText(host.resources.getString(R.string.night_detail_warnings))).assertExists("$name: warnings card")
        }
    }

    private fun detailVariants(loaded: NightDetailUiState): List<Pair<String, NightDetailUiState>> {
        val night = checkNotNull(loaded.night)
        val selection = checkNotNull(loaded.selection)
        val comparison = loaded.comparison as ComparisonState.Ready
        val idle = loaded.copy(selection = null, comparison = ComparisonState.None)
        return listOf(
            "loaded" to loaded,
            "every warning and flag, stale" to
                loaded.copy(
                    night =
                        night.copy(
                            elevatedHr = true,
                            lowConfidence = true,
                            calibrated = true,
                            rulesFallback = true,
                            warnings = WarningCode.entries,
                            nadirBpm = null,
                            modelId = null,
                        ),
                    stale = true,
                ),
            "epoch without data" to
                loaded.copy(
                    selection =
                        selection.copy(
                            writtenStage = null,
                            hasData = false,
                            hrRaw = null,
                            hrSmooth = null,
                            features = selection.features.map { it.copy(value = null) },
                        ),
                ),
            "comparison running" to loaded.copy(comparison = ComparisonState.Running),
            "comparison failed" to loaded.copy(comparison = ComparisonState.Failed(UiText.of(R.string.error_hc_transient))),
            "comparison without scores" to loaded.copy(comparison = comparison.copy(kappa = null, accuracy = null)),
            "inspection loading" to idle.copy(inspection = InspectionState.Loading),
            "nothing written, inspection failed" to
                idle.copy(
                    night = night.copy(stages = emptyList(), status = NightStatus.NOT_ESTIMATED),
                    inspection = InspectionState.Failed(false),
                ),
            "recompute pending" to loaded.copy(recompute = JobUi(JobPhase.PENDING)),
            "recompute retrying" to loaded.copy(recompute = JobUi(JobPhase.PENDING, retrying = true)),
            "recompute running" to loaded.copy(recompute = JobUi(JobPhase.RUNNING)),
            "exporting and deleting" to loaded.copy(exporting = true, deleting = true),
            "overwrite never" to loaded.copy(overwriteNever = true, synthetic = false),
            "message" to loaded.copy(messages = listOf(UiMessage(UiText.of(R.string.night_export_done, "pulsestages-2026-10-12.csv")))),
        ) +
            NotEstimatedReason.entries.map { "not estimated $it" to idle.copy(inspection = InspectionState.NotEstimated(null, it)) } +
            BlockedReason.entries.map { "blocked $it" to idle.copy(inspection = InspectionState.Blocked(it)) } +
            listOf(true, false).map { "inspection failed, retryable $it" to idle.copy(inspection = InspectionState.Failed(it)) } +
            NightStatus.entries.map { "status $it" to idle.copy(night = night.copy(status = it, statusReason = reasonOf(it))) }
    }

    private companion object {
        const val MODEL_FILE = "src/main/assets/models/hybrid_model.json"
        const val MINUTE_MS = 60_000L
        const val DETAIL_NIGHT = "ps:demo-2"

        /** 2026-10-13 09:30 UTC: the morning after the newest demo night. */
        val NOW: Instant = Instant.parse("2026-10-13T09:30:00Z")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = ScreenConfig.ALL.map { arrayOf(it) }
    }
}

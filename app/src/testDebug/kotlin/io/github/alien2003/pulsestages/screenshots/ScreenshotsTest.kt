// detekt treats only src/test as tests; this is the test source set of the debug variant (the demo code is debug-only).
@file:Suppress("FunctionNaming", "MagicNumber")

package io.github.alien2003.pulsestages.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.SavedStateHandle
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.fake.FakeSettingsRepository
import io.github.alien2003.pulsestages.data.hc.fake.RecordingLogger
import io.github.alien2003.pulsestages.data.hc.pipeline.SourceScanner
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.hc.usecase.LastRun
import io.github.alien2003.pulsestages.data.hc.usecase.PermissionStatus
import io.github.alien2003.pulsestages.data.hc.usecase.SpecialAccess
import io.github.alien2003.pulsestages.data.local.db.HomeCounters
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.OHEALTH_PACKAGE
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import io.github.alien2003.pulsestages.demo.DemoNightsSource
import io.github.alien2003.pulsestages.demo.DemoReferenceSource
import io.github.alien2003.pulsestages.testutil.FakeAppInfoResolver
import io.github.alien2003.pulsestages.testutil.FakeDocumentStore
import io.github.alien2003.pulsestages.testutil.FakeStaleNightsSource
import io.github.alien2003.pulsestages.ui.TopLevelDestination
import io.github.alien2003.pulsestages.ui.about.AboutViewModel
import io.github.alien2003.pulsestages.ui.compare.ComparePhase
import io.github.alien2003.pulsestages.ui.compare.CompareRoute
import io.github.alien2003.pulsestages.ui.compare.CompareViewModel
import io.github.alien2003.pulsestages.ui.home.HomeActions
import io.github.alien2003.pulsestages.ui.home.HomeScreen
import io.github.alien2003.pulsestages.ui.home.HomeUiState
import io.github.alien2003.pulsestages.ui.home.NotificationAccess
import io.github.alien2003.pulsestages.ui.home.SourceLabel
import io.github.alien2003.pulsestages.ui.nights.DetailLoad
import io.github.alien2003.pulsestages.ui.nights.NightDetailRoute
import io.github.alien2003.pulsestages.ui.nights.NightDetailViewModel
import io.github.alien2003.pulsestages.ui.nights.NightsRoute
import io.github.alien2003.pulsestages.ui.nights.NightsUiState
import io.github.alien2003.pulsestages.ui.nights.NightsViewModel
import io.github.alien2003.pulsestages.ui.settings.SettingsRoute
import io.github.alien2003.pulsestages.ui.settings.SettingsViewModel
import io.github.alien2003.pulsestages.ui.sources.SourcesRoute
import io.github.alien2003.pulsestages.ui.sources.SourcesViewModel
import io.github.alien2003.pulsestages.ui.theme.PulseStagesTheme
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * Renders the six screens of the README (docs/screenshots) from synthetic data on the JVM, with Robolectric's native
 * graphics: the Nights list, the night detail and Compare from the debug build's demo sources (synthetic nights from
 * core-algo's generator, estimated with the bundled model), Home, Settings and Sources from fixed example states. No
 * real health data is involved. Every test run renders them (a smoke test of every main screen) into
 * build/screenshots; `-PrecordScreenshots` writes them to docs/screenshots (see app/build.gradle.kts).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "en-rUS-w411dp-h891dp-port-xhdpi")
class ScreenshotsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val outputDir = File(System.getProperty(OUTPUT_PROPERTY) ?: "build/screenshots")
    private val clock: Clock = Clock.fixed(NOW, ZoneId.of("UTC"))
    private val logger = RecordingLogger()
    private val settings = FakeSettingsRepository()
    private val model: HybridModel = HybridModel.parse(File(MODEL_FILE).readText())
    private val models = HybridModelProvider { model }
    private val appInfo = FakeAppInfoResolver(LABELS)
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var defaultZone: TimeZone
    private lateinit var defaultLocale: Locale

    @Before
    fun fixZoneAndLocale() {
        defaultZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        activity?.pause()?.stop()?.destroy()
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    @Test
    @Config(qualifiers = "+h1900dp")
    fun home() {
        val state =
            HomeUiState(
                loading = false,
                availability = HcAvailability.AVAILABLE,
                permissions =
                    PermissionStatus(
                        granted = HealthPermissions.REQUIRED + HealthPermissions.SPECIAL,
                        missingRequired = emptySet(),
                        missingOptional = HealthPermissions.OPTIONAL,
                        history = SpecialAccess.GRANTED,
                        background = SpecialAccess.GRANTED,
                    ),
                sources = listOf(SourceLabel(OHEALTH_PACKAGE, LABELS.getValue(OHEALTH_PACKAGE))),
                lastRun = LastRun(NOW.toEpochMilli() - 2 * HOUR_MS, NOW.toEpochMilli() - 2 * HOUR_MS + 40_000, SyncOutcome.SUCCESS, null),
                nextRunMs = NOW.toEpochMilli() + HOUR_MS / 2,
                counters = HomeCounters(found = 14, estimated = 13, skipped = 1, failed = 0, stale = 1),
            )
        // A tall window shows the whole screen: status, permissions, schedule, counters and the actions.
        capture("home", TopLevelDestination.HOME, ready = { true }) {
            HomeScreen(state = state, notifications = NotificationAccess.GRANTED, actions = HomeActions())
        }
    }

    @Test
    fun nights() {
        val source = demoNights()
        val viewModel = NightsViewModel(source, logger, SavedStateHandle())
        val detail = NightDetailViewModel(source, FakeDocumentStore(), logger, SavedStateHandle())
        capture("nights", TopLevelDestination.NIGHTS, ready = {
            (viewModel.state.value as? NightsUiState.Loaded)?.nights?.isNotEmpty() ==
                true
        }) {
            NightsRoute(onOpenNight = {}, viewModel = viewModel, detailViewModel = detail)
        }
    }

    @Test
    fun nightDetail() {
        val viewModel = NightDetailViewModel(demoNights(), FakeDocumentStore(), logger, SavedStateHandle())
        var selected = false
        capture(
            "night-detail",
            TopLevelDestination.NIGHTS,
            ready = {
                val state = viewModel.state.value
                val ready = state.load == DetailLoad.LOADED && state.ready != null
                if (ready && !selected) {
                    // An epoch in the second sleep cycle, as if the user had tapped the hypnogram there.
                    viewModel.selectAt(checkNotNull(state.night).sessionStartMs + 150 * MINUTE_MS)
                    selected = true
                }
                ready && state.selection != null
            },
        ) {
            NightDetailRoute(clientRecordId = DETAIL_NIGHT, viewModel = viewModel, onBack = {}, onWideLayout = {})
        }
    }

    @Test
    fun compare() {
        val source = DemoReferenceSource(settings, models, Dispatchers.Default, clock)
        val viewModel = CompareViewModel(source, appInfo, clock, logger)
        capture("compare", TopLevelDestination.COMPARE, ready = {
            viewModel.state.value.let {
                it.phase == ComparePhase.READY &&
                    it.metrics != null
            }
        }) {
            CompareRoute(viewModel = viewModel)
        }
    }

    @Test
    fun settings() {
        val viewModel = SettingsViewModel(settings, FakeDocumentStore(), logger, FakeStaleNightsSource(settings))
        val about = AboutViewModel({ "1.0.0" }, models, logger)
        capture("settings", TopLevelDestination.SETTINGS, ready = { !viewModel.state.value.loading }) {
            SettingsRoute(onOpenSources = {}, onOpenLog = {}, viewModel = viewModel, aboutViewModel = about)
        }
    }

    @Test
    fun sources() {
        val ownPackage = "io.github.alien2003.pulsestages"
        val health = FakeHealthRepository(ownPackage) { NOW.toEpochMilli() }
        val nightOf = { daysAgo: Int -> NOW.toEpochMilli() - daysAgo * DAY_MS - 10 * HOUR_MS }
        for (daysAgo in 1..60) health.addNight(OHEALTH_PACKAGE, nightOf(daysAgo))
        for (daysAgo in 2..42 step 2) health.addNight(SLEEP_APP, nightOf(daysAgo) + 20 * MINUTE_MS)
        for (daysAgo in 1..55) health.addNight(ownPackage, nightOf(daysAgo), withHeartRate = false)
        val sourceSettings = FakeSettingsRepository(AppSettings())
        val scanner = SourceScanner(health, sourceSettings, clock, ownPackage)
        val viewModel = SourcesViewModel(scanner, sourceSettings, appInfo, logger, ownPackage)
        capture(
            "sources",
            TopLevelDestination.SETTINGS,
            ready = { !viewModel.state.value.loading && viewModel.state.value.items.size == 3 },
        ) {
            SourcesRoute(onBack = {}, viewModel = viewModel)
        }
    }

    private fun demoNights() = DemoNightsSource(settings, models, Dispatchers.Default, clock)

    /** Shows [content] in the app shell with [tab] selected, waits until [ready], runs [prepare] and writes `<name>.png`. */
    private fun capture(
        name: String,
        tab: TopLevelDestination,
        ready: () -> Boolean,
        prepare: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity = controller
        controller.get().setContent {
            PulseStagesTheme(darkTheme = false, dynamicColor = false) { Shell(tab, content) }
        }
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            ready()
        }
        compose.waitForIdle()
        prepare()
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        outputDir.mkdirs()
        val file = File(outputDir, "$name.png")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)) }
        assertTrue("$file was not written", file.length() > 0)
    }

    /** The app shell of PulseStagesNavHost: the screen above the four-tab navigation bar. */
    @Composable
    private fun Shell(
        selected: TopLevelDestination,
        content: @Composable () -> Unit,
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    TopLevelDestination.entries.forEach { tab ->
                        val label = stringResource(tab.label)
                        NavigationBarItem(
                            selected = tab == selected,
                            onClick = {},
                            icon = { Icon(painter = painterResource(tab.icon), contentDescription = label) },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)) {
                content()
            }
        }
    }

    private companion object {
        const val OUTPUT_PROPERTY = "pulsestages.screenshots.dir"
        const val MODEL_FILE = "src/main/assets/models/hybrid_model.json"
        const val TIMEOUT_MS = 120_000L
        const val PNG_QUALITY = 100
        const val MINUTE_MS = 60_000L
        const val DETAIL_NIGHT = "ps:demo-2"
        const val SLEEP_APP = "com.urbandroid.sleep"

        /** 2026-10-13 09:30 UTC: the morning after the newest demo night. */
        val NOW: Instant = Instant.parse("2026-10-13T09:30:00Z")

        val LABELS: Map<String, String> =
            mapOf(
                OHEALTH_PACKAGE to "OHealth",
                SLEEP_APP to "Sleep as Android",
                "io.github.alien2003.pulsestages" to "PulseStages",
                "io.github.alien2003.pulsestages.demo.watch" to "Demo watch",
                "io.github.alien2003.pulsestages.demo.tracker" to "Demo sleep tracker",
            )
    }
}

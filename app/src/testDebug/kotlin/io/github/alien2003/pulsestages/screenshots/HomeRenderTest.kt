// detekt treats only src/test as tests; this is the test source set of the debug variant (the demo code is debug-only).
@file:Suppress("FunctionNaming", "MagicNumber")

package io.github.alien2003.pulsestages.screenshots

import android.app.Application
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.usecase.LastRun
import io.github.alien2003.pulsestages.data.hc.usecase.PermissionStatus
import io.github.alien2003.pulsestages.data.hc.usecase.SpecialAccess
import io.github.alien2003.pulsestages.data.local.db.BackfillStateEntity
import io.github.alien2003.pulsestages.data.local.db.BackfillStatus
import io.github.alien2003.pulsestages.data.local.db.HomeCounters
import io.github.alien2003.pulsestages.data.local.db.PersonalModelKind
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.OHEALTH_PACKAGE
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import io.github.alien2003.pulsestages.ui.TopLevelDestination
import io.github.alien2003.pulsestages.ui.common.JobPhase
import io.github.alien2003.pulsestages.ui.common.JobUi
import io.github.alien2003.pulsestages.ui.common.UiMessage
import io.github.alien2003.pulsestages.ui.common.UiText
import io.github.alien2003.pulsestages.ui.home.HomeActions
import io.github.alien2003.pulsestages.ui.home.HomeDialog
import io.github.alien2003.pulsestages.ui.home.HomeScreen
import io.github.alien2003.pulsestages.ui.home.HomeUiState
import io.github.alien2003.pulsestages.ui.home.NotificationAccess
import io.github.alien2003.pulsestages.ui.home.SourceLabel
import io.github.alien2003.pulsestages.work.worker.JobProgress
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Home screen in every Health Connect availability and permission state, with each notification access,
 * every job and backfill state, every last-run outcome and model, both dialogs and a Snackbar message, in English and
 * Russian (also at font scale 2 and in a wide window). Any exception fails the test, and so does a text that still
 * shows a format placeholder.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "en-rUS-w411dp-h891dp-port-xhdpi")
class HomeRenderTest(
    private val config: ScreenConfig,
) {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val host = ScreenHost(compose)

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
    fun everyAvailabilityPermissionAndJobState() {
        val permissionStates =
            accessStates().flatMap { (name, state) ->
                NotificationAccess.entries.map { access -> "$name, notifications $access" to (state to access) }
            }
        val others = workStates().map { (name, state) -> name to (state to NotificationAccess.GRANTED) }
        host.showEach(
            TopLevelDestination.HOME,
            permissionStates + others,
            check = { name, (state, _) -> if (state.dialog != null) compose.onNode(isDialog()).assertExists("$name: dialog") },
        ) { (state, notifications) -> HomeScreen(state = state, notifications = notifications, actions = HomeActions()) }
        config.assertAppliedTo(host.resources)
    }

    private val all =
        PermissionStatus(
            granted = HealthPermissions.REQUIRED + HealthPermissions.SPECIAL + HealthPermissions.OPTIONAL,
            missingRequired = emptySet(),
            missingOptional = emptySet(),
            history = SpecialAccess.GRANTED,
            background = SpecialAccess.GRANTED,
        )

    private val counters = HomeCounters(found = 14, estimated = 13, skipped = 1, failed = 0, stale = 0)

    private val ready =
        HomeUiState(
            loading = false,
            availability = HcAvailability.AVAILABLE,
            permissions = all,
            sources = listOf(SourceLabel(OHEALTH_PACKAGE, "OHealth"), SourceLabel("com.example.sleep", null)),
            lastRun = LastRun(NOW_MS - 2 * HOUR_MS, NOW_MS - 2 * HOUR_MS + 40_000, SyncOutcome.SUCCESS, null),
            nextRunMs = NOW_MS + HOUR_MS / 2,
            counters = counters,
        )

    /** Availability and permission states (each is shown with every notification access). */
    private fun accessStates(): List<Pair<String, HomeUiState>> =
        listOf(
            "loading" to HomeUiState(),
            "status failed" to HomeUiState(loading = false, failed = true),
            "availability unknown" to HomeUiState(loading = false),
            "update required" to HomeUiState(loading = false, availability = HcAvailability.PROVIDER_UPDATE_REQUIRED),
            "not supported" to HomeUiState(loading = false, availability = HcAvailability.NOT_SUPPORTED),
            "permissions unknown" to HomeUiState(loading = false, availability = HcAvailability.AVAILABLE),
            "nothing granted" to
                ready.copy(
                    permissions =
                        PermissionStatus(
                            granted = emptySet(),
                            missingRequired = HealthPermissions.REQUIRED,
                            missingOptional = HealthPermissions.OPTIONAL,
                            history = SpecialAccess.NOT_GRANTED,
                            background = SpecialAccess.NOT_GRANTED,
                        ),
                    sources = emptyList(),
                    lastRun = null,
                    nextRunMs = null,
                    counters = null,
                ),
            "special access not available" to
                ready.copy(permissions = all.copy(history = SpecialAccess.NOT_AVAILABLE, background = SpecialAccess.NOT_AVAILABLE)),
            "permission dialog blocked" to
                ready.copy(
                    permissions = all.copy(missingRequired = setOf(HealthPermissions.REQUIRED.first())),
                    permissionDialogBlocked = true,
                ),
            "ready" to ready,
        )

    /** Jobs, backfill results, last runs, models, dialogs and messages. */
    private fun workStates(): List<Pair<String, HomeUiState>> =
        listOf(
            "jobs running" to
                ready.copy(
                    estimateNow = JobUi(JobPhase.RUNNING),
                    backfill = JobUi(JobPhase.RUNNING, JobProgress(done = 3, total = 9, nightsFound = 12, nightsEstimated = 10)),
                    recompute = JobUi(JobPhase.RUNNING, JobProgress(done = 4, total = 20)),
                    erasing = true,
                    counters = counters.copy(failed = 2, stale = 5),
                    lastRun = LastRun(NOW_MS - MINUTE_MS, NOW_MS - DAY_MS, null, null),
                ),
            "jobs pending" to
                ready.copy(
                    estimateNow = JobUi(JobPhase.PENDING, retrying = true),
                    backfill = JobUi(JobPhase.PENDING),
                    recompute = JobUi(JobPhase.RUNNING),
                ),
            "overwrite never, stale nights" to ready.copy(overwritePolicy = OverwritePolicy.NEVER, counters = counters.copy(stale = 3)),
            "heart rate from any app" to ready.copy(hrSources = HrSourcesMode.ANY),
            "recompute confirmation" to ready.copy(dialog = HomeDialog.RECOMPUTE_ALL),
            "delete confirmation" to ready.copy(dialog = HomeDialog.DELETE_ALL),
            "message" to ready.copy(messages = listOf(UiMessage(UiText.plural(R.plurals.home_delete_done, 3)))),
        ) +
            BackfillStatus.entries.map { status -> "backfill $status" to ready.copy(backfillState = backfill(status)) } +
            SyncOutcome.entries.map { outcome ->
                "last run $outcome" to ready.copy(lastRun = LastRun(NOW_MS - HOUR_MS, NOW_MS - HOUR_MS + 5_000, outcome, "detail"))
            } +
            PersonalModelKind.entries.map { kind -> "model $kind" to ready.copy(activeModel = kind) }

    private fun backfill(status: BackfillStatus): BackfillStateEntity =
        BackfillStateEntity(
            status = status,
            rangeStartMs = NOW_MS - 400 * DAY_MS,
            rangeEndMs = NOW_MS,
            cursorMs = NOW_MS - 200 * DAY_MS,
            windowsTotal = 58,
            windowsDone = 29,
            nightsFound = 180,
            nightsEstimated = 171,
            nightsSkipped = 9,
            historyLimited = true,
            startedAt = NOW_MS - DAY_MS,
            updatedAt = NOW_MS - HOUR_MS,
        )

    private companion object {
        /** 2026-10-13 09:30 UTC. */
        const val NOW_MS = 1_791_883_800_000L
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
        const val DAY_MS = 24 * HOUR_MS

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun configs(): List<Array<Any>> = ScreenConfig.ALL.map { arrayOf(it) }
    }
}

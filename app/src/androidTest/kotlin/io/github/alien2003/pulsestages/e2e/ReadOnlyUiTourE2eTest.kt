package io.github.alien2003.pulsestages.e2e

import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alien2003.pulsestages.MainActivity
import io.github.alien2003.pulsestages.e2e.safety.E2eHealth
import io.github.alien2003.pulsestages.e2e.safety.LocalAppState
import io.github.alien2003.pulsestages.e2e.safety.ReadBudget
import io.github.alien2003.pulsestages.e2e.safety.SafetySnapshots
import io.github.alien2003.pulsestages.e2e.safety.WorkEntry
import io.github.alien2003.pulsestages.e2e.safety.WorkSnapshot
import io.github.alien2003.pulsestages.e2e.support.DemoFlag
import io.github.alien2003.pulsestages.e2e.support.DemoFlagBackup
import io.github.alien2003.pulsestages.e2e.support.E2eLogger
import io.github.alien2003.pulsestages.e2e.support.E2eStatusRule
import io.github.alien2003.pulsestages.e2e.support.E2eWorkManager
import io.github.alien2003.pulsestages.e2e.ui.SafeClicks
import io.github.alien2003.pulsestages.e2e.ui.Screenshots
import io.github.alien2003.pulsestages.e2e.ui.UiPreconditions
import io.github.alien2003.pulsestages.e2e.ui.UiTour
import io.github.alien2003.pulsestages.work.schedule.WorkRequests
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * A read-only tour of the REAL app (its own Hilt graph, data and Health Connect): MainActivity is started and every
 * screen is visited — Home (status, permissions, counters), the privacy page, Nights (the app's nights listed, the
 * All / Nights / Naps filter), one night's detail with the epoch panel and "Compare with RULES", Compare, Settings with
 * every group expanded and collapsed again, Sources, Log, About, and the debug build's demo mode switched on and back.
 *
 * The app's screens hold the app's own, UNGUARDED Health Connect repository, eraser and work scheduler: the barrier is
 * [SafeClicks], an allow-list of the few things the tour presses (no action that writes, deletes, schedules,
 * calibrates, resets, imports, exports or shares). The demo-mode flag is backed up on disk before it is touched
 * ([DemoFlagBackup]; the runner's cleanup step restores it after a crash) and restored to its exact previous value in
 * `finally`. Afterwards the app's own records, its settings file and its sync state must be unchanged, and no
 * user-requested work (estimate now, backfill, recompute) may have been enqueued in this process's WorkManager (the
 * in-memory one of [E2eWorkManager]: no work of the app can run here). A crash of the app fails the test. Screenshots
 * go to `e2e/ui/`, the report to `e2e/ui.json`.
 */
@RunWith(AndroidJUnit4::class)
class ReadOnlyUiTourE2eTest {
    // The v1 rule, like the app's Robolectric screen tests: the app's own coroutines run as in production.
    @Suppress("DEPRECATION")
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(E2eStatusRule()).around(compose)

    /**
     * The report.
     *
     * @property steps what each step found.
     * @property screenshots the screenshots taken.
     * @property demoFlagBefore the persisted demo-mode flag before the tour.
     * @property demoFlagAfter the same after it.
     * @property localBefore the app's settings file, sync state and demo flag before the tour.
     * @property localAfter the same after it.
     * @property refusedClicks clicks the safety check refused (must be 0).
     * @property workersRefused workers of the app that this process's WorkManager was asked to create (never run).
     */
    @Serializable
    data class Report(
        val steps: List<String>,
        val screenshots: List<String>,
        val demoFlagBefore: String,
        val demoFlagAfter: String,
        val localBefore: LocalAppState,
        val localAfter: LocalAppState,
        val refusedClicks: Int,
        val workersRefused: List<String>,
    )

    @Test
    fun everyScreenReadOnly() {
        // A flag an interrupted earlier tour left flipped is put back first (its backup holds the real value).
        DemoFlagBackup.restoreIfPresent()?.let { E2e.progress("restored the demo-mode flag an interrupted run left: $it") }
        val budget = ReadBudget.shared()
        // The screens read Health Connect themselves (the Compare tab most): leave them room in the quota window.
        runBlocking { budget.awaitHeadroom(APP_READS_RESERVE, "UI tour") }
        UiPreconditions.awaitUnlockedScreen()
        val logger = E2eLogger()
        val ownBefore = runBlocking { SafetySnapshots(E2eHealth.budgetedRepository(logger)).ownRecords() }
        val worksBefore = WorkSnapshot.take()
        val demo = DemoFlag.read()
        val localBefore = LocalAppState.now()
        DemoFlagBackup.save(demo)
        val tour = UiTour(compose, ownBefore.filterNot { it.isTestRecord })
        var restoreFailure: Throwable? = null
        try {
            visitEveryScreen(tour, demo)
        } finally {
            // Kept apart, so that it never hides a failure of the tour; the backup stays when the restore failed.
            restoreFailure =
                runCatching {
                    DemoFlag.restore(demo)
                    DemoFlagBackup.deleteAfterRestore(demo)
                }.exceptionOrNull()
            // What the app's own screens read is not seen by the budget: the room left for them counts as spent.
            budget.charge(APP_READS_RESERVE, "room left for the app's own reads during the UI tour")
            writeReport(tour, demo, localBefore)
        }
        restoreFailure?.let { throw it }
        assertEquals("the demo-mode flag after the tour", demo, DemoFlag.read())
        assertEquals("clicks refused by the safety check", 0, SafeClicks.refused.get())
        assertEquals("the app's settings file, sync state or demo flag changed during the UI tour", localBefore, LocalAppState.now())
        val ownAfter = runBlocking { SafetySnapshots(E2eHealth.reservedRepository(logger)).ownRecords() }
        assertEquals("the app's own records changed during the UI tour", ownBefore, ownAfter)
        val requested = newUserRequestedWorks(worksBefore, WorkSnapshot.take())
        assertTrue("the tour enqueued work: $requested", requested.isEmpty())
    }

    /** Starts MainActivity and visits every screen; the activity is finished again whatever happens. */
    private fun visitEveryScreen(
        tour: UiTour,
        demo: DemoFlag,
    ) {
        val intent = Intent(E2e.app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity: Activity = E2e.instrumentation.startActivitySync(intent)
        try {
            tour.home()
            tour.rationale()
            tour.nights()
            tour.nightDetail()
            tour.compare()
            tour.settings()
            tour.demoMode(demo)
            tour.backHome()
        } finally {
            runCatching { E2e.instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    private fun writeReport(
        tour: UiTour,
        demo: DemoFlag,
        localBefore: LocalAppState,
    ) {
        E2e.writeJson(
            "ui.json",
            Report(
                steps = tour.log,
                screenshots = Screenshots.taken,
                demoFlagBefore = demo.toString(),
                demoFlagAfter = DemoFlag.read().toString(),
                localBefore = localBefore,
                localAfter = LocalAppState.now(),
                refusedClicks = SafeClicks.refused.get(),
                workersRefused = E2eWorkManager.refusedWorkers(),
            ),
        )
    }

    /** Works of "estimate now", backfill or recompute that did not exist before the tour. */
    private fun newUserRequestedWorks(
        before: List<WorkEntry>,
        after: List<WorkEntry>,
    ): List<WorkEntry> {
        val known = before.mapTo(HashSet()) { it.id }
        val userNames = setOf(WorkRequests.Names.NOW, WorkRequests.Names.BACKFILL, WorkRequests.Names.RECOMPUTE_ALL)
        return after.filter { it.id !in known }.filter { work ->
            work.uniqueName in userNames || WorkRequests.Tags.BACKFILL in work.tags || WorkRequests.Tags.RECOMPUTE in work.tags ||
                (work.uniqueName == null && WorkRequests.Tags.ESTIMATE in work.tags)
        }
    }

    private companion object {
        /**
         * Read calls left free in the 15-minute window for the app's own screens (Home, Nights, a night's detail with
         * its re-estimate, the Compare tab's 90 days of reference nights, Sources), and charged after the tour.
         */
        const val APP_READS_RESERVE = 400
    }
}

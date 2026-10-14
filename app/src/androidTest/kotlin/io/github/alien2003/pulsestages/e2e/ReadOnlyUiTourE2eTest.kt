package io.github.alien2003.pulsestages.e2e

import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alien2003.pulsestages.MainActivity
import io.github.alien2003.pulsestages.e2e.safety.E2eHealth
import io.github.alien2003.pulsestages.e2e.safety.ReadBudget
import io.github.alien2003.pulsestages.e2e.safety.SafetySnapshots
import io.github.alien2003.pulsestages.e2e.safety.WorkEntry
import io.github.alien2003.pulsestages.e2e.safety.WorkSnapshot
import io.github.alien2003.pulsestages.e2e.support.E2eLogger
import io.github.alien2003.pulsestages.e2e.support.E2eStatusRule
import io.github.alien2003.pulsestages.e2e.ui.DemoFlag
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
 * No action that writes, deletes, schedules, calibrates, resets, imports, exports or shares is pressed
 * ([SafeClicks] refuses them); the demo-mode flag is restored to its exact previous value in `finally`. Afterwards the
 * app's own records must be unchanged and no user-requested work (estimate now, backfill, recompute) may have been
 * enqueued. A crash of the app fails the test. Screenshots go to `e2e/ui/`, the report to `e2e/ui.json`.
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
     * @property refusedClicks clicks the safety check refused (must be 0).
     */
    @Serializable
    data class Report(
        val steps: List<String>,
        val screenshots: List<String>,
        val demoFlagBefore: String,
        val demoFlagAfter: String,
        val refusedClicks: Int,
    )

    @Test
    fun everyScreenReadOnly() {
        // The screens read Health Connect themselves (the Compare tab most): leave them room in the quota window.
        runBlocking { ReadBudget.shared().awaitHeadroom(UI_HEADROOM, "UI tour") }
        UiPreconditions.requireUnlockedScreen()
        val snapshots = SafetySnapshots(E2eHealth.budgetedRepository(E2eLogger()))
        val ownBefore = runBlocking { snapshots.ownRecords() }
        val worksBefore = WorkSnapshot.take()
        val demo = DemoFlag.read()
        val tour = UiTour(compose, ownBefore.filterNot { it.isTestRecord })
        var activity: Activity? = null
        try {
            val intent =
                Intent(
                    E2e.app,
                    MainActivity::class.java,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            activity = E2e.instrumentation.startActivitySync(intent)
            tour.home()
            tour.rationale()
            tour.nights()
            tour.nightDetail()
            tour.compare()
            tour.settings()
            tour.demoMode(demo)
            tour.backHome()
        } finally {
            DemoFlag.restore(demo)
            activity?.let { started -> E2e.instrumentation.runOnMainSync { started.finish() } }
            E2e.writeJson(
                "ui.json",
                Report(tour.log, Screenshots.taken, demo.toString(), DemoFlag.read().toString(), SafeClicks.refused.get()),
            )
        }
        assertEquals("the demo-mode flag after the tour", demo, DemoFlag.read())
        assertEquals("clicks refused by the safety check", 0, SafeClicks.refused.get())
        val ownAfter = runBlocking { snapshots.ownRecords() }
        assertEquals("the app's own records changed during the UI tour", ownBefore, ownAfter)
        val requested = newUserRequestedWorks(worksBefore, WorkSnapshot.take())
        assertTrue("the tour enqueued work: $requested", requested.isEmpty())
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
        const val UI_HEADROOM = 700
    }
}

package io.github.alien2003.pulsestages.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alien2003.pulsestages.e2e.safety.WorkEntry
import io.github.alien2003.pulsestages.e2e.safety.WorkSnapshot
import io.github.alien2003.pulsestages.e2e.support.E2eStatusRule
import io.github.alien2003.pulsestages.work.schedule.WorkRequests
import kotlinx.serialization.Serializable
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The schedule of the app's background work as WorkManager holds it (states, next run, constraints, intervals).
 * READ-ONLY: only WorkManager queries; nothing is enqueued, cancelled or run. Writes `e2e/schedule.json`.
 */
@RunWith(AndroidJUnit4::class)
class ScheduleReadOnlyE2eTest {
    @get:Rule
    val status = E2eStatusRule()

    /**
     * The report.
     *
     * @property takenAtMs when it was read.
     * @property works every work of the app.
     * @property periodicScheduled the periodic check is enqueued.
     * @property dailyScheduled the daily run is enqueued.
     */
    @Serializable
    data class ScheduleReport(
        val takenAtMs: Long,
        val works: List<WorkEntry>,
        val periodicScheduled: Boolean,
        val dailyScheduled: Boolean,
    )

    @Test
    fun reportTheSchedule() {
        val works = WorkSnapshot.take()
        val unfinished = setOf("ENQUEUED", "RUNNING", "BLOCKED")
        val report =
            ScheduleReport(
                takenAtMs = System.currentTimeMillis(),
                works = works,
                periodicScheduled = works.any { it.uniqueName == WorkRequests.Names.PERIODIC && it.state in unfinished },
                dailyScheduled = works.any { it.uniqueName == WorkRequests.Names.DAILY && it.state in unfinished },
            )
        E2e.writeJson("schedule.json", report)
        works.forEach { work ->
            val next = work.nextScheduleTimeMillis.takeIf { it != Long.MAX_VALUE }?.let { Instant.ofEpochMilli(it).toString() } ?: "-"
            E2e.progress("work ${work.uniqueName ?: work.tags}: ${work.state}, next $next, attempts ${work.runAttemptCount}")
        }
        // Informational: an app that never ran since install has no schedule yet; the report says so.
        assertTrue("WorkManager answered", works.all { it.id.isNotBlank() })
    }
}

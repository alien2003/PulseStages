package io.github.alien2003.pulsestages.ui.common

import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.work.run.OutcomeKind
import io.github.alien2003.pulsestages.work.run.RetryCause
import io.github.alien2003.pulsestages.work.run.RunReport
import io.github.alien2003.pulsestages.work.run.RunSummary
import io.github.alien2003.pulsestages.work.schedule.JobState
import io.github.alien2003.pulsestages.work.schedule.WorkStatus
import io.github.alien2003.pulsestages.work.worker.JobProgress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkResultsTest {
    private fun report(
        kind: OutcomeKind,
        summary: RunSummary? = null,
        blocked: BlockedReason? = null,
    ) = RunReport(kind, summary, blocked, retryCause = if (kind == OutcomeKind.RETRY_EXHAUSTED) RetryCause.QUOTA else null, detail = null)

    @Test
    fun `job phases follow the WorkManager state`() {
        assertEquals(JobUi(), JobUi.of(WorkStatus.NOT_SCHEDULED))
        assertEquals(JobUi(JobPhase.PENDING), JobUi.of(WorkStatus(JobState.ENQUEUED)))
        assertEquals(JobUi(JobPhase.PENDING, retrying = true), JobUi.of(WorkStatus(JobState.ENQUEUED, runAttemptCount = 2)))
        assertEquals(JobUi(JobPhase.PENDING), JobUi.of(WorkStatus(JobState.BLOCKED)))
        val progress = JobProgress(1, 4)
        assertEquals(JobUi(JobPhase.RUNNING, progress), JobUi.of(WorkStatus(JobState.RUNNING, progress = progress)))
        listOf(JobState.SUCCEEDED, JobState.FAILED, JobState.CANCELLED).forEach { state ->
            assertFalse(JobUi.of(WorkStatus(state)).active, "$state")
        }
    }

    @Test
    fun `a watch ignores results until requested and the result of an earlier run`() {
        val watch = JobWatch()
        val earlier = WorkStatus(JobState.SUCCEEDED, lastOutput = report(OutcomeKind.COMPLETED, RunSummary(estimated = 1)))
        assertNull(watch.onStatus(earlier), "not requested")

        watch.request()
        assertNull(watch.onStatus(earlier), "the same old result again")
        assertNull(watch.onStatus(WorkStatus(JobState.ENQUEUED)))
        assertNull(watch.onStatus(WorkStatus(JobState.RUNNING)))
        val done = WorkStatus(JobState.SUCCEEDED, lastOutput = report(OutcomeKind.COMPLETED, RunSummary(estimated = 2)))
        assertEquals(done, watch.onStatus(done))
        assertNull(watch.onStatus(done), "reported once")
    }

    @Test
    fun `a run that finished before its active states were seen is still reported when its result differs`() {
        val watch = JobWatch()
        watch.onStatus(WorkStatus.NOT_SCHEDULED)
        watch.request()
        assertNull(watch.onStatus(WorkStatus.NOT_SCHEDULED))
        val done = WorkStatus(JobState.FAILED, lastOutput = report(OutcomeKind.FAILED))
        assertEquals(done, watch.onStatus(done))
    }

    @Test
    fun `results are worded by outcome`() {
        val summary = RunSummary(found = 5, estimated = 3, skipped = 2)
        assertEquals(
            UiText.of(R.string.result_summary_estimate, UiText.plural(R.plurals.result_estimated, 3), 5, 2),
            WorkResultText.report(RunSubject.ESTIMATE, report(OutcomeKind.COMPLETED, summary)),
        )
        assertEquals(
            UiText.of(R.string.result_summary_backfill, UiText.plural(R.plurals.result_estimated, 3), 5, 2),
            WorkResultText.report(RunSubject.BACKFILL, report(OutcomeKind.COMPLETED, summary)),
        )
        assertEquals(
            UiText.of(R.string.result_night_recomputed),
            WorkResultText.report(RunSubject.RECOMPUTE_NIGHT, report(OutcomeKind.COMPLETED, RunSummary(found = 1, estimated = 1))),
        )
        assertEquals(
            UiText.of(R.string.result_night_not_recomputed),
            WorkResultText.report(RunSubject.RECOMPUTE_NIGHT, report(OutcomeKind.COMPLETED, RunSummary(found = 1, skipped = 1))),
        )
        assertEquals(
            UiText.of(R.string.result_already_running),
            WorkResultText.report(RunSubject.RECOMPUTE, report(OutcomeKind.COMPLETED, RunSummary(alreadyRunning = true))),
        )
        assertEquals(UiText.of(R.string.result_busy), WorkResultText.report(RunSubject.ESTIMATE, report(OutcomeKind.RETRY_EXHAUSTED)))
        assertEquals(UiText.of(R.string.result_failed), WorkResultText.report(RunSubject.ESTIMATE, report(OutcomeKind.FAILED)))
        assertEquals(
            UiText.of(R.string.error_hc_unavailable),
            WorkResultText.report(RunSubject.ESTIMATE, report(OutcomeKind.BLOCKED, blocked = BlockedReason.HEALTH_CONNECT_UNAVAILABLE)),
        )
        assertEquals(UiText.of(R.string.result_blocked_permission), WorkResultText.blocked(BlockedReason.PERMISSION_MISSING))
    }

    @Test
    fun `failed nights are appended to the summary`() {
        val text =
            WorkResultText.report(
                RunSubject.ESTIMATE,
                report(OutcomeKind.COMPLETED, RunSummary(found = 4, estimated = 2, failed = 1)),
            )
        val main = UiText.of(R.string.result_summary_estimate, UiText.plural(R.plurals.result_estimated, 2), 4, 0)
        assertEquals(UiText.of(R.string.result_with_failures, main, UiText.plural(R.plurals.result_failed_nights, 1)), text)
    }

    @Test
    fun `nights rewritten with late data are a second sentence of the summary, only when there are any`() {
        val main = UiText.of(R.string.result_summary_estimate, UiText.plural(R.plurals.result_estimated, 1), 4, 2)
        val summary = RunSummary(found = 4, estimated = 1, skipped = 2)
        assertEquals(
            UiText.of(R.string.result_with_refreshed, main, UiText.plural(R.plurals.result_refreshed, 1)),
            WorkResultText.report(RunSubject.ESTIMATE, report(OutcomeKind.COMPLETED, summary.copy(refreshed = 1))),
        )
        assertEquals(main, WorkResultText.report(RunSubject.ESTIMATE, report(OutcomeKind.COMPLETED, summary)))
        assertEquals(
            UiText.of(
                R.string.result_with_failures,
                UiText.of(R.string.result_with_refreshed, main, UiText.plural(R.plurals.result_refreshed, 2)),
                UiText.plural(R.plurals.result_failed_nights, 1),
            ),
            WorkResultText.report(RunSubject.ESTIMATE, report(OutcomeKind.COMPLETED, summary.copy(failed = 1, refreshed = 2))),
            "failures stay the last sentence",
        )
    }

    @Test
    fun `a sync request to Sleep as Android follows the refreshed nights and precedes the failures`() {
        val main = UiText.of(R.string.result_summary_estimate, UiText.plural(R.plurals.result_estimated, 1), 4, 2)
        val summary = RunSummary(found = 4, estimated = 1, skipped = 2, sleepAsAndroidSyncRequested = true)
        val saa = UiText.of(R.string.result_saa_sync_requested)
        assertEquals(
            UiText.of(R.string.result_with_saa_sync, main, saa),
            WorkResultText.report(RunSubject.ESTIMATE, report(OutcomeKind.COMPLETED, summary)),
        )
        assertEquals(
            UiText.of(
                R.string.result_with_failures,
                UiText.of(
                    R.string.result_with_saa_sync,
                    UiText.of(R.string.result_with_refreshed, main, UiText.plural(R.plurals.result_refreshed, 2)),
                    saa,
                ),
                UiText.plural(R.plurals.result_failed_nights, 1),
            ),
            WorkResultText.report(RunSubject.ESTIMATE, report(OutcomeKind.COMPLETED, summary.copy(failed = 1, refreshed = 2))),
            "failures stay the last sentence",
        )
        val backfill = RunSummary(found = 3, estimated = 3, sleepAsAndroidSyncRequested = true)
        assertEquals(
            UiText.of(
                R.string.result_with_saa_sync,
                UiText.of(R.string.result_summary_backfill, UiText.plural(R.plurals.result_estimated, 3), 3, 0),
                saa,
            ),
            WorkResultText.report(RunSubject.BACKFILL, report(OutcomeKind.COMPLETED, backfill)),
        )
        assertEquals(
            UiText.of(R.string.result_with_saa_sync, UiText.of(R.string.result_night_recomputed), saa),
            WorkResultText.report(RunSubject.RECOMPUTE_NIGHT, report(OutcomeKind.COMPLETED, backfill.copy(found = 1, estimated = 1))),
        )
    }

    @Test
    fun `finished statuses without output and cancellations have a text, active ones none`() {
        assertEquals(UiText.of(R.string.result_done), WorkResultText.of(RunSubject.ESTIMATE, WorkStatus(JobState.SUCCEEDED)))
        assertEquals(UiText.of(R.string.result_failed), WorkResultText.of(RunSubject.ESTIMATE, WorkStatus(JobState.FAILED)))
        assertEquals(UiText.of(R.string.result_cancelled), WorkResultText.of(RunSubject.BACKFILL, WorkStatus(JobState.CANCELLED)))
        assertNull(WorkResultText.of(RunSubject.ESTIMATE, WorkStatus(JobState.RUNNING)))
        assertTrue(JobState.BLOCKED.isActive)
        assertFalse(JobState.NOT_SCHEDULED.isActive)
    }
}

package io.github.alien2003.pulsestages.work.run

import androidx.work.ListenableWorker
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WorkResultsTest {
    private val limits = RetryLimits(maxRetries = 5, maxUnexpectedRetries = 3)
    private val completed = WorkOutcome.Completed(RunSummary(found = 4, estimated = 3, skipped = 1, stale = 1, failed = 0, superseded = 2))

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 10, 100])
    fun `completed and blocked runs succeed on every attempt (no retry storm)`(attempt: Int) {
        assertEquals(ResultKind.SUCCESS, WorkResults.kindOf(completed, attempt, limits))
        assertEquals(
            ResultKind.SUCCESS,
            WorkResults.kindOf(WorkOutcome.Blocked(BlockedReason.BACKGROUND_PERMISSION_MISSING), attempt, limits),
        )
        assertEquals(ResultKind.SUCCESS, WorkResults.kindOf(WorkOutcome.Blocked(BlockedReason.HEALTH_CONNECT_UNAVAILABLE), attempt, limits))
    }

    @Test
    fun `quota and transient errors retry until the limit, then fail`() {
        val quota = WorkOutcome.Retry(RetryCause.QUOTA, "rate limit")
        val transient = WorkOutcome.Retry(RetryCause.TRANSIENT, "binder")

        assertEquals((0..4).map { ResultKind.RETRY }, (0..4).map { WorkResults.kindOf(quota, it, limits) })
        assertEquals(ResultKind.FAILURE, WorkResults.kindOf(quota, 5, limits))
        assertEquals(ResultKind.RETRY, WorkResults.kindOf(transient, 4, limits))
        assertEquals(ResultKind.FAILURE, WorkResults.kindOf(transient, 6, limits))
    }

    @Test
    fun `unexpected errors are retried at most N times, then the work fails`() {
        val unexpected = WorkOutcome.Unexpected("IllegalStateException: boom")

        assertEquals(
            listOf(ResultKind.RETRY, ResultKind.RETRY, ResultKind.RETRY, ResultKind.FAILURE),
            (0..3).map {
                WorkResults.kindOf(unexpected, it, limits)
            },
        )
        assertInstanceOf(ListenableWorker.Result.Retry::class.java, WorkResults.toResult(unexpected, 2, limits))
        val failure = assertInstanceOf(ListenableWorker.Result.Failure::class.java, WorkResults.toResult(unexpected, 3, limits))
        val report = checkNotNull(WorkResults.parse(failure.outputData))
        assertEquals(OutcomeKind.FAILED, report.kind)
        assertEquals("IllegalStateException: boom", report.detail)
    }

    @Test
    fun `a permanent failure fails the work at once`() {
        val failed = WorkOutcome.Failed("InvalidData: bad record")

        assertEquals(listOf(ResultKind.FAILURE, ResultKind.FAILURE), listOf(0, 2).map { WorkResults.kindOf(failed, it, limits) })
        val failure = assertInstanceOf(ListenableWorker.Result.Failure::class.java, WorkResults.toResult(failed, 0, limits))
        val report = checkNotNull(WorkResults.parse(failure.outputData))
        assertEquals(OutcomeKind.FAILED, report.kind)
        assertEquals("InvalidData: bad record", report.detail)
    }

    @Test
    fun `the output of a completed run carries its counters`() {
        val success = assertInstanceOf(ListenableWorker.Result.Success::class.java, WorkResults.toResult(completed, 0, limits))

        val report = checkNotNull(WorkResults.parse(success.outputData))

        assertEquals(OutcomeKind.COMPLETED, report.kind)
        assertEquals(completed.summary, report.summary)
        assertNull(report.blockedReason)
    }

    @Test
    fun `a blocked run is a success whose output says why`() {
        val success =
            assertInstanceOf(
                ListenableWorker.Result.Success::class.java,
                WorkResults.toResult(WorkOutcome.Blocked(BlockedReason.BACKGROUND_PERMISSION_MISSING, "x".repeat(5_000)), 0, limits),
            )

        val report = checkNotNull(WorkResults.parse(success.outputData))

        assertEquals(OutcomeKind.BLOCKED, report.kind)
        assertEquals(BlockedReason.BACKGROUND_PERMISSION_MISSING, report.blockedReason)
        assertEquals(1_000, report.detail?.length, "details are cut to stay far below WorkManager's 10 KB limit")
    }

    @Test
    fun `retries that ran out are reported with their cause`() {
        val failure =
            assertInstanceOf(
                ListenableWorker.Result.Failure::class.java,
                WorkResults.toResult(WorkOutcome.Retry(RetryCause.QUOTA), 5, limits),
            )

        assertEquals(RetryCause.QUOTA, WorkResults.parse(failure.outputData)?.retryCause)
        assertEquals(OutcomeKind.RETRY_EXHAUSTED, WorkResults.parse(failure.outputData)?.kind)
    }
}

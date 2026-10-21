package io.github.alien2003.pulsestages.e2e.safety

import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.HrvSample
import io.github.alien2003.pulsestages.algo.model.RespiratoryRateSample
import io.github.alien2003.pulsestages.algo.model.Spo2Sample
import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.data.hc.EstimatedSessionWrite
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HcFeature
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.SleepChanges
import io.github.alien2003.pulsestages.data.hc.SleepOriginStats
import io.github.alien2003.pulsestages.data.hc.WriteResult
import kotlin.math.ceil

/**
 * Charges every read of [delegate] (the guarded real repository) to [budget] as [charging] says: one call before it
 * (a budgeted call waits for room in the 15-minute window and is refused when the run's budget is used up; a recorded
 * one never waits), then the further pages the result implies. The repository pages internally, so the pages are
 * estimated from the result: sleep sessions exactly, heart rate and steps with [PADDING_FACTOR] for the 24 hours of
 * left padding the repository reads and drops. Permission, feature and availability checks are no data reads and are
 * not charged, nor are the read-backs inside [writeEstimatedSession] (the app's VerifiedUpsert). Writes and deletes go
 * to [delegate] (the guard) unchanged.
 */
@Suppress("TooManyFunctions") // The wide HealthRepository boundary.
class BudgetedReadRepository(
    private val delegate: HealthRepository,
    private val budget: ReadBudget,
    private val charging: ReadCharging,
) : HealthRepository {
    override fun availability(): HcAvailability = delegate.availability()

    override suspend fun grantedPermissions(): Set<String> = delegate.grantedPermissions()

    override fun isFeatureAvailable(feature: HcFeature): Boolean = delegate.isFeatureAvailable(feature)

    override suspend fun readSleepSessions(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HcSleepSession> = charged("readSleepSessions", { pages(it.size) }) { delegate.readSleepSessions(startMs, endMs, packages) }

    override suspend fun oldestSleepSessionStartMs(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): Long? = charged("oldestSleepSessionStartMs", { 1 }) { delegate.oldestSleepSessionStartMs(startMs, endMs, packages) }

    override suspend fun readHeartRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrSample> = charged("readHeartRate", { paddedPages(it.size) }) { delegate.readHeartRate(startMs, endMs, packages) }

    override suspend fun readSteps(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<StepsSample> = charged("readSteps", { paddedPages(it.size) }) { delegate.readSteps(startMs, endMs, packages) }

    override suspend fun newestSteps(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): StepsSample? = charged("newestSteps", { 1 }) { delegate.newestSteps(startMs, endMs, packages) }

    override suspend fun readSpo2(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<Spo2Sample> = charged("readSpo2", { pages(it.size) }) { delegate.readSpo2(startMs, endMs, packages) }

    override suspend fun readRespiratoryRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<RespiratoryRateSample> =
        charged("readRespiratoryRate", {
            pages(it.size)
        }) { delegate.readRespiratoryRate(startMs, endMs, packages) }

    override suspend fun readHrv(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrvSample> = charged("readHrv", { pages(it.size) }) { delegate.readHrv(startMs, endMs, packages) }

    override suspend fun sleepChangesToken(packages: Set<String>): String =
        charged("getChangesToken", {
            1
        }) { delegate.sleepChangesToken(packages) }

    override suspend fun sleepChanges(token: String): SleepChanges = charged("getChanges", { 1 }) { delegate.sleepChanges(token) }

    override suspend fun readOwnSessions(
        startMs: Long,
        endMs: Long,
    ): List<HcSleepSession> = charged("readOwnSessions", { pages(it.size) }) { delegate.readOwnSessions(startMs, endMs) }

    override suspend fun scanSleepOrigins(
        startMs: Long,
        endMs: Long,
    ): List<SleepOriginStats> =
        charged("scanSleepOrigins", { stats ->
            pages(
                stats.sumOf {
                    it.sessionCount
                },
            )
        }) { delegate.scanSleepOrigins(startMs, endMs) }

    override suspend fun writeEstimatedSession(write: EstimatedSessionWrite): WriteResult = delegate.writeEstimatedSession(write)

    override suspend fun deleteOwnSessions(clientRecordIds: List<String>) = delegate.deleteOwnSessions(clientRecordIds)

    private suspend fun <T> charged(
        operation: String,
        pagesOf: (T) -> Int,
        call: suspend () -> T,
    ): T {
        budget.acquire(1, operation, charging)
        val result =
            try {
                call()
            } catch (e: HealthDataException.QuotaExceeded) {
                budget.onPlatformQuotaError()
                throw e
            }
        budget.charge(pagesOf(result) - 1, operation)
        return result
    }

    private companion object {
        const val PAGE = 1000.0

        /** Heart-rate and steps reads span the night plus 24 h of left padding: about 3.5 times the samples returned. */
        const val PADDING_FACTOR = 3.5

        fun pages(records: Int): Int = ceil(records.coerceAtLeast(1) / PAGE).toInt()

        fun paddedPages(samples: Int): Int = ceil((samples.coerceAtLeast(1) * PADDING_FACTOR) / PAGE).toInt().coerceAtLeast(1)
    }
}

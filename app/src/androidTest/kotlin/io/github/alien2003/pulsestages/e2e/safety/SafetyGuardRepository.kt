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
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.SleepChanges
import io.github.alien2003.pulsestages.data.hc.SleepOriginStats
import io.github.alien2003.pulsestages.data.hc.WriteResult
import io.github.alien2003.pulsestages.e2e.E2e
import java.util.concurrent.atomic.AtomicInteger

/**
 * A write or delete outside the suite's namespace was attempted on the real Health Connect. An [AssertionError], not
 * an Exception: the pipeline under test isolates failures per night with `catch (e: Exception)`, and a violation must
 * never be swallowed there. Every violation is also counted in [SafetyGuardRepository.violations].
 */
class SafetyViolation(
    message: String,
) : AssertionError(message)

/**
 * The only form in which the suite holds the real Health Connect repository ([E2eHealth.guardedRepository]): every
 * read is delegated unchanged; a write or delete is forwarded only when EVERY clientRecordId it names starts with
 * [E2e.TEST_NAMESPACE] (`ps-e2e:`) and has more after it, otherwise it throws [SafetyViolation] without touching Health
 * Connect. The app's own records (`ps:` + sha256) and other apps' records can therefore never be written or deleted
 * through it. The repository can only write SleepSessionRecord and only delete our own records by clientRecordId
 * (never by time range).
 *
 * Every method is implemented explicitly (no `by` delegation): a method added to [HealthRepository] later does not
 * compile here until it has been classified as a read or guarded as a write.
 */
@Suppress("TooManyFunctions") // The wide HealthRepository boundary.
class SafetyGuardRepository(
    private val delegate: HealthRepository,
) : HealthRepository {
    override fun availability(): HcAvailability = delegate.availability()

    override suspend fun grantedPermissions(): Set<String> = delegate.grantedPermissions()

    override fun isFeatureAvailable(feature: HcFeature): Boolean = delegate.isFeatureAvailable(feature)

    override suspend fun readSleepSessions(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HcSleepSession> = delegate.readSleepSessions(startMs, endMs, packages)

    override suspend fun oldestSleepSessionStartMs(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): Long? = delegate.oldestSleepSessionStartMs(startMs, endMs, packages)

    override suspend fun readHeartRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrSample> = delegate.readHeartRate(startMs, endMs, packages)

    override suspend fun readSteps(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<StepsSample> = delegate.readSteps(startMs, endMs, packages)

    override suspend fun readSpo2(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<Spo2Sample> = delegate.readSpo2(startMs, endMs, packages)

    override suspend fun readRespiratoryRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<RespiratoryRateSample> = delegate.readRespiratoryRate(startMs, endMs, packages)

    override suspend fun readHrv(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrvSample> = delegate.readHrv(startMs, endMs, packages)

    override suspend fun sleepChangesToken(packages: Set<String>): String = delegate.sleepChangesToken(packages)

    override suspend fun sleepChanges(token: String): SleepChanges = delegate.sleepChanges(token)

    override suspend fun readOwnSessions(
        startMs: Long,
        endMs: Long,
    ): List<HcSleepSession> = delegate.readOwnSessions(startMs, endMs)

    override suspend fun scanSleepOrigins(
        startMs: Long,
        endMs: Long,
    ): List<SleepOriginStats> = delegate.scanSleepOrigins(startMs, endMs)

    /** Writes [write] only when its clientRecordId is in the test namespace. */
    override suspend fun writeEstimatedSession(write: EstimatedSessionWrite): WriteResult {
        requireTestNamespace("writeEstimatedSession", listOf(write.clientRecordId))
        forwardedWrites.incrementAndGet()
        return delegate.writeEstimatedSession(write)
    }

    /** Deletes [clientRecordIds] only when every one of them is in the test namespace; an empty list deletes nothing. */
    override suspend fun deleteOwnSessions(clientRecordIds: List<String>) {
        if (clientRecordIds.isEmpty()) return
        requireTestNamespace("deleteOwnSessions", clientRecordIds)
        forwardedWrites.incrementAndGet()
        delegate.deleteOwnSessions(clientRecordIds)
    }

    /** The namespace rule. */
    companion object {
        /** Writes and deletes refused so far in this process. */
        val violations: AtomicInteger = AtomicInteger(0)

        /** Writes and deletes (all in the test namespace) forwarded to Health Connect so far in this process. */
        val forwardedWrites: AtomicInteger = AtomicInteger(0)

        /** True for a clientRecordId of the suite: [E2e.TEST_NAMESPACE] followed by at least one character, no whitespace. */
        fun isTestId(clientRecordId: String?): Boolean =
            clientRecordId != null &&
                clientRecordId.startsWith(E2e.TEST_NAMESPACE) &&
                clientRecordId.length > E2e.TEST_NAMESPACE.length &&
                clientRecordId.none { it.isWhitespace() }

        /** Throws [SafetyViolation] (and counts it) unless every one of [clientRecordIds] [isTestId]. */
        fun requireTestNamespace(
            operation: String,
            clientRecordIds: Collection<String>,
        ) {
            val outside = clientRecordIds.filterNot(::isTestId)
            if (outside.isEmpty()) return
            violations.incrementAndGet()
            throw SafetyViolation("$operation refused: ${outside.size} clientRecordId(s) outside ${E2e.TEST_NAMESPACE}: ${outside.take(3)}")
        }
    }
}

package io.github.alien2003.pulsestages.e2e.shadow

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Remembers every read of [delegate] by its exact arguments, so that the scenarios of the shadow test after the first
 * one (defaults, idempotency, recompute, policies) are served from memory and do not spend the app's Health Connect
 * quota again. The data are the ones the real repository returned for the same call. Changes-API calls, permission and
 * availability checks are not cached; writes and deletes go to [delegate] (whose safety guard refuses them unless they
 * are in the test namespace — the shadow above this class never forwards one).
 */
@Suppress("TooManyFunctions") // The wide HealthRepository boundary.
class CachingReadRepository(
    private val delegate: HealthRepository,
) : HealthRepository {
    private data class Key(
        val operation: String,
        val startMs: Long,
        val endMs: Long,
        val packages: Set<String>?,
    )

    /** A cached result (also null ones). */
    private class Entry(
        val value: Any?,
    )

    private val cache = ConcurrentHashMap<Key, Entry>()
    private val grantedCache = AtomicReference<Set<String>?>(null)

    /** Reads served from memory. */
    val hits: AtomicInteger = AtomicInteger(0)

    /** Reads that went to Health Connect. */
    val misses: AtomicInteger = AtomicInteger(0)

    /** The heart rate returned for exactly these arguments, if it was read. */
    @Suppress("UNCHECKED_CAST")
    fun cachedHeartRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrSample>? = cache[Key("hr", startMs, endMs, packages)]?.value as List<HrSample>?

    override fun availability(): HcAvailability = delegate.availability()

    /** The granted permissions, read once per test process (they do not change during the run). */
    override suspend fun grantedPermissions(): Set<String> =
        grantedCache.get() ?: delegate.grantedPermissions().also { grantedCache.set(it) }

    override fun isFeatureAvailable(feature: HcFeature): Boolean = delegate.isFeatureAvailable(feature)

    override suspend fun readSleepSessions(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HcSleepSession> = cached(Key("sleep", startMs, endMs, packages)) { delegate.readSleepSessions(startMs, endMs, packages) }

    override suspend fun oldestSleepSessionStartMs(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): Long? = cached(Key("oldest", startMs, endMs, packages)) { delegate.oldestSleepSessionStartMs(startMs, endMs, packages) }

    override suspend fun readHeartRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrSample> = cached(Key("hr", startMs, endMs, packages)) { delegate.readHeartRate(startMs, endMs, packages) }

    override suspend fun readSteps(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<StepsSample> = cached(Key("steps", startMs, endMs, packages)) { delegate.readSteps(startMs, endMs, packages) }

    override suspend fun newestSteps(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): StepsSample? = cached(Key("newest", startMs, endMs, packages)) { delegate.newestSteps(startMs, endMs, packages) }

    override suspend fun readSpo2(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<Spo2Sample> = cached(Key("spo2", startMs, endMs, packages)) { delegate.readSpo2(startMs, endMs, packages) }

    override suspend fun readRespiratoryRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<RespiratoryRateSample> = cached(Key("rr", startMs, endMs, packages)) { delegate.readRespiratoryRate(startMs, endMs, packages) }

    override suspend fun readHrv(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrvSample> = cached(Key("hrv", startMs, endMs, packages)) { delegate.readHrv(startMs, endMs, packages) }

    override suspend fun sleepChangesToken(packages: Set<String>): String = delegate.sleepChangesToken(packages)

    override suspend fun sleepChanges(token: String): SleepChanges = delegate.sleepChanges(token)

    override suspend fun readOwnSessions(
        startMs: Long,
        endMs: Long,
    ): List<HcSleepSession> = cached(Key("own", startMs, endMs, null)) { delegate.readOwnSessions(startMs, endMs) }

    override suspend fun writeEstimatedSession(write: EstimatedSessionWrite): WriteResult = delegate.writeEstimatedSession(write)

    override suspend fun deleteOwnSessions(clientRecordIds: List<String>) = delegate.deleteOwnSessions(clientRecordIds)

    override suspend fun scanSleepOrigins(
        startMs: Long,
        endMs: Long,
    ): List<SleepOriginStats> = cached(Key("origins", startMs, endMs, null)) { delegate.scanSleepOrigins(startMs, endMs) }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> cached(
        key: Key,
        read: suspend () -> T,
    ): T {
        cache[key]?.let {
            hits.incrementAndGet()
            return it.value as T
        }
        misses.incrementAndGet()
        val value = read()
        cache[key] = Entry(value)
        return value
    }
}

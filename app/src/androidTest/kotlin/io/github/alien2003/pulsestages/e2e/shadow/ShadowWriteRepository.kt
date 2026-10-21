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
import java.util.UUID

/**
 * A write the pipeline under test asked for. Never forwarded to Health Connect.
 *
 * @property phase the scenario that made it.
 * @property write what would have been written.
 * @property atMs when it was asked for.
 */
data class CapturedWrite(
    val phase: String,
    val write: EstimatedSessionWrite,
    val atMs: Long,
)

/**
 * A delete the pipeline under test asked for. Never forwarded to Health Connect.
 *
 * @property phase the scenario that made it.
 * @property clientRecordIds the ids.
 * @property atMs when it was asked for.
 */
data class CapturedDelete(
    val phase: String,
    val clientRecordIds: List<String>,
    val atMs: Long,
)

/**
 * The [HealthRepository] the shadow pipeline runs against: reads come from [reads] (the real Health Connect behind
 * the safety guard, the read budget and a cache), while the app's OWN namespace is simulated in memory. Writes and
 * deletes are captured in [writes] / [deletes] and applied to that simulation only; they are NEVER forwarded, so the
 * pipeline can run its full backfill / sync / recompute logic without any write reaching Health Connect.
 *
 * The simulation starts empty (a fresh install of the app): the app's real own records (`ps:`) are removed from every
 * read result, and the simulated ones are added where the real repository would return them (own-package and
 * any-origin sleep reads, [readOwnSessions], [scanSleepOrigins]). The upsert follows Health Connect with the app's
 * read-back verification: the stored record is the one written (VerifiedUpsert replaces a higher stale version), and
 * its id is stable per clientRecordId. A scenario may add synthetic heart rate to the reads ([extraHeartRate]).
 */
@Suppress("TooManyFunctions") // The wide HealthRepository boundary.
class ShadowWriteRepository(
    private val reads: HealthRepository,
    private val ownPackage: String,
) : HealthRepository {
    private val store = LinkedHashMap<String, HcSleepSession>()

    private val capturedWrites = mutableListOf<CapturedWrite>()
    private val capturedDeletes = mutableListOf<CapturedDelete>()

    /** The scenario the next captured calls belong to. */
    @Volatile
    var phase: String = "setup"

    /**
     * Synthetic heart rate a scenario adds to what Health Connect returned (late data, in memory only: never written
     * anywhere). Called with the arguments and the result of every heart-rate read, it returns the samples the
     * pipeline sees: a NEW list sorted by time (the result may be a cached list shared with other reads and must not be
     * modified). Null: the real heart rate.
     */
    @Volatile
    var extraHeartRate: ((startMs: Long, endMs: Long, packages: Set<String>?, samples: List<HrSample>) -> List<HrSample>)? = null

    /** Every captured write, in order. */
    val writes: List<CapturedWrite> get() = synchronized(this) { capturedWrites.toList() }

    /** Every captured delete, in order. */
    val deletes: List<CapturedDelete> get() = synchronized(this) { capturedDeletes.toList() }

    /** The simulated own records by clientRecordId. */
    val stored: Map<String, HcSleepSession> get() = synchronized(this) { LinkedHashMap(store) }

    /** The writes of [phase]. */
    fun writesOf(phase: String): List<CapturedWrite> = writes.filter { it.phase == phase }

    /** The deletes of [phase]. */
    fun deletesOf(phase: String): List<CapturedDelete> = deletes.filter { it.phase == phase }

    override fun availability(): HcAvailability = reads.availability()

    override suspend fun grantedPermissions(): Set<String> = reads.grantedPermissions()

    override fun isFeatureAvailable(feature: HcFeature): Boolean = reads.isFeatureAvailable(feature)

    override suspend fun readSleepSessions(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HcSleepSession> {
        val others = packages?.minus(ownPackage)
        val real =
            when {
                others == null -> reads.readSleepSessions(startMs, endMs, null)
                others.isEmpty() -> emptyList()
                else -> reads.readSleepSessions(startMs, endMs, others)
            }.filter { it.packageName != ownPackage }
        val simulated = if (packages == null || ownPackage in packages) ownStartingIn(startMs, endMs) else emptyList()
        return (real + simulated).sortedWith(compareBy({ it.startMs }, { it.uuid }))
    }

    override suspend fun oldestSleepSessionStartMs(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): Long? {
        val others = packages?.minus(ownPackage)
        val real =
            when {
                others == null -> {
                    reads
                        .readSleepSessions(
                            startMs,
                            endMs,
                            null,
                        ).filter { it.packageName != ownPackage }
                        .minOfOrNull { it.startMs }
                }

                others.isEmpty() -> {
                    null
                }

                else -> {
                    reads.oldestSleepSessionStartMs(startMs, endMs, others)
                }
            }
        val simulated = if (packages == null || ownPackage in packages) ownStartingIn(startMs, endMs).minOfOrNull { it.startMs } else null
        return listOfNotNull(real, simulated).minOrNull()
    }

    override suspend fun readHeartRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrSample> {
        val real = reads.readHeartRate(startMs, endMs, packages)
        return extraHeartRate?.invoke(startMs, endMs, packages, real) ?: real
    }

    override suspend fun readSteps(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<StepsSample> = reads.readSteps(startMs, endMs, packages)

    override suspend fun newestSteps(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): StepsSample? = reads.newestSteps(startMs, endMs, packages)

    override suspend fun readSpo2(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<Spo2Sample> = reads.readSpo2(startMs, endMs, packages)

    override suspend fun readRespiratoryRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<RespiratoryRateSample> = reads.readRespiratoryRate(startMs, endMs, packages)

    override suspend fun readHrv(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrvSample> = reads.readHrv(startMs, endMs, packages)

    /** A token of the source packages only (the pipeline never asks for its own package; the own namespace is simulated). */
    override suspend fun sleepChangesToken(packages: Set<String>): String = reads.sleepChangesToken(packages - ownPackage)

    /** The real changes of the source packages; the app's own records are left out (their namespace is simulated). */
    override suspend fun sleepChanges(token: String): SleepChanges {
        val page = reads.sleepChanges(token)
        return page.copy(upserted = page.upserted.filter { it.packageName != ownPackage })
    }

    override suspend fun readOwnSessions(
        startMs: Long,
        endMs: Long,
    ): List<HcSleepSession> = ownStartingIn(startMs, endMs)

    override suspend fun writeEstimatedSession(write: EstimatedSessionWrite): WriteResult {
        val now = System.currentTimeMillis()
        return synchronized(this) {
            capturedWrites += CapturedWrite(phase, write, now)
            val id =
                store[write.clientRecordId]?.uuid ?: UUID.nameUUIDFromBytes("$ownPackage/${write.clientRecordId}".toByteArray()).toString()
            store[write.clientRecordId] = sessionOf(write, id, now)
            WriteResult(id, write.clientRecordVersion)
        }
    }

    override suspend fun deleteOwnSessions(clientRecordIds: List<String>) {
        val ids = clientRecordIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return
        synchronized(this) {
            capturedDeletes += CapturedDelete(phase, ids, System.currentTimeMillis())
            ids.forEach { store.remove(it) }
        }
    }

    override suspend fun scanSleepOrigins(
        startMs: Long,
        endMs: Long,
    ): List<SleepOriginStats> {
        val real = reads.scanSleepOrigins(startMs, endMs).filter { it.packageName != ownPackage }
        val own = ownStartingIn(startMs, endMs)
        val simulated = if (own.isEmpty()) emptyList() else listOf(SleepOriginStats(ownPackage, own.size, own.maxOf { it.endMs }))
        return (real + simulated).sortedWith(compareByDescending<SleepOriginStats> { it.sessionCount }.thenBy { it.packageName })
    }

    private fun ownStartingIn(
        startMs: Long,
        endMs: Long,
    ): List<HcSleepSession> = synchronized(this) { store.values.filter { it.startMs in startMs until endMs }.sortedBy { it.startMs } }

    private fun sessionOf(
        write: EstimatedSessionWrite,
        id: String,
        now: Long,
    ): HcSleepSession =
        HcSleepSession(
            uuid = id,
            packageName = ownPackage,
            startMs = write.startMs,
            endMs = write.endMs,
            startZoneOffsetSec = write.startZoneOffsetSec,
            endZoneOffsetSec = write.endZoneOffsetSec,
            stages = write.stages.sortedWith(compareBy({ it.startMs }, { it.endMs })),
            title = write.title,
            notes = write.notes,
            clientRecordId = write.clientRecordId,
            clientRecordVersion = write.clientRecordVersion,
            lastModifiedMs = now,
        )
}

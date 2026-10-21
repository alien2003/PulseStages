package io.github.alien2003.pulsestages.data.hc

import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.HrvSample
import io.github.alien2003.pulsestages.algo.model.RespiratoryRateSample
import io.github.alien2003.pulsestages.algo.model.Spo2Sample
import io.github.alien2003.pulsestages.algo.model.StepsSample

/** Health Connect availability as seen by this app. */
enum class HcAvailability {
    /** Health Connect can be used. */
    AVAILABLE,

    /** The Health Connect APK (Android 9–13) is missing, disabled or too old: offer install/update. */
    PROVIDER_UPDATE_REQUIRED,

    /** Not supported on this device/profile. */
    NOT_SUPPORTED,
}

/** Optional platform features. */
enum class HcFeature {
    /** `FEATURE_READ_HEALTH_DATA_HISTORY`: READ_HEALTH_DATA_HISTORY can be requested (data older than 30 days). */
    READ_HISTORY,

    /** `FEATURE_READ_HEALTH_DATA_IN_BACKGROUND`: READ_HEALTH_DATA_IN_BACKGROUND can be requested. */
    READ_IN_BACKGROUND,
}

/**
 * A stage of a sleep session as stored in Health Connect.
 *
 * @property startMs start, epoch ms.
 * @property endMs end, epoch ms (exclusive).
 * @property type a SleepSessionRecord.STAGE_TYPE_* value.
 */
data class HcStage(
    val startMs: Long,
    val endMs: Long,
    val type: Int,
)

/**
 * A sleep session read from Health Connect.
 *
 * @property uuid Health Connect record id (metadata.id).
 * @property packageName metadata.dataOrigin.packageName.
 * @property startMs session start, epoch ms.
 * @property endMs session end, epoch ms.
 * @property startZoneOffsetSec start zone offset in seconds, null when the writer gave none.
 * @property endZoneOffsetSec end zone offset in seconds, null when the writer gave none.
 * @property stages the stages, as stored.
 * @property title the session title, if any.
 * @property notes the session notes, if any.
 * @property clientRecordId metadata.clientRecordId, if any.
 * @property clientRecordVersion metadata.clientRecordVersion.
 * @property lastModifiedMs metadata.lastModifiedTime, epoch ms.
 */
data class HcSleepSession(
    val uuid: String,
    val packageName: String,
    val startMs: Long,
    val endMs: Long,
    val startZoneOffsetSec: Int?,
    val endZoneOffsetSec: Int?,
    val stages: List<HcStage>,
    val title: String?,
    val notes: String?,
    val clientRecordId: String?,
    val clientRecordVersion: Long,
    val lastModifiedMs: Long,
)

/**
 * Per-origin statistics for the "Sources" screen.
 *
 * @property packageName the data origin.
 * @property sessionCount sleep sessions of that origin in the scanned range.
 * @property lastSessionEndMs end of its latest session, epoch ms.
 */
data class SleepOriginStats(
    val packageName: String,
    val sessionCount: Int,
    val lastSessionEndMs: Long,
)

/**
 * One page of the Changes API, already filtered to sleep sessions.
 *
 * @property upserted sessions inserted or updated since the token.
 * @property deletedIds Health Connect ids of deleted records (DeletionChange.recordId).
 * @property nextToken the token for the next call (the same token when nothing changed).
 * @property hasMore more changes are available: call again with [nextToken].
 * @property tokenExpired the token is no longer valid: take a new one and re-read by time range.
 */
data class SleepChanges(
    val upserted: List<HcSleepSession>,
    val deletedIds: List<String>,
    val nextToken: String,
    val hasMore: Boolean,
    val tokenExpired: Boolean,
)

/**
 * Our own estimated session to be written. Stage types are SleepSessionRecord.STAGE_TYPE_* values.
 *
 * @property clientRecordId deterministic id of the night (upsert key).
 * @property clientRecordVersion version; a stored record is only replaced by an equal or higher version.
 * @property startMs session start, epoch ms.
 * @property endMs session end, epoch ms.
 * @property startZoneOffsetSec start zone offset in seconds (always explicit).
 * @property endZoneOffsetSec end zone offset in seconds (always explicit).
 * @property stages sorted, contiguous stages inside the session.
 * @property title session title (≤ 1000 characters).
 * @property notes compact JSON notes.
 */
data class EstimatedSessionWrite(
    val clientRecordId: String,
    val clientRecordVersion: Long,
    val startMs: Long,
    val endMs: Long,
    val startZoneOffsetSec: Int,
    val endZoneOffsetSec: Int,
    val stages: List<HcStage>,
    val title: String,
    val notes: String,
)

/**
 * Result of a write: the Health Connect id and the version actually stored after read-back verification.
 *
 * @property recordId Health Connect id (metadata.id) of the stored record.
 * @property storedVersion clientRecordVersion of the stored record.
 */
data class WriteResult(
    val recordId: String,
    val storedVersion: Long,
)

/** Errors surfaced by [HealthRepository]; everything else is wrapped into [Transient]. */
sealed class HealthDataException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Health Connect is not available (not installed / update required / unsupported). */
    class Unavailable(
        message: String,
        cause: Throwable? = null,
    ) : HealthDataException(message, cause)

    /** A required permission is missing or was revoked. */
    class PermissionDenied(
        message: String,
        cause: Throwable? = null,
    ) : HealthDataException(message, cause)

    /** Rate limit / quota exceeded (ERROR_RATE_LIMIT_EXCEEDED, DATA_SYNC_IN_PROGRESS); retry with backoff. */
    class QuotaExceeded(
        message: String,
        cause: Throwable? = null,
    ) : HealthDataException(message, cause)

    /** IPC / IO / other transient error; retry later. */
    class Transient(
        message: String,
        cause: Throwable? = null,
    ) : HealthDataException(message, cause)

    /** Invalid request or data (bug or unexpected data); do not retry the same night blindly. */
    class InvalidData(
        message: String,
        cause: Throwable? = null,
    ) : HealthDataException(message, cause)
}

/**
 * Thin, fakeable boundary over Health Connect. Reads paginate internally, except the single-record reads
 * ([oldestSleepSessionStartMs], [newestSteps]). Read windows are padded on the left internally where needed (Health
 * Connect filters reads by record START time); heart-rate samples are then limited to the requested window by their
 * time, step records are filtered by overlap with it and returned with their own times. All methods throw only
 * [HealthDataException].
 *
 * `packages == null` means "any origin".
 */
interface HealthRepository {
    /** Availability of Health Connect on this device. */
    fun availability(): HcAvailability

    /** Health permissions currently granted to this app. */
    suspend fun grantedPermissions(): Set<String>

    /** Whether an optional feature is available on this Health Connect version. */
    fun isFeatureAvailable(feature: HcFeature): Boolean

    /** Sleep sessions whose START lies in `[startMs, endMs)`. */
    suspend fun readSleepSessions(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HcSleepSession>

    /**
     * Start (epoch ms) of the oldest sleep session whose START lies in `[startMs, endMs)`, or null when there is none.
     * One request for a single record (ascending by start time, page size 1): it never pages through the history.
     */
    suspend fun oldestSleepSessionStartMs(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): Long?

    /** Heart-rate samples with time in `[startMs, endMs)`, sorted by time. */
    suspend fun readHeartRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrSample>

    /** Step records overlapping `[startMs, endMs)`. */
    suspend fun readSteps(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<StepsSample>

    /**
     * The step record with the latest START in `[startMs, endMs)`, with its own times and count, or null when there is
     * none. One request for a single record (descending by start time, page size 1, no left padding): it never pages
     * through the history.
     */
    suspend fun newestSteps(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): StepsSample?

    /** SpO2 measurements with time in `[startMs, endMs)`. Optional channels return an empty list when the permission is not granted. */
    suspend fun readSpo2(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<Spo2Sample>

    /** Respiratory-rate measurements with time in `[startMs, endMs)`; empty when the permission is not granted. */
    suspend fun readRespiratoryRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<RespiratoryRateSample>

    /** HRV (RMSSD) measurements with time in `[startMs, endMs)`; empty when the permission is not granted. */
    suspend fun readHrv(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrvSample>

    /** A new changes token for SleepSessionRecord limited to [packages]. */
    suspend fun sleepChangesToken(packages: Set<String>): String

    /** One page of changes since [token]. */
    suspend fun sleepChanges(token: String): SleepChanges

    /** Our own sessions (written by this app) whose start lies in `[startMs, endMs)`. */
    suspend fun readOwnSessions(
        startMs: Long,
        endMs: Long,
    ): List<HcSleepSession>

    /**
     * Upserts our session by clientRecordId and verifies by reading it back. If the stored version is not the
     * one we wrote (e.g. a higher stale version exists), deletes it by clientRecordId and inserts again.
     */
    suspend fun writeEstimatedSession(write: EstimatedSessionWrite): WriteResult

    /** Deletes OUR sessions by clientRecordId (non-existent ids are ignored). Never touches other apps' data. */
    suspend fun deleteOwnSessions(clientRecordIds: List<String>)

    /** Origins that wrote sleep sessions starting in `[startMs, endMs)`. */
    suspend fun scanSleepOrigins(
        startMs: Long,
        endMs: Long,
    ): List<SleepOriginStats>
}

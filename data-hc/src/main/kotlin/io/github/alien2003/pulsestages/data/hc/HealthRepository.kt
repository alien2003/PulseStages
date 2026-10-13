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
enum class HcFeature { READ_HISTORY, READ_IN_BACKGROUND }

/** A stage of a sleep session as stored in Health Connect ([type] is a SleepSessionRecord.STAGE_TYPE_* value). */
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

/** Per-origin statistics for the "Sources" screen. */
data class SleepOriginStats(
    val packageName: String,
    val sessionCount: Int,
    val lastSessionEndMs: Long,
)

/** One page of the Changes API, already filtered to sleep sessions. */
data class SleepChanges(
    val upserted: List<HcSleepSession>,
    val deletedIds: List<String>,
    val nextToken: String,
    val hasMore: Boolean,
    val tokenExpired: Boolean,
)

/** Our own estimated session to be written. Stage types are SleepSessionRecord.STAGE_TYPE_* values. */
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

/** Result of a write: the Health Connect id and the version actually stored after read-back verification. */
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
 * Thin, fakeable boundary over Health Connect. All reads paginate internally. Read windows are padded on the
 * left internally where needed (Health Connect filters reads by record START time), and returned samples are
 * clipped to the requested window. All methods throw only [HealthDataException].
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

    /** Optional channels: return an empty list when the permission is not granted. */
    suspend fun readSpo2(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<Spo2Sample>

    suspend fun readRespiratoryRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<RespiratoryRateSample>

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

package io.github.alien2003.pulsestages.data.hc

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.HrvSample
import io.github.alien2003.pulsestages.algo.model.RespiratoryRateSample
import io.github.alien2003.pulsestages.algo.model.Spo2Sample
import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.data.hc.di.HealthConnectDispatcher
import io.github.alien2003.pulsestages.data.hc.di.OwnPackageName
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/**
 * [HealthRepository] over the Jetpack Health Connect client (connect-client 1.1.0) for API 28–37: the framework
 * module on Android 14+, the Health Connect APK on Android 9–13.
 *
 * Every call runs on [dispatcher], maps client exceptions with [HealthExceptionMapper] and retries quota / transient
 * errors with [retryPolicy] (delays through [sleeper]). Reads paginate with `pageSize = 1000` until the page token
 * is null or empty (the APK path returns `""` on the last page). Health Connect filters reads by record START time,
 * so series / interval records (heart rate, steps) are read with [SERIES_LEFT_PADDING_MS] of left padding and then
 * clipped to the requested window.
 *
 * On the Health Connect APK (Android 9–13) two provider errors arrive as a plain `RemoteException` (research/hc-api.md
 * §2 and §6): an outdated changes token (`CHANGES_TOKEN_OUTDATED`), which [sleepChanges] reports as
 * `tokenExpired`, and a delete of an id that no longer exists, which [deleteOwnSessions] resolves by reading which of
 * the ids still exist. [clock] bounds that read.
 */
@Suppress("TooManyFunctions", "LongParameterList") // Wide HealthRepository boundary; the test seam takes every collaborator.
@Singleton
class HealthConnectRepository internal constructor(
    private val access: HealthConnectAccess,
    private val platform: PlatformInfo,
    private val ownPackage: String,
    private val dispatcher: CoroutineDispatcher,
    private val sleeper: Sleeper,
    private val retryPolicy: RetryPolicy,
    private val logger: AppLogger,
    private val clock: Clock,
) : HealthRepository {
    /** The production constructor: the real client of the application context on the running platform. */
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @OwnPackageName ownPackage: String,
        @HealthConnectDispatcher dispatcher: CoroutineDispatcher,
        sleeper: Sleeper,
        retryPolicy: RetryPolicy,
        logger: AppLogger,
        clock: Clock,
    ) : this(
        HealthConnectAccess.of(context.applicationContext),
        PlatformInfo.CURRENT,
        ownPackage,
        dispatcher,
        sleeper,
        retryPolicy,
        logger,
        clock,
    )

    @Volatile
    private var cachedClient: HealthConnectClient? = null

    /** The phone that computes and writes the estimates (docs/health-connect.md §4.2 has the recordingMethod rationale). */
    private val device: Device by lazy(platform.device)

    /** Android 9–13: the Health Connect APK, whose errors carry less information than the framework's. */
    private val isApkPath: Boolean get() = platform.sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private val ownStore =
        object : OwnSessionStore {
            override suspend fun insert(write: EstimatedSessionWrite): String =
                call("insertRecords") {
                    val record = HcRecordMapper.toRecord(write, device)
                    val ids = client().insertRecords(listOf(record)).recordIdsList
                    ids.firstOrNull()
                        ?: throw HealthDataException.InvalidData("insertRecords returned no id for ${write.clientRecordId}")
                }

            override suspend fun findOwn(
                clientRecordId: String,
                startMs: Long,
            ): StoredOwnSession? =
                readSessions("readOwnSessions", startMs - VERIFY_LOOKUP_MARGIN_MS, startMs + VERIFY_LOOKUP_MARGIN_MS, setOf(ownPackage))
                    .firstOrNull { it.clientRecordId == clientRecordId }
                    ?.let { StoredOwnSession(it.uuid, it.clientRecordVersion, it.stages.size) }

            override suspend fun deleteOwn(clientRecordIds: List<String>) = deleteOwnSessions(clientRecordIds)
        }

    @Suppress("TooGenericExceptionCaught") // getSdkStatus must never crash the caller; any failure means "not usable".
    override fun availability(): HcAvailability =
        try {
            when (access.sdkStatus()) {
                HealthConnectClient.SDK_AVAILABLE -> HcAvailability.AVAILABLE
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcAvailability.PROVIDER_UPDATE_REQUIRED
                else -> HcAvailability.NOT_SUPPORTED
            }
        } catch (e: RuntimeException) {
            logger.w(TAG, "getSdkStatus failed; treating Health Connect as not supported", throwable = e)
            HcAvailability.NOT_SUPPORTED
        }

    override suspend fun grantedPermissions(): Set<String> =
        call("getGrantedPermissions") {
            client().permissionController.getGrantedPermissions()
        }

    @Suppress("TooGenericExceptionCaught") // Feature checks are best effort: a failure means "unavailable".
    override fun isFeatureAvailable(feature: HcFeature): Boolean {
        if (availability() != HcAvailability.AVAILABLE) return false
        val code =
            when (feature) {
                HcFeature.READ_HISTORY -> HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY
                HcFeature.READ_IN_BACKGROUND -> HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
            }
        return try {
            client().features.getFeatureStatus(code) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (e: RuntimeException) {
            logger.w(TAG, "getFeatureStatus($feature) failed; treating it as unavailable", throwable = e)
            false
        }
    }

    override suspend fun readSleepSessions(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HcSleepSession> = readSessions("readSleepSessions", startMs, endMs, packages)

    override suspend fun oldestSleepSessionStartMs(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): Long? {
        // An empty package set selects nothing (an empty origin filter would select every app).
        if (startMs >= endMs || packages?.isEmpty() == true) return null
        val request =
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs)),
                dataOriginFilter = packages?.map(::DataOrigin)?.toSet() ?: emptySet(),
                ascendingOrder = true,
                pageSize = 1,
            )
        // One page of one record; the page token is deliberately not followed.
        return call("oldestSleepSession") { client().readRecords(request) }
            .records
            .firstOrNull()
            ?.startTime
            ?.toEpochMilli()
    }

    override suspend fun readHeartRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrSample> =
        if (startMs >= endMs) {
            emptyList()
        } else {
            HcRecordMapper.heartRate(
                readAll("readHeartRate", HeartRateRecord::class, startMs - SERIES_LEFT_PADDING_MS, endMs, packages),
                startMs,
                endMs,
            )
        }

    override suspend fun readSteps(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<StepsSample> =
        if (startMs >= endMs) {
            emptyList()
        } else {
            HcRecordMapper.steps(
                readAll("readSteps", StepsRecord::class, startMs - SERIES_LEFT_PADDING_MS, endMs, packages),
                startMs,
                endMs,
            )
        }

    override suspend fun readSpo2(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<Spo2Sample> =
        if (!isGranted(HealthPermissions.READ_OXYGEN_SATURATION)) {
            emptyList()
        } else {
            HcRecordMapper.spo2(readAll("readSpo2", OxygenSaturationRecord::class, startMs, endMs, packages), startMs, endMs)
        }

    override suspend fun readRespiratoryRate(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<RespiratoryRateSample> =
        if (!isGranted(HealthPermissions.READ_RESPIRATORY_RATE)) {
            emptyList()
        } else {
            HcRecordMapper.respiratoryRate(
                readAll("readRespiratoryRate", RespiratoryRateRecord::class, startMs, endMs, packages),
                startMs,
                endMs,
            )
        }

    override suspend fun readHrv(
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HrvSample> =
        if (!isGranted(HealthPermissions.READ_HEART_RATE_VARIABILITY)) {
            emptyList()
        } else {
            HcRecordMapper.hrv(readAll("readHrv", HeartRateVariabilityRmssdRecord::class, startMs, endMs, packages), startMs, endMs)
        }

    override suspend fun sleepChangesToken(packages: Set<String>): String {
        // An empty origin filter would mean "every app": never ask for that.
        if (packages.isEmpty()) throw HealthDataException.InvalidData("a changes token needs at least one package")
        return call("getChangesToken") {
            client().getChangesToken(
                ChangesTokenRequest(
                    recordTypes = setOf(SleepSessionRecord::class),
                    dataOriginFilters = packages.map(::DataOrigin).toSet(),
                ),
            )
        }
    }

    override suspend fun sleepChanges(token: String): SleepChanges =
        when {
            // A blank token is invalid on every path (the platform answers "expired" for it anyway).
            token.isBlank() -> EXPIRED_CHANGES

            isApkPath -> apkChanges(token)

            else -> readChanges(token, retryPolicy)
        }

    /**
     * The APK reports an outdated token as a RemoteException (CHANGES_TOKEN_OUTDATED), indistinguishable from a binder
     * hiccup: one quick retry, then the token counts as expired. The caller re-reads the recent nights by time range,
     * which also covers whatever a merely transient failure would have hidden.
     */
    private suspend fun apkChanges(token: String): SleepChanges =
        try {
            readChanges(token, RetryPolicy(retryPolicy.delaysMs.take(APK_CHANGES_RETRIES)))
        } catch (e: HealthDataException.Transient) {
            if (!HealthExceptionMapper.isCausedByRemoteException(e)) throw e
            logger.w(
                TAG,
                "getChanges failed with a RemoteException on the Health Connect APK; treating the token as outdated",
                throwable = e,
            )
            EXPIRED_CHANGES
        }

    private suspend fun readChanges(
        token: String,
        policy: RetryPolicy,
    ): SleepChanges =
        call("getChanges", policy) {
            val response = client().getChanges(token)
            SleepChanges(
                upserted =
                    response.changes
                        .filterIsInstance<UpsertionChange>()
                        .mapNotNull { it.record as? SleepSessionRecord }
                        .map(HcRecordMapper::toSession),
                deletedIds = response.changes.filterIsInstance<DeletionChange>().map { it.recordId },
                nextToken = response.nextChangesToken,
                hasMore = response.hasMore,
                tokenExpired = response.changesTokenExpired,
            )
        }

    override suspend fun readOwnSessions(
        startMs: Long,
        endMs: Long,
    ): List<HcSleepSession> = readSessions("readOwnSessions", startMs, endMs, setOf(ownPackage))

    override suspend fun writeEstimatedSession(write: EstimatedSessionWrite): WriteResult = VerifiedUpsert.write(ownStore, write)

    override suspend fun deleteOwnSessions(clientRecordIds: List<String>) {
        val ids = clientRecordIds.filter { it.isNotBlank() }.distinct()
        // deleteRecords with both id lists empty throws IllegalArgumentException: never call it like that.
        if (ids.isEmpty()) return
        ids.chunked(DELETE_CHUNK_SIZE).forEach { deleteChunk(it) }
    }

    override suspend fun scanSleepOrigins(
        startMs: Long,
        endMs: Long,
    ): List<SleepOriginStats> = HcRecordMapper.originStats(readSessions("scanSleepOrigins", startMs, endMs, packages = null))

    /**
     * Deletes one chunk of our sessions by clientRecordId (Health Connect only ever deletes the caller's own
     * records). The framework ignores unknown ids. The APK path (Android 9–13) is documented to fail the whole call
     * with a RemoteException when an id does not exist, and a rate limit or a dead binder looks the same there. So on
     * the APK the chunk is tried once without retries; after a RemoteException our own sessions are read and only the
     * ids that still exist are deleted (with the normal retries). An id is skipped only when that read shows it is gone,
     * so a delete that really failed is thrown and the caller never forgets a record that still exists.
     */
    private suspend fun deleteChunk(chunk: List<String>) {
        if (!isApkPath) {
            deleteRecords(chunk, retryPolicy)
            return
        }
        try {
            deleteRecords(chunk, RetryPolicy.NONE)
        } catch (e: HealthDataException.Transient) {
            // The APK has no quota errors of its own (no platform error codes below API 34): only Transient is retried.
            if (HealthExceptionMapper.isCausedByRemoteException(e)) deleteExisting(chunk, e) else deleteRecords(chunk, retryPolicy)
        }
    }

    /** APK path after [failure]: deletes the ids of [chunk] that a read of our own sessions still finds. */
    private suspend fun deleteExisting(
        chunk: List<String>,
        failure: HealthDataException,
    ) {
        val stored =
            readSessions("readOwnSessions", 0L, clock.millis() + OWN_READ_END_PADDING_MS, setOf(ownPackage))
                .mapNotNullTo(HashSet()) { it.clientRecordId }
        val existing = chunk.filter { it in stored }
        if (existing.size < chunk.size) {
            logger.i(TAG, "deleteRecords failed (${failure.message}); ${chunk.size - existing.size} of ${chunk.size} id(s) no longer exist")
        }
        if (existing.isNotEmpty()) deleteRecords(existing, retryPolicy)
    }

    private suspend fun deleteRecords(
        clientRecordIds: List<String>,
        policy: RetryPolicy,
    ) {
        call("deleteRecords", policy) {
            client().deleteRecords(SleepSessionRecord::class, recordIdsList = emptyList(), clientRecordIdsList = clientRecordIds)
        }
    }

    private suspend fun readSessions(
        operation: String,
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<HcSleepSession> = readAll(operation, SleepSessionRecord::class, startMs, endMs, packages).map(HcRecordMapper::toSession)

    /** Reads every page of [type] records whose start lies in `[startMs, endMs)`. */
    private suspend fun <T : Record> readAll(
        operation: String,
        type: KClass<T>,
        startMs: Long,
        endMs: Long,
        packages: Set<String>?,
    ): List<T> {
        // An empty package set selects nothing (an empty origin filter would select every app).
        if (startMs >= endMs || packages?.isEmpty() == true) return emptyList()
        val filter = TimeRangeFilter.between(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs))
        val origins = packages?.map(::DataOrigin)?.toSet() ?: emptySet()
        val records = ArrayList<T>()
        var pageToken: String? = null
        do {
            val request =
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = filter,
                    dataOriginFilter = origins,
                    ascendingOrder = true,
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                )
            val response = call(operation) { client().readRecords(request) }
            records += response.records
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return records
    }

    private suspend fun isGranted(permission: String): Boolean = permission in grantedPermissions()

    private fun client(): HealthConnectClient =
        cachedClient ?: synchronized(this) {
            cachedClient ?: access.client().also { cachedClient = it }
        }

    private suspend fun <T> call(
        operation: String,
        policy: RetryPolicy = retryPolicy,
        block: suspend () -> T,
    ): T =
        withContext(dispatcher) {
            retryHealthCall(
                policy = policy,
                sleeper = sleeper,
                onRetry = { retry, delayMs, error ->
                    logger.w(TAG, "$operation: retry $retry in $delayMs ms after ${error.javaClass.simpleName}: ${error.message}")
                },
            ) {
                mapHealthErrors(
                    mapper = { error ->
                        HealthExceptionMapper.map(
                            operation = operation,
                            error = error,
                            platformErrorCode = platform.errorCodeOf,
                            isHealthConnectAvailable = { availability() == HcAvailability.AVAILABLE },
                        )
                    },
                    block = block,
                )
            }
        }

    /** Constants. */
    companion object {
        private const val TAG = "HealthConnect"

        /** Page size of every read (the platform allows 1..5000). */
        const val PAGE_SIZE: Int = 1000

        /** Left padding of heart-rate / steps reads: a series record may start up to a day before the window. */
        const val SERIES_LEFT_PADDING_MS: Long = 24L * 60 * 60 * 1000

        /** clientRecordIds per deleteRecords call. */
        const val DELETE_CHUNK_SIZE: Int = 100

        /** Window around the session start in which a written session is looked up for verification. */
        const val VERIFY_LOOKUP_MARGIN_MS: Long = 60_000L

        /** In-call retries of getChanges on the APK path before a RemoteException counts as an outdated token. */
        const val APK_CHANGES_RETRIES: Int = 1

        /** End of the read of all our sessions, after now (no session starts in the future; clocks may disagree). */
        const val OWN_READ_END_PADDING_MS: Long = 24L * 60 * 60 * 1000

        private val EXPIRED_CHANGES = SleepChanges(emptyList(), emptyList(), nextToken = "", hasMore = false, tokenExpired = true)
    }
}

/**
 * How [HealthConnectRepository] reaches Health Connect; replaced by a fake client in JVM tests.
 *
 * @property client creates the client (`HealthConnectClient.getOrCreate`; throws when Health Connect is unavailable).
 * @property sdkStatus `HealthConnectClient.getSdkStatus`.
 */
internal class HealthConnectAccess(
    val client: () -> HealthConnectClient,
    val sdkStatus: () -> Int,
) {
    companion object {
        /** The real client for [applicationContext] (the platform implementation stores the context it gets). */
        fun of(applicationContext: Context): HealthConnectAccess =
            HealthConnectAccess(
                client = { HealthConnectClient.getOrCreate(applicationContext) },
                sdkStatus = { HealthConnectClient.getSdkStatus(applicationContext) },
            )
    }
}

/**
 * Facts about the running platform that change the repository's behaviour; replaced in JVM tests.
 *
 * @property sdkInt `Build.VERSION.SDK_INT` (below 34 the Health Connect APK path is used).
 * @property errorCodeOf extracts the platform `HealthConnectException` error code ([PlatformErrorCodes.of]).
 * @property device the device written into our records' metadata.
 */
internal class PlatformInfo(
    val sdkInt: Int,
    val errorCodeOf: (Throwable) -> Int?,
    val device: () -> Device,
) {
    companion object {
        /** The running device: the phone, by manufacturer and model. */
        val CURRENT: PlatformInfo =
            PlatformInfo(
                sdkInt = Build.VERSION.SDK_INT,
                errorCodeOf = PlatformErrorCodes::of,
                device = { Device(type = Device.TYPE_PHONE, manufacturer = Build.MANUFACTURER, model = Build.MODEL) },
            )
    }
}

package io.github.alien2003.pulsestages.data.hc

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

/**
 * A [HealthConnectClient] for JVM tests of [HealthConnectRepository], following the verified client behaviour:
 * reads filter by record start time and data origin and page with numeric tokens (null at the end, or `""` like the
 * APK path when [apkStyleLastPage]); a `""` token sent back throws [NumberFormatException] like the platform; inserts
 * with a clientRecordId replace the stored record iff `newVersion >= storedVersion`; `deleteRecords` with two empty
 * lists throws [IllegalArgumentException]. Errors are injected per operation through [failures].
 */
class FakeHealthConnectClient(
    private val ownPackage: String,
) : HealthConnectClient {
    /** Granted permissions. */
    val granted: MutableSet<String> = mutableSetOf()

    /** Available features (FEATURE_* constants). */
    val availableFeatures: MutableSet<Int> = mutableSetOf()

    /** Records per page. */
    var pageSize: Int = 2

    /** End pagination with `""` (APK path) instead of null (platform). */
    var apkStyleLastPage: Boolean = false

    /** Exceptions thrown by the next calls, per operation name ("readRecords", "insertRecords", ...). */
    val failures: MutableMap<String, ArrayDeque<Throwable>> = mutableMapOf()

    /** Every read request, in order. */
    val readRequests: MutableList<ReadRecordsRequest<*>> = mutableListOf()

    /** clientRecordIds of every deleteRecords call, in order. */
    val deleteCalls: MutableList<List<String>> = mutableListOf()

    /** Every inserted record, in order. */
    val inserted: MutableList<Record> = mutableListOf()

    /** Every changes-token request. */
    val tokenRequests: MutableList<ChangesTokenRequest> = mutableListOf()

    /** Responses of the next getChanges calls. */
    val changes: ArrayDeque<ChangesResponse> = ArrayDeque()

    /** Makes a deleteRecords call with these ids fail (after it was recorded). */
    var deleteFailure: (List<String>) -> Throwable? = { null }

    /** Stored records with the package that wrote them (Metadata.dataOrigin cannot be set from outside the library). */
    val stored: MutableList<Pair<String, Record>> = mutableListOf()

    override val permissionController: PermissionController =
        object : PermissionController {
            override suspend fun getGrantedPermissions(): Set<String> {
                fail("getGrantedPermissions")
                return granted.toSet()
            }

            override suspend fun revokeAllPermissions() {
                granted.clear()
            }
        }

    override val features: HealthConnectFeatures =
        object : HealthConnectFeatures {
            override fun getFeatureStatus(feature: Int): Int =
                if (feature in
                    availableFeatures
                ) {
                    HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
                } else {
                    HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE
                }
        }

    /** Adds a record of [packageName]. */
    fun store(
        packageName: String,
        record: Record,
    ) {
        stored += packageName to record
    }

    @Suppress("UNCHECKED_CAST") // The stored record's class is checked with recordType.isInstance.
    override suspend fun <T : Record> readRecords(request: ReadRecordsRequest<T>): ReadRecordsResponse<T> {
        fail("readRecords")
        readRequests += request
        val origins = request.dataOriginFilter.map { it.packageName }.toSet()
        val matching =
            stored
                .filter { (pkg, record) ->
                    request.recordType.isInstance(record) &&
                        (origins.isEmpty() || pkg in origins) &&
                        startOf(record).inRange(request.timeRangeFilter)
                }.map { it.second as T }
                .sortedBy { startOf(it) }
        val offset = request.pageToken?.toInt() ?: 0
        val page = matching.drop(offset).take(pageSize)
        val next =
            when {
                offset + pageSize < matching.size -> (offset + pageSize).toString()
                apkStyleLastPage -> ""
                else -> null
            }
        return ReadRecordsResponse(page, next)
    }

    override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse {
        fail("insertRecords")
        inserted += records
        val ids =
            records.map { record ->
                val session = record as SleepSessionRecord
                val clientRecordId = checkNotNull(session.metadata.clientRecordId)
                val existing = stored.firstOrNull { (pkg, r) -> pkg == ownPackage && r.metadata.clientRecordId == clientRecordId }
                if (existing == null || session.metadata.clientRecordVersion >= existing.second.metadata.clientRecordVersion) {
                    if (existing != null) stored.remove(existing)
                    stored += ownPackage to session
                }
                "id-$clientRecordId"
            }
        return InsertRecordsResponse(ids)
    }

    override suspend fun deleteRecords(
        recordType: KClass<out Record>,
        recordIdsList: List<String>,
        clientRecordIdsList: List<String>,
    ) {
        fail("deleteRecords")
        require(recordIdsList.isNotEmpty() || clientRecordIdsList.isNotEmpty()) { "record ids can't be empty" }
        deleteCalls += clientRecordIdsList
        deleteFailure(clientRecordIdsList)?.let { throw it }
        stored.removeAll { (pkg, record) -> pkg == ownPackage && record.metadata.clientRecordId in clientRecordIdsList }
    }

    override suspend fun getChangesToken(request: ChangesTokenRequest): String {
        fail("getChangesToken")
        tokenRequests += request
        return "token-${tokenRequests.size}"
    }

    override suspend fun getChanges(changesToken: String): ChangesResponse {
        fail("getChanges")
        return changes.removeFirst()
    }

    override suspend fun updateRecords(records: List<Record>): Unit = throw UnsupportedOperationException("not used")

    override suspend fun deleteRecords(
        recordType: KClass<out Record>,
        timeRangeFilter: TimeRangeFilter,
    ): Unit = throw UnsupportedOperationException("not used")

    override suspend fun <T : Record> readRecord(
        recordType: KClass<T>,
        recordId: String,
    ): ReadRecordResponse<T> = throw UnsupportedOperationException("not used")

    override suspend fun aggregate(request: AggregateRequest): AggregationResult = throw UnsupportedOperationException("not used")

    override suspend fun aggregateGroupByDuration(request: AggregateGroupByDurationRequest): List<AggregationResultGroupedByDuration> =
        throw UnsupportedOperationException("not used")

    override suspend fun aggregateGroupByPeriod(request: AggregateGroupByPeriodRequest): List<AggregationResultGroupedByPeriod> =
        throw UnsupportedOperationException("not used")

    private fun fail(operation: String) {
        failures[operation]?.removeFirstOrNull()?.let { throw it }
    }

    private fun startOf(record: Record): Instant =
        when (record) {
            is SleepSessionRecord -> record.startTime
            is HeartRateRecord -> record.startTime
            is StepsRecord -> record.startTime
            is OxygenSaturationRecord -> record.time
            is RespiratoryRateRecord -> record.time
            is HeartRateVariabilityRmssdRecord -> record.time
            else -> error("unsupported record $record")
        }

    private fun Instant.inRange(filter: TimeRangeFilter): Boolean {
        val start = filter.startTime
        val end = filter.endTime
        return (start == null || !isBefore(start)) && (end == null || isBefore(end))
    }
}

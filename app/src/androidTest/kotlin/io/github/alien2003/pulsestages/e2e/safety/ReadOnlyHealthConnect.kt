package io.github.alien2003.pulsestages.e2e.safety

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthExceptionMapper
import io.github.alien2003.pulsestages.data.hc.PlatformErrorCodes
import io.github.alien2003.pulsestages.data.hc.RetryPolicy
import io.github.alien2003.pulsestages.data.hc.mapHealthErrors
import io.github.alien2003.pulsestages.data.hc.retryHealthCall
import io.github.alien2003.pulsestages.e2e.E2e
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Identity of one record for the fingerprints: its data origin, Health Connect id and last modification.
 *
 * @property packageName metadata.dataOrigin.packageName.
 * @property id metadata.id.
 * @property lastModifiedMs metadata.lastModifiedTime, epoch ms.
 */
data class RecordKey(
    val packageName: String,
    val id: String,
    val lastModifiedMs: Long,
)

/**
 * Direct, READ-ONLY access to Health Connect for the safety fingerprints: the only client method it calls is
 * `readRecords` (any origin, ascending, 1000 records per page, every page charged to [budget]). It reads sleep
 * sessions, heart rate and steps, keeps only their [RecordKey]s and never holds a write or delete method. Rate-limit
 * errors are retried with long waits, so a snapshot taken right after a busy phase still completes.
 */
class ReadOnlyHealthConnect(
    private val budget: ReadBudget = ReadBudget.shared(),
) {
    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(E2e.app.applicationContext) }

    /** Keys of every sleep session (any origin) starting in `[fromMs, toMs)`. */
    suspend fun sleepKeys(
        fromMs: Long,
        toMs: Long,
    ): List<RecordKey> = keys(SleepSessionRecord::class, fromMs, toMs)

    /** Keys of every heart-rate record (any origin) starting in `[fromMs, toMs)`. */
    suspend fun heartRateKeys(
        fromMs: Long,
        toMs: Long,
    ): List<RecordKey> = keys(HeartRateRecord::class, fromMs, toMs)

    /** Keys of every steps record (any origin) starting in `[fromMs, toMs)`. */
    suspend fun stepsKeys(
        fromMs: Long,
        toMs: Long,
    ): List<RecordKey> = keys(StepsRecord::class, fromMs, toMs)

    private suspend fun <T : Record> keys(
        type: KClass<T>,
        fromMs: Long,
        toMs: Long,
    ): List<RecordKey> {
        val filter = TimeRangeFilter.between(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs))
        val keys = ArrayList<RecordKey>()
        var pageToken: String? = null
        do {
            val request = ReadRecordsRequest(type, filter, ascendingOrder = true, pageSize = PAGE_SIZE, pageToken = pageToken)
            budget.acquire(1, "snapshot ${type.simpleName}")
            val response = read(type.simpleName.orEmpty()) { client.readRecords(request) }
            response.records.mapTo(keys) {
                RecordKey(it.metadata.dataOrigin.packageName, it.metadata.id, it.metadata.lastModifiedTime.toEpochMilli())
            }
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        return keys
    }

    private suspend fun <R> read(
        operation: String,
        block: suspend () -> R,
    ): R {
        var waited = 0
        while (true) {
            try {
                return retryHealthCall(RetryPolicy.DEFAULT, sleeper = { delay(it) }) {
                    mapHealthErrors(
                        mapper = { HealthExceptionMapper.map("readRecords($operation)", it, PlatformErrorCodes::of) },
                        block = block,
                    )
                }
            } catch (e: HealthDataException.QuotaExceeded) {
                budget.onPlatformQuotaError()
                if (waited >= QUOTA_WAITS) throw e
                waited++
                E2e.progress(
                    "Health Connect rate limit while reading $operation; waiting ${QUOTA_WAIT_MS / MINUTE_MS} min ($waited/$QUOTA_WAITS)",
                )
                delay(QUOTA_WAIT_MS)
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 1000
        const val QUOTA_WAITS = 4
        const val QUOTA_WAIT_MS = 5L * 60 * 1000
        const val MINUTE_MS = 60_000L
    }
}

package io.github.alien2003.pulsestages.e2e.safety

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
import io.github.alien2003.pulsestages.data.hc.HealthExceptionMapper
import io.github.alien2003.pulsestages.data.hc.PlatformErrorCodes
import io.github.alien2003.pulsestages.data.hc.mapHealthErrors
import io.github.alien2003.pulsestages.e2e.E2e

/**
 * A plain `insertRecords` of ONE [SleepSessionRecord] whose clientRecordId is in the suite's namespace, bypassing the
 * app's read-back verification ([io.github.alien2003.pulsestages.data.hc.VerifiedUpsert]). [RealWriteE2eTest] uses it
 * once, to show what Health Connect itself does with a lower clientRecordVersion (it keeps the stored record). The
 * parameter type admits sleep sessions only, and the namespace is checked by [SafetyGuardRepository.requireTestNamespace]
 * before the client is touched.
 *
 * @see io.github.alien2003.pulsestages.e2e.RealWriteE2eTest
 */
class GuardedSleepInserter {
    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(E2e.app.applicationContext) }

    /** Inserts [record] (an upsert by its clientRecordId) and returns the id Health Connect reports. */
    suspend fun insert(record: SleepSessionRecord): String? {
        SafetyGuardRepository.requireTestNamespace("insertRecords", listOf(record.metadata.clientRecordId.orEmpty()))
        return mapHealthErrors(mapper = { HealthExceptionMapper.map("insertRecords", it, PlatformErrorCodes::of) }) {
            client.insertRecords(listOf(record)).recordIdsList.firstOrNull()
        }
    }
}

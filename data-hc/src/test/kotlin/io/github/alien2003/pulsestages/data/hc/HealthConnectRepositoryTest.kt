package io.github.alien2003.pulsestages.data.hc

import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.units.Percentage
import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.data.hc.fake.RecordingLogger
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OWN
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The real [HealthConnectRepository] against [FakeHealthConnectClient] (plain JVM, virtual time). */
class HealthConnectRepositoryTest {
    private class FakePlatformException(
        val code: Int,
    ) : RuntimeException("platform $code")

    private class RecordingSleeper : Sleeper {
        val waits = mutableListOf<Long>()

        override suspend fun sleep(millis: Long) {
            waits += millis
        }
    }

    private val client = FakeHealthConnectClient(OWN)
    private val sleeper = RecordingSleeper()
    private val start = nightStart(1)
    private var sdkStatus = HealthConnectClient.SDK_AVAILABLE
    private var clientFactory: () -> HealthConnectClient = { client }
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOON), ZoneOffset.UTC)

    private fun TestScope.repository(
        sdkInt: Int = 36,
        retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    ) = HealthConnectRepository(
        access = HealthConnectAccess(client = { clientFactory() }, sdkStatus = { sdkStatus }),
        platform = PlatformInfo(sdkInt, { (it as? FakePlatformException)?.code }) { Device(Device.TYPE_PHONE, "OPPO", "CPH2765") },
        ownPackage = OWN,
        dispatcher = StandardTestDispatcher(testScheduler),
        sleeper = sleeper,
        retryPolicy = retryPolicy,
        logger = RecordingLogger(),
        clock = clock,
    )

    private fun sleepRecord(
        startMs: Long,
        hours: Long = 8,
        metadata: Metadata = Metadata.unknownRecordingMethod(),
        stages: List<SleepSessionRecord.Stage> = emptyList(),
    ) = SleepSessionRecord(
        startTime = Instant.ofEpochMilli(startMs),
        startZoneOffset = ZoneOffset.UTC,
        endTime = Instant.ofEpochMilli(startMs + hours * HOUR_MS),
        endZoneOffset = ZoneOffset.UTC,
        metadata = metadata,
        stages = stages,
    )

    private fun write(version: Long) =
        EstimatedSessionWrite(
            clientRecordId = "ps:abc",
            clientRecordVersion = version,
            startMs = start,
            endMs = start + 8 * HOUR_MS,
            startZoneOffsetSec = 10_800,
            endZoneOffsetSec = 7_200,
            stages = listOf(HcStage(start, start + 4 * HOUR_MS, 4), HcStage(start + 4 * HOUR_MS, start + 8 * HOUR_MS, 5)),
            title = "Sleep stages from heart rate (estimate)",
            notes = "{}",
        )

    @Test
    fun `reads page until the token is null`() =
        runTest {
            (0 until 5).forEach { client.store(OHEALTH, sleepRecord(start + it * 24 * HOUR_MS)) }

            val sessions = repository().readSleepSessions(start, start + 10 * 24 * HOUR_MS, setOf(OHEALTH))

            assertEquals(5, sessions.size)
            assertEquals(listOf(null, "2", "4"), client.readRequests.map { it.pageToken })
            assertEquals(setOf(DataOrigin(OHEALTH)), client.readRequests.first().dataOriginFilter)
            assertEquals(HealthConnectRepository.PAGE_SIZE, client.readRequests.first().pageSize)
        }

    @Test
    fun `an empty last page token (APK path) ends the loop and is never sent back`() =
        runTest {
            client.apkStyleLastPage = true
            (0 until 3).forEach { client.store(OHEALTH, sleepRecord(start + it * 24 * HOUR_MS)) }

            assertEquals(3, repository().readSleepSessions(start, start + 10 * 24 * HOUR_MS, null).size)
            assertEquals(listOf(null, "2"), client.readRequests.map { it.pageToken })
            assertEquals(emptySet<DataOrigin>(), client.readRequests.first().dataOriginFilter, "null packages = any origin")
        }

    @Test
    fun `an empty package set or an empty window reads nothing`() =
        runTest {
            val repo = repository()
            assertTrue(repo.readSleepSessions(start, start + HOUR_MS, emptySet()).isEmpty())
            assertTrue(repo.readHeartRate(start, start, null).isEmpty())
            assertTrue(repo.readSteps(start + HOUR_MS, start, null).isEmpty())
            assertTrue(client.readRequests.isEmpty())
        }

    @Test
    fun `heart rate is read with 24 h of left padding and clipped to the window`() =
        runTest {
            fun series(
                from: Long,
                samples: List<Long>,
            ) = HeartRateRecord(
                startTime = Instant.ofEpochMilli(from),
                startZoneOffset = ZoneOffset.UTC,
                endTime = Instant.ofEpochMilli(samples.max()),
                endZoneOffset = ZoneOffset.UTC,
                samples = samples.map { HeartRateRecord.Sample(Instant.ofEpochMilli(it), 60) },
                metadata = Metadata.unknownRecordingMethod(),
            )
            client.store(OHEALTH, series(start - 23 * HOUR_MS, listOf(start - 23 * HOUR_MS, start + MINUTE_MS)))
            client.store(OHEALTH, series(start + 2 * HOUR_MS, listOf(start + 2 * HOUR_MS, start + 9 * HOUR_MS)))

            val samples = repository().readHeartRate(start, start + 8 * HOUR_MS, setOf(OHEALTH))

            assertEquals(listOf(HrSample(start + MINUTE_MS, 60.0), HrSample(start + 2 * HOUR_MS, 60.0)), samples)
            val filter = client.readRequests.single().timeRangeFilter
            assertEquals(Instant.ofEpochMilli(start - HealthConnectRepository.SERIES_LEFT_PADDING_MS), filter.startTime)
            assertEquals(Instant.ofEpochMilli(start + 8 * HOUR_MS), filter.endTime)
        }

    @Test
    fun `optional channels are not read without their permission`() =
        runTest {
            client.store(
                OHEALTH,
                OxygenSaturationRecord(Instant.ofEpochMilli(start), ZoneOffset.UTC, Percentage(95.0), Metadata.unknownRecordingMethod()),
            )
            val repo = repository()

            assertTrue(repo.readSpo2(start - HOUR_MS, start + HOUR_MS, null).isEmpty())
            assertTrue(client.readRequests.isEmpty())

            client.granted += HealthPermissions.READ_OXYGEN_SATURATION
            assertEquals(95.0, repo.readSpo2(start - HOUR_MS, start + HOUR_MS, null).single().percent)
        }

    @Test
    fun `rate limits are retried with exponential backoff and then reported as quota errors`() =
        runTest {
            client.store(OHEALTH, sleepRecord(start))
            client.failures["readRecords"] = ArrayDeque(List(2) { IllegalStateException(FakePlatformException(7)) })
            val repo = repository()

            assertEquals(1, repo.readSleepSessions(start, start + HOUR_MS, null).size)
            assertEquals(listOf(1_000L, 2_000L), sleeper.waits)

            sleeper.waits.clear()
            client.failures["readRecords"] = ArrayDeque(List(10) { IllegalStateException(FakePlatformException(8)) })
            assertThrows<HealthDataException.QuotaExceeded> { repo.readSleepSessions(start, start + HOUR_MS, null) }
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), sleeper.waits)
        }

    @Test
    fun `permission errors and invalid requests are not retried`() =
        runTest {
            val repo = repository()
            client.failures["readRecords"] = ArrayDeque(listOf(SecurityException("READ_SLEEP")))
            assertThrows<HealthDataException.PermissionDenied> { repo.readSleepSessions(start, start + HOUR_MS, null) }
            client.failures["readRecords"] = ArrayDeque(listOf(IllegalArgumentException("pageSize")))
            assertThrows<HealthDataException.InvalidData> { repo.readSleepSessions(start, start + HOUR_MS, null) }
            assertTrue(sleeper.waits.isEmpty())
        }

    @Test
    fun `a missing Health Connect is reported as unavailable`() =
        runTest {
            sdkStatus = HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
            clientFactory = { throw IllegalStateException("Service not available") }
            val repo = repository(retryPolicy = RetryPolicy.NONE)

            assertEquals(HcAvailability.PROVIDER_UPDATE_REQUIRED, repo.availability())
            assertThrows<HealthDataException.Unavailable> { repo.grantedPermissions() }
            assertFalse(repo.isFeatureAvailable(HcFeature.READ_HISTORY))

            sdkStatus = HealthConnectClient.SDK_UNAVAILABLE
            clientFactory = { throw UnsupportedOperationException("SDK version too low or running in a profile") }
            assertEquals(HcAvailability.NOT_SUPPORTED, repo.availability())
            assertThrows<HealthDataException.Unavailable> { repo.readOwnSessions(start, start + HOUR_MS) }
        }

    @Test
    fun `features come from the client`() =
        runTest {
            client.availableFeatures += HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
            val repo = repository()
            assertTrue(repo.isFeatureAvailable(HcFeature.READ_IN_BACKGROUND))
            assertFalse(repo.isFeatureAvailable(HcFeature.READ_HISTORY))
        }

    @Test
    fun `our session is written with unknown recording method, the phone and explicit offsets`() =
        runTest {
            val result = repository().writeEstimatedSession(write(1000))

            assertEquals(WriteResult("", 1000), result, "the fake client cannot assign metadata ids")
            val record = client.inserted.single() as SleepSessionRecord
            assertEquals(Metadata.RECORDING_METHOD_UNKNOWN, record.metadata.recordingMethod)
            assertEquals(Device(Device.TYPE_PHONE, "OPPO", "CPH2765"), record.metadata.device)
            assertEquals("ps:abc", record.metadata.clientRecordId)
            assertEquals(ZoneOffset.ofHours(3), record.startZoneOffset)
            assertEquals(ZoneOffset.ofHours(2), record.endZoneOffset)
            assertTrue(client.deleteCalls.isEmpty())
        }

    @Test
    fun `a stale higher version is replaced by delete-then-insert`() =
        runTest {
            client.store(
                OWN,
                sleepRecord(start, metadata = Metadata.unknownRecordingMethod(clientRecordId = "ps:abc", clientRecordVersion = 5000)),
            )

            val result = repository().writeEstimatedSession(write(1000))

            assertEquals(1000L, result.storedVersion)
            assertEquals(listOf(listOf("ps:abc")), client.deleteCalls)
            assertEquals(2, client.inserted.size)
            assertEquals(listOf(1000L), client.stored.map { it.second.metadata.clientRecordVersion })
        }

    @Test
    fun `deletes are chunked by 100 and never sent with empty lists`() =
        runTest {
            val repo = repository()
            repo.deleteOwnSessions(emptyList())
            repo.deleteOwnSessions(listOf("", " "))
            assertTrue(client.deleteCalls.isEmpty())

            repo.deleteOwnSessions((1..250).map { "ps:$it" } + "ps:1")
            assertEquals(listOf(100, 100, 50), client.deleteCalls.map { it.size })
        }

    private fun storeOwn(vararg ids: String) {
        ids.forEach { id ->
            client.store(OWN, sleepRecord(start, metadata = Metadata.unknownRecordingMethod(clientRecordId = id, clientRecordVersion = 1)))
        }
    }

    @Test
    fun `on the APK path a failed delete reads which ids still exist and deletes only those, without backoff`() =
        runTest {
            storeOwn("ps:a", "ps:b")
            client.deleteFailure = { ids -> if ("ps:missing" in ids) RemoteException() else null }

            repository(sdkInt = 33).deleteOwnSessions(listOf("ps:a", "ps:missing", "ps:b"))

            assertEquals(listOf(listOf("ps:a", "ps:missing", "ps:b"), listOf("ps:a", "ps:b")), client.deleteCalls)
            assertTrue(client.stored.isEmpty())
            assertTrue(sleeper.waits.isEmpty(), "a missing id is not retried with backoff")
            val read = client.readRequests.single()
            assertEquals(setOf(DataOrigin(OWN)), read.dataOriginFilter, "the check reads our own sessions only")
            assertEquals(Instant.EPOCH, read.timeRangeFilter.startTime)
        }

    @Test
    fun `on the APK path a chunk of ids that are all gone costs one delete call and one read`() =
        runTest {
            client.deleteFailure = { RemoteException() }

            repository(sdkInt = 33).deleteOwnSessions((1..100).map { "ps:$it" })

            assertEquals(1, client.deleteCalls.size)
            assertEquals(1, client.readRequests.size)
            assertTrue(sleeper.waits.isEmpty())
        }

    @Test
    fun `on the APK path a delete that keeps failing for a record that exists is thrown, never skipped`() =
        runTest {
            storeOwn("ps:a")
            client.deleteFailure = { RemoteException() }

            assertThrows<HealthDataException.Transient> { repository(sdkInt = 33).deleteOwnSessions(listOf("ps:a")) }

            assertEquals(1, client.stored.size, "our record still exists, so the caller must not forget it")
            assertEquals(1 + 1 + RetryPolicy.DEFAULT.delaysMs.size, client.deleteCalls.size)
            assertEquals(RetryPolicy.DEFAULT.delaysMs, sleeper.waits)
        }

    @Test
    fun `on the APK path an outdated changes token (a RemoteException) is reported as expired after one retry`() =
        runTest {
            client.failures["getChanges"] = ArrayDeque(List(2) { RemoteException() })

            val page = repository(sdkInt = 33).sleepChanges("token")

            assertTrue(page.tokenExpired)
            assertEquals(listOf(1_000L), sleeper.waits)

            sleeper.waits.clear()
            client.failures["getChanges"] = ArrayDeque(List(10) { RemoteException() })
            assertThrows<HealthDataException.Transient>("Android 14+ reports an invalid token itself") {
                repository(sdkInt = 34).sleepChanges("token")
            }
            assertEquals(RetryPolicy.DEFAULT.delaysMs, sleeper.waits)
        }

    @Test
    fun `on Android 14+ a failing delete is a transient error`() =
        runTest {
            client.deleteFailure = { RemoteException() }
            assertThrows<HealthDataException.Transient> {
                repository(
                    sdkInt = 34,
                    retryPolicy = RetryPolicy.NONE,
                ).deleteOwnSessions(listOf("ps:a"))
            }
        }

    @Test
    fun `changes are mapped to sleep upserts and deletions`() =
        runTest {
            val upserted = sleepRecord(start, metadata = Metadata.unknownRecordingMethodWithId("uuid-1"))
            client.changes +=
                ChangesResponse(
                    listOf(UpsertionChange(upserted), DeletionChange("uuid-2")),
                    "next",
                    hasMore = true,
                    changesTokenExpired = false,
                )
            client.changes += ChangesResponse(emptyList(), "", hasMore = false, changesTokenExpired = true)
            val repo = repository()

            val page = repo.sleepChanges("token")
            assertEquals(listOf("uuid-1"), page.upserted.map { it.uuid })
            assertEquals(listOf("uuid-2"), page.deletedIds)
            assertEquals("next", page.nextToken)
            assertTrue(page.hasMore)
            assertTrue(repo.sleepChanges("next").tokenExpired)

            assertTrue(repo.sleepChanges(" ").tokenExpired, "a blank token is never sent")
            assertTrue(client.changes.isEmpty())
        }

    @Test
    fun `a changes token is requested for sleep sessions of the given packages only`() =
        runTest {
            val repo = repository()
            assertEquals("token-1", repo.sleepChangesToken(setOf(OHEALTH)))
            val request = client.tokenRequests.single()
            assertEquals(setOf(SleepSessionRecord::class), request.recordTypes)
            assertEquals(setOf(DataOrigin(OHEALTH)), request.dataOriginFilters)
            assertThrows<HealthDataException.InvalidData> { repo.sleepChangesToken(emptySet()) }
            assertEquals(1, client.tokenRequests.size)
        }

    @Test
    fun `availability follows the SDK status and never throws`() =
        runTest {
            val repo = repository()
            assertEquals(HcAvailability.AVAILABLE, repo.availability())
            sdkStatus = HealthConnectClient.SDK_UNAVAILABLE
            assertEquals(HcAvailability.NOT_SUPPORTED, repo.availability())
            val throwing =
                HealthConnectRepository(
                    HealthConnectAccess({ client }, { throw SecurityException("no package visibility") }),
                    PlatformInfo(36, { null }) { Device(Device.TYPE_PHONE) },
                    OWN,
                    StandardTestDispatcher(testScheduler),
                    sleeper,
                    RetryPolicy.NONE,
                    RecordingLogger(),
                    clock,
                )
            assertEquals(HcAvailability.NOT_SUPPORTED, throwing.availability())
            assertNull(PlatformErrorCodes.of(IllegalStateException()))
            assertInstanceOf(HealthConnectRepository::class.java, throwing)
        }
}

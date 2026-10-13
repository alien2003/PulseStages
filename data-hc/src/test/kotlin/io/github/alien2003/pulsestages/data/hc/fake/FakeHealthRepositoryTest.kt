package io.github.alien2003.pulsestages.data.hc.fake

import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.data.hc.EstimatedSessionWrite
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The fake must behave like Health Connect, or the pipeline tests prove nothing. */
class FakeHealthRepositoryTest {
    private var now = NOON
    private val fake = FakeHealthRepository { now }
    private val start = nightStart(1)

    private fun write(
        cid: String = "ps:x",
        version: Long = 1000,
        startMs: Long = start,
    ) = EstimatedSessionWrite(cid, version, startMs, startMs + HOUR_MS, 0, 0, listOf(HcStage(startMs, startMs + HOUR_MS, 4)), "t", "n")

    @Test
    fun `reads filter by start time and origin`() =
        runTest {
            val inside = fake.addSleepSession(OHEALTH, start, start + 8 * HOUR_MS)
            fake.addSleepSession(OHEALTH, start - HOUR_MS, start + 7 * HOUR_MS)
            val other = fake.addSleepSession(OTHER_APP, start + HOUR_MS, start + 2 * HOUR_MS)

            assertEquals(listOf(inside, other), fake.readSleepSessions(start, start + 2 * HOUR_MS, packages = null))
            assertEquals(listOf(inside), fake.readSleepSessions(start, start + 2 * HOUR_MS, setOf(OHEALTH)))
            assertEquals(emptyList<Any>(), fake.readSleepSessions(start, start + 2 * HOUR_MS, emptySet()))
        }

    @Test
    fun `heart-rate series starting up to 24 h before the window are found and clipped`() =
        runTest {
            val samples = listOf(HrSample(start - 23 * HOUR_MS, 70.0), HrSample(start + MINUTE_MS, 55.0))
            fake.addHeartRateSeries(OHEALTH, samples)
            fake.addHeartRateSeries(OHEALTH, listOf(HrSample(start - 25 * HOUR_MS, 80.0), HrSample(start + 2 * MINUTE_MS, 56.0)))

            assertEquals(listOf(HrSample(start + MINUTE_MS, 55.0)), fake.readHeartRate(start, start + HOUR_MS, setOf(OHEALTH)))
        }

    @Test
    fun `writes are upserts by clientRecordId with deterministic ids and the version rule`() =
        runTest {
            val first = fake.writeEstimatedSession(write(version = 1000))
            val second = fake.writeEstimatedSession(write(version = 1001))
            assertEquals(first.recordId, second.recordId)
            assertEquals(fake.ownRecordId("ps:x"), first.recordId)
            assertEquals(1, fake.ownSessions().size)
            assertEquals(1001, fake.ownSessions().single().clientRecordVersion)
            assertTrue(fake.ownRecordId("ps:x") != fake.ownRecordId("ps:y"))
        }

    @Test
    fun `future start times and invalid stages are rejected`() =
        runTest {
            assertThrows<HealthDataException.InvalidData> { fake.writeEstimatedSession(write(startMs = now + MINUTE_MS)) }
            val overlapping =
                write().copy(
                    stages = listOf(HcStage(start, start + 40 * MINUTE_MS, 4), HcStage(start + 30 * MINUTE_MS, start + HOUR_MS, 5)),
                )
            assertThrows<HealthDataException.InvalidData> { fake.writeEstimatedSession(overlapping) }
            assertTrue(fake.ownSessions().isEmpty())
        }

    @Test
    fun `deletes only touch our own records and ignore unknown ids`() =
        runTest {
            val theirs = fake.addSleepSession(OHEALTH, start, start + 8 * HOUR_MS)
            fake.writeEstimatedSession(write())
            fake.deleteOwnSessions(listOf("ps:x", "ps:unknown", theirs.uuid))
            fake.deleteOwnSessions(emptyList())

            assertTrue(fake.ownSessions().isEmpty())
            assertEquals(listOf(theirs), fake.allSessions())
            assertEquals(1, fake.callCount(FakeHealthRepository.Operation.DELETE), "an empty list makes no call")
        }

    @Test
    fun `the change log pages per token and package set, tokens expire`() =
        runTest {
            val token = fake.sleepChangesToken(setOf(OHEALTH))
            val a = fake.addSleepSession(OHEALTH, start, start + 8 * HOUR_MS)
            fake.addSleepSession(OTHER_APP, start, start + 8 * HOUR_MS)
            fake.writeEstimatedSession(write())
            val b = fake.addSleepSession(OHEALTH, start + DAY, start + DAY + 8 * HOUR_MS)
            fake.deleteSleepSession(a.uuid)
            fake.changesPageSize = 2

            val page1 = fake.sleepChanges(token)
            assertTrue(page1.hasMore)
            assertEquals(
                listOf(b),
                page1.upserted,
                "a was deleted later, its upsert has no record any more; other apps and ours are filtered",
            )
            val page2 = fake.sleepChanges(page1.nextToken)
            assertFalse(page2.hasMore)
            assertEquals(emptyList<Any>(), page2.upserted)
            assertEquals(listOf(a.uuid), page2.deletedIds)
            assertEquals(page2.nextToken, fake.sleepChanges(page2.nextToken).nextToken, "no changes → same token")

            now += FakeHealthRepository.TOKEN_LIFETIME_MS + 1
            assertTrue(fake.sleepChanges(page2.nextToken).tokenExpired)
            assertTrue(fake.sleepChanges("garbage").tokenExpired)
        }

    @Test
    fun `background mode without the permission refuses reads of other apps' data, own-only reads still work`() =
        runTest {
            fake.addSleepSession(OHEALTH, start, start + 8 * HOUR_MS)
            fake.addHeartRate(OHEALTH, start, start + HOUR_MS)
            fake.writeEstimatedSession(write())
            fake.inBackground = true
            fake.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)

            // Like the platform: a filter other than exactly our package (or no filter) is a SecurityException.
            assertThrows<HealthDataException.PermissionDenied> { fake.readSleepSessions(start - HOUR_MS, start + HOUR_MS, null) }
            assertThrows<HealthDataException.PermissionDenied> { fake.readSleepSessions(start - HOUR_MS, start + HOUR_MS, setOf(OHEALTH)) }
            assertThrows<HealthDataException.PermissionDenied> {
                fake.readSleepSessions(start - HOUR_MS, start + HOUR_MS, setOf(OHEALTH, fake.ownPackage))
            }
            assertThrows<HealthDataException.PermissionDenied> { fake.readHeartRate(start, start + HOUR_MS, setOf(OHEALTH)) }
            assertThrows<HealthDataException.PermissionDenied> { fake.scanSleepOrigins(start - HOUR_MS, start + HOUR_MS) }
            assertThrows<HealthDataException.PermissionDenied> { fake.sleepChangesToken(setOf(OHEALTH)) }
            assertEquals(1, fake.readSleepSessions(start - HOUR_MS, start + HOUR_MS, setOf(fake.ownPackage)).size)
            assertEquals(1, fake.readOwnSessions(start - HOUR_MS, start + HOUR_MS).size)

            fake.inBackground = false
            assertEquals(2, fake.readSleepSessions(start - HOUR_MS, start + HOUR_MS, null).size, "the foreground reads everything")
        }

    @Test
    fun `history limit, permission revocation, availability and injected failures`() =
        runTest {
            fake.addSleepSession(OHEALTH, start - 40 * DAY, start - 40 * DAY + HOUR_MS)
            fake.historyStartMs = now - 30 * DAY
            assertEquals(1, fake.readSleepSessions(0, now, null).size)
            fake.revoke(HealthPermissions.READ_HEALTH_DATA_HISTORY)
            assertEquals(0, fake.readSleepSessions(0, now, null).size)

            fake.revoke(HealthPermissions.READ_HEART_RATE)
            assertThrows<HealthDataException.PermissionDenied> { fake.readHeartRate(start, start + HOUR_MS, null) }
            fake.revoke(HealthPermissions.READ_OXYGEN_SATURATION)
            assertEquals(emptyList<Any>(), fake.readSpo2(start, start + HOUR_MS, null))

            fake.failOn(FakeHealthRepository.Operation.READ_STEPS, HealthDataException.Transient("x"), times = 2, afterCalls = 1)
            fake.readSteps(start, start + HOUR_MS, null)
            assertThrows<HealthDataException.Transient> { fake.readSteps(start, start + HOUR_MS, null) }
            assertThrows<HealthDataException.Transient> { fake.readSteps(start, start + HOUR_MS, null) }
            fake.readSteps(start, start + HOUR_MS, null)

            fake.availability = HcAvailability.NOT_SUPPORTED
            assertThrows<HealthDataException.Unavailable> { fake.grantedPermissions() }
            assertFalse(fake.isFeatureAvailable(io.github.alien2003.pulsestages.data.hc.HcFeature.READ_HISTORY))
        }

    private companion object {
        const val DAY = 24 * HOUR_MS
    }
}

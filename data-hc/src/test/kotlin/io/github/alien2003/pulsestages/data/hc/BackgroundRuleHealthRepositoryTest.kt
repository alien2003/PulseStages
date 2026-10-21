package io.github.alien2003.pulsestages.data.hc

import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.hc.pipeline.HealthAccessGuard
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The background rule on top of a Health Connect that reads silently in the background (android14-release): what the
 * app really reads, not what the run was started as, decides; a silent own-only read never reaches the pipeline.
 */
class BackgroundRuleHealthRepositoryTest {
    private val fake =
        FakeHealthRepository { NOON }.apply {
            backgroundRule = FakeHealthRepository.BackgroundRule.SELF_ONLY
            revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
        }
    private val start = nightStart(1)
    private val window = (start - HOUR_MS) to (start + HOUR_MS)

    private fun repository(foreground: AppForegroundState = AppForegroundState { !fake.inBackground }) =
        BackgroundRuleHealthRepository(fake, fake.ownPackage, foreground)

    private fun own(): EstimatedSessionWrite =
        EstimatedSessionWrite("ps:own", 1000, start, start + HOUR_MS, 0, 0, listOf(HcStage(start, start + HOUR_MS, 4)), "t", "n")

    @Test
    fun `in the foreground every read passes through`() =
        runTest {
            fake.addSleepSession(OHEALTH, start, start + 8 * HOUR_MS)
            fake.addHeartRate(OHEALTH, start, start + HOUR_MS)
            val repository = repository()

            assertEquals(1, repository.readSleepSessions(window.first, window.second, null).size)
            assertTrue(repository.readHeartRate(start, start + HOUR_MS, setOf(OHEALTH)).isNotEmpty())
            assertEquals(1, repository.scanSleepOrigins(window.first, window.second).size)
        }

    @Test
    fun `in the background without the permission other apps' data is refused instead of read as nothing`() =
        runTest {
            fake.addSleepSession(OHEALTH, start, start + 8 * HOUR_MS)
            fake.addHeartRate(OHEALTH, start, start + HOUR_MS)
            fake.writeEstimatedSession(own())
            val token = fake.sleepChangesToken(setOf(OHEALTH))
            fake.inBackground = true
            val repository = repository()
            assertTrue(
                fake.readSleepSessions(window.first, window.second, setOf(OHEALTH)).none { it.packageName == OHEALTH },
                "the platform returns none of OHealth's sessions, without an error",
            )

            assertThrows<HealthDataException.PermissionDenied> { repository.readSleepSessions(window.first, window.second, setOf(OHEALTH)) }
            assertThrows<HealthDataException.PermissionDenied> { repository.readSleepSessions(window.first, window.second, null) }
            assertThrows<HealthDataException.PermissionDenied> { repository.readHeartRate(start, start + HOUR_MS, null) }
            assertThrows<HealthDataException.PermissionDenied> { repository.readSteps(start, start + HOUR_MS, setOf(OHEALTH)) }
            assertThrows<HealthDataException.PermissionDenied> { repository.newestSteps(start, start + HOUR_MS, setOf(OHEALTH)) }
            assertThrows<HealthDataException.PermissionDenied> { repository.readSpo2(start, start + HOUR_MS, setOf(OHEALTH)) }
            assertThrows<HealthDataException.PermissionDenied> { repository.readRespiratoryRate(start, start + HOUR_MS, setOf(OHEALTH)) }
            assertThrows<HealthDataException.PermissionDenied> { repository.readHrv(start, start + HOUR_MS, setOf(OHEALTH)) }
            assertThrows<HealthDataException.PermissionDenied> { repository.scanSleepOrigins(window.first, window.second) }
            assertThrows<HealthDataException.PermissionDenied> { repository.sleepChanges(token) }
            assertEquals(
                BlockedReason.BACKGROUND_PERMISSION_MISSING,
                HealthAccessGuard(repository).reasonForDenial(),
                "the refusal is classified as the missing background permission",
            )

            // Our own data may be read, written and deleted from the background.
            assertEquals(1, repository.readSleepSessions(window.first, window.second, setOf(fake.ownPackage)).size)
            assertEquals(1, repository.readOwnSessions(window.first, window.second).size)
            assertTrue(repository.readHeartRate(start, start + HOUR_MS, emptySet()).isEmpty())
            assertTrue(repository.sleepChangesToken(setOf(OHEALTH)).isNotBlank())
            repository.writeEstimatedSession(own().copy(clientRecordVersion = 1001))
            repository.deleteOwnSessions(listOf("ps:own"))
            assertTrue(fake.ownSessions().isEmpty())
        }

    @Test
    fun `the newest step record is read under the background rule, not delegated as it is`() =
        runTest {
            fake.addSteps(OHEALTH, start, start + MINUTE_MS, 40)
            val newest = StepsSample(start, start + MINUTE_MS, 40)
            assertEquals(newest, repository().newestSteps(window.first, window.second, setOf(OHEALTH)), "in the foreground")
            fake.inBackground = true
            assertNull(fake.newestSteps(window.first, window.second, setOf(OHEALTH)), "the platform finds none, without an error")
            fake.clearCalls()

            assertThrows<HealthDataException.PermissionDenied> { repository().newestSteps(window.first, window.second, setOf(OHEALTH)) }
            assertThrows<HealthDataException.PermissionDenied> { repository().newestSteps(window.first, window.second, null) }
            assertEquals(0, fake.callCount(FakeHealthRepository.Operation.READ_NEWEST_STEPS), "refused before the call")
            assertNull(repository().newestSteps(window.first, window.second, setOf(fake.ownPackage)), "our own steps: none")

            fake.grant(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            assertEquals(newest, repository().newestSteps(window.first, window.second, setOf(OHEALTH)))
            val states = ArrayDeque(listOf(true, false))
            fake.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            assertThrows<HealthDataException.PermissionDenied> {
                repository { states.removeFirst() }.newestSteps(window.first, window.second, setOf(OHEALTH))
            }
            assertTrue(states.isEmpty(), "checked before and after the call")
        }

    @Test
    fun `in the background with the permission every read passes through`() =
        runTest {
            fake.addSleepSession(OHEALTH, start, start + 8 * HOUR_MS)
            fake.inBackground = true
            fake.grant(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)

            assertEquals(1, repository().readSleepSessions(window.first, window.second, setOf(OHEALTH)).size)
        }

    @Test
    fun `a read during which the app leaves the foreground is discarded`() =
        runTest {
            fake.addSleepSession(OHEALTH, start, start + 8 * HOUR_MS)
            val states = ArrayDeque(listOf(true, false))
            val repository = repository { states.removeFirst() }

            assertThrows<HealthDataException.PermissionDenied> { repository.readSleepSessions(window.first, window.second, setOf(OHEALTH)) }
            assertTrue(states.isEmpty(), "checked before and after the call")
        }
}

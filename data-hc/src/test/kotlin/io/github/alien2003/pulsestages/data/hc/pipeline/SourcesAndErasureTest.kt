package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.OWN
import io.github.alien2003.pulsestages.data.hc.testutil.THIRD_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SourceScannerTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `the last 90 days are scanned without the history permission and merged with the settings`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(100))
            h.repository.addNight(OHEALTH, nightStart(5))
            val last = h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.addNight(OTHER_APP, nightStart(3))
            h.repository.addNight(OWN, nightStart(1) + HOUR_MS)
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_HISTORY)
            h.settings.set {
                it.copy(
                    sources = it.sources.copy(manualPackages = listOf(THIRD_APP), sourcePriority = listOf(OHEALTH, THIRD_APP)),
                )
            }

            val result = h.scanner.scan()

            assertFalse(result.fullHistory)
            assertEquals(NOON - 90 * DAY_MS, result.fromMs)
            assertEquals(listOf(OHEALTH, THIRD_APP, OTHER_APP, OWN), result.sources.map { it.packageName })
            val ohealth = result.sources.first()
            assertEquals(SourceEntry(OHEALTH, 2, last.endMs, enabled = true, priority = 0, manual = false, isOwn = false), ohealth)
            assertEquals(SourceEntry(THIRD_APP, 0, null, enabled = false, priority = 1, manual = true, isOwn = false), result.sources[1])
            assertEquals(1, result.sources[2].sessionCount)
            assertFalse(result.sources[2].enabled)
            assertTrue(result.sources[3].isOwn)
        }

    @Test
    fun `the full history is scanned with the history permission`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(400))
            val result = h.scanner.scan()
            assertTrue(result.fullHistory)
            assertEquals(0L, result.fromMs)
            assertEquals(1, result.sources.single().sessionCount)
        }

    @Test
    fun `the configured sources are listed from the settings alone when Health Connect cannot be read`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OTHER_APP, nightStart(3))
            h.repository.availability = HcAvailability.NOT_SUPPORTED
            h.settings.set {
                it.copy(
                    sources =
                        it.sources.copy(
                            sourcePriority = listOf(THIRD_APP, OHEALTH),
                            sourcePackages = listOf(OHEALTH),
                            manualPackages = listOf(THIRD_APP),
                        ),
                )
            }

            val configured = h.scanner.configured()

            assertEquals(
                listOf(
                    SourceEntry(THIRD_APP, 0, null, enabled = false, priority = 0, manual = true, isOwn = false),
                    SourceEntry(OHEALTH, 0, null, enabled = true, priority = 1, manual = false, isOwn = false),
                ),
                configured,
            )
        }
}

class MyDataEraserTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `all our sessions are deleted by clientRecordId, other apps are untouched and nights become DELETED`() =
        harnessTest(dir) { h ->
            (3 downTo 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            h.processRecent()
            val orphan = h.repository.addNight(OHEALTH, nightStart(5))
            // An output written by a previous install: in Health Connect but not in the ledger.
            h.repository.preloadOwnSession(
                io.github.alien2003.pulsestages.data.hc.EstimatedSessionWrite(
                    NightKeys.clientRecordId(listOf(orphan.uuid)),
                    1000,
                    orphan.startMs,
                    orphan.endMs,
                    0,
                    0,
                    emptyList(),
                    "old",
                    "{}",
                ),
            )
            val others = h.repository.allSessions().filter { it.packageName == OHEALTH }

            val report = h.eraser.eraseAll()

            assertEquals(
                EraseReport(fromLedger = 3, foundOnlyInHealthConnect = 1, deleted = 4, markedDeleted = 3, withoutClientRecordId = 0),
                report,
            )
            assertTrue(h.repository.ownSessions().isEmpty())
            assertEquals(others, h.repository.allSessions())
            assertTrue(h.ledger.allNights().all { it.status == NightStatus.DELETED && it.hcRecordId == null })
            assertEquals(1, h.repository.callCount(Operation.DELETE))
        }

    @Test
    fun `erased nights are not written again by the automatic sync, but a backfill restores them`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            h.eraser.eraseAll()
            h.repository.expireAllTokens()

            h.sync.run(inBackground = false)
            assertTrue(h.repository.ownSessions().isEmpty(), "a token reset re-read does not undo the user's deletion")

            h.backfill.run(inBackground = false)
            assertEquals(1, h.repository.ownSessions().size)
            assertEquals(
                NightStatus.WRITTEN,
                h.ledger
                    .allNights()
                    .single()
                    .status,
            )
        }

    @Test
    fun `a fragment that joins an erased night does not bring it back through the automatic sync`() =
        harnessTest(dir) { h ->
            val first = h.repository.addNight(OHEALTH, nightStart(1), durationMs = 4 * HOUR_MS)
            h.sync.run(inBackground = false)
            h.eraser.eraseAll()

            h.repository.addNight(OHEALTH, first.endMs + 15 * MINUTE_MS, durationMs = 3 * HOUR_MS)
            val report = h.sync.run(inBackground = false)

            assertEquals(0, report.pipeline.estimated)
            assertTrue(h.repository.ownSessions().isEmpty(), "the merged night has another clientRecordId, but the user deleted it")
            assertEquals(listOf(NightStatus.DELETED), h.ledger.allNights().map { it.status })

            h.backfill.run(inBackground = false)
            assertEquals(1, h.repository.ownSessions().size, "an explicit backfill still restores it")
        }

    @Test
    fun `erasing cancels a running backfill so that it cannot write the erased nights again`() =
        harnessTest(dir) { h ->
            (10 downTo 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            h.processRecent()
            val old = h.repository.addNight(OHEALTH, nightStart(40))
            h.estimator.beforeEstimate = { if (it.sessionStartMs == old.startMs) delay(10_000) }
            val backfill = async { h.backfill.run(inBackground = false) }
            delay(1_000)

            val erased = h.eraser.eraseAll()
            val outcome = backfill.await()

            assertEquals(11, erased.deleted, "the night the backfill was finishing is erased too")
            assertEquals(BackfillOutcome.CANCELLED, outcome.outcome)
            assertTrue(h.repository.ownSessions().isEmpty())
            assertTrue(h.ledger.allNights().all { it.status == NightStatus.DELETED })
        }

    @Test
    fun `erasing stops a running recompute so that it cannot write the erased nights again`() =
        harnessTest(dir) { h ->
            // Three nights more than a week apart: three recompute groups.
            listOf(30, 20, 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            h.backfill.run(inBackground = false)
            assertEquals(3, h.repository.ownSessions().size)
            val first = nightStart(30)
            h.estimator.beforeEstimate = { if (it.sessionStartMs == first) delay(10_000) }

            val recompute = async { h.recomputer.recomputeAll(RecomputeScope.ALL, inBackground = false) }
            delay(1_000)
            val erased = h.eraser.eraseAll()
            val report = recompute.await()

            assertEquals(3, erased.deleted, "the night the recompute was finishing is erased too")
            assertTrue(report.cancelled)
            assertTrue(h.repository.ownSessions().isEmpty(), "erased sessions were written again")
            assertTrue(h.ledger.allNights().all { it.status == NightStatus.DELETED })

            assertEquals(3, h.recomputer.recompute(h.ledger.allNights().map { it.clientRecordId }, inBackground = false).estimated)
            assertEquals(3, h.repository.ownSessions().size, "a recompute requested after the erase writes them again")
        }

    @Test
    fun `a recompute or backfill requested before an erase does not start`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.processRecent()
            val requestedAt = h.clock.millis()
            h.clock.advanceBy(1_000)
            h.eraser.eraseAll()
            val night = h.ledger.allNights().single()

            val recompute = h.recomputer.recompute(listOf(night.clientRecordId), inBackground = false, requestedAtMs = requestedAt)
            assertTrue(recompute.cancelled)
            assertTrue(h.recomputer.recomputeAll(RecomputeScope.ALL, inBackground = false, notUpdatedSinceMs = requestedAt).cancelled)
            assertEquals(BackfillOutcome.CANCELLED, h.backfill.run(inBackground = false, requestedAtMs = requestedAt).outcome)
            assertTrue(h.repository.ownSessions().isEmpty())
        }

    @Test
    fun `a night deleted while a recompute or backfill runs is not written again by it, the other nights are`() =
        harnessTest(dir) { h ->
            listOf(30, 20, 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            h.backfill.run(inBackground = false)
            val nights = h.ledger.allNights().sortedBy { it.sessionStartMs }
            val newest = nights.last()
            val oldest = nights.first().sessionStartMs
            h.estimator.beforeEstimate = { if (it.sessionStartMs == oldest) delay(10_000) }

            val recompute = async { h.recomputer.recomputeAll(RecomputeScope.ALL, inBackground = false) }
            delay(1_000)
            h.clock.advanceBy(1_000)
            assertEquals(DeleteNightResult.DELETED, h.eraser.deleteNight(newest.clientRecordId))
            val report = recompute.await()

            assertEquals(2, report.estimated)
            assertFalse(report.cancelled)
            assertEquals(listOf(nights[0], nights[1]).map { it.clientRecordId }, h.repository.ownSessions().map { it.clientRecordId })
            assertEquals(
                NightStatus.DELETED,
                h.ledger
                    .allNights()
                    .single { it.clientRecordId == newest.clientRecordId }
                    .status,
            )

            // The same for a backfill: it restores a night deleted before it started, not one deleted during the run.
            h.clock.advanceBy(1_000)
            val backfill = async { h.backfill.run(inBackground = false) }
            delay(1_000)
            h.clock.advanceBy(1_000)
            assertEquals(DeleteNightResult.DELETED, h.eraser.deleteNight(nights[1].clientRecordId))
            assertEquals(BackfillOutcome.DONE, backfill.await().outcome)

            assertEquals(
                listOf(nights[0].clientRecordId, newest.clientRecordId),
                h.repository.ownSessions().map { it.clientRecordId },
                "the night deleted before the backfill is restored, the one deleted during it is not",
            )
            assertEquals(
                NightStatus.DELETED,
                h.ledger
                    .allNights()
                    .single { it.clientRecordId == nights[1].clientRecordId }
                    .status,
            )
        }

    @Test
    fun `erasing needs Health Connect`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.processRecent()
            h.repository.availability = HcAvailability.NOT_SUPPORTED
            assertThrows<io.github.alien2003.pulsestages.data.hc.HealthDataException.Unavailable> { h.eraser.eraseAll() }
            assertEquals(
                NightStatus.WRITTEN,
                h.ledger
                    .allNights()
                    .single()
                    .status,
                "nothing is marked when the delete fails",
            )
        }
}

class HealthAccessGuardTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `the guard checks availability, required permissions and the background permission`() =
        harnessTest(dir) { h ->
            assertTrue(h.guard.check(inBackground = true).allowed)

            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            assertEquals(BlockedReason.BACKGROUND_PERMISSION_MISSING, h.guard.check(inBackground = true).blockedReason)
            assertTrue(h.guard.check(inBackground = false).allowed)

            h.repository.revoke(HealthPermissions.WRITE_SLEEP)
            val missing = h.guard.check(inBackground = false)
            assertEquals(BlockedReason.PERMISSION_MISSING, missing.blockedReason)
            assertEquals(setOf(HealthPermissions.WRITE_SLEEP), missing.missingRequired)

            h.repository.availability = HcAvailability.PROVIDER_UPDATE_REQUIRED
            assertEquals(BlockedReason.HEALTH_CONNECT_UNAVAILABLE, h.guard.check(inBackground = false).blockedReason)
        }

    @Test
    fun `a refusal with every required permission granted but no background permission is the background rule`() =
        harnessTest(dir) { h ->
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            assertEquals(BlockedReason.BACKGROUND_PERMISSION_MISSING, h.guard.reasonForDenial())

            h.repository.revoke(HealthPermissions.READ_STEPS)
            assertEquals(BlockedReason.PERMISSION_MISSING, h.guard.reasonForDenial(), "a revoked permission explains it better")

            h.repository.grant(HealthPermissions.READ_STEPS)
            h.repository.grant(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            assertEquals(BlockedReason.PERMISSION_MISSING, h.guard.reasonForDenial())

            h.repository.availability = HcAvailability.NOT_SUPPORTED
            assertEquals(BlockedReason.HEALTH_CONNECT_UNAVAILABLE, h.guard.reasonForDenial())
        }
}

package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.SourceOutcome
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NewNightsSyncTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `the first run takes a token and processes the last 7 days only`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(10))
            val recent = h.repository.addNight(OHEALTH, nightStart(2))

            val report = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.SUCCESS, report.outcome)
            assertTrue(report.tokenRenewed)
            assertEquals(NOON - 7 * DAY_MS, report.rereadFromMs)
            assertEquals(1, report.pipeline.estimated)
            assertEquals(listOf(recent.startMs), h.repository.ownSessions().map { it.startMs })
            val state = h.syncState.state.first()
            assertNotNull(state.changesToken)
            assertEquals(listOf(OHEALTH), state.changesToken?.packages)
            assertEquals(SyncOutcome.SUCCESS, state.lastRunOutcome)
        }

    @Test
    fun `new nights are found through the Changes API and the token advances`() =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            val firstToken =
                h.syncState.state
                    .first()
                    .changesToken
                    ?.token
            h.clock.advanceBy(DAY_MS)
            h.repository.addNight(OHEALTH, nightStart(0))
            h.repository.clearCalls()

            val report = h.sync.run(inBackground = false)

            assertFalse(report.tokenRenewed)
            assertEquals(1, report.pages)
            assertEquals(1, report.pipeline.estimated)
            assertEquals(1, h.repository.ownSessions().size)
            assertEquals(0, h.repository.callCount(Operation.CHANGES_TOKEN))
            val advanced =
                h.syncState.state
                    .first()
                    .changesToken
                    ?.token
            assertNotNull(advanced)
            assertTrue(advanced != firstToken)

            val idle = h.sync.run(inBackground = false)
            assertEquals(PipelineReport(), idle.pipeline)
            assertEquals(
                advanced,
                h.syncState.state
                    .first()
                    .changesToken
                    ?.token,
                "no changes → same token",
            )
        }

    @Test
    fun `all pages are processed while hasMore and the token is saved after each page`() =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            h.repository.changesPageSize = 1
            (3 downTo 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }

            val report = h.sync.run(inBackground = false)

            assertEquals(3, report.pages)
            assertEquals(3, report.pipeline.estimated)
            assertEquals(3, h.repository.ownSessions().size)
            assertEquals(3, h.repository.callCount(Operation.CHANGES), "one call per page (the first sync used no Changes call)")
        }

    @Test
    fun `a quota error during changes does not advance the token, the next run completes`() =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            val token =
                h.syncState.state
                    .first()
                    .changesToken
                    ?.token
            (3 downTo 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            h.repository.failOn(Operation.WRITE, HealthDataException.QuotaExceeded("rate limit"), afterCalls = 1)

            val limited = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.QUOTA_EXCEEDED, limited.outcome)
            assertTrue(limited.pipeline.quotaHit)
            assertEquals(
                token,
                h.syncState.state
                    .first()
                    .changesToken
                    ?.token,
            )
            assertEquals(
                SyncOutcome.QUOTA_EXCEEDED,
                h.syncState.state
                    .first()
                    .lastRunOutcome,
            )

            val retried = h.sync.run(inBackground = false)
            assertEquals(SyncOutcome.SUCCESS, retried.outcome)
            assertEquals(3, h.repository.ownSessions().size)
        }

    @Test
    fun `an expired token falls back to re-reading the last 7 days`() =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            h.clock.advanceBy(HOUR_MS)
            val late = h.repository.addNight(OHEALTH, nightStart(3))
            h.repository.expireAllTokens()

            val report = h.sync.run(inBackground = false)

            assertTrue(report.tokenRenewed)
            assertEquals(1, report.pipeline.estimated)
            assertEquals(listOf(late.startMs), h.repository.ownSessions().map { it.startMs })
            assertNotNull(
                h.syncState.state
                    .first()
                    .changesToken,
            )

            h.clock.advanceBy(HOUR_MS)
            assertFalse(h.sync.run(inBackground = false).tokenRenewed, "the renewed token is used next time")
        }

    @Test
    fun `an expired-token re-read that stops early keeps the old token and is repeated over the same range`() =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            val oldToken =
                h.syncState.state
                    .first()
                    .changesToken
            h.clock.advanceBy(20 * DAY_MS)
            val missed = (19 downTo 1).map { h.repository.addNight(OHEALTH, nightStart(-20 + it)) }
            h.repository.expireAllTokens()
            h.repository.failOn(Operation.WRITE, HealthDataException.QuotaExceeded("rate limit"), afterCalls = 3)

            val limited = h.sync.run(inBackground = false)
            assertEquals(SyncOutcome.QUOTA_EXCEEDED, limited.outcome)
            assertEquals(NOON - DAY_MS, limited.rereadFromMs, "back to the last sync")
            assertEquals(
                oldToken,
                h.syncState.state
                    .first()
                    .changesToken,
                "no new token after an early stop",
            )

            h.repository.failOn(Operation.READ_SLEEP_SESSIONS, HealthDataException.Transient("binder died"))
            assertEquals(SyncOutcome.FAILED, h.sync.run(inBackground = false).outcome)
            assertEquals(
                oldToken,
                h.syncState.state
                    .first()
                    .changesToken,
                "an error keeps the old token as well",
            )

            val completed = h.sync.run(inBackground = false)
            assertEquals(SyncOutcome.SUCCESS, completed.outcome)
            assertEquals(limited.rereadFromMs, completed.rereadFromMs, "the same range, not just the last 7 days")
            assertEquals(missed.map { it.startMs }.sorted(), h.repository.ownSessions().map { it.startMs })
            assertNotEquals(
                oldToken?.token,
                h.syncState.state
                    .first()
                    .changesToken
                    ?.token,
            )
        }

    @Test
    fun `a re-read after a token reset applies the deletions the abandoned change log would have reported`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(1))
            val kept = h.repository.addNight(OHEALTH, nightStart(2))
            h.sync.run(inBackground = false)
            h.repository.deleteSleepSession(source.uuid)
            h.repository.expireAllTokens()

            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.deletedSessions)
            assertEquals(listOf(kept.startMs), h.repository.ownSessions().map { it.startMs })
            assertEquals(
                NightStatus.SOURCE_DELETED,
                h.ledger
                    .allNights()
                    .single { it.sourceUuids == listOf(source.uuid) }
                    .status,
            )
        }

    @Test
    fun `a new package set re-reads and applies deletions that happened before the switch`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            h.repository.deleteSleepSession(source.uuid)
            h.settings.set {
                it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OHEALTH, OTHER_APP)))
            }

            h.sync.run(inBackground = false)

            assertTrue(h.repository.ownSessions().isEmpty())
            assertEquals(listOf(NightStatus.SOURCE_DELETED), h.ledger.allNights().map { it.status })
        }

    @Test
    fun `a token not used for more than 25 days is renewed and the re-read reaches back to the last sync`() =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            h.clock.advanceBy(26 * DAY_MS)
            val missed = h.repository.addNight(OHEALTH, nightStart(-6))

            val report = h.sync.run(inBackground = false)

            assertTrue(report.tokenRenewed)
            assertEquals(NOON - DAY_MS, report.rereadFromMs)
            assertEquals(listOf(missed.startMs), h.repository.ownSessions().map { it.startMs })
        }

    @Test
    fun `a changed package set gets a new token and a re-read`() =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            val other = h.repository.addNight(OTHER_APP, nightStart(2))
            h.settings.set {
                it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OHEALTH, OTHER_APP)))
            }

            val report = h.sync.run(inBackground = false)

            assertTrue(report.tokenRenewed)
            assertEquals(listOf(other.startMs), h.repository.ownSessions().map { it.startMs })
            assertEquals(
                listOf(OHEALTH, OTHER_APP).sorted(),
                h.syncState.state
                    .first()
                    .changesToken
                    ?.packages,
            )
        }

    @Test
    fun `a fragment arriving through the Changes API supersedes the earlier night`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addNight(OHEALTH, start, durationMs = 4 * HOUR_MS)
            h.sync.run(inBackground = false)
            h.repository.addNight(OHEALTH, start + 4 * HOUR_MS + 15 * MINUTE_MS, durationMs = 3 * HOUR_MS)

            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.pipeline.estimated)
            assertEquals(1, report.pipeline.superseded)
            val own = h.repository.ownSessions().single()
            assertEquals(start, own.startMs)
            assertEquals(start + 7 * HOUR_MS + 15 * MINUTE_MS, own.endMs)
        }

    @Test
    fun `deleting the only source removes our session`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            assertEquals(1, h.repository.ownSessions().size)

            h.repository.deleteSleepSession(source.uuid)
            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.deletedSessions)
            assertTrue(h.repository.ownSessions().isEmpty())
            val night = h.ledger.allNights().single()
            assertEquals(NightStatus.SOURCE_DELETED, night.status)
            assertNull(night.hcRecordId)
            assertEquals(
                SourceOutcome.DELETED,
                h.ledger
                    .allSources()
                    .single()
                    .outcome,
            )
            assertTrue(h.ledger.allBaselines().isEmpty())
        }

    @Test
    fun `deleting one fragment rebuilds the night from the remaining fragments`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            val first = h.repository.addNight(OHEALTH, start, durationMs = 4 * HOUR_MS)
            val second = h.repository.addNight(OHEALTH, start + 4 * HOUR_MS + 10 * MINUTE_MS, durationMs = 3 * HOUR_MS)
            h.sync.run(inBackground = false)
            val mergedCid = NightKeys.clientRecordId(listOf(first.uuid, second.uuid))
            assertEquals(listOf(mergedCid), h.repository.ownSessions().map { it.clientRecordId })

            h.repository.deleteSleepSession(second.uuid)
            h.sync.run(inBackground = false)

            val rebuiltCid = NightKeys.clientRecordId(listOf(first.uuid))
            assertEquals(listOf(rebuiltCid), h.repository.ownSessions().map { it.clientRecordId })
            assertEquals(
                first.endMs,
                h.repository
                    .ownSessions()
                    .single()
                    .endMs,
            )
            assertEquals(
                mapOf(mergedCid to NightStatus.SUPERSEDED, rebuiltCid to NightStatus.WRITTEN),
                h.ledger.allNights().associate { it.clientRecordId to it.status },
            )
        }

    @Test
    fun `when the remaining fragment is a nap the night is retired`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addNight(OHEALTH, start, durationMs = 60 * MINUTE_MS)
            val main = h.repository.addNight(OHEALTH, start + 70 * MINUTE_MS, durationMs = 6 * HOUR_MS)
            h.sync.run(inBackground = false)
            assertEquals(1, h.repository.ownSessions().size)

            h.repository.deleteSleepSession(main.uuid)
            h.sync.run(inBackground = false)

            assertTrue(h.repository.ownSessions().isEmpty())
            assertEquals(listOf(NightStatus.SOURCE_DELETED), h.ledger.allNights().map { it.status })
            val napRow = h.ledger.allSources().single { it.uuid != main.uuid }
            assertEquals(SourceOutcome.NAP, napRow.outcome, "the remaining fragment is counted as a nap, not as estimated")
            assertNull(napRow.nightClientRecordId)
            assertEquals(
                SourceOutcome.DELETED,
                h.ledger
                    .allSources()
                    .single { it.uuid == main.uuid }
                    .outcome,
            )
        }

    @Test
    fun `NEVER - deleting one fragment keeps the night's output`() =
        harnessTest(dir) { h ->
            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.NEVER)) }
            val start = nightStart(1)
            h.repository.addNight(OHEALTH, start, durationMs = 4 * HOUR_MS)
            val second = h.repository.addNight(OHEALTH, start + 4 * HOUR_MS + 10 * MINUTE_MS, durationMs = 3 * HOUR_MS)
            h.sync.run(inBackground = false)
            val before = h.repository.ownSessions().single()

            h.repository.deleteSleepSession(second.uuid)
            h.sync.run(inBackground = false)

            assertEquals(listOf(before), h.repository.ownSessions(), "NEVER never replaces an output, so it is kept")
            assertEquals(listOf(NightStatus.WRITTEN), h.ledger.allNights().map { it.status })
            assertTrue(h.logger.lines.none { "do not form a night" in it.message })
        }

    @Test
    fun `when the winning source of a night is deleted, the lower-priority duplicate takes its place`() =
        harnessTest(dir) { h ->
            h.settings.set {
                it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OHEALTH, OTHER_APP)))
            }
            val winner = h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.addNight(OTHER_APP, nightStart(1) + 10 * MINUTE_MS)
            h.sync.run(inBackground = false)
            assertEquals(listOf(OHEALTH), h.ledger.allNights().map { it.sourcePackage })

            h.repository.deleteSleepSession(winner.uuid)
            h.sync.run(inBackground = false)

            assertEquals(1, h.repository.ownSessions().size)
            val written = h.ledger.allNights().single { it.status == NightStatus.WRITTEN }
            assertEquals(OTHER_APP, written.sourcePackage)
        }

    @Test
    fun `a supersede that failed after the write is repaired by the next sync`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addNight(OHEALTH, start, durationMs = 4 * HOUR_MS)
            h.sync.run(inBackground = false)
            h.repository.addNight(OHEALTH, start + 4 * HOUR_MS + 15 * MINUTE_MS, durationMs = 3 * HOUR_MS)
            h.repository.failOn(Operation.DELETE, HealthDataException.Transient("binder died"))

            assertEquals(SyncOutcome.PARTIAL_FAILURE, h.sync.run(inBackground = false).outcome)
            assertEquals(2, h.repository.ownSessions().size, "the new night was written, the old one could not be deleted")

            val repaired = h.sync.run(inBackground = false)

            assertEquals(1, repaired.pipeline.superseded)
            assertEquals(1, h.repository.ownSessions().size)
            assertEquals(listOf(NightStatus.SUPERSEDED, NightStatus.WRITTEN), h.ledger.allNights().map { it.status })
            assertEquals(
                start + 7 * HOUR_MS + 15 * MINUTE_MS,
                h.repository
                    .ownSessions()
                    .single()
                    .endMs,
            )
        }

    @Test
    fun `in the background without READ_HEALTH_DATA_IN_BACKGROUND nothing is read`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.inBackground = true
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)

            val report = h.sync.run(inBackground = true)

            assertEquals(SyncOutcome.BACKGROUND_READ_BLOCKED, report.outcome)
            assertEquals(BlockedReason.BACKGROUND_PERMISSION_MISSING, report.pipeline.blockedReason)
            assertEquals(listOf(Operation.GRANTED_PERMISSIONS), h.repository.calls)
            assertTrue(h.ledger.allNights().isEmpty(), "no empty nights are produced")

            h.repository.grant(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            assertEquals(SyncOutcome.SUCCESS, h.sync.run(inBackground = true).outcome)
            assertEquals(1, h.repository.ownSessions().size)
        }

    @Test
    fun `a foreground run that loses the foreground without the background permission is reported as background-blocked`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            h.estimator.beforeEstimate = { h.repository.inBackground = true }

            val midRun = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.BACKGROUND_READ_BLOCKED, midRun.outcome, "not a missing permission: all of them are granted")
            assertEquals(1, midRun.pipeline.estimated)

            val atStart = h.sync.run(inBackground = false)
            assertEquals(SyncOutcome.BACKGROUND_READ_BLOCKED, atStart.outcome)
        }

    @Test
    fun `foreground runs do not need the background permission`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            assertEquals(SyncOutcome.SUCCESS, h.sync.run(inBackground = false).outcome)
            assertEquals(1, h.repository.ownSessions().size)
        }

    @Test
    fun `unavailable Health Connect, missing permissions and unexpected errors are reported, never thrown`() =
        harnessTest(dir) { h ->
            h.repository.availability = HcAvailability.PROVIDER_UPDATE_REQUIRED
            assertEquals(SyncOutcome.HEALTH_CONNECT_UNAVAILABLE, h.sync.run(inBackground = false).outcome)

            h.repository.availability = HcAvailability.AVAILABLE
            h.repository.revoke(HealthPermissions.READ_HEART_RATE)
            assertEquals(SyncOutcome.PERMISSION_DENIED, h.sync.run(inBackground = false).outcome)

            h.repository.grant(HealthPermissions.READ_HEART_RATE)
            h.repository.failOn(Operation.CHANGES_TOKEN, HealthDataException.Transient("binder died"))
            val transient = h.sync.run(inBackground = false)
            assertEquals(SyncOutcome.FAILED, transient.outcome)
            assertTrue(transient.error.orEmpty().contains("binder died"))
            assertNull(
                h.syncState.state
                    .first()
                    .changesToken,
            )

            assertEquals(SyncOutcome.SUCCESS, h.sync.run(inBackground = false).outcome)
        }

    @Test
    fun `a night that failed is reported as a partial failure`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            h.estimator.failWhen = { if (it.sessionStartMs == nightStart(2)) IllegalArgumentException("boom") else null }

            val report = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.PARTIAL_FAILURE, report.outcome)
            assertEquals(1, report.pipeline.failed)
            assertEquals(1, report.pipeline.estimated)
        }

    @Test
    fun `heart rate that arrives after its session is picked up by a later sync`() =
        harnessTest(dir) { h ->
            val start = nightStart(0)
            h.repository.addNight(OHEALTH, start, withHeartRate = false)
            h.sync.run(inBackground = false)
            assertEquals(
                NightStatus.NOT_ESTIMATED,
                h.ledger
                    .allNights()
                    .single()
                    .status,
            )

            h.clock.advanceBy(HOUR_MS)
            h.repository.addHeartRate(OHEALTH, start, start + 8 * HOUR_MS)
            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.pipeline.estimated)
            assertEquals(
                NightStatus.WRITTEN,
                h.ledger
                    .allNights()
                    .single()
                    .status,
            )
            assertEquals(1, h.repository.ownSessions().size)
        }

    @Test
    fun `failed nights are retried by the next sync`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(0))
            h.estimator.failWhen = { IllegalStateException("transient bug") }
            h.sync.run(inBackground = false)
            assertEquals(listOf(NightStatus.FAILED), h.ledger.allNights().map { it.status })

            h.estimator.failWhen = { null }
            h.clock.advanceBy(HOUR_MS)
            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.pipeline.estimated)
            assertEquals(listOf(nightStart(0)), h.repository.ownSessions().map { it.startMs })
        }

    @Test
    fun `a late night that failed transiently is retried by the next sync`() =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            h.repository.addNight(OHEALTH, nightStart(5))
            h.repository.failOn(Operation.READ_HEART_RATE, HealthDataException.Transient("binder died"))
            assertEquals(SyncOutcome.PARTIAL_FAILURE, h.sync.run(inBackground = false).outcome)
            assertEquals(listOf(NightStatus.FAILED), h.ledger.allNights().map { it.status })

            h.clock.advanceBy(HOUR_MS)
            assertEquals(
                1,
                h.sync
                    .run(inBackground = false)
                    .pipeline.estimated,
                "the token advanced, but the night is retried",
            )
            assertEquals(1, h.repository.ownSessions().size)
        }

    @Test
    fun `a late night whose heart rate arrives a day later is retried with growing gaps and then written`() =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            val late = h.repository.addNight(OHEALTH, nightStart(3), withHeartRate = false)
            h.sync.run(inBackground = false)
            h.repository.clearCalls()

            repeat(23) {
                h.clock.advanceBy(HOUR_MS)
                h.sync.run(inBackground = false)
            }
            assertEquals(5, h.repository.callCount(Operation.READ_HEART_RATE), "retries 1, 2, 4, 8 and 16 h after it was first seen")

            h.repository.addHeartRate(OHEALTH, late.startMs, late.endMs)
            repeat(12) {
                h.clock.advanceBy(HOUR_MS)
                h.sync.run(inBackground = false)
            }
            assertEquals(listOf(late.startMs), h.repository.ownSessions().map { it.startMs }, "written by the retry 32 h after")
        }

    @Test
    fun `nights first seen more than 36 hours ago are no longer retried automatically`() =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            val late = h.repository.addNight(OHEALTH, nightStart(5), withHeartRate = false)
            h.sync.run(inBackground = false)

            h.clock.advanceBy(40 * HOUR_MS)
            h.repository.addHeartRate(OHEALTH, late.startMs, late.endMs)
            h.repository.clearCalls()
            h.sync.run(inBackground = false)

            assertEquals(0, h.repository.callCount(Operation.READ_HEART_RATE))
            assertEquals(listOf(NightStatus.NOT_ESTIMATED), h.ledger.allNights().map { it.status })
        }

    @Test
    fun `no enabled source means nothing is read`() =
        harnessTest(dir) { h ->
            h.settings.set { it.copy(sources = it.sources.copy(sourcePackages = emptyList())) }
            val report = h.sync.run(inBackground = false)
            assertEquals(SyncOutcome.SUCCESS, report.outcome)
            assertEquals(0, h.repository.callCount(Operation.CHANGES_TOKEN))
            assertEquals(0, h.repository.callCount(Operation.READ_SLEEP_SESSIONS))
        }
}

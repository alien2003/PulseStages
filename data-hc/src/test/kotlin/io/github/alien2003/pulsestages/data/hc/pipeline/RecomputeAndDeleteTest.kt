package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RecomputeAndDeleteTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `recompute all rewrites every night, also up-to-date ones, and reports progress`() =
        harnessTest(dir) { h ->
            listOf(20, 10, 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            h.backfill.run(inBackground = false)
            val progress = mutableListOf<RecomputeProgress>()

            val report = h.recomputer.recomputeAll(RecomputeScope.ALL, inBackground = false) { progress += it }

            assertEquals(3, report.estimated)
            assertEquals(listOf(0, 1, 2, 3), progress.map { it.done })
            assertTrue(progress.all { it.total == 3 })
            assertEquals(listOf(1, 1, 1), h.ledger.allNights().map { it.recomputeCount })
            assertEquals(
                listOf(ALGO_VERSION * 1000L + 1),
                h.repository
                    .ownSessions()
                    .map { it.clientRecordVersion }
                    .distinct(),
            )
        }

    @Test
    fun `an interrupted recompute all resumes with the nights not recomputed yet`() =
        harnessTest(dir) { h ->
            listOf(20, 10, 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            h.backfill.run(inBackground = false)
            h.clock.advanceBy(MINUTE_MS)
            val requestedAt = h.clock.millis()
            h.clock.advanceBy(MINUTE_MS)
            h.repository.failOn(Operation.READ_HEART_RATE, HealthDataException.QuotaExceeded("quota"), afterCalls = 1)

            val first = h.recomputer.recomputeAll(RecomputeScope.ALL, inBackground = true, notUpdatedSinceMs = requestedAt)

            assertTrue(first.quotaHit)
            assertEquals(1, first.estimated)
            h.estimator.inputs.clear()
            val second = h.recomputer.recomputeAll(RecomputeScope.ALL, inBackground = true, notUpdatedSinceMs = requestedAt)
            assertEquals(2, second.estimated)
            assertEquals(
                listOf(nightStart(10), nightStart(1)),
                h.estimator.inputs.map { it.sessionStartMs },
                "the first night is not redone",
            )
            assertEquals(listOf(1, 1, 1), h.ledger.allNights().map { it.recomputeCount })
        }

    @Test
    fun `an explicit recompute still obeys overwritePolicy NEVER`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.NEVER)) }
            val night = h.ledger.allNights().single()

            val report = h.recomputer.recompute(listOf(night.clientRecordId), inBackground = false)

            assertEquals(0, report.estimated)
            assertEquals(1, report.skipped)
            assertEquals(
                0,
                h.ledger
                    .allNights()
                    .single()
                    .recomputeCount,
            )
        }

    @Test
    fun `delete my session removes our record of one night and keeps it deleted for automatic syncs`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            val (kept, deleted) = h.ledger.allNights()

            assertEquals(DeleteNightResult.DELETED, h.eraser.deleteNight(deleted.clientRecordId))

            assertEquals(listOf(kept.clientRecordId), h.repository.ownSessions().map { it.clientRecordId })
            assertEquals(
                NightStatus.DELETED,
                h.ledger
                    .allNights()
                    .single { it.clientRecordId == deleted.clientRecordId }
                    .status,
            )
            assertEquals(DeleteNightResult.NO_OUTPUT, h.eraser.deleteNight(deleted.clientRecordId))
            assertEquals(DeleteNightResult.NOT_FOUND, h.eraser.deleteNight("ps:unknown"))

            h.sync.run(inBackground = false)
            assertEquals(1, h.repository.ownSessions().size, "the next sync does not write it again")

            h.recomputer.recompute(listOf(deleted.clientRecordId), inBackground = false)
            assertEquals(2, h.repository.ownSessions().size, "an explicit recompute of that night does")
        }
}

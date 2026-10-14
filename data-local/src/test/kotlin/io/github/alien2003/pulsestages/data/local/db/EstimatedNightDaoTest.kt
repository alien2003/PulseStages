package io.github.alien2003.pulsestages.data.local.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.data.local.testutil.DatabaseTest
import io.github.alien2003.pulsestages.data.local.testutil.FlowRecorder
import io.github.alien2003.pulsestages.data.local.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.local.testutil.night
import io.github.alien2003.pulsestages.data.local.testutil.session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimatedNightDaoTest : DatabaseTest() {
    private val dao get() = db.estimatedNightDao()
    private val baselines get() = db.nightBaselineDao()
    private val sessions get() = db.sourceSessionDao()

    @Test
    fun upsertInsertsThenUpdatesInPlaceKeepingIdAndCreatedAt() =
        runTest {
            val id = dao.upsert(night("ps:a", startMs = 0, now = 1_000L))

            val updatedId =
                dao.upsert(
                    night("ps:a", startMs = 0, now = 5_000L).copy(
                        recomputeCount = 1,
                        clientRecordVersion = 1001L,
                        warnings = listOf(WarningCode.DATA_GAPS),
                    ),
                )

            assertEquals(id, updatedId)
            val stored = checkNotNull(dao.findByClientRecordId("ps:a"))
            assertEquals(id, stored.id)
            assertEquals(1_000L, stored.createdAt)
            assertEquals(5_000L, stored.updatedAt)
            assertEquals(1, stored.recomputeCount)
            assertEquals(1001L, stored.clientRecordVersion)
            assertEquals(listOf(WarningCode.DATA_GAPS), stored.warnings)
            assertEquals(1, dao.observeSummaries().first().size)
        }

    @Test
    fun modeAndSleepWakeOnlyRoundTripAndDefaultToANightWithEveryStage() =
        runTest {
            dao.upsert(night("ps:night", startMs = 0))
            dao.upsert(night("ps:nap", startMs = 24 * HOUR_MS, hours = 1).copy(mode = SessionMode.NAP, sleepWakeOnly = true))

            val night = checkNotNull(dao.findByClientRecordId("ps:night"))
            val nap = checkNotNull(dao.findByClientRecordId("ps:nap"))
            assertEquals(SessionMode.NIGHT, night.mode)
            assertEquals(false, night.sleepWakeOnly)
            assertEquals(SessionMode.NAP, nap.mode)
            assertEquals(true, nap.sleepWakeOnly)
            val summaries = dao.observeSummaries().first().associateBy { it.clientRecordId }
            assertEquals(SessionMode.NAP, summaries.getValue("ps:nap").mode)
            assertEquals(true, summaries.getValue("ps:nap").sleepWakeOnly)
            assertEquals(SessionMode.NIGHT, summaries.getValue("ps:night").mode)
            // A row inserted without the new columns (as version 1 wrote them) reads as a night with every stage.
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO estimated_nights (clientRecordId, sourceUuids, sourcePackage, sessionStartMs, sessionEndMs, " +
                    "startZoneOffsetSec, endZoneOffsetSec, algoVersion, paramsHash, estimator, coverage, confidence, recomputeCount, " +
                    "clientRecordVersion, status, awakeMin, lightMin, deepMin, remMin, unknownMin, elevatedHr, lowConfidence, " +
                    "calibrated, rulesFallback, warnings, hrSamples, stagesJson, createdAt, updatedAt) " +
                    "VALUES ('ps:raw', '[]', 'p', 0, 1, 0, 0, 1, 'h', 'HMM', 0, 0, 0, 0, 'WRITTEN', 0, 0, 0, 0, 0, 0, 0, 0, 0, " +
                    "'[]', 0, '[]', 0, 0)",
            )
            val raw = checkNotNull(dao.findByClientRecordId("ps:raw"))
            assertEquals(SessionMode.NIGHT, raw.mode)
            assertEquals(false, raw.sleepWakeOnly)
        }

    @Test
    fun clientRecordIdIsUniqueInTheSchema() =
        runTest {
            dao.upsert(night("ps:a", startMs = 0))
            val sql =
                "INSERT INTO estimated_nights (clientRecordId, sourceUuids, sourcePackage, sessionStartMs, sessionEndMs, " +
                    "startZoneOffsetSec, endZoneOffsetSec, algoVersion, paramsHash, estimator, coverage, confidence, recomputeCount, " +
                    "clientRecordVersion, status, awakeMin, lightMin, deepMin, remMin, unknownMin, elevatedHr, lowConfidence, " +
                    "calibrated, rulesFallback, warnings, hrSamples, stagesJson, createdAt, updatedAt) VALUES ('ps:a', '[]', 'p', 0, 1, " +
                    "0, 0, 1, 'h', 'HMM', 0, 0, 0, 0, 'WRITTEN', 0, 0, 0, 0, 0, 0, 0, 0, 0, '[]', 0, '[]', 0, 0)"

            assertThrows(SQLiteConstraintException::class.java) { db.openHelper.writableDatabase.execSQL(sql) }
        }

    @Test
    fun upsertStoresSortedDistinctSourcesAndReplacesLinks() =
        runTest {
            dao.upsert(night("ps:a", startMs = 0, sourceUuids = listOf("u3", "u1", "u3")))
            assertEquals(listOf("u1", "u3"), checkNotNull(dao.findByClientRecordId("ps:a")).sourceUuids)
            assertEquals(listOf("u1", "u3"), dao.sourceUuidsOf("ps:a"))

            dao.upsert(night("ps:a", startMs = 0, sourceUuids = listOf("u2")))

            assertEquals(listOf("u2"), dao.sourceUuidsOf("ps:a"))
            assertTrue(dao.findBySourceUuids(listOf("u1")).isEmpty())
            assertEquals(listOf("ps:a"), dao.findBySourceUuids(listOf("u2")).map { it.clientRecordId })
        }

    @Test
    fun entityRoundTripsEveryColumn() =
        runTest {
            val original = night("ps:a", startMs = 1_700_000_000_000L).copy(modelId = "walch", statusReason = "x", lowConfidence = true)

            val id = dao.upsert(original)

            assertEquals(original.copy(id = id), dao.findById(id))
        }

    @Test
    fun recordNightWritesNightSessionsAndBaselineTogether() =
        runTest {
            val written = night("ps:a", startMs = 0, sourceUuids = listOf("u1", "u2"), nadirBpm = 48.5)

            dao.recordNight(
                written,
                listOf(
                    session("u1", 0, SourceOutcome.ESTIMATED, "ps:a", hours = 3),
                    session("u2", 3 * HOUR_MS, SourceOutcome.ESTIMATED, "ps:a", hours = 5),
                ),
            )

            assertEquals(written.clientRecordId, dao.findByClientRecordId("ps:a")?.clientRecordId)
            assertEquals(listOf("u1", "u2"), sessions.findByNight("ps:a").map { it.uuid })
            assertEquals(NightBaselineEntity("ps:a", written.sessionEndMs, 48.5), baselines.find("ps:a"))
        }

    @Test
    fun recordNightWithoutEstimateLeavesBaselinesUntouched() =
        runTest {
            dao.recordNight(night("ps:a", startMs = 0, nadirBpm = 50.0), emptyList())

            dao.recordNight(
                night("ps:a", startMs = 0, status = NightStatus.NOT_ESTIMATED, nadirBpm = null).copy(statusReason = "LOW_COVERAGE"),
                listOf(session("ps:a-src", 0, SourceOutcome.NOT_ESTIMATED, "ps:a")),
            )
            dao.recordNight(night("ps:b", startMs = 24 * HOUR_MS, status = NightStatus.FAILED, nadirBpm = 70.0), emptyList())

            assertEquals(50.0, checkNotNull(baselines.find("ps:a")).nadirBpm, 0.0)
            assertNull("FAILED nights never feed the baseline", baselines.find("ps:b"))
            assertEquals(NightStatus.NOT_ESTIMATED, dao.findByClientRecordId("ps:a")?.status)
        }

    @Test
    fun failingOuterTransactionRollsBackEveryTable() =
        runTest {
            val failure =
                runCatching {
                    db.withTransaction {
                        dao.recordNight(night("ps:a", startMs = 0), listOf(session("ps:a-src", 0, SourceOutcome.ESTIMATED, "ps:a")))
                        error("write to Health Connect failed")
                    }
                }

            assertTrue(failure.isFailure)
            assertNull(dao.findByClientRecordId("ps:a"))
            assertTrue(dao.sourceUuidsOf("ps:a").isEmpty())
            assertNull(sessions.findByUuid("ps:a-src"))
            assertNull(baselines.find("ps:a"))
        }

    @Test
    fun findBySourceUuidsReturnsDistinctNightsAndChunksLargeLists() =
        runTest {
            dao.upsert(night("ps:a", startMs = 0, sourceUuids = listOf("u1", "u2")))
            dao.upsert(night("ps:b", startMs = 24 * HOUR_MS, sourceUuids = listOf("u3")))
            dao.upsert(night("ps:c", startMs = 48 * HOUR_MS, sourceUuids = listOf("u4")))

            assertEquals(listOf("ps:a"), dao.findBySourceUuids(listOf("u1", "u2")).map { it.clientRecordId })
            val many = (0 until 1_500).map { "missing-$it" } + "u3" + "u1"
            assertEquals(listOf("ps:a", "ps:b"), dao.findBySourceUuids(many).map { it.clientRecordId })
            assertTrue(dao.findBySourceUuids(emptyList()).isEmpty())
        }

    @Test
    fun findOverlappingUsesHalfOpenIntervalsAndStatuses() =
        runTest {
            dao.upsert(night("ps:a", startMs = 0, hours = 8))
            dao.upsert(night("ps:b", startMs = 10 * HOUR_MS, hours = 8, status = NightStatus.SUPERSEDED))

            val written = listOf(NightStatus.WRITTEN)
            assertEquals(listOf("ps:a"), dao.findOverlapping(7 * HOUR_MS, 20 * HOUR_MS, written).map { it.clientRecordId })
            assertTrue("touching end is not an overlap", dao.findOverlapping(8 * HOUR_MS, 9 * HOUR_MS, written).isEmpty())
            assertEquals(
                listOf("ps:a", "ps:b"),
                dao.findOverlapping(0, 30 * HOUR_MS, listOf(NightStatus.WRITTEN, NightStatus.SUPERSEDED)).map { it.clientRecordId },
            )
            assertEquals(listOf("ps:b"), dao.findStartingIn(9 * HOUR_MS, 11 * HOUR_MS).map { it.clientRecordId })
        }

    @Test
    fun markSupersededClearsOutputAndDropsBaselines() =
        runTest {
            dao.recordNight(night("ps:a", startMs = 0), emptyList())
            dao.recordNight(night("ps:b", startMs = 2 * HOUR_MS), emptyList())
            dao.recordNight(night("ps:keep", startMs = 48 * HOUR_MS), emptyList())

            val changed = dao.markSuperseded(listOf("ps:a", "ps:b", "ps:missing"), supersededBy = "ps:merged", now = 9_000L)

            assertEquals(2, changed)
            listOf("ps:a", "ps:b").forEach { id ->
                val stored = checkNotNull(dao.findByClientRecordId(id))
                assertEquals(NightStatus.SUPERSEDED, stored.status)
                assertEquals("superseded by ps:merged", stored.statusReason)
                assertNull(stored.hcRecordId)
                assertEquals(9_000L, stored.updatedAt)
                assertNull(baselines.find(id))
            }
            assertEquals(NightStatus.WRITTEN, dao.findByClientRecordId("ps:keep")?.status)
            assertNotNull(baselines.find("ps:keep"))
            assertEquals(listOf("ps:keep"), dao.clientRecordIdsWithOutput())
        }

    @Test
    fun markSourceDeletedAndMarkDeletedDifferInBaselineHandling() =
        runTest {
            dao.recordNight(night("ps:a", startMs = 0), emptyList())
            dao.recordNight(night("ps:b", startMs = 24 * HOUR_MS), emptyList())

            dao.markSourceDeleted(listOf("ps:a"), now = 2_000L)
            dao.markDeleted(listOf("ps:b"), reason = "deleted by user", now = 3_000L)

            val a = checkNotNull(dao.findByClientRecordId("ps:a"))
            assertEquals(NightStatus.SOURCE_DELETED, a.status)
            assertNull(a.hcRecordId)
            assertNull(baselines.find("ps:a"))
            val b = checkNotNull(dao.findByClientRecordId("ps:b"))
            assertEquals(NightStatus.DELETED, b.status)
            assertEquals("deleted by user", b.statusReason)
            assertNull(b.hcRecordId)
            assertNotNull("the nadir still describes the user", baselines.find("ps:b"))
            assertTrue(dao.clientRecordIdsWithOutput().isEmpty())
        }

    @Test
    fun updateStatusHandlesMoreIdsThanOneStatement() =
        runTest {
            val ids = (0 until 1_200).map { "ps:$it" }
            db.withTransaction { ids.forEachIndexed { i, id -> dao.upsert(night(id, startMs = i * 24 * HOUR_MS)) } }

            val changed = dao.updateStatus(ids, NightStatus.FAILED, "boom", clearOutput = false, now = 7L)

            assertEquals(1_200, changed)
            assertEquals(1_200, dao.countWithStatus(NightStatus.FAILED))
            assertEquals(1_200, dao.clientRecordIdsWithOutput().size)
        }

    @Test
    fun staleNightsAreWrittenNightsWithAnotherVersionOrHash() =
        runTest {
            dao.upsert(night("ps:current", startMs = 0, algoVersion = 2, paramsHash = "h"))
            dao.upsert(night("ps:old-algo", startMs = 24 * HOUR_MS, algoVersion = 1, paramsHash = "h"))
            dao.upsert(night("ps:old-hash", startMs = 48 * HOUR_MS, algoVersion = 2, paramsHash = "g"))
            dao.upsert(night("ps:superseded", startMs = 72 * HOUR_MS, algoVersion = 1, status = NightStatus.SUPERSEDED))

            assertEquals(listOf("ps:old-algo", "ps:old-hash"), dao.findStale(2, "h").map { it.clientRecordId })
            assertEquals(2, dao.observeStaleCount(2, "h").first())

            dao.upsert(night("ps:old-hash", startMs = 48 * HOUR_MS, algoVersion = 2, paramsHash = "h"))

            assertEquals(1, dao.observeStaleCount(2, "h").first())
        }

    @Test
    fun skippedNapsAreStaleWithAnotherHashOtherNightsWithoutOutputAreNot() =
        runTest {
            val skipped = "${NotEstimatedReason.NAP_SKIPPED}: nap of 60 min (napMode.outputPolicy = SKIP)"
            assertTrue(skipped.startsWith(SKIPPED_NAP_REASON_PREFIX))

            fun noOutput(
                id: String,
                day: Long,
                reason: String,
                hash: String = "g",
            ) = night(id, startMs = day * 24 * HOUR_MS, algoVersion = 2, paramsHash = hash, status = NightStatus.NOT_ESTIMATED)
                .copy(statusReason = reason)
            dao.upsert(noOutput("ps:nap-old", 0, skipped))
            dao.upsert(noOutput("ps:nap-current", 1, skipped, hash = "h"))
            dao.upsert(noOutput("ps:no-hr", 2, "${NotEstimatedReason.NO_HR_DATA}: no samples"))
            dao.upsert(noOutput("ps:look-alike", 3, "NAPXSKIPPED: not the code"))

            assertEquals(listOf("ps:nap-old"), dao.findStale(2, "h").map { it.clientRecordId })
            assertEquals(1, dao.observeStaleCount(2, "h").first())
            assertTrue(checkNotNull(dao.findByClientRecordId("ps:nap-old")).isStale(2, "h"))
            assertFalse(checkNotNull(dao.findByClientRecordId("ps:no-hr")).isStale(2, "h"))
        }

    @Test
    fun summariesAreNewestFirstAndFlowEmitsChanges() =
        runTest {
            dao.upsert(night("ps:old", startMs = 0))
            val recorder = FlowRecorder(this, dao.observeSummaries())
            assertEquals(listOf("ps:old"), recorder.next().map { it.clientRecordId })

            dao.upsert(night("ps:new", startMs = 24 * HOUR_MS))

            val updated = recorder.awaitMatching { it.size == 2 }
            recorder.cancel()
            assertEquals(listOf("ps:new", "ps:old"), updated.map { it.clientRecordId })
            assertEquals(NightStatus.WRITTEN, updated.first().status)
            assertEquals(60.0, updated.first().deepMin, 0.0)
            dao.markDeleted(listOf("ps:old"), reason = null, now = 2L)
            assertEquals(
                listOf("ps:new"),
                dao.observeSummariesWithStatus(listOf(NightStatus.WRITTEN)).first().map { it.clientRecordId },
            )
        }
}

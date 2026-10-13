package io.github.alien2003.pulsestages.data.local.db

import io.github.alien2003.pulsestages.data.local.testutil.DatabaseTest
import io.github.alien2003.pulsestages.data.local.testutil.FlowRecorder
import io.github.alien2003.pulsestages.data.local.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.local.testutil.night
import io.github.alien2003.pulsestages.data.local.testutil.session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceSessionDaoTest : DatabaseTest() {
    private val dao get() = db.sourceSessionDao()

    @Test
    fun upsertReplacesBySessionUuid() =
        runTest {
            dao.upsertAll(listOf(session("u1", 0, SourceOutcome.NOT_ESTIMATED)))

            dao.upsertAll(listOf(session("u1", 0, SourceOutcome.ESTIMATED, nightClientRecordId = "ps:a").copy(lastSeenAt = 5L)))

            val stored = checkNotNull(dao.findByUuid("u1"))
            assertEquals(SourceOutcome.ESTIMATED, stored.outcome)
            assertEquals("ps:a", stored.nightClientRecordId)
            assertEquals(5L, stored.lastSeenAt)
            assertEquals(1, dao.observeAll().first().size)
        }

    @Test
    fun queriesByUuidsAndNight() =
        runTest {
            dao.upsertAll(
                listOf(
                    session("u2", 3 * HOUR_MS, SourceOutcome.ESTIMATED, "ps:a"),
                    session("u1", 0, SourceOutcome.ESTIMATED, "ps:a"),
                    session("u3", 48 * HOUR_MS, SourceOutcome.NAP),
                ),
            )

            assertEquals(listOf("u1", "u2"), dao.findByNight("ps:a").map { it.uuid })
            assertEquals(listOf("u1", "u3"), dao.findByUuids(listOf("u3", "u1", "missing")).map { it.uuid })
            assertEquals(listOf("u3", "u2", "u1"), dao.observeAll().first().map { it.uuid })
            assertNull(dao.findByUuid("missing"))
        }

    @Test
    fun markDeletedChangesOnlyTheGivenSessions() =
        runTest {
            dao.upsertAll(
                listOf(session("u1", 0, SourceOutcome.ESTIMATED, "ps:a"), session("u2", HOUR_MS, SourceOutcome.ESTIMATED, "ps:a")),
            )

            val changed = dao.markDeleted(listOf("u1", "missing"), reason = "DeletionChange", now = 77L)

            assertEquals(1, changed)
            val u1 = checkNotNull(dao.findByUuid("u1"))
            assertEquals(SourceOutcome.DELETED, u1.outcome)
            assertEquals("DeletionChange", u1.reason)
            assertEquals(77L, u1.lastSeenAt)
            assertEquals(SourceOutcome.ESTIMATED, dao.findByUuid("u2")?.outcome)
        }

    @Test
    fun largeUuidListsAreChunked() =
        runTest {
            val uuids = (0 until 1_300).map { "u$it" }
            dao.upsertAll(uuids.mapIndexed { i, uuid -> session(uuid, i.toLong(), SourceOutcome.ESTIMATED) })

            assertEquals(uuids, dao.findByUuids(uuids + uuids.take(10)).map { it.uuid })
            assertEquals(1_300, dao.markDeleted(uuids, reason = null, now = 1L))
            assertEquals(
                setOf(SourceOutcome.DELETED),
                dao
                    .observeAll()
                    .first()
                    .map { it.outcome }
                    .toSet(),
            )
        }

    @Test
    fun homeCountersFollowTheDocumentedRules() =
        runTest {
            assertEquals(
                HomeCounters(found = 0, estimated = 0, skipped = 0, failed = 0, stale = 0),
                dao.observeHomeCounters(1, "h").first(),
            )
            dao.upsertAll(
                listOf(
                    session("e1", 0, SourceOutcome.ESTIMATED),
                    session("e2", 1, SourceOutcome.ESTIMATED),
                    session("nap", 2, SourceOutcome.NAP),
                    session("stages", 3, SourceOutcome.HAS_STAGES),
                    session("dup", 4, SourceOutcome.LOWER_PRIORITY_DUPLICATE),
                    session("ne", 5, SourceOutcome.NOT_ESTIMATED),
                    session("fail", 6, SourceOutcome.FAILED),
                    session("deleted", 7, SourceOutcome.DELETED),
                    session("own", 8, SourceOutcome.OWN),
                ),
            )
            val nights = db.estimatedNightDao()
            nights.upsert(night("ps:fresh", startMs = 0, algoVersion = 1, paramsHash = "h"))
            nights.upsert(night("ps:stale", startMs = 24 * HOUR_MS, algoVersion = 1, paramsHash = "old"))

            assertEquals(
                HomeCounters(found = 8, estimated = 2, skipped = 5, failed = 1, stale = 1),
                dao.observeHomeCounters(1, "h").first(),
            )
        }

    @Test
    fun homeCountersFlowReactsToBothTables() =
        runTest {
            val recorder = FlowRecorder(this, dao.observeHomeCounters(1, "h"))
            assertEquals(0, recorder.next().found)

            dao.upsertAll(listOf(session("e1", 0, SourceOutcome.ESTIMATED)))
            recorder.awaitMatching { it.found == 1 }
            db.estimatedNightDao().upsert(night("ps:stale", startMs = 0, paramsHash = "old"))

            val last = recorder.awaitMatching { it.stale == 1 }
            recorder.cancel()
            assertEquals(HomeCounters(found = 1, estimated = 1, skipped = 0, failed = 0, stale = 1), last)
        }
}

class NightBaselineDaoTest : DatabaseTest() {
    private val dao get() = db.nightBaselineDao()

    private suspend fun nightEndingAt(
        id: String,
        endMs: Long,
        nadir: Double,
    ) = dao.upsert(NightBaselineEntity(id, endMs, nadir))

    @Test
    fun noBaselineWithFewerThanThreePreviousNights() =
        runTest {
            nightEndingAt("n1", 10, 50.0)
            nightEndingAt("n2", 20, 52.0)

            assertNull(dao.baselineBefore(nightStartMs = 100, nights = 14))
        }

    @Test
    fun medianOfOddAndEvenCounts() =
        runTest {
            nightEndingAt("n1", 10, 50.0)
            nightEndingAt("n2", 20, 60.0)
            nightEndingAt("n3", 30, 52.0)
            assertEquals(52.0, checkNotNull(dao.baselineBefore(100, 14)), 0.0)

            nightEndingAt("n4", 40, 54.0)
            assertEquals(53.0, checkNotNull(dao.baselineBefore(100, 14)), 0.0)
        }

    @Test
    fun onlyNightsEndingStrictlyBeforeTheStartCount() =
        runTest {
            nightEndingAt("n1", 10, 50.0)
            nightEndingAt("n2", 20, 51.0)
            nightEndingAt("n3", 30, 52.0)
            nightEndingAt("same-end", 100, 90.0)
            nightEndingAt("later", 200, 95.0)

            assertEquals(51.0, checkNotNull(dao.baselineBefore(nightStartMs = 100, nights = 14)), 0.0)
            assertNull("only two nights end before 25", dao.baselineBefore(nightStartMs = 25, nights = 14))
        }

    @Test
    fun usesOnlyTheLatestNNights() =
        runTest {
            // 10 old nights with nadir 40, then 3 recent nights with nadir 60.
            (1..10).forEach { nightEndingAt("old$it", it.toLong(), 40.0) }
            (11..13).forEach { nightEndingAt("new$it", it.toLong(), 60.0) }

            assertEquals(60.0, checkNotNull(dao.baselineBefore(nightStartMs = 1_000, nights = 3)), 0.0)
            assertEquals(40.0, checkNotNull(dao.baselineBefore(nightStartMs = 1_000, nights = 13)), 0.0)
            assertEquals(listOf(60.0, 60.0, 60.0, 40.0), dao.recentNadirs(beforeMs = 1_000, limit = 4))
            assertNull(dao.baselineBefore(nightStartMs = 1_000, nights = 2))
            assertNull(dao.baselineBefore(nightStartMs = 1_000, nights = 0))
        }

    @Test
    fun upsertReplacesAndDeleteRemoves() =
        runTest {
            nightEndingAt("n1", 10, 50.0)
            nightEndingAt("n1", 15, 55.0)
            assertEquals(NightBaselineEntity("n1", 15, 55.0), dao.find("n1"))

            dao.delete("n1")

            assertNull(dao.find("n1"))
        }
}

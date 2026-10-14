package io.github.alien2003.pulsestages.data.local.db

import io.github.alien2003.pulsestages.algo.model.SessionMode
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
            nights.upsert(night("ps:nap", startMs = 48 * HOUR_MS, hours = 1, paramsHash = "h").copy(mode = SessionMode.NAP))
            nights.upsert(
                night("ps:not-estimated-nap", startMs = 72 * HOUR_MS, hours = 1, status = NightStatus.NOT_ESTIMATED)
                    .copy(mode = SessionMode.NAP, statusReason = "NO_HR_DATA: no samples"),
            )
            // A nap skipped by the nap policy with other settings is stale: it may be written with the current ones.
            nights.upsert(
                night("ps:skipped-nap", startMs = 96 * HOUR_MS, hours = 1, status = NightStatus.NOT_ESTIMATED, paramsHash = "old")
                    .copy(mode = SessionMode.NAP, statusReason = "$SKIPPED_NAP_REASON_PREFIX nap of 60 min"),
            )

            assertEquals(
                HomeCounters(found = 8, estimated = 2, skipped = 5, failed = 1, stale = 2, nights = 2, naps = 1),
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
            assertEquals(HomeCounters(found = 1, estimated = 1, skipped = 0, failed = 0, stale = 1, nights = 1, naps = 0), last)
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

            assertNull(dao.baselineBefore(nightStartMs = 100, nights = 14, minDurationMs = 0))
        }

    @Test
    fun medianOfOddAndEvenCounts() =
        runTest {
            nightEndingAt("n1", 10, 50.0)
            nightEndingAt("n2", 20, 60.0)
            nightEndingAt("n3", 30, 52.0)
            assertEquals(52.0, checkNotNull(dao.baselineBefore(100, 14, 0)), 0.0)

            nightEndingAt("n4", 40, 54.0)
            assertEquals(53.0, checkNotNull(dao.baselineBefore(100, 14, 0)), 0.0)
        }

    @Test
    fun onlyNightsEndingStrictlyBeforeTheStartCount() =
        runTest {
            nightEndingAt("n1", 10, 50.0)
            nightEndingAt("n2", 20, 51.0)
            nightEndingAt("n3", 30, 52.0)
            nightEndingAt("same-end", 100, 90.0)
            nightEndingAt("later", 200, 95.0)

            assertEquals(51.0, checkNotNull(dao.baselineBefore(nightStartMs = 100, nights = 14, minDurationMs = 0)), 0.0)
            assertNull("only two nights end before 25", dao.baselineBefore(nightStartMs = 25, nights = 14, minDurationMs = 0))
        }

    @Test
    fun usesOnlyTheLatestNNights() =
        runTest {
            // 10 old nights with nadir 40, then 3 recent nights with nadir 60.
            (1..10).forEach { nightEndingAt("old$it", it.toLong(), 40.0) }
            (11..13).forEach { nightEndingAt("new$it", it.toLong(), 60.0) }

            assertEquals(60.0, checkNotNull(dao.baselineBefore(nightStartMs = 1_000, nights = 3, minDurationMs = 0)), 0.0)
            assertEquals(40.0, checkNotNull(dao.baselineBefore(nightStartMs = 1_000, nights = 13, minDurationMs = 0)), 0.0)
            assertEquals(listOf(60.0, 60.0, 60.0, 40.0), dao.recentNadirs(beforeMs = 1_000, limit = 4, minDurationMs = 0))
            assertNull(dao.baselineBefore(nightStartMs = 1_000, nights = 2, minDurationMs = 0))
            assertNull(dao.baselineBefore(nightStartMs = 1_000, nights = 0, minDurationMs = 0))
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

class NapBaselineDaoTest : DatabaseTest() {
    private val dao get() = db.nightBaselineDao()
    private val nights get() = db.estimatedNightDao()
    private val minute = 60_000L
    private val threeHours = 180 * minute

    /** Records a WRITTEN session of [minutes] starting at [startMs] with [nadir] the way the pipeline does. */
    private suspend fun written(
        id: String,
        startMs: Long,
        minutes: Long,
        nadir: Double,
    ) {
        val row = night(id, startMs, nadirBpm = nadir).let { it.copy(sessionEndMs = it.sessionStartMs + minutes * minute) }
        nights.recordNight(row, emptyList())
    }

    @Test
    fun napsGetARowButOnlyLongEnoughNightsFormTheBaseline() =
        runTest {
            val day = 24 * HOUR_MS
            (0 until 3).forEach { written("night$it", it * day, minutes = 480, nadir = 50.0 + it) }
            // Daytime naps after the nights: higher nadirs, later ends (the latest rows).
            (0 until 3).forEach { written("nap$it", 3 * day + it * 4 * HOUR_MS, minutes = 45, nadir = 70.0) }
            val nextNight = 5 * day

            assertEquals("naps are stored", NightBaselineEntity("nap0", 3 * day + 45 * minute, 70.0), dao.find("nap0"))
            assertEquals(listOf(52.0, 51.0, 50.0), dao.recentNadirs(nextNight, 14, threeHours))
            assertEquals(51.0, checkNotNull(dao.baselineBefore(nextNight, 14, threeHours)), 0.0)
            // The latest 3 rows would all be naps: the limit applies after the filter.
            assertEquals(51.0, checkNotNull(dao.baselineBefore(nextNight, 3, threeHours)), 0.0)
            // 0 restores the old rule: every session counts.
            assertEquals(70.0, checkNotNull(dao.baselineBefore(nextNight, 3, 0)), 0.0)
            assertEquals(61.0, checkNotNull(dao.baselineBefore(nextNight, 14, 0)), 0.0)
        }

    @Test
    fun theThresholdIsInclusiveAndUsesTheSessionOfTheNightRow() =
        runTest {
            written("exact", 0, minutes = 180, nadir = 50.0)
            written("short", HOUR_MS * 24, minutes = 179, nadir = 60.0)

            assertEquals(listOf(50.0), dao.recentNadirs(10 * 24 * HOUR_MS, 14, threeHours))
            assertEquals(listOf(60.0, 50.0), dao.recentNadirs(10 * 24 * HOUR_MS, 14, threeHours - minute))
        }

    @Test
    fun aRowWithoutANightCountsOnlyWithoutAMinimum() =
        runTest {
            dao.upsert(NightBaselineEntity("orphan", 10, 50.0))

            assertEquals(listOf(50.0), dao.recentNadirs(100, 14, 0))
            assertEquals(emptyList<Double>(), dao.recentNadirs(100, 14, threeHours))
        }
}

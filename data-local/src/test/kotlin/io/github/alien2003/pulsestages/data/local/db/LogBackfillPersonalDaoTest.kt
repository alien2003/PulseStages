package io.github.alien2003.pulsestages.data.local.db

import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.testutil.DatabaseTest
import io.github.alien2003.pulsestages.data.local.testutil.FlowRecorder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogDaoTest : DatabaseTest() {
    private val dao get() = db.logDao()

    private fun entry(
        i: Int,
        level: LogLevel = LogLevel.INFO,
        nightKey: String? = null,
    ) = LogEntryEntity(timestampMs = i.toLong(), level = level, tag = "T", message = "m$i", nightKey = nightKey)

    @Test
    fun trimKeepsTheNewestRows() =
        runTest {
            dao.insertAll((1..25).map { entry(it) })

            val deleted = dao.trimToNewest(keep = 10)

            assertEquals(15, deleted)
            assertEquals((16..25).map { "m$it" }, dao.getAll().map { it.message })
            assertEquals(0, dao.trimToNewest(keep = 10))
            assertEquals(0, dao.trimToNewest(keep = 100))
            assertEquals(10, dao.count())
        }

    @Test
    fun trimToZeroDeletesEverything() =
        runTest {
            dao.insertAll((1..3).map { entry(it) })

            dao.trimToNewest(keep = 0)

            assertEquals(0, dao.count())
        }

    @Test
    fun recentEntriesAreNewestFirstAndFilterableByLevel() =
        runTest {
            dao.insertAll(
                listOf(
                    entry(1, LogLevel.DEBUG),
                    entry(2, LogLevel.INFO),
                    entry(3, LogLevel.WARN),
                    entry(4, LogLevel.ERROR),
                    entry(5, LogLevel.INFO),
                ),
            )

            assertEquals(listOf("m5", "m4", "m3"), dao.observeRecent(limit = 3).first().map { it.message })
            assertEquals(
                listOf("m4", "m3"),
                dao.observeRecentWithLevels(listOf(LogLevel.WARN, LogLevel.ERROR), limit = 100).first().map { it.message },
            )
            assertEquals(LogLevel.DEBUG, dao.getAll().first().level)
        }

    @Test
    fun entriesForOneNightAreOldestFirstAndObservable() =
        runTest {
            dao.insertAll(listOf(entry(1, nightKey = "ps:a"), entry(2, nightKey = "ps:b"), entry(3, nightKey = "ps:a")))
            val recorder = FlowRecorder(this, dao.observeForNight("ps:a"))
            assertEquals(listOf("m1", "m3"), recorder.next().map { it.message })

            dao.insertAll(listOf(entry(4, nightKey = "ps:a")))

            val updated = recorder.awaitMatching { it.size == 3 }
            recorder.cancel()
            assertEquals(listOf("m1", "m3", "m4"), updated.map { it.message })
        }

    @Test
    fun clearRemovesEverything() =
        runTest {
            dao.insertAll((1..5).map { entry(it) })

            dao.clear()

            assertEquals(0, dao.count())
        }
}

class BackfillStateDaoTest : DatabaseTest() {
    private val dao get() = db.backfillStateDao()

    private val running =
        BackfillStateEntity(
            status = BackfillStatus.RUNNING,
            rangeStartMs = 0,
            rangeEndMs = 70 * 86_400_000L,
            cursorMs = 0,
            windowsTotal = 10,
            startedAt = 5,
            updatedAt = 5,
        )

    @Test
    fun noStateUntilSaved() =
        runTest {
            assertNull(dao.get())
            assertNull(dao.observe().first())
        }

    @Test
    fun saveUpsertsTheSingleRowAndIgnoresOtherIds() =
        runTest {
            dao.save(running)
            dao.save(
                running.copy(
                    id = 42,
                    cursorMs = 7 * 86_400_000L,
                    windowsDone = 1,
                    nightsFound = 7,
                    nightsEstimated = 6,
                    nightsSkipped = 1,
                    updatedAt = 9,
                ),
            )

            val stored = checkNotNull(dao.get())
            assertEquals(BackfillStateEntity.SINGLETON_ID, stored.id)
            assertEquals(7 * 86_400_000L, stored.cursorMs)
            assertEquals(1, stored.windowsDone)
            assertEquals(7, stored.nightsFound)
            assertEquals(BackfillStateEntity.DEFAULT_WINDOW_DAYS, stored.windowDays)
            assertEquals(
                1,
                db.query("SELECT COUNT(*) FROM backfill_state", null).use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
        }

    @Test
    fun observeEmitsProgressAndClearResets() =
        runTest {
            val recorder = FlowRecorder(this, dao.observe())
            assertNull(recorder.next())

            dao.save(running)
            recorder.awaitMatching { it?.status == BackfillStatus.RUNNING }
            dao.save(running.copy(status = BackfillStatus.WAITING_RETRY, lastError = "quota", historyLimited = true))

            val last = checkNotNull(recorder.awaitMatching { it?.status == BackfillStatus.WAITING_RETRY })
            recorder.cancel()
            assertEquals(BackfillStatus.WAITING_RETRY, last.status)
            assertEquals("quota", last.lastError)
            assertTrue(last.historyLimited)

            dao.clear()
            assertNull(dao.get())
        }
}

class PersonalModelDaoTest : DatabaseTest() {
    private val dao get() = db.personalModelDao()

    private fun model(
        createdAt: Long,
        active: Boolean,
        kind: PersonalModelKind = PersonalModelKind.EM,
    ) = PersonalModelEntity(createdAt = createdAt, kind = kind, json = "{}", fingerprint = "fp$createdAt", active = active, nightsUsed = 30)

    @Test
    fun insertingAnActiveModelDeactivatesTheOthers() =
        runTest {
            val first = dao.insert(model(1, active = true))
            val second = dao.insert(model(2, active = true, kind = PersonalModelKind.CALIBRATED))

            assertEquals(second, dao.getActive()?.id)
            assertFalse(checkNotNull(dao.findById(first)).active)
            assertEquals(1, dao.observeAll().first().count { it.active })
        }

    @Test
    fun insertingAnInactiveModelKeepsTheActiveOne() =
        runTest {
            val active = dao.insert(model(1, active = true))
            dao.insert(model(2, active = false))

            assertEquals(active, dao.getActive()?.id)
        }

    @Test
    fun activateSwitchesTheActiveModelAndRejectsUnknownIds() =
        runTest {
            val first = dao.insert(model(1, active = true))
            val second = dao.insert(model(2, active = false))

            assertTrue(dao.activate(second))
            assertEquals(second, dao.getActive()?.id)
            assertEquals(second, dao.observeActive().first()?.id)
            assertFalse(checkNotNull(dao.findById(first)).active)

            assertFalse(dao.activate(999))
            assertEquals(second, dao.getActive()?.id)
        }

    @Test
    fun deactivateAllAndDelete() =
        runTest {
            val id = dao.insert(model(1, active = true).copy(kappaBefore = 0.21, kappaAfter = 0.27))
            assertEquals(0.27, checkNotNull(dao.findById(id)?.kappaAfter), 0.0)

            dao.deactivateAll()
            assertNull(dao.getActive())

            dao.delete(id)
            assertTrue(dao.observeAll().first().isEmpty())
        }

    @Test
    fun allModelsAreNewestFirst() =
        runTest {
            dao.insert(model(1, active = false))
            dao.insert(model(3, active = false))
            dao.insert(model(2, active = false))

            assertEquals(listOf(3L, 2L, 1L), dao.observeAll().first().map { it.createdAt })
        }
}

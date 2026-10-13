package io.github.alien2003.pulsestages.data.local.log

import android.util.Log
import io.github.alien2003.pulsestages.data.local.db.LogEntryEntity
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.testutil.DatabaseTest
import io.github.alien2003.pulsestages.data.local.testutil.MutableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowLog

class RoomAppLoggerTest : DatabaseTest() {
    private val clock = MutableClock(1_700_000_000_000L)
    private val level = MutableStateFlow(LogLevel.INFO)

    private fun TestScope.logger(
        sink: LogcatSink = AndroidLogcatSink,
        maxRows: Int = LogEntryEntity.MAX_ROWS,
    ) = RoomAppLogger(db.logDao(), level, backgroundScope, clock, sink, maxRows)

    private suspend fun stored() = db.logDao().getAll()

    @Test
    fun storesLinesAtOrAboveTheConfiguredLevel() =
        runTest {
            val logger = logger()

            logger.d("Pipeline", "debug line")
            logger.i("Pipeline", "info line")
            logger.w("Pipeline", "warn line")
            logger.e("Pipeline", "error line")
            logger.flush()

            assertEquals(listOf("info line", "warn line", "error line"), stored().map { it.message })
            assertEquals(listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR), stored().map { it.level })
        }

    @Test
    fun levelChangesApplyImmediately() =
        runTest {
            val logger = logger()

            level.value = LogLevel.ERROR
            logger.w("T", "dropped warn")
            logger.e("T", "kept error")
            level.value = LogLevel.DEBUG
            logger.d("T", "kept debug")
            logger.flush()

            assertEquals(listOf("kept error", "kept debug"), stored().map { it.message })
        }

    @Test
    fun storesTimestampTagNightKeyAndStackTrace() =
        runTest {
            val logger = logger()

            logger.e("Pipeline", "estimation failed", nightKey = "ps:abc", throwable = IllegalStateException("boom"))
            clock.nowMs += 5
            logger.i("Sync", "done")
            logger.flush()

            val (failure, done) = stored()
            assertEquals(1_700_000_000_000L, failure.timestampMs)
            assertEquals("Pipeline", failure.tag)
            assertEquals("ps:abc", failure.nightKey)
            assertTrue(failure.message.startsWith("estimation failed\njava.lang.IllegalStateException: boom"))
            assertEquals(1_700_000_000_005L, done.timestampMs)
            assertEquals(null, done.nightKey)
        }

    @Test
    fun mirrorsToLogcatWithThePulseStagesTag() =
        runTest {
            ShadowLog.clear()
            val logger = logger()

            logger.d("Pipeline", "not mirrored below the level")
            logger.i("Pipeline", "night written", nightKey = "ps:abc")
            logger.e("Sync", "quota exceeded")
            logger.flush()

            val lines = ShadowLog.getLogsForTag("PulseStages")
            assertEquals(listOf("[Pipeline] [ps:abc] night written", "[Sync] quota exceeded"), lines.map { it.msg })
            assertEquals(listOf(Log.INFO, Log.ERROR), lines.map { it.type })
        }

    @Test
    fun keepsOrderAcrossBatches() =
        runTest {
            val logger = logger(sink = { _, _ -> })

            repeat(1_000) { logger.i("T", "line $it") }
            logger.flush()

            val entries = stored()
            assertEquals((0 until 1_000).map { "line $it" }, entries.map { it.message })
            assertEquals(entries.map { it.id }.sorted(), entries.map { it.id })
        }

    @Test
    fun trimsTheTableToTheNewestTenThousandRows() =
        runTest {
            val logger = logger(sink = { _, _ -> })

            repeat(LogEntryEntity.MAX_ROWS + 50) { logger.i("T", "line $it") }
            logger.flush()

            assertEquals(10_000, db.logDao().count())
            val entries = stored()
            assertEquals("line 50", entries.first().message)
            assertEquals("line ${LogEntryEntity.MAX_ROWS + 49}", entries.last().message)
        }

    @Test
    fun aThrowingLogcatSinkNeverReachesTheCaller() =
        runTest {
            val logger = logger(sink = { _, _ -> throw IllegalStateException("logcat unavailable") })

            logger.e("T", "still stored")
            logger.flush()

            assertEquals(listOf("still stored"), stored().map { it.message })
        }

    @Test
    fun storageFailuresNeverReachTheCaller() =
        runTest {
            ShadowLog.clear()
            val logger = logger()
            db.close()

            logger.e("T", "cannot be stored")
            logger.flush()
            logger.i("T", "logger still works")
            logger.flush()

            val messages = ShadowLog.getLogsForTag("PulseStages").map { it.msg }
            assertTrue(messages.contains("[T] logger still works"))
            assertTrue(messages.any { it.startsWith("[AppLogger] Could not store 1 log entries") })
        }

    @Test
    fun cancelledScopeTurnsStorageOffWithoutThrowing() =
        runTest {
            val scope = CoroutineScope(Job())
            val logger = RoomAppLogger(db.logDao(), level, scope, clock, { _, _ -> })
            logger.i("T", "stored before cancel")
            logger.flush()

            scope.cancel()
            logger.i("T", "after cancel")
            logger.flush()

            assertEquals(listOf("stored before cancel"), stored().map { it.message })
        }

    @Test
    fun longMessagesAreTruncated() =
        runTest {
            val logger = logger(sink = { _, _ -> })

            logger.i("T", "x".repeat(20_000))
            logger.flush()

            val message = stored().single().message
            assertEquals(8_000, message.length)
            assertTrue(message.endsWith("…"))
        }
}

package io.github.alien2003.pulsestages.data.local.sync

import io.github.alien2003.pulsestages.data.local.testutil.MutableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SyncStateStoreTest {
    @TempDir
    lateinit var dir: File

    private val clock = MutableClock(1_000L)

    private val file: File get() = File(dir, "${SyncStateStore.FILE_NAME}.preferences_pb")

    private suspend fun TestScope.withStore(block: suspend (SyncStateStore) -> Unit) {
        val storeJob = Job(coroutineContext[Job])
        val dataStore = SyncStateStore.createDataStore(CoroutineScope(coroutineContext + storeJob)) { file }
        try {
            block(SyncStateStore(dataStore, clock))
        } finally {
            storeJob.cancelAndJoin()
        }
    }

    @Test
    fun `no token is stored initially`() =
        runTest {
            withStore { store ->
                assertNull(store.tokenFor(listOf("com.example.a")))
                assertEquals(SyncState(), store.state.first())
            }
        }

    @Test
    fun `a token is returned only for exactly the package set it was created for`() =
        runTest {
            withStore { store ->
                store.saveNewToken(listOf("com.example.b", "com.example.a"), "t1")

                val expected = ChangesTokenState("t1", listOf("com.example.a", "com.example.b"), createdAtMs = 1_000L, updatedAtMs = 1_000L)
                assertEquals(expected, store.tokenFor(listOf("com.example.a", "com.example.b")))
                assertEquals(expected, store.tokenFor(setOf("com.example.b", "com.example.a")), "order does not matter")
                assertEquals(
                    expected,
                    store.tokenFor(listOf("com.example.a", "com.example.b", "com.example.a")),
                    "duplicates do not matter",
                )
                assertNull(store.tokenFor(listOf("com.example.a")), "subset")
                assertNull(store.tokenFor(listOf("com.example.a", "com.example.b", "com.example.c")), "superset")
                assertNull(store.tokenFor(emptyList()), "empty set")
            }
        }

    @Test
    fun `a new package set replaces the old token`() =
        runTest {
            withStore { store ->
                store.saveNewToken(listOf("com.example.a"), "t1")
                clock.nowMs = 2_000L

                store.saveNewToken(listOf("com.example.a", "com.example.c"), "t2")

                assertNull(store.tokenFor(listOf("com.example.a")))
                assertEquals(
                    ChangesTokenState("t2", listOf("com.example.a", "com.example.c"), 2_000L, 2_000L),
                    store.tokenFor(listOf("com.example.c", "com.example.a")),
                )
            }
        }

    @Test
    fun `advancing keeps the creation time for the same set`() =
        runTest {
            withStore { store ->
                store.saveNewToken(listOf("com.example.a"), "t1")
                clock.nowMs = 5_000L

                store.advanceToken(listOf("com.example.a"), "t2")

                assertEquals(ChangesTokenState("t2", listOf("com.example.a"), 1_000L, 5_000L), store.tokenFor(listOf("com.example.a")))
            }
        }

    @Test
    fun `advancing for another set starts a new token`() =
        runTest {
            withStore { store ->
                store.saveNewToken(listOf("com.example.a"), "t1")
                clock.nowMs = 7_000L

                store.advanceToken(listOf("com.example.b"), "t9")

                assertNull(store.tokenFor(listOf("com.example.a")))
                assertEquals(ChangesTokenState("t9", listOf("com.example.b"), 7_000L, 7_000L), store.tokenFor(listOf("com.example.b")))
            }
        }

    @Test
    fun `clearToken forgets the token and keeps the run state`() =
        runTest {
            withStore { store ->
                store.markRunStarted()
                store.saveNewToken(listOf("com.example.a"), "t1")

                store.clearToken()

                assertNull(store.tokenFor(listOf("com.example.a")))
                val state = store.state.first()
                assertNull(state.changesToken)
                assertEquals(1_000L, state.lastRunStartedAtMs)
            }
        }

    @Test
    fun `run bookkeeping and EM night count persist across instances`() =
        runTest {
            withStore { store ->
                store.markRunStarted()
                clock.nowMs = 4_000L
                store.markRunFinished(SyncOutcome.QUOTA_EXCEEDED, "rate limited")
                store.setLastEmAdaptationNightCount(30)
                store.saveNewToken(listOf("com.example.a"), "t1")
            }
            withStore { store ->
                assertEquals(
                    SyncState(
                        changesToken = ChangesTokenState("t1", listOf("com.example.a"), 4_000L, 4_000L),
                        lastRunStartedAtMs = 1_000L,
                        lastRunFinishedAtMs = 4_000L,
                        lastRunOutcome = SyncOutcome.QUOTA_EXCEEDED,
                        lastRunDetail = "rate limited",
                        lastEmAdaptationNightCount = 30,
                    ),
                    store.state.first(),
                )

                clock.nowMs = 9_000L
                store.markRunFinished(SyncOutcome.SUCCESS)

                val state = store.state.first()
                assertEquals(SyncOutcome.SUCCESS, state.lastRunOutcome)
                assertNull(state.lastRunDetail, "a finish without detail clears the old detail")
                assertEquals(9_000L, state.lastRunFinishedAtMs)
            }
        }

    @Test
    fun `a pending follow-up to Sleep as Android persists across instances until it is cleared`() =
        runTest {
            withStore { store ->
                assertNull(store.state.first().sleepAsAndroidFollowUpDueAtMs, "none pending initially")
                store.setSleepAsAndroidFollowUpDue(601_000L)
                store.setSleepAsAndroidFollowUpDue(605_000L)
                store.saveNewToken(listOf("com.example.a"), "t1")
            }
            withStore { store ->
                assertEquals(605_000L, store.state.first().sleepAsAndroidFollowUpDueAtMs, "the later request replaced the first")

                store.clearSleepAsAndroidFollowUp()

                val state = store.state.first()
                assertNull(state.sleepAsAndroidFollowUpDueAtMs)
                assertEquals("t1", state.changesToken?.token, "nothing else is touched")
                store.clearSleepAsAndroidFollowUp()
                assertNull(store.state.first().sleepAsAndroidFollowUpDueAtMs, "clearing nothing is harmless")
            }
        }

    @Test
    fun `the time of the last late-data pass persists across instances and the next pass replaces it`() =
        runTest {
            withStore { store ->
                assertNull(store.state.first().lateDataPassAtMs, "no pass yet")
                store.markLateDataPass(2_000L)
                store.setSleepAsAndroidFollowUpDue(601_000L)
            }
            withStore { store ->
                assertEquals(2_000L, store.state.first().lateDataPassAtMs)

                store.markLateDataPass(3_302_000L)

                val state = store.state.first()
                assertEquals(3_302_000L, state.lateDataPassAtMs)
                assertEquals(601_000L, state.sleepAsAndroidFollowUpDueAtMs, "nothing else is touched")
                assertNull(state.lastRunStartedAtMs)
            }
        }

    @Test
    fun `corrupted file starts from an empty state`() =
        runTest {
            file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x04))

            withStore { store ->
                assertEquals(SyncState(), store.state.first())
                store.saveNewToken(listOf("com.example.a"), "t1")
                assertEquals("t1", store.tokenFor(listOf("com.example.a"))?.token)
            }
        }
}

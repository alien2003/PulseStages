package io.github.alien2003.pulsestages.data.local.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.alien2003.pulsestages.data.local.testutil.MutableClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SyncStateStoreTest {
    @TempDir
    lateinit var dir: File

    private val clock = MutableClock(1_000L)

    private val file: File get() = File(dir, "${SyncStateStore.FILE_NAME}.preferences_pb")

    /** Opens the file without the store, e.g. to write it as an earlier version of the app left it. */
    private suspend fun TestScope.withDataStore(block: suspend (DataStore<Preferences>) -> Unit) {
        val storeJob = Job(coroutineContext[Job])
        val dataStore = SyncStateStore.createDataStore(CoroutineScope(coroutineContext + storeJob)) { file }
        try {
            block(dataStore)
        } finally {
            storeJob.cancelAndJoin()
        }
    }

    private suspend fun TestScope.withStore(block: suspend (SyncStateStore) -> Unit) {
        withDataStore { block(SyncStateStore(it, clock)) }
    }

    /** Names of the keys stored in the file. */
    private suspend fun TestScope.storedKeys(): Set<String> {
        var names = emptySet<String>()
        withDataStore { dataStore ->
            names =
                dataStore.data
                    .first()
                    .asMap()
                    .keys
                    .mapTo(HashSet()) { it.name }
        }
        return names
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
    fun `the time of the last late-data pass persists across instances and the next pass replaces it`() =
        runTest {
            withStore { store ->
                assertNull(store.state.first().lateDataPassAtMs, "no pass yet")
                store.markLateDataPass(2_000L)
                store.updateSleepAsAndroidRequest { it.copy(dueAtMs = 601_000L) }
            }
            withStore { store ->
                assertEquals(2_000L, store.state.first().lateDataPassAtMs)

                store.markLateDataPass(3_302_000L)

                val state = store.state.first()
                assertEquals(3_302_000L, state.lateDataPassAtMs)
                assertEquals(601_000L, state.sleepAsAndroidRequest.dueAtMs, "nothing else is touched")
                assertNull(state.lastRunStartedAtMs)
            }
        }

    @Test
    fun `the Sleep as Android request persists across instances and a cleared one leaves no key behind`() =
        runTest {
            val request =
                SleepAsAndroidRequestState(
                    dueAtMs = 601_000L,
                    followUp = true,
                    owedSinceMs = 1_000L,
                    settledKey = "a:1:2:3;b:4:5:6",
                    settledSinceMs = 2_000L,
                    lastSentAtMs = 500L,
                    sentKey = "a:1:2:0",
                )
            withStore { store ->
                assertEquals(SleepAsAndroidRequestState(), store.state.first().sleepAsAndroidRequest, "nothing owed initially")
                store.updateSleepAsAndroidRequest { request }
                store.saveNewToken(listOf("com.example.a"), "t1")
            }
            withStore { store ->
                assertEquals(request, store.state.first().sleepAsAndroidRequest)

                store.updateSleepAsAndroidRequest { SleepAsAndroidRequestState() }

                val state = store.state.first()
                assertEquals(SleepAsAndroidRequestState(), state.sleepAsAndroidRequest)
                assertEquals("t1", state.changesToken?.token, "nothing else is touched")
            }
            assertEquals(
                setOf("changes_token", "changes_token_packages", "changes_token_created_at", "changes_token_updated_at"),
                storedKeys(),
            )
        }

    @Test
    fun `an update transforms the stored request in one edit, so concurrent updates are not lost`() =
        runTest {
            withStore { store ->
                store.updateSleepAsAndroidRequest { it.copy(dueAtMs = 1_000L, followUp = true, owedSinceMs = 1_000L) }
                store.updateSleepAsAndroidRequest { it.copy(settledKey = "a:1:2:3", settledSinceMs = 2_000L) }
                assertEquals(
                    SleepAsAndroidRequestState(
                        1_000L,
                        followUp = true,
                        owedSinceMs = 1_000L,
                        settledKey = "a:1:2:3",
                        settledSinceMs = 2_000L,
                    ),
                    store.state.first().sleepAsAndroidRequest,
                    "the second update started from the first",
                )

                val increment: (SleepAsAndroidRequestState) -> SleepAsAndroidRequestState = {
                    it.copy(lastSentAtMs = (it.lastSentAtMs ?: 0L) + 1)
                }
                coroutineScope { repeat(20) { launch { store.updateSleepAsAndroidRequest(transform = increment) } } }

                val request = store.state.first().sleepAsAndroidRequest
                assertEquals(20L, request.lastSentAtMs)
            }
        }

    @Test
    fun `a stored follow-up without a due time is read as nothing owed`() =
        runTest {
            withStore { store ->
                store.updateSleepAsAndroidRequest { SleepAsAndroidRequestState(followUp = true, lastSentAtMs = 500L) }

                assertEquals(SleepAsAndroidRequestState(lastSentAtMs = 500L), store.state.first().sleepAsAndroidRequest)
            }
        }

    @Test
    fun `a late-data pass request keeps the later of the stored and the requested time`() =
        runTest {
            withStore { store ->
                assertNull(store.state.first().lateDataPassRequestedAtMs, "none requested yet")
                store.updateSleepAsAndroidRequest(requestLateDataPassAtMs = 5_000L) { it.copy(settledSinceMs = 5_000L) }
                store.updateSleepAsAndroidRequest(requestLateDataPassAtMs = 3_000L) { it }
                assertEquals(5_000L, store.state.first().lateDataPassRequestedAtMs, "an earlier time does not replace a later one")
                store.updateSleepAsAndroidRequest { it.copy(dueAtMs = 9_000L) }
                assertEquals(5_000L, store.state.first().lateDataPassRequestedAtMs, "an update without a request keeps it")
                store.markLateDataPass(6_000L)
                assertEquals(5_000L, store.state.first().lateDataPassRequestedAtMs, "a completed pass clears nothing")

                store.updateSleepAsAndroidRequest(requestLateDataPassAtMs = 8_000L) { it }
            }
            withStore { store ->
                val state = store.state.first()
                assertEquals(8_000L, state.lateDataPassRequestedAtMs)
                assertEquals(6_000L, state.lateDataPassAtMs)
                assertEquals(SleepAsAndroidRequestState(dueAtMs = 9_000L, settledSinceMs = 5_000L), state.sleepAsAndroidRequest)
            }
        }

    @Test
    fun `a follow-up left pending by an earlier release is read as an owed request and the next update removes its key`() =
        runTest {
            withDataStore { dataStore -> dataStore.edit { it[LEGACY_FOLLOW_UP_DUE_AT] = 601_000L } }
            withStore { store ->
                val owed = SleepAsAndroidRequestState(dueAtMs = 601_000L, owedSinceMs = 601_000L)
                assertEquals(owed, store.state.first().sleepAsAndroidRequest)

                store.updateSleepAsAndroidRequest { it.copy(settledKey = "a:1:2:3", settledSinceMs = 700_000L) }

                val state = store.state.first()
                assertEquals(
                    owed.copy(settledKey = "a:1:2:3", settledSinceMs = 700_000L),
                    state.sleepAsAndroidRequest,
                    "kept under the new keys",
                )
            }
            assertFalse(LEGACY_FOLLOW_UP_DUE_AT.name in storedKeys(), "the 0.4.0 key is gone")
        }

    @Test
    fun `a follow-up of an earlier release starts no settled streak and is ignored once a new due time is stored`() =
        runTest {
            withDataStore { dataStore ->
                dataStore.edit {
                    it[LEGACY_FOLLOW_UP_DUE_AT] = 601_000L
                    it[longPreferencesKey("sleep_as_android_settled_since")] = 400_000L
                    it[stringPreferencesKey("sleep_as_android_settled_key")] = "a:1:2:3"
                    it[longPreferencesKey("sleep_as_android_last_sent_at")] = 300_000L
                    it[stringPreferencesKey("sleep_as_android_sent_key")] = "a:1:2:0"
                }
            }
            withStore { store ->
                assertEquals(
                    SleepAsAndroidRequestState(dueAtMs = 601_000L, owedSinceMs = 601_000L, lastSentAtMs = 300_000L, sentKey = "a:1:2:0"),
                    store.state.first().sleepAsAndroidRequest,
                    "a write of 0.4.0 left it: the streak of an earlier version starts again",
                )
            }
            withDataStore { dataStore ->
                dataStore.edit {
                    it[longPreferencesKey("sleep_as_android_request_due_at")] = 900_000L
                    it[booleanPreferencesKey("sleep_as_android_request_follow_up")] = true
                    it[longPreferencesKey("sleep_as_android_request_owed_since")] = 800_000L
                }
            }
            withStore { store ->
                assertEquals(
                    SleepAsAndroidRequestState(900_000L, true, 800_000L, "a:1:2:3", 400_000L, 300_000L, "a:1:2:0"),
                    store.state.first().sleepAsAndroidRequest,
                )
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

    private companion object {
        /** The key in which 0.4.0 kept its pending follow-up. */
        val LEGACY_FOLLOW_UP_DUE_AT = longPreferencesKey("sleep_as_android_follow_up_due_at")
    }
}

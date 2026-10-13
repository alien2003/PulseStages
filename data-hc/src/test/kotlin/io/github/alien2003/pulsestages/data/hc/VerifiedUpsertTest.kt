package io.github.alien2003.pulsestages.data.hc

import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VerifiedUpsertTest {
    private val start = nightStart(1)

    private fun write(version: Long) =
        EstimatedSessionWrite(
            clientRecordId = "ps:abc",
            clientRecordVersion = version,
            startMs = start,
            endMs = start + 8 * HOUR_MS,
            startZoneOffsetSec = 7200,
            endZoneOffsetSec = 7200,
            stages = listOf(HcStage(start, start + 8 * HOUR_MS, 4)),
            title = "t",
            notes = "{}",
        )

    /** Store whose insert is ignored [ignoredInserts] times (like a provider that keeps a stale record). */
    private class ScriptedStore(
        var stored: StoredOwnSession?,
        var ignoredInserts: Int,
    ) : OwnSessionStore {
        val events = mutableListOf<String>()

        override suspend fun insert(write: EstimatedSessionWrite): String {
            events += "insert v${write.clientRecordVersion}"
            if (ignoredInserts > 0) {
                ignoredInserts--
            } else {
                stored = StoredOwnSession("id-1", write.clientRecordVersion, write.stages.size)
            }
            return "id-1"
        }

        override suspend fun findOwn(
            clientRecordId: String,
            startMs: Long,
        ): StoredOwnSession? {
            events += "read"
            return stored
        }

        override suspend fun deleteOwn(clientRecordIds: List<String>) {
            events += "delete $clientRecordIds"
            stored = null
        }
    }

    @Test
    fun `a verified insert needs no fallback`() =
        runTest {
            val store = ScriptedStore(stored = null, ignoredInserts = 0)
            assertEquals(WriteResult("id-1", 1000), VerifiedUpsert.write(store, write(1000)))
            assertEquals(listOf("insert v1000", "read"), store.events)
        }

    @Test
    fun `an ignored insert (stale higher version) falls back to delete then insert`() =
        runTest {
            val store = ScriptedStore(stored = StoredOwnSession("id-1", 5000, 3), ignoredInserts = 1)
            assertEquals(WriteResult("id-1", 1000), VerifiedUpsert.write(store, write(1000)))
            assertEquals(listOf("insert v1000", "read", "delete [ps:abc]", "insert v1000", "read"), store.events)
        }

    @Test
    fun `a second mismatch is reported instead of silently accepted`() =
        runTest {
            val store = ScriptedStore(stored = StoredOwnSession("id-1", 5000, 3), ignoredInserts = 5)
            store.stored = StoredOwnSession("id-1", 5000, 3)
            val stubborn =
                object : OwnSessionStore by store {
                    override suspend fun deleteOwn(clientRecordIds: List<String>) {
                        // The provider does not delete either.
                    }
                }
            assertThrows<HealthDataException.InvalidData> { VerifiedUpsert.write(stubborn, write(1000)) }
        }

    @Test
    fun `the fake applies the platform rule and the fallback replaces a stale higher version`() =
        runTest {
            val fake = FakeHealthRepository { NOON }
            fake.preloadOwnSession(write(9999))
            val result = fake.writeEstimatedSession(write(1000))
            assertEquals(1000, result.storedVersion)
            assertEquals(1, fake.ownSessions().size)
            assertEquals(1000, fake.ownSessions().single().clientRecordVersion)
            assertEquals(fake.ownRecordId("ps:abc"), result.recordId)
        }

    @Test
    fun `with a provider where equal versions are ignored the fallback still stores our content`() =
        runTest {
            val fake = FakeHealthRepository { NOON }
            fake.upsertRule = FakeHealthRepository.UpsertRule.STRICTLY_GREATER
            fake.preloadOwnSession(write(1000).copy(stages = emptyList()))
            val result = fake.writeEstimatedSession(write(1000))
            assertEquals(1000, result.storedVersion)
            assertEquals(
                1,
                fake
                    .ownSessions()
                    .single()
                    .stages.size,
            )
        }
}

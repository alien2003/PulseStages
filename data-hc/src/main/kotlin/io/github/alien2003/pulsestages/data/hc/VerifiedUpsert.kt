package io.github.alien2003.pulsestages.data.hc

/**
 * What [VerifiedUpsert] reads back about one of our sessions.
 *
 * @property recordId Health Connect id (metadata.id).
 * @property clientRecordVersion stored clientRecordVersion.
 * @property stageCount number of stored stages.
 */
data class StoredOwnSession(
    val recordId: String,
    val clientRecordVersion: Long,
    val stageCount: Int,
)

/** The primitive operations [VerifiedUpsert] needs; implemented over the real client and by the fake. */
interface OwnSessionStore {
    /** Inserts [write] (an upsert by clientRecordId) and returns the record id Health Connect reports. */
    suspend fun insert(write: EstimatedSessionWrite): String

    /** Our session with [clientRecordId] whose start lies near [startMs], or null. */
    suspend fun findOwn(
        clientRecordId: String,
        startMs: Long,
    ): StoredOwnSession?

    /** Deletes our sessions with these clientRecordIds (missing ids are ignored). */
    suspend fun deleteOwn(clientRecordIds: List<String>)
}

/**
 * The idempotent write of one estimated session:
 * 1. insert (Health Connect upserts by clientRecordId: on Android 14+ the stored row is replaced in place iff
 *    `newVersion >= storedVersion`; the Android 9–13 APK only promises "the higher version wins");
 * 2. read it back and compare clientRecordVersion and stage count;
 * 3. if they differ (a higher stale version is stored, or the APK path ignored the insert), delete the record by
 *    clientRecordId and insert again, then verify once more.
 *
 * The result always carries the version that is actually stored; a second mismatch is reported as
 * [HealthDataException.InvalidData] rather than silently accepted.
 */
object VerifiedUpsert {
    /** Writes [write] through [store] and verifies it. */
    suspend fun write(
        store: OwnSessionStore,
        write: EstimatedSessionWrite,
    ): WriteResult {
        val insertedId = store.insert(write)
        val first = store.findOwn(write.clientRecordId, write.startMs)
        if (first != null && first.matches(write)) return WriteResult(first.recordId, first.clientRecordVersion)

        store.deleteOwn(listOf(write.clientRecordId))
        val reinsertedId = store.insert(write)
        val second = store.findOwn(write.clientRecordId, write.startMs)
        if (second != null && second.matches(write)) return WriteResult(second.recordId, second.clientRecordVersion)

        throw HealthDataException.InvalidData(
            "write of ${write.clientRecordId} v${write.clientRecordVersion} could not be verified " +
                "(ids $insertedId / $reinsertedId, read back: first=$first, second=$second)",
        )
    }

    private fun StoredOwnSession.matches(write: EstimatedSessionWrite): Boolean =
        clientRecordVersion == write.clientRecordVersion && stageCount == write.stages.size
}

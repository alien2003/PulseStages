package io.github.alien2003.pulsestages.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import kotlinx.coroutines.flow.Flow

/** Source sessions seen in Health Connect (`source_sessions`). */
@Dao
abstract class SourceSessionDao {
    /** Inserts or replaces sessions by uuid. */
    @Upsert
    abstract suspend fun upsertAll(sessions: List<SourceSessionEntity>)

    /** The session with [uuid], or null. */
    @Query("SELECT * FROM source_sessions WHERE uuid = :uuid")
    abstract suspend fun findByUuid(uuid: String): SourceSessionEntity?

    /** Sessions with any of [uuids], oldest first. Large lists are chunked. */
    @Transaction
    open suspend fun findByUuids(uuids: Collection<String>): List<SourceSessionEntity> =
        uuids
            .distinct()
            .chunked(MAX_BIND_ARGS)
            .flatMap { findByUuidsChunk(it) }
            .sortedWith(compareBy({ it.startMs }, { it.uuid }))

    /** Sessions belonging to the night [clientRecordId], oldest first. */
    @Query("SELECT * FROM source_sessions WHERE nightClientRecordId = :clientRecordId ORDER BY startMs, uuid")
    abstract suspend fun findByNight(clientRecordId: String): List<SourceSessionEntity>

    /** All sessions, newest first. */
    @Query("SELECT * FROM source_sessions ORDER BY startMs DESC, uuid")
    abstract fun observeAll(): Flow<List<SourceSessionEntity>>

    /** Marks sessions DELETED. Large lists are chunked. Returns the number of rows changed. */
    @Transaction
    open suspend fun markDeleted(
        uuids: Collection<String>,
        reason: String?,
        now: Long,
    ): Int = uuids.distinct().chunked(MAX_BIND_ARGS).sumOf { markDeletedChunk(it, reason, now) }

    /** Home screen counters; [algoVersion] and [paramsHash] are the current ones (for the stale count). */
    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM source_sessions WHERE outcome != 'OWN') AS found, " +
            "(SELECT COUNT(*) FROM source_sessions WHERE outcome = 'ESTIMATED') AS estimated, " +
            "(SELECT COUNT(*) FROM source_sessions WHERE outcome IN " +
            "('NAP', 'HAS_STAGES', 'LOWER_PRIORITY_DUPLICATE', 'NOT_ESTIMATED', 'FAILED')) AS skipped, " +
            "(SELECT COUNT(*) FROM source_sessions WHERE outcome = 'FAILED') AS failed, " +
            "(SELECT COUNT(*) FROM estimated_nights WHERE ${EstimatedNightDao.STALE_CONDITION}) AS stale, " +
            "(SELECT COUNT(*) FROM estimated_nights WHERE status = 'WRITTEN' AND mode = 'NIGHT') AS nights, " +
            "(SELECT COUNT(*) FROM estimated_nights WHERE status = 'WRITTEN' AND mode = 'NAP') AS naps",
    )
    abstract fun observeHomeCounters(
        algoVersion: Int,
        paramsHash: String,
    ): Flow<HomeCounters>

    /** One chunk of [findByUuids]. */
    @Query("SELECT * FROM source_sessions WHERE uuid IN (:uuids)")
    protected abstract suspend fun findByUuidsChunk(uuids: List<String>): List<SourceSessionEntity>

    /** One chunk of [markDeleted]. */
    @Query("UPDATE source_sessions SET outcome = 'DELETED', reason = :reason, lastSeenAt = :now WHERE uuid IN (:uuids)")
    protected abstract suspend fun markDeletedChunk(
        uuids: List<String>,
        reason: String?,
        now: Long,
    ): Int
}

/**
 * Nadirs of estimated nights (`night_baselines`) and the rolling baseline derived from them. Every WRITTEN night with a
 * nadir has a row, naps included; the baseline counts only the rows whose night lasted at least the nap threshold
 * (`napMode.maxSessionMin`, compared with the session of the night's `estimated_nights` row, not with its stored
 * `mode`), so a changed threshold applies to the stored history without a recompute.
 */
@Dao
abstract class NightBaselineDao {
    /** Inserts or replaces the baseline row of a night. */
    @Upsert
    abstract suspend fun upsert(baseline: NightBaselineEntity)

    /** Deletes the baseline row of [clientRecordId]. */
    @Query("DELETE FROM night_baselines WHERE clientRecordId = :clientRecordId")
    abstract suspend fun delete(clientRecordId: String)

    /** The baseline row of [clientRecordId], or null. */
    @Query("SELECT * FROM night_baselines WHERE clientRecordId = :clientRecordId")
    abstract suspend fun find(clientRecordId: String): NightBaselineEntity?

    /**
     * Nadirs of the [limit] latest nights that ended strictly before [beforeMs] and whose session (end − start of
     * their `estimated_nights` row) lasted at least [minDurationMs], latest first. With [minDurationMs] ≤ 0 every row
     * counts, also one without a night row; with a positive minimum such a row (no known length) does not.
     */
    @Query(
        "SELECT b.nadirBpm FROM night_baselines b LEFT JOIN estimated_nights n ON n.clientRecordId = b.clientRecordId " +
            "WHERE b.sessionEndMs < :beforeMs AND (:minDurationMs <= 0 OR n.sessionEndMs - n.sessionStartMs >= :minDurationMs) " +
            "ORDER BY b.sessionEndMs DESC, b.clientRecordId LIMIT :limit",
    )
    abstract suspend fun recentNadirs(
        beforeMs: Long,
        limit: Int,
        minDurationMs: Long,
    ): List<Double>

    /**
     * Baseline nadir for a night starting at [nightStartMs]: the median of the nadirs of the last [nights] nights
     * that ended before it and lasted at least [minDurationMs] (shorter sessions are naps and never count), or null
     * when fewer than [NightBaseline.MIN_NIGHTS] such nights exist.
     */
    open suspend fun baselineBefore(
        nightStartMs: Long,
        nights: Int,
        minDurationMs: Long,
    ): Double? = if (nights <= 0) null else NightBaseline.median(recentNadirs(nightStartMs, nights, minDurationMs))
}

/** Stays well below SQLite's 999 bound-parameter limit of older Android versions. */
internal const val MAX_BIND_ARGS: Int = 500

/** Rolling-baseline rule. */
object NightBaseline {
    /** Minimum number of previous nights for a baseline. */
    const val MIN_NIGHTS: Int = 3

    /**
     * Median of [nadirs] (mean of the two middle values for an even count), or null when there are fewer than
     * [MIN_NIGHTS] values or a value is not finite.
     */
    fun median(nadirs: List<Double>): Double? {
        if (nadirs.size < MIN_NIGHTS || nadirs.any { !it.isFinite() }) return null
        val sorted = nadirs.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}

/** Internal log (`log_entries`). */
@Dao
interface LogDao {
    /** Appends entries. */
    @Insert
    suspend fun insertAll(entries: List<LogEntryEntity>)

    /** Deletes everything except the newest [keep] rows (by insertion order). Returns the number deleted. */
    @Query("DELETE FROM log_entries WHERE id <= (SELECT id FROM log_entries ORDER BY id DESC LIMIT 1 OFFSET :keep)")
    suspend fun trimToNewest(keep: Int): Int

    /** The newest [limit] entries, newest first. */
    @Query("SELECT * FROM log_entries ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LogEntryEntity>>

    /** The newest [limit] entries with one of [levels], newest first. */
    @Query("SELECT * FROM log_entries WHERE level IN (:levels) ORDER BY id DESC LIMIT :limit")
    fun observeRecentWithLevels(
        levels: List<LogLevel>,
        limit: Int,
    ): Flow<List<LogEntryEntity>>

    /** Entries about one night, oldest first. */
    @Query("SELECT * FROM log_entries WHERE nightKey = :nightKey ORDER BY id")
    fun observeForNight(nightKey: String): Flow<List<LogEntryEntity>>

    /** Every entry, oldest first (for export). */
    @Query("SELECT * FROM log_entries ORDER BY id")
    suspend fun getAll(): List<LogEntryEntity>

    /** Number of entries. */
    @Query("SELECT COUNT(*) FROM log_entries")
    suspend fun count(): Int

    /** Deletes every entry. */
    @Query("DELETE FROM log_entries")
    suspend fun clear()
}

/** The single backfill row (`backfill_state`). */
@Dao
abstract class BackfillStateDao {
    /** The state, or null if no backfill was ever started. */
    @Query("SELECT * FROM backfill_state WHERE id = ${BackfillStateEntity.SINGLETON_ID}")
    abstract suspend fun get(): BackfillStateEntity?

    /** Observes the state. */
    @Query("SELECT * FROM backfill_state WHERE id = ${BackfillStateEntity.SINGLETON_ID}")
    abstract fun observe(): Flow<BackfillStateEntity?>

    /** Inserts or replaces the single row (any other id in [state] is ignored). */
    open suspend fun save(state: BackfillStateEntity) {
        upsertRow(state.copy(id = BackfillStateEntity.SINGLETON_ID))
    }

    /** Removes the state (back to "never started"). */
    @Query("DELETE FROM backfill_state")
    abstract suspend fun clear()

    /** Raw upsert; use [save]. */
    @Upsert
    protected abstract suspend fun upsertRow(state: BackfillStateEntity)
}

/** Stored personal models (`personal_models`); at most one is active. */
@Dao
abstract class PersonalModelDao {
    /** Inserts [model] as a new row; when it is active, every other model is deactivated first. Returns its id. */
    @Transaction
    open suspend fun insert(model: PersonalModelEntity): Long {
        if (model.active) deactivateAll()
        return insertRow(model.copy(id = 0))
    }

    /** Makes model [id] the only active one. Returns false (and changes nothing) when it does not exist. */
    @Transaction
    open suspend fun activate(id: Long): Boolean {
        if (findById(id) == null) return false
        deactivateAll()
        setActive(id)
        return true
    }

    /** Deactivates every model (back to the global model). */
    @Query("UPDATE personal_models SET active = 0 WHERE active != 0")
    abstract suspend fun deactivateAll()

    /** The active model, or null. */
    @Query("SELECT * FROM personal_models WHERE active = 1 ORDER BY id DESC LIMIT 1")
    abstract suspend fun getActive(): PersonalModelEntity?

    /** Observes the active model. */
    @Query("SELECT * FROM personal_models WHERE active = 1 ORDER BY id DESC LIMIT 1")
    abstract fun observeActive(): Flow<PersonalModelEntity?>

    /** Every model, newest first. */
    @Query("SELECT * FROM personal_models ORDER BY createdAt DESC, id DESC")
    abstract fun observeAll(): Flow<List<PersonalModelEntity>>

    /** The model with [id], or null. */
    @Query("SELECT * FROM personal_models WHERE id = :id")
    abstract suspend fun findById(id: Long): PersonalModelEntity?

    /** Deletes model [id]. */
    @Query("DELETE FROM personal_models WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /** Raw insert; use [insert]. */
    @Insert
    protected abstract suspend fun insertRow(model: PersonalModelEntity): Long

    /** Raw activation; use [activate]. */
    @Query("UPDATE personal_models SET active = 1 WHERE id = :id")
    protected abstract suspend fun setActive(id: Long): Int
}

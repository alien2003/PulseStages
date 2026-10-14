package io.github.alien2003.pulsestages.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import io.github.alien2003.pulsestages.algo.model.SessionMode
import kotlinx.coroutines.flow.Flow

/**
 * Output nights (`estimated_nights`) together with their links (`night_sources`). Writes that change several
 * tables (night + links + source sessions + baseline) run in one transaction.
 */
@Dao
@Suppress("TooManyFunctions") // The ledger's query surface used by the pipeline, the eraser and the UI.
abstract class EstimatedNightDao {
    /** The night with [clientRecordId], or null. */
    @Query("SELECT * FROM estimated_nights WHERE clientRecordId = :clientRecordId")
    abstract suspend fun findByClientRecordId(clientRecordId: String): EstimatedNightEntity?

    /** The night with local [id], or null. */
    @Query("SELECT * FROM estimated_nights WHERE id = :id")
    abstract suspend fun findById(id: Long): EstimatedNightEntity?

    /** Observes the night with local [id]. */
    @Query("SELECT * FROM estimated_nights WHERE id = :id")
    abstract fun observeById(id: Long): Flow<EstimatedNightEntity?>

    /** Observes the night with [clientRecordId]. */
    @Query("SELECT * FROM estimated_nights WHERE clientRecordId = :clientRecordId")
    abstract fun observeByClientRecordId(clientRecordId: String): Flow<EstimatedNightEntity?>

    /** All nights, newest first, as lightweight summaries (Nights list). */
    @Query("SELECT $SUMMARY_COLUMNS FROM estimated_nights ORDER BY sessionStartMs DESC, id DESC")
    abstract fun observeSummaries(): Flow<List<NightSummary>>

    /** Nights with one of [statuses], newest first, as summaries. */
    @Query("SELECT $SUMMARY_COLUMNS FROM estimated_nights WHERE status IN (:statuses) ORDER BY sessionStartMs DESC, id DESC")
    abstract fun observeSummariesWithStatus(statuses: List<NightStatus>): Flow<List<NightSummary>>

    /** Nights with one of [statuses] whose session overlaps `[startMs, endMs)`, oldest first. */
    @Query(
        "SELECT * FROM estimated_nights WHERE sessionStartMs < :endMs AND sessionEndMs > :startMs AND status IN (:statuses) " +
            "ORDER BY sessionStartMs, id",
    )
    abstract suspend fun findOverlapping(
        startMs: Long,
        endMs: Long,
        statuses: List<NightStatus>,
    ): List<EstimatedNightEntity>

    /** Nights whose session starts in `[startMs, endMs)`, oldest first. */
    @Query("SELECT * FROM estimated_nights WHERE sessionStartMs >= :startMs AND sessionStartMs < :endMs ORDER BY sessionStartMs, id")
    abstract suspend fun findStartingIn(
        startMs: Long,
        endMs: Long,
    ): List<EstimatedNightEntity>

    /** Sorted source uuids linked to [clientRecordId]. */
    @Query("SELECT sourceUuid FROM night_sources WHERE clientRecordId = :clientRecordId ORDER BY sourceUuid")
    abstract suspend fun sourceUuidsOf(clientRecordId: String): List<String>

    /** WRITTEN nights with another algorithm version or params hash (to offer a recompute), oldest first. */
    @Query(
        "SELECT * FROM estimated_nights WHERE status = 'WRITTEN' AND (algoVersion != :algoVersion OR paramsHash != :paramsHash) " +
            "ORDER BY sessionStartMs, id",
    )
    abstract suspend fun findStale(
        algoVersion: Int,
        paramsHash: String,
    ): List<EstimatedNightEntity>

    /** Number of stale WRITTEN nights. */
    @Query("SELECT COUNT(*) FROM estimated_nights WHERE status = 'WRITTEN' AND (algoVersion != :algoVersion OR paramsHash != :paramsHash)")
    abstract fun observeStaleCount(
        algoVersion: Int,
        paramsHash: String,
    ): Flow<Int>

    /** Number of nights with [status]. */
    @Query("SELECT COUNT(*) FROM estimated_nights WHERE status = :status")
    abstract suspend fun countWithStatus(status: NightStatus): Int

    /** Number of nights with [status] processed in [mode] (night or nap). */
    @Query("SELECT COUNT(*) FROM estimated_nights WHERE status = :status AND mode = :mode")
    abstract suspend fun countWithStatusAndMode(
        status: NightStatus,
        mode: SessionMode,
    ): Int

    /** clientRecordIds of every night whose record we believe to exist in Health Connect (`hcRecordId` set). */
    @Query("SELECT clientRecordId FROM estimated_nights WHERE hcRecordId IS NOT NULL ORDER BY sessionStartMs, id")
    abstract suspend fun clientRecordIdsWithOutput(): List<String>

    /** Distinct nights linked to any of [sourceUuids] (any status), oldest first. Large lists are chunked. */
    @Transaction
    open suspend fun findBySourceUuids(sourceUuids: Collection<String>): List<EstimatedNightEntity> =
        sourceUuids
            .distinct()
            .chunked(MAX_BIND_ARGS)
            .flatMap { findBySourceUuidsChunk(it) }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.sessionStartMs }, { it.id }))

    /**
     * Inserts [night] or, when a row with the same clientRecordId exists, updates it in place (keeping its `id` and
     * `createdAt`). `sourceUuids` are stored sorted and distinct, and `night_sources` is replaced by them.
     * Returns the row id.
     */
    @Transaction
    open suspend fun upsert(night: EstimatedNightEntity): Long {
        val normalized = night.copy(sourceUuids = night.sourceUuids.distinct().sorted())
        val existing = findByClientRecordId(normalized.clientRecordId)
        val id =
            if (existing == null) {
                insertNight(normalized.copy(id = 0))
            } else {
                updateNight(normalized.copy(id = existing.id, createdAt = existing.createdAt))
                existing.id
            }
        deleteSourcesOf(normalized.clientRecordId)
        insertSources(normalized.sourceUuids.map { NightSourceEntity(normalized.clientRecordId, it) })
        return id
    }

    /**
     * Stores one pipeline result atomically: [upsert]s [night], upserts [sourceSessions] and, for a
     * [NightStatus.WRITTEN] night with a nadir, its baseline row (other results leave baselines untouched). Naps get a
     * row too: [NightBaselineDao] counts only nights long enough for the baseline, so the stored history follows a
     * changed `napMode.maxSessionMin`.
     * Returns the night's row id.
     */
    @Transaction
    open suspend fun recordNight(
        night: EstimatedNightEntity,
        sourceSessions: List<SourceSessionEntity>,
    ): Long {
        val id = upsert(night)
        if (sourceSessions.isNotEmpty()) upsertSourceSessions(sourceSessions)
        val nadir = night.nadirBpm
        if (night.status == NightStatus.WRITTEN && nadir != null) {
            upsertBaseline(NightBaselineEntity(night.clientRecordId, night.sessionEndMs, nadir))
        }
        return id
    }

    /**
     * Sets [status] and [reason] on the nights with [clientRecordIds]. With [clearOutput] the Health Connect record id
     * is cleared (our record no longer exists). Returns the number of rows changed.
     */
    @Transaction
    open suspend fun updateStatus(
        clientRecordIds: Collection<String>,
        status: NightStatus,
        reason: String?,
        clearOutput: Boolean,
        now: Long,
    ): Int =
        clientRecordIds.distinct().chunked(MAX_BIND_ARGS).sumOf { chunk ->
            setStatusChunk(chunk, status, reason, clearOutput, now)
        }

    /**
     * Marks [clientRecordIds] SUPERSEDED by [supersededBy] after their Health Connect records were deleted, and drops
     * their baseline rows (the new night carries the nadir).
     */
    @Transaction
    open suspend fun markSuperseded(
        clientRecordIds: Collection<String>,
        supersededBy: String,
        now: Long,
    ): Int {
        val changed = updateStatus(clientRecordIds, NightStatus.SUPERSEDED, "superseded by $supersededBy", clearOutput = true, now = now)
        clientRecordIds.distinct().chunked(MAX_BIND_ARGS).forEach { deleteBaselinesChunk(it) }
        return changed
    }

    /** Marks [clientRecordIds] SOURCE_DELETED (all sources gone, our record deleted) and drops their baselines. */
    @Transaction
    open suspend fun markSourceDeleted(
        clientRecordIds: Collection<String>,
        now: Long,
    ): Int {
        val changed =
            updateStatus(clientRecordIds, NightStatus.SOURCE_DELETED, "all source sessions deleted", clearOutput = true, now = now)
        clientRecordIds.distinct().chunked(MAX_BIND_ARGS).forEach { deleteBaselinesChunk(it) }
        return changed
    }

    /** Marks [clientRecordIds] DELETED (our record was deleted by the user); baselines stay (they describe the user). */
    open suspend fun markDeleted(
        clientRecordIds: Collection<String>,
        reason: String?,
        now: Long,
    ): Int = updateStatus(clientRecordIds, NightStatus.DELETED, reason, clearOutput = true, now = now)

    /** One chunk of [findBySourceUuids]; prefer that function. */
    @Query(
        "SELECT DISTINCT n.* FROM estimated_nights n INNER JOIN night_sources s ON s.clientRecordId = n.clientRecordId " +
            "WHERE s.sourceUuid IN (:sourceUuids)",
    )
    protected abstract suspend fun findBySourceUuidsChunk(sourceUuids: List<String>): List<EstimatedNightEntity>

    /** Plain insert; fails on a duplicate clientRecordId. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertNight(night: EstimatedNightEntity): Long

    /** Plain update by primary key. */
    @Update
    protected abstract suspend fun updateNight(night: EstimatedNightEntity)

    /** Inserts links, ignoring existing ones. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSources(sources: List<NightSourceEntity>)

    /** Removes all links of a night. */
    @Query("DELETE FROM night_sources WHERE clientRecordId = :clientRecordId")
    protected abstract suspend fun deleteSourcesOf(clientRecordId: String)

    /** Upserts source sessions by uuid. */
    @Upsert
    protected abstract suspend fun upsertSourceSessions(sessions: List<SourceSessionEntity>)

    /** Upserts a baseline row. */
    @Upsert
    protected abstract suspend fun upsertBaseline(baseline: NightBaselineEntity)

    /** Deletes baseline rows. */
    @Query("DELETE FROM night_baselines WHERE clientRecordId IN (:clientRecordIds)")
    protected abstract suspend fun deleteBaselinesChunk(clientRecordIds: List<String>)

    /** One chunk of [updateStatus]. */
    @Query(
        "UPDATE estimated_nights SET status = :status, statusReason = :reason, updatedAt = :now, " +
            "hcRecordId = CASE WHEN :clearOutput THEN NULL ELSE hcRecordId END WHERE clientRecordId IN (:clientRecordIds)",
    )
    protected abstract suspend fun setStatusChunk(
        clientRecordIds: List<String>,
        status: NightStatus,
        reason: String?,
        clearOutput: Boolean,
        now: Long,
    ): Int

    private companion object {
        const val SUMMARY_COLUMNS =
            "id, clientRecordId, sourcePackage, sessionStartMs, sessionEndMs, startZoneOffsetSec, endZoneOffsetSec, " +
                "status, statusReason, estimator, algoVersion, paramsHash, coverage, confidence, awakeMin, lightMin, deepMin, " +
                "remMin, unknownMin, elevatedHr, lowConfidence, calibrated, rulesFallback, updatedAt, mode, sleepWakeOnly"
    }
}

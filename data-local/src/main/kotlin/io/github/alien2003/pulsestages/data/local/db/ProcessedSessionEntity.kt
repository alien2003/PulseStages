package io.github.alien2003.pulsestages.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Bookkeeping row: a source sleep session (written by another app) that PulseStages has already processed and the
 * deterministic clientRecordId it wrote back. Placeholder schema for the skeleton.
 */
@Entity(tableName = "processed_sessions")
data class ProcessedSessionEntity(
    @PrimaryKey val sourceRecordId: String,
    val clientRecordId: String,
    val algorithmVersion: Int,
    val processedAtEpochMillis: Long,
)

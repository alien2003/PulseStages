package io.github.alien2003.pulsestages.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ProcessedSessionDao {
    @Query("SELECT * FROM processed_sessions WHERE sourceRecordId = :sourceRecordId")
    suspend fun find(sourceRecordId: String): ProcessedSessionEntity?

    @Upsert
    suspend fun upsert(entity: ProcessedSessionEntity)
}

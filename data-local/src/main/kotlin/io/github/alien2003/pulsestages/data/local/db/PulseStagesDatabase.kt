package io.github.alien2003.pulsestages.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ProcessedSessionEntity::class], version = 1, exportSchema = true)
abstract class PulseStagesDatabase : RoomDatabase() {
    abstract fun processedSessionDao(): ProcessedSessionDao

    companion object {
        const val FILE_NAME = "pulsestages.db"
    }
}

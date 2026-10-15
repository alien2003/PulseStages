package io.github.alien2003.pulsestages.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The local ledger. Every schema version is exported to `data-local/schemas/`; every later version ships a migration
 * ([LedgerMigrations]; there is deliberately no destructive fallback: the ledger is what keeps Health Connect writes
 * idempotent). Version 2 added `estimated_nights.mode` and `estimated_nights.sleepWakeOnly` (night / nap modes), version 3
 * `estimated_nights.inputFingerprint` and `estimated_nights.inputCheckedAt` (refresh of written nights).
 */
@Database(
    entities = [
        EstimatedNightEntity::class,
        NightSourceEntity::class,
        SourceSessionEntity::class,
        NightBaselineEntity::class,
        LogEntryEntity::class,
        BackfillStateEntity::class,
        PersonalModelEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(LedgerConverters::class)
abstract class PulseStagesDatabase : RoomDatabase() {
    /** Output nights and their source links. */
    abstract fun estimatedNightDao(): EstimatedNightDao

    /** Source sessions and Home counters. */
    abstract fun sourceSessionDao(): SourceSessionDao

    /** Nadirs and the rolling baseline. */
    abstract fun nightBaselineDao(): NightBaselineDao

    /** Internal log. */
    abstract fun logDao(): LogDao

    /** Backfill state. */
    abstract fun backfillStateDao(): BackfillStateDao

    /** Personal models. */
    abstract fun personalModelDao(): PersonalModelDao

    /** Database constants. */
    companion object {
        /** File name of the database. */
        const val FILE_NAME: String = "pulsestages.db"
    }
}

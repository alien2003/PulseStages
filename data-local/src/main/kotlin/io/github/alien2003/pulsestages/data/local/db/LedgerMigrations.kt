package io.github.alien2003.pulsestages.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The schema migrations of [PulseStagesDatabase], one per version step, all of them given to the database builder. */
object LedgerMigrations {
    /**
     * Sessions of version 1 shorter than this were processed like naps: 180 min was the default of all three
     * thresholds that separated naps from nights then (`features.baselineMinSessionMin`, `hybrid.shortSessionMaxMin`,
     * `post.proportionsMinSessionMin`), and it is the default of the one threshold `napMode.maxSessionMin` that
     * replaced them. Fixed here, not read from the current defaults: a migration must not change with later releases.
     */
    internal const val V1_NAP_THRESHOLD_MS: Long = 180L * 60_000L

    /**
     * 1 → 2 (night / nap modes): `estimated_nights` gains `mode` (TEXT, a `SessionMode` name, default `NIGHT`) and
     * `sleepWakeOnly` (INTEGER 0/1, default 0: version 1 wrote every stage). Rows of sessions shorter than
     * [V1_NAP_THRESHOLD_MS] (end − start) are marked `NAP`, the mode they would have now with the default threshold;
     * a row is set again from the current settings whenever its session is processed again. Nothing else changes;
     * existing rows keep every other value.
     */
    val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `estimated_nights` ADD COLUMN `mode` TEXT NOT NULL DEFAULT 'NIGHT'")
                db.execSQL("ALTER TABLE `estimated_nights` ADD COLUMN `sleepWakeOnly` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE `estimated_nights` SET `mode` = 'NAP' WHERE `sessionEndMs` - `sessionStartMs` < ?",
                    arrayOf<Any?>(V1_NAP_THRESHOLD_MS),
                )
            }
        }

    /** Every migration, oldest first. */
    val ALL: List<Migration> = listOf(MIGRATION_1_2)
}

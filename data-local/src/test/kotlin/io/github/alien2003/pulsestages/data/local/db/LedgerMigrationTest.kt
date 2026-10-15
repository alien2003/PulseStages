package io.github.alien2003.pulsestages.data.local.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.model.WarningCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The Room migrations against the exported schemas (`data-local/schemas/`): a database created exactly as schema 1 or 2
 * describes it (its CREATE statements and identity hash), with rows in its tables, is opened by the current
 * [PulseStagesDatabase] with [LedgerMigrations.ALL]. Room checks the migrated tables against the entities of the
 * current version (a mismatch fails the open), and every row keeps its values; the night / nap columns of version 2
 * read as a session written with every stage, a night unless it is shorter than the 180 min that separated naps from
 * nights in version 1; the input columns of version 3 (`inputFingerprint`, `inputCheckedAt`) read as null. Without the
 * migration the open fails (no destructive fallback).
 */
@RunWith(RobolectricTestRunner::class)
class LedgerMigrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val name = "migration-test.db"

    @After
    fun deleteDatabase() {
        context.deleteDatabase(name)
    }

    private fun schema(version: Int): JsonObject {
        val directory = File(checkNotNull(System.getProperty("pulsestages.roomSchemas")) { "system property not set by Gradle" })
        val file = directory.resolve("${PulseStagesDatabase::class.java.name}/$version.json")
        return Json
            .parseToJsonElement(file.readText())
            .jsonObject
            .getValue("database")
            .jsonObject
    }

    /** A database file in the exact shape of schema [version], with the rows [insertRows] adds. */
    private fun createDatabase(
        version: Int,
        insertRows: (SQLiteDatabase) -> Unit,
    ) {
        val database = schema(version)
        assertEquals(version, database.getValue("version").jsonPrimitive.int)
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            for (entity in database.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(
                    entity
                        .getValue("createSql")
                        .jsonPrimitive.content
                        .replace("\${TABLE_NAME}", table),
                )
                entity["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(
                        index.jsonObject
                            .getValue("createSql")
                            .jsonPrimitive.content
                            .replace("\${TABLE_NAME}", table),
                    )
                }
            }
            database.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            insertRows(db)
            db.version = version
        }
    }

    /** A database of schema 1 with [insertVersion1Rows]. */
    private fun createVersion1() = createDatabase(1, ::insertVersion1Rows)

    /** One row per table of schema 1: a night with its source and baseline, a 179-min and a 180-min session. */
    private fun insertVersion1Rows(db: SQLiteDatabase) {
        db.insertOrThrow("estimated_nights", null, version1Night())
        db.insertOrThrow("estimated_nights", null, version1Session("ps:v1-nap", NAP_START, 179 * MINUTE))
        db.insertOrThrow("estimated_nights", null, version1Session("ps:v1-3h", NAP_START + 6 * HOUR, 180 * MINUTE))
        insertSourceAndBaseline(db)
    }

    /**
     * Rows of schema 2: the night of [insertVersion1Rows], a nap written as sleep / wake and a skipped nap without an
     * output, with the night's source and baseline.
     */
    private fun insertVersion2Rows(db: SQLiteDatabase) {
        db.insertOrThrow("estimated_nights", null, version2Session("ps:v1", START, 8 * HOUR, SessionMode.NIGHT, sleepWakeOnly = false))
        db.insertOrThrow(
            "estimated_nights",
            null,
            version2Session("ps:v2-nap", NAP_START, 90 * MINUTE, SessionMode.NAP, sleepWakeOnly = true),
        )
        db.insertOrThrow(
            "estimated_nights",
            null,
            version2Session("ps:v2-skipped", NAP_START + 6 * HOUR, 60 * MINUTE, SessionMode.NAP, sleepWakeOnly = false).apply {
                putNull("hcRecordId")
                put("status", "NOT_ESTIMATED")
                put("statusReason", SKIPPED_REASON)
                put("clientRecordVersion", 0L)
            },
        )
        insertSourceAndBaseline(db)
    }

    /** The source session, its link and the baseline of the night `ps:v1`. */
    private fun insertSourceAndBaseline(db: SQLiteDatabase) {
        db.insertOrThrow(
            "night_sources",
            null,
            ContentValues().apply {
                put("clientRecordId", "ps:v1")
                put("sourceUuid", "u1")
            },
        )
        db.insertOrThrow(
            "source_sessions",
            null,
            ContentValues().apply {
                put("uuid", "u1")
                put("packageName", PACKAGE)
                put("startMs", START)
                put("endMs", START + 8 * HOUR)
                put("outcome", "ESTIMATED")
                put("nightClientRecordId", "ps:v1")
                put("lastSeenAt", 7L)
            },
        )
        db.insertOrThrow(
            "night_baselines",
            null,
            ContentValues().apply {
                put("clientRecordId", "ps:v1")
                put("sessionEndMs", START + 8 * HOUR)
                put("nadirBpm", 51.5)
            },
        )
    }

    private fun version1Night(): ContentValues = version1Session("ps:v1", START, 8 * HOUR)

    private fun version2Session(
        id: String,
        start: Long,
        duration: Long,
        mode: SessionMode,
        sleepWakeOnly: Boolean,
    ): ContentValues =
        version1Session(id, start, duration).apply {
            put("mode", mode.name)
            put("sleepWakeOnly", if (sleepWakeOnly) 1 else 0)
        }

    /**
     * The entity of a row inserted by [version1Session] (and [version2Session]) with [id], as read after the migrations
     * (with row id 0: compare it with the read row's `copy(id = 0)`).
     */
    private fun expectedSession(
        id: String,
        start: Long,
        duration: Long,
        mode: SessionMode,
        sleepWakeOnly: Boolean,
    ): EstimatedNightEntity =
        EstimatedNightEntity(
            clientRecordId = id,
            sourceUuids = if (id == "ps:v1") listOf("u1") else emptyList(),
            sourcePackage = PACKAGE,
            sessionStartMs = start,
            sessionEndMs = start + duration,
            startZoneOffsetSec = 10_800,
            endZoneOffsetSec = 10_800,
            hcRecordId = "hc-1",
            algoVersion = 1,
            paramsHash = "hash-v1",
            estimator = EstimatorType.HYBRID,
            modelId = "model-v1",
            coverage = 0.93,
            confidence = 0.61,
            recomputeCount = 2,
            clientRecordVersion = 1002L,
            status = NightStatus.WRITTEN,
            statusReason = null,
            awakeMin = 30.0,
            lightMin = 300.0,
            deepMin = 60.0,
            remMin = 90.0,
            unknownMin = 0.0,
            elevatedHr = true,
            lowConfidence = false,
            calibrated = false,
            rulesFallback = false,
            warnings = listOf(WarningCode.DATA_GAPS),
            nadirBpm = 51.5,
            hrSamples = 231,
            stages = listOf(StageInterval(start, start + duration, SleepStage.LIGHT)),
            createdAt = 5L,
            updatedAt = 6L,
            mode = mode,
            sleepWakeOnly = sleepWakeOnly,
            inputFingerprint = null,
            inputCheckedAt = null,
        )

    private fun version1Session(
        id: String,
        start: Long,
        duration: Long,
    ): ContentValues =
        ContentValues().apply {
            put("clientRecordId", id)
            put("sourceUuids", if (id == "ps:v1") """["u1"]""" else "[]")
            put("sourcePackage", PACKAGE)
            put("sessionStartMs", start)
            put("sessionEndMs", start + duration)
            put("startZoneOffsetSec", 10_800)
            put("endZoneOffsetSec", 10_800)
            put("hcRecordId", "hc-1")
            put("algoVersion", 1)
            put("paramsHash", "hash-v1")
            put("estimator", "HYBRID")
            put("modelId", "model-v1")
            put("coverage", 0.93)
            put("confidence", 0.61)
            put("recomputeCount", 2)
            put("clientRecordVersion", 1002L)
            put("status", "WRITTEN")
            put("awakeMin", 30.0)
            put("lightMin", 300.0)
            put("deepMin", 60.0)
            put("remMin", 90.0)
            put("unknownMin", 0.0)
            put("elevatedHr", 1)
            put("lowConfidence", 0)
            put("calibrated", 0)
            put("rulesFallback", 0)
            put("warnings", """["DATA_GAPS"]""")
            put("nadirBpm", 51.5)
            put("hrSamples", 231)
            put("stagesJson", """[[$start,${start + duration},"LIGHT"]]""")
            put("createdAt", 5L)
            put("updatedAt", 6L)
        }

    private fun open(withMigrations: Boolean): PulseStagesDatabase = open(if (withMigrations) LedgerMigrations.ALL else emptyList())

    private fun open(migrations: List<Migration>): PulseStagesDatabase =
        Room
            .databaseBuilder(context, PulseStagesDatabase::class.java, name)
            .allowMainThreadQueries()
            .apply { migrations.forEach { addMigrations(it) } }
            .build()

    /** The fields of table [table] in schema [version], by column name. */
    private fun fields(
        version: Int,
        table: String = "estimated_nights",
    ): Map<String, JsonObject> =
        entity(version, table)
            .getValue("fields")
            .jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("columnName").jsonPrimitive.content }

    private fun entity(
        version: Int,
        table: String,
    ): JsonObject =
        schema(version)
            .getValue("entities")
            .jsonArray
            .map { it.jsonObject }
            .single { it.getValue("tableName").jsonPrimitive.content == table }

    @Test
    fun version1MigratesThroughVersion2To3KeepingEveryRowAndReadsAsANightWithEveryStageWithoutFingerprint() {
        createVersion1()

        val db = open(withMigrations = true)
        try {
            val night = checkNotNull(runBlocking { db.estimatedNightDao().findByClientRecordId("ps:v1") })
            assertEquals(expectedSession("ps:v1", START, 8 * HOUR, SessionMode.NIGHT, sleepWakeOnly = false), night.copy(id = 0))
            assertNull(night.inputFingerprint)
            assertNull(night.inputCheckedAt)
            assertEquals(SessionMode.NIGHT, night.mode)
            assertEquals(false, night.sleepWakeOnly)
            assertEquals(listOf("u1"), night.sourceUuids)
            assertEquals(EstimatorType.HYBRID, night.estimator)
            assertEquals(NightStatus.WRITTEN, night.status)
            assertEquals("hash-v1", night.paramsHash)
            assertEquals(1002L, night.clientRecordVersion)
            assertEquals(2, night.recomputeCount)
            assertEquals(true, night.elevatedHr)
            assertEquals(listOf(WarningCode.DATA_GAPS), night.warnings)
            assertEquals(listOf(StageInterval(START, START + 8 * HOUR, SleepStage.LIGHT)), night.stages)
            assertEquals(60.0, night.deepMin, 0.0)
            assertEquals(listOf("u1"), runBlocking { db.estimatedNightDao().sourceUuidsOf("ps:v1") })
            assertEquals(SourceOutcome.ESTIMATED, runBlocking { db.sourceSessionDao().findByUuid("u1") }?.outcome)
            assertNotNull(runBlocking { db.nightBaselineDao().find("ps:v1") })
            // Sessions shorter than 180 min were processed like naps in version 1: they become naps, 180 min stays a night.
            val nap = checkNotNull(runBlocking { db.estimatedNightDao().findByClientRecordId("ps:v1-nap") })
            assertEquals(SessionMode.NAP, nap.mode)
            assertEquals(false, nap.sleepWakeOnly)
            assertEquals(NightStatus.WRITTEN, nap.status)
            assertEquals(SessionMode.NIGHT, runBlocking { db.estimatedNightDao().findByClientRecordId("ps:v1-3h") }?.mode)
            // The migrated database takes rows of the new shape.
            runBlocking {
                db.estimatedNightDao().upsert(
                    night.copy(clientRecordId = "ps:nap", mode = SessionMode.NAP, sleepWakeOnly = true),
                )
            }
            assertEquals(SessionMode.NAP, runBlocking { db.estimatedNightDao().findByClientRecordId("ps:nap") }?.mode)
            assertEquals(3, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun theMigrationAddsExactlyTheColumnsOfTheExportedSchema2() {
        val columns = { version: Int ->
            schema(version)
                .getValue("entities")
                .jsonArray
                .map { it.jsonObject }
                .single { it.getValue("tableName").jsonPrimitive.content == "estimated_nights" }
                .getValue("fields")
                .jsonArray
                .associate { field ->
                    val f = field.jsonObject
                    f.getValue("columnName").jsonPrimitive.content to f["defaultValue"]?.jsonPrimitive?.content
                }
        }
        val added = columns(2) - columns(1).keys
        assertEquals(mapOf("mode" to "'NIGHT'", "sleepWakeOnly" to "0"), added)
        assertEquals(2, schema(2).getValue("version").jsonPrimitive.int)
    }

    @Test
    fun version2MigratesTo3KeepingEveryValueAndReadsWithoutFingerprint() {
        createDatabase(2, ::insertVersion2Rows)

        val db = open(withMigrations = true)
        try {
            val dao = db.estimatedNightDao()
            val rows = runBlocking { dao.findStartingIn(0, Long.MAX_VALUE) }.associateBy { it.clientRecordId }
            assertEquals(setOf("ps:v1", "ps:v2-nap", "ps:v2-skipped"), rows.keys)
            val night = rows.getValue("ps:v1")
            assertEquals(expectedSession("ps:v1", START, 8 * HOUR, SessionMode.NIGHT, sleepWakeOnly = false), night.copy(id = 0))
            val nap = rows.getValue("ps:v2-nap")
            assertEquals(expectedSession("ps:v2-nap", NAP_START, 90 * MINUTE, SessionMode.NAP, sleepWakeOnly = true), nap.copy(id = 0))
            val skipped = rows.getValue("ps:v2-skipped")
            assertEquals(
                expectedSession("ps:v2-skipped", NAP_START + 6 * HOUR, 60 * MINUTE, SessionMode.NAP, sleepWakeOnly = false)
                    .copy(hcRecordId = null, status = NightStatus.NOT_ESTIMATED, statusReason = SKIPPED_REASON, clientRecordVersion = 0L),
                skipped.copy(id = 0),
            )
            assertTrue(skipped.skippedNap)
            assertTrue(rows.values.all { it.inputFingerprint == null && it.inputCheckedAt == null })
            assertEquals(listOf("u1"), runBlocking { dao.sourceUuidsOf("ps:v1") })
            assertEquals(SourceOutcome.ESTIMATED, runBlocking { db.sourceSessionDao().findByUuid("u1") }?.outcome)
            assertEquals(NightBaselineEntity("ps:v1", START + 8 * HOUR, 51.5), runBlocking { db.nightBaselineDao().find("ps:v1") })
            assertEquals(3, db.openHelper.readableDatabase.version)
            // The migrated table takes the new columns.
            assertEquals(1, runBlocking { dao.markInputChecked("ps:v1", "1|hr:0:x", 9L) })
            val checked = checkNotNull(runBlocking { dao.findByClientRecordId("ps:v1") })
            assertEquals(night.copy(inputFingerprint = "1|hr:0:x", inputCheckedAt = 9L), checked)
        } finally {
            db.close()
        }
    }

    @Test
    fun migration2To3AddsExactlyTheTwoNullableInputColumnsWithoutADefault() {
        val before = fields(2)
        val after = fields(3)
        val added = after - before.keys

        assertEquals(setOf("inputFingerprint", "inputCheckedAt"), added.keys)
        val affinity = { column: String -> added.getValue(column)["affinity"]?.jsonPrimitive?.content }
        assertEquals("TEXT", affinity("inputFingerprint"))
        assertEquals("INTEGER", affinity("inputCheckedAt"))
        added.values.forEach { field ->
            assertEquals("nullable: $field", false, field["notNull"]?.jsonPrimitive?.boolean ?: false)
            assertNull("no default: $field", field["defaultValue"])
        }
        assertEquals("every other column is unchanged", before, after - added.keys)
        assertEquals(entity(2, "estimated_nights")["indices"], entity(3, "estimated_nights")["indices"])
        val tables =
            schema(2)
                .getValue("entities")
                .jsonArray
                .map { it.jsonObject.getValue("tableName") }
                .map { it.jsonPrimitive.content }
        val otherTables = tables - "estimated_nights"
        otherTables.forEach { table -> assertEquals("table $table is unchanged", entity(2, table), entity(3, table)) }
        assertEquals(3, schema(3).getValue("version").jsonPrimitive.int)
    }

    @Test
    fun aVersion2DatabaseIsNotOpenedWithoutTheMigrationTo3() {
        createDatabase(2, ::insertVersion2Rows)
        val db = open(listOf(LedgerMigrations.MIGRATION_1_2))
        try {
            val failure = runCatching { db.openHelper.writableDatabase }.exceptionOrNull()
            assertTrue("expected a missing-migration failure, got $failure", failure is IllegalStateException)
        } finally {
            db.close()
        }
    }

    @Test
    fun withoutTheMigrationTheOldDatabaseIsNotOpened() {
        createVersion1()
        val db = open(withMigrations = false)
        try {
            val failure = runCatching { db.openHelper.writableDatabase }.exceptionOrNull()
            assertTrue("expected a missing-migration failure, got $failure", failure is IllegalStateException)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val PACKAGE = "com.heytap.health.international"
        const val START = 1_768_516_200_000L
        const val HOUR = 3_600_000L
        const val MINUTE = 60_000L
        const val NAP_START = START + 30 * HOUR
        const val SKIPPED_REASON = "NAP_SKIPPED: nap of 60 min (napMode.outputPolicy = SKIP)"
    }
}

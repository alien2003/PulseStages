package io.github.alien2003.pulsestages.data.local.testutil

import androidx.room.Room
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.PulseStagesDatabase
import io.github.alien2003.pulsestages.data.local.db.SourceOutcome
import io.github.alien2003.pulsestages.data.local.db.SourceSessionEntity
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Base class of the Room tests: a fresh in-memory [PulseStagesDatabase] per test on Robolectric's real (native)
 * SQLite. JUnit 4 (Robolectric's runner) executed by the JUnit Platform through the Vintage engine.
 */
@RunWith(RobolectricTestRunner::class)
abstract class DatabaseTest {
    protected lateinit var db: PulseStagesDatabase

    @Before
    fun openDatabase() {
        db =
            Room
                .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PulseStagesDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }
}

const val HOUR_MS: Long = 3_600_000L

/** A night starting at [startMs] lasting [hours] hours. */
fun night(
    clientRecordId: String,
    startMs: Long,
    hours: Long = 8,
    sourceUuids: List<String> = listOf("$clientRecordId-src"),
    status: NightStatus = NightStatus.WRITTEN,
    algoVersion: Int = 1,
    paramsHash: String = "hash-1",
    nadirBpm: Double? = 50.0,
    hcRecordId: String? = if (status == NightStatus.WRITTEN) "hc-$clientRecordId" else null,
    now: Long = 1_000L,
): EstimatedNightEntity =
    EstimatedNightEntity(
        clientRecordId = clientRecordId,
        sourceUuids = sourceUuids,
        sourcePackage = "com.heytap.health.international",
        sessionStartMs = startMs,
        sessionEndMs = startMs + hours * HOUR_MS,
        startZoneOffsetSec = 10_800,
        endZoneOffsetSec = 10_800,
        hcRecordId = hcRecordId,
        algoVersion = algoVersion,
        paramsHash = paramsHash,
        estimator = EstimatorType.HYBRID,
        coverage = 0.93,
        confidence = 0.61,
        clientRecordVersion = 1000L,
        status = status,
        deepMin = 60.0,
        remMin = 90.0,
        lightMin = 300.0,
        awakeMin = 30.0,
        nadirBpm = nadirBpm,
        hrSamples = 231,
        stages = listOf(StageInterval(startMs, startMs + hours * HOUR_MS, SleepStage.LIGHT)),
        createdAt = now,
        updatedAt = now,
    )

/** A source session. */
fun session(
    uuid: String,
    startMs: Long,
    outcome: SourceOutcome,
    nightClientRecordId: String? = null,
    hours: Long = 8,
): SourceSessionEntity =
    SourceSessionEntity(
        uuid = uuid,
        packageName = "com.heytap.health.international",
        startMs = startMs,
        endMs = startMs + hours * HOUR_MS,
        outcome = outcome,
        nightClientRecordId = nightClientRecordId,
        lastSeenAt = 1_000L,
    )

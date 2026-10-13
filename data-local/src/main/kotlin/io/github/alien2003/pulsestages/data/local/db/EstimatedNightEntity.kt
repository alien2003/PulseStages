package io.github.alien2003.pulsestages.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.model.WarningCode

/**
 * Lifecycle of one output night. "Stale" is not a status: a [WRITTEN] night is stale when its algorithm version or
 * params hash differs from the current ones ([EstimatedNightEntity.isStale]).
 */
enum class NightStatus {
    /** Our session with stages is in Health Connect. */
    WRITTEN,

    /** Not estimated (no heart rate / low coverage / too short); the reason is in `statusReason`. */
    NOT_ESTIMATED,

    /** Estimation or writing failed; the reason is in `statusReason`. */
    FAILED,

    /** Replaced by a merged / changed night; our Health Connect record was deleted. */
    SUPERSEDED,

    /** Our session was deleted by the user (or "delete my data"). */
    DELETED,

    /** Every source session was deleted, so our record was deleted too. */
    SOURCE_DELETED,
}

/**
 * One output night of the ledger (table `estimated_nights`). All times are epoch milliseconds UTC.
 *
 * @property id local row id.
 * @property clientRecordId deterministic Health Connect clientRecordId (`"ps:" + sha256(sorted source uuids)`), unique.
 * @property sourceUuids Health Connect ids of the source sessions this night was built from, sorted (also linked in
 *   `night_sources`).
 * @property sourcePackage package that wrote the source sessions.
 * @property sessionStartMs start of the (merged) source session.
 * @property sessionEndMs end of the (merged) source session.
 * @property startZoneOffsetSec zone offset at the start, seconds.
 * @property endZoneOffsetSec zone offset at the end, seconds.
 * @property hcRecordId Health Connect id of OUR record; non-null exactly while we believe our record exists.
 * @property algoVersion `ALGO_VERSION` used for the estimate.
 * @property paramsHash params hash used for the estimate.
 * @property estimator estimator that produced the stages (or the configured one when nothing was estimated).
 * @property modelId hybrid model id, if a model was used.
 * @property coverage share of session epochs with heart-rate data, 0..1.
 * @property confidence night confidence, 0..1.
 * @property recomputeCount number of rewrites of this clientRecordId (0 for the first write).
 * @property clientRecordVersion clientRecordVersion of the record we wrote.
 * @property status lifecycle status.
 * @property statusReason English reason for NOT_ESTIMATED / FAILED / SUPERSEDED / ..., or null.
 * @property awakeMin minutes written as AWAKE.
 * @property lightMin minutes written as LIGHT.
 * @property deepMin minutes written as DEEP.
 * @property remMin minutes written as REM.
 * @property unknownMin minutes written as UNKNOWN.
 * @property elevatedHr night flag: nadir above the baseline.
 * @property lowConfidence night flag: low confidence.
 * @property calibrated night flag: stage proportions were calibrated.
 * @property rulesFallback night flag: the rules estimator was used because of low coverage.
 * @property warnings estimation warnings (stored as a JSON array of [WarningCode] names; unknown names are dropped).
 * @property nadirBpm lowest smoothed heart rate of the night, if known.
 * @property hrSamples number of heart-rate samples used.
 * @property stages the stages exactly as written (after awakeAsLight mapping), stored compactly as
 *   `[[startMs,endMs,"DEEP"],...]` in column `stagesJson`.
 * @property createdAt when the row was created.
 * @property updatedAt when the row was last changed.
 */
@Suppress("LongParameterList") // Mirrors the table: one constructor parameter per column.
@Entity(
    tableName = "estimated_nights",
    indices = [
        Index(value = ["clientRecordId"], unique = true),
        Index(value = ["sessionStartMs"]),
        Index(value = ["sessionEndMs"]),
    ],
)
data class EstimatedNightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientRecordId: String,
    val sourceUuids: List<String>,
    val sourcePackage: String,
    val sessionStartMs: Long,
    val sessionEndMs: Long,
    val startZoneOffsetSec: Int,
    val endZoneOffsetSec: Int,
    val hcRecordId: String? = null,
    val algoVersion: Int,
    val paramsHash: String,
    val estimator: EstimatorType,
    val modelId: String? = null,
    val coverage: Double = 0.0,
    val confidence: Double = 0.0,
    val recomputeCount: Int = 0,
    val clientRecordVersion: Long = 0,
    val status: NightStatus,
    val statusReason: String? = null,
    val awakeMin: Double = 0.0,
    val lightMin: Double = 0.0,
    val deepMin: Double = 0.0,
    val remMin: Double = 0.0,
    val unknownMin: Double = 0.0,
    val elevatedHr: Boolean = false,
    val lowConfidence: Boolean = false,
    val calibrated: Boolean = false,
    val rulesFallback: Boolean = false,
    val warnings: List<WarningCode> = emptyList(),
    val nadirBpm: Double? = null,
    val hrSamples: Int = 0,
    @ColumnInfo(name = "stagesJson") val stages: List<StageInterval> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** True when this night is [NightStatus.WRITTEN] with a different algorithm version or params hash. */
    fun isStale(
        currentAlgoVersion: Int,
        currentParamsHash: String,
    ): Boolean = isStale(status, algoVersion, paramsHash, currentAlgoVersion, currentParamsHash)
}

/**
 * Lightweight projection of [EstimatedNightEntity] for lists (no stages, warnings or source uuids). Properties have
 * the meaning documented on [EstimatedNightEntity].
 *
 * @property id local row id.
 * @property clientRecordId deterministic clientRecordId.
 * @property sourcePackage source package.
 * @property sessionStartMs session start.
 * @property sessionEndMs session end.
 * @property startZoneOffsetSec zone offset at the start.
 * @property endZoneOffsetSec zone offset at the end.
 * @property status lifecycle status.
 * @property statusReason reason, if any.
 * @property estimator estimator.
 * @property algoVersion algorithm version.
 * @property paramsHash params hash.
 * @property coverage coverage, 0..1.
 * @property confidence confidence, 0..1.
 * @property awakeMin AWAKE minutes.
 * @property lightMin LIGHT minutes.
 * @property deepMin DEEP minutes.
 * @property remMin REM minutes.
 * @property unknownMin UNKNOWN minutes.
 * @property elevatedHr elevated heart rate flag.
 * @property lowConfidence low confidence flag.
 * @property calibrated calibrated flag.
 * @property rulesFallback rules fallback flag.
 * @property updatedAt last change.
 */
@Suppress("LongParameterList") // Projection of table columns.
data class NightSummary(
    val id: Long,
    val clientRecordId: String,
    val sourcePackage: String,
    val sessionStartMs: Long,
    val sessionEndMs: Long,
    val startZoneOffsetSec: Int,
    val endZoneOffsetSec: Int,
    val status: NightStatus,
    val statusReason: String?,
    val estimator: EstimatorType,
    val algoVersion: Int,
    val paramsHash: String,
    val coverage: Double,
    val confidence: Double,
    val awakeMin: Double,
    val lightMin: Double,
    val deepMin: Double,
    val remMin: Double,
    val unknownMin: Double,
    val elevatedHr: Boolean,
    val lowConfidence: Boolean,
    val calibrated: Boolean,
    val rulesFallback: Boolean,
    val updatedAt: Long,
) {
    /** True when this night is [NightStatus.WRITTEN] with a different algorithm version or params hash. */
    fun isStale(
        currentAlgoVersion: Int,
        currentParamsHash: String,
    ): Boolean = isStale(status, algoVersion, paramsHash, currentAlgoVersion, currentParamsHash)
}

private fun isStale(
    status: NightStatus,
    algoVersion: Int,
    paramsHash: String,
    currentAlgoVersion: Int,
    currentParamsHash: String,
): Boolean = status == NightStatus.WRITTEN && (algoVersion != currentAlgoVersion || paramsHash != currentParamsHash)

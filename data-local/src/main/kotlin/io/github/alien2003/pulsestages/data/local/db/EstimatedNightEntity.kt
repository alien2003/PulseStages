package io.github.alien2003.pulsestages.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.model.WarningCode

/**
 * Lifecycle of one output night. "Stale" is not a status: a [WRITTEN] night, or a nap skipped by the nap policy (see
 * [isSkippedNap]), is stale when its algorithm version or params hash differs from the current ones
 * ([EstimatedNightEntity.isStale]).
 */
enum class NightStatus {
    /** Our session with stages is in Health Connect. */
    WRITTEN,

    /** Not estimated (no heart rate / low coverage / too short / a nap skipped by the nap policy); see `statusReason`. */
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
 * @property sessionStartMs start of the (merged) source session (our record starts one second earlier with
 *   `write.startBeforeSource`).
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
 * @property stages the estimated stages after the awakeAsLight mapping, stored compactly as `[[startMs,endMs,"DEEP"],...]`
 *   in column `stagesJson`: exactly what was written, except for a nap written with the SLEEP_WAKE output
 *   ([sleepWakeOnly]), whose LIGHT / DEEP / REM / UNKNOWN were written as Health Connect's generic "sleeping" stage.
 *   Always in the night's times: with `write.startBeforeSource` the written first stage starts one second earlier,
 *   at the record's start.
 * @property createdAt when the row was created.
 * @property updatedAt when the row was last changed.
 * @property mode whether the session was processed as a night or as a nap (`napMode.maxSessionMin` of the settings it
 *   was processed with; schema version 2, NIGHT for the rows of version 1).
 * @property sleepWakeOnly the nap's record holds only AWAKE and SLEEPING stages (`napMode.outputPolicy` SLEEP_WAKE);
 *   [stages] and the minute columns keep the estimated stages (schema version 2).
 * @property inputFingerprint fingerprint of the estimation input that the output was last compared with (schema version
 *   3): for heart rate, steps and each optional channel that was read, the number of samples and a SHA-256 of the
 *   samples sorted canonically (never the samples themselves). Set by every write and by every refresh check of a
 *   written night; null for nights that never had an output and for outputs written before version 3 and not checked
 *   since. A night retired by a status change (deleted, superseded, source deleted) keeps the value of its last output;
 *   one recorded again without an output (not estimated, failed, a skipped nap) has none.
 * @property inputCheckedAt when the input of the output was last read and compared: the write, or a later refresh check
 *   (schema version 3). Unlike [updatedAt] it changes without a rewrite. Null when unknown.
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
    @ColumnInfo(defaultValue = "NIGHT") val mode: SessionMode = SessionMode.NIGHT,
    @ColumnInfo(defaultValue = "0") val sleepWakeOnly: Boolean = false,
    val inputFingerprint: String? = null,
    val inputCheckedAt: Long? = null,
) {
    /** A nap that the nap policy skipped ([isSkippedNap]): its outcome depends on the settings only, not on data. */
    val skippedNap: Boolean get() = isSkippedNap(status, statusReason)

    /**
     * True when this night is [NightStatus.WRITTEN], or a [skippedNap], with a different algorithm version or params
     * hash: processing it again with the current settings may give another result.
     */
    fun isStale(
        currentAlgoVersion: Int,
        currentParamsHash: String,
    ): Boolean = isStale(status, statusReason, sameVersion = algoVersion == currentAlgoVersion && paramsHash == currentParamsHash)

    /** Why the automatic refresh rewrote this night, when that was its last write ([InputUpdate.of]). */
    val inputUpdate: InputUpdate? get() = InputUpdate.of(status, statusReason)
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
 * @property mode night or nap.
 * @property sleepWakeOnly a nap written with only AWAKE and SLEEPING stages.
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
    val mode: SessionMode = SessionMode.NIGHT,
    val sleepWakeOnly: Boolean = false,
) {
    /** A nap that the nap policy skipped ([isSkippedNap]). */
    val skippedNap: Boolean get() = isSkippedNap(status, statusReason)

    /** True when this night is [NightStatus.WRITTEN], or a [skippedNap], with a different algorithm version or params hash. */
    fun isStale(
        currentAlgoVersion: Int,
        currentParamsHash: String,
    ): Boolean = isStale(status, statusReason, sameVersion = algoVersion == currentAlgoVersion && paramsHash == currentParamsHash)
}

/**
 * The start of the `statusReason` of a nap that `napMode.outputPolicy` SKIP left out: the code of
 * `NotEstimatedReason.NAP_SKIPPED` and a colon (the pipeline writes `"NAP_SKIPPED: nap of … min …"`).
 */
const val SKIPPED_NAP_REASON_PREFIX: String = "NAP_SKIPPED:"

/**
 * Whether a night with [status] and [statusReason] is a nap skipped by the nap policy: NOT_ESTIMATED with a reason
 * starting with [SKIPPED_NAP_REASON_PREFIX]. Its outcome depends on the settings only (no data is read for it): it is
 * not retried for late heart rate, it counts as stale when the settings change, and it is no problem in the list.
 */
fun isSkippedNap(
    status: NightStatus,
    statusReason: String?,
): Boolean = status == NightStatus.NOT_ESTIMATED && statusReason?.startsWith(SKIPPED_NAP_REASON_PREFIX) == true

/** What changed in the input of a written night that the automatic refresh rewrote ([InputUpdate]). */
enum class InputChange {
    /**
     * More heart-rate samples than at the previous write (the estimate's sample count, `hrSamples`: the session and its
     * context margin).
     */
    MORE_HEART_RATE,

    /**
     * The heart rate in the read window changed otherwise: samples replaced at the same count, or changes outside the
     * part the estimate counts (fewer samples than at the previous write never rewrite a night).
     */
    HEART_RATE,

    /** The heart rate is unchanged; steps or an optional channel (SpO2, respiratory rate, HRV) changed. */
    OTHER_DATA,
}

/**
 * Why the automatic refresh rewrote a WRITTEN night: its estimation input changed after the previous write
 * ([change]) and the new estimate changed the record. Stored as the night's `statusReason`, which a regular write
 * clears: `"INPUT_UPDATED: MORE_HEART_RATE 46 57 - …"` ([reason], parsed back by [of]).
 *
 * @property change what changed in the input.
 * @property hrSamplesBefore heart-rate samples of the estimate at the previous write (`hrSamples`: the session and its
 *   context margin).
 * @property hrSamplesAfter heart-rate samples of the new estimate.
 */
data class InputUpdate(
    val change: InputChange,
    val hrSamplesBefore: Int,
    val hrSamplesAfter: Int,
) {
    /** The `statusReason` of the rewritten night: the code, the kind, both counts and an English explanation. */
    fun reason(): String = "$INPUT_UPDATED_REASON_PREFIX ${change.name} $hrSamplesBefore $hrSamplesAfter - ${describe()}"

    /** English description for the log, e.g. "more heart rate arrived (46 -> 57 heart-rate samples)". */
    fun describe(): String =
        when (change) {
            InputChange.MORE_HEART_RATE -> "more heart rate arrived ($hrSamplesBefore -> $hrSamplesAfter heart-rate samples)"
            InputChange.HEART_RATE -> "the heart rate changed ($hrSamplesBefore -> $hrSamplesAfter heart-rate samples)"
            InputChange.OTHER_DATA -> "steps or other input data changed"
        }

    /** Parsing. */
    companion object {
        /** The [InputUpdate] of a WRITTEN night whose [statusReason] was written by [reason], or null. */
        fun of(
            status: NightStatus,
            statusReason: String?,
        ): InputUpdate? {
            val parts =
                statusReason
                    ?.takeIf { status == NightStatus.WRITTEN && it.startsWith(INPUT_UPDATED_REASON_PREFIX) }
                    ?.removePrefix(INPUT_UPDATED_REASON_PREFIX)
                    ?.trim()
                    ?.split(' ')
                    .orEmpty()
            val change = InputChange.entries.firstOrNull { it.name == parts.getOrNull(0) }
            val before = parts.getOrNull(1)?.toIntOrNull()
            val after = parts.getOrNull(2)?.toIntOrNull()
            return if (change != null && before != null && after != null) InputUpdate(change, before, after) else null
        }
    }
}

/** The start of the `statusReason` of a night that the automatic refresh rewrote ([InputUpdate.reason]). */
const val INPUT_UPDATED_REASON_PREFIX: String = "INPUT_UPDATED:"

/** A night with [status] and [statusReason] made with another algorithm version or params hash (not [sameVersion]). */
private fun isStale(
    status: NightStatus,
    statusReason: String?,
    sameVersion: Boolean,
): Boolean = (status == NightStatus.WRITTEN || isSkippedNap(status, statusReason)) && !sameVersion

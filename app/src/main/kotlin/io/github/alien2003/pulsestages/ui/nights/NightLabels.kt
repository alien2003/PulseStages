package io.github.alien2003.pulsestages.ui.nights

import androidx.annotation.StringRes
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.local.db.NightStatus

/** Localized name of a sleep stage. */
@StringRes
fun SleepStage.labelRes(): Int =
    when (this) {
        SleepStage.AWAKE -> R.string.stage_awake
        SleepStage.LIGHT -> R.string.stage_light
        SleepStage.DEEP -> R.string.stage_deep
        SleepStage.REM -> R.string.stage_rem
        SleepStage.UNKNOWN -> R.string.stage_unknown
    }

/** Short localized name of a sleep stage (chart row labels and table headers). */
@StringRes
fun SleepStage.shortLabelRes(): Int =
    when (this) {
        SleepStage.AWAKE -> R.string.stage_short_awake
        SleepStage.LIGHT -> R.string.stage_short_light
        SleepStage.DEEP -> R.string.stage_short_deep
        SleepStage.REM -> R.string.stage_short_rem
        SleepStage.UNKNOWN -> R.string.stage_unknown
    }

/** Localized name of a night status. */
@StringRes
fun NightStatus.labelRes(): Int =
    when (this) {
        NightStatus.WRITTEN -> R.string.night_status_written
        NightStatus.NOT_ESTIMATED -> R.string.night_status_not_estimated
        NightStatus.FAILED -> R.string.night_status_failed
        NightStatus.SUPERSEDED -> R.string.night_status_superseded
        NightStatus.DELETED -> R.string.night_status_deleted
        NightStatus.SOURCE_DELETED -> R.string.night_status_source_deleted
    }

/** Localized name of an estimator. */
@StringRes
fun EstimatorType.labelRes(): Int =
    when (this) {
        EstimatorType.RULES -> R.string.estimator_rules
        EstimatorType.HMM -> R.string.estimator_hmm
        EstimatorType.HYBRID -> R.string.estimator_hybrid
    }

/** Localized explanation of an estimation warning. */
@StringRes
fun WarningCode.labelRes(): Int =
    when (this) {
        WarningCode.LOW_HR_RANGE -> R.string.warning_low_hr_range
        WarningCode.ELEVATED_HR -> R.string.warning_elevated_hr
        WarningCode.EM_ORDER_VIOLATION -> R.string.warning_em_order_violation
        WarningCode.SANITY_DEEP_HIGH -> R.string.warning_sanity_deep_high
        WarningCode.SANITY_DEEP_LOW -> R.string.warning_sanity_deep_low
        WarningCode.SANITY_REM_HIGH -> R.string.warning_sanity_rem_high
        WarningCode.PROPORTIONS_CALIBRATED -> R.string.warning_proportions_calibrated
        WarningCode.RULES_FALLBACK_LOW_COVERAGE -> R.string.warning_rules_fallback
        WarningCode.CYCLE_PRIOR_DISABLED -> R.string.warning_cycle_prior_disabled
        WarningCode.HYBRID_MODEL_UNAVAILABLE -> R.string.warning_hybrid_model_unavailable
        WarningCode.DATA_GAPS -> R.string.warning_data_gaps
    }

/** Localized reason why a night was not estimated. */
@StringRes
fun NotEstimatedReason.labelRes(): Int =
    when (this) {
        NotEstimatedReason.NO_HR_DATA -> R.string.not_estimated_no_hr
        NotEstimatedReason.LOW_COVERAGE -> R.string.not_estimated_low_coverage
        NotEstimatedReason.SESSION_TOO_SHORT -> R.string.not_estimated_too_short
    }

/** Localized reason why nothing could be read from Health Connect. */
@StringRes
fun BlockedReason.labelRes(): Int =
    when (this) {
        BlockedReason.HEALTH_CONNECT_UNAVAILABLE -> R.string.error_hc_unavailable
        BlockedReason.PERMISSION_MISSING -> R.string.error_hc_permission
        BlockedReason.BACKGROUND_PERMISSION_MISSING -> R.string.result_blocked_background
    }

/**
 * Minutes of each stage of a night, in display order (deep, REM, light, awake, unknown).
 *
 * @property stage the stage.
 * @property minutes minutes of that stage.
 */
data class StageMinutes(
    val stage: SleepStage,
    val minutes: Double,
)

/** Stage minutes in display order; [unknown] is included only when positive. */
fun stageMinutes(
    deep: Double,
    rem: Double,
    light: Double,
    awake: Double,
    unknown: Double,
): List<StageMinutes> =
    buildList {
        add(StageMinutes(SleepStage.DEEP, deep))
        add(StageMinutes(SleepStage.REM, rem))
        add(StageMinutes(SleepStage.LIGHT, light))
        add(StageMinutes(SleepStage.AWAKE, awake))
        if (unknown > 0.0) add(StageMinutes(SleepStage.UNKNOWN, unknown))
    }

/** Stage minutes of [stages] in display order (UNKNOWN only when present). */
fun stageMinutesOf(stages: List<StageInterval>): List<StageMinutes> {
    fun minutes(stage: SleepStage): Double = stages.filter { it.stage == stage }.sumOf { it.endMs - it.startMs } / MS_PER_MINUTE
    return stageMinutes(
        deep = minutes(SleepStage.DEEP),
        rem = minutes(SleepStage.REM),
        light = minutes(SleepStage.LIGHT),
        awake = minutes(SleepStage.AWAKE),
        unknown = minutes(SleepStage.UNKNOWN),
    )
}

private const val MS_PER_MINUTE = 60_000.0

package io.github.alien2003.pulsestages.ui.nights

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * Pure geometry of the night charts (hypnogram and heart-rate curve), kept free of Compose so it can be tested on
 * the JVM. Both charts of a night share one TimeAxis, so a tap on either selects the same epoch.
 */

/** Rows of the hypnogram from top to bottom: awake on top, deep at the bottom (the usual clinical layout). */
val HYPNOGRAM_ROWS: List<SleepStage> = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)

/** Row of [stage] in the hypnogram (0 = top), or null for [SleepStage.UNKNOWN] (drawn as a gap band). */
fun hypnogramRow(stage: SleepStage): Int? = HYPNOGRAM_ROWS.indexOf(stage).takeIf { it >= 0 }

/** Vertical centre of [row] in a drawing [heightPx] high with [rows] equal rows. */
fun rowCenterY(
    row: Int,
    heightPx: Float,
    rows: Int = HYPNOGRAM_ROWS.size,
): Float = (row + HALF) * heightPx / rows

/**
 * Linear mapping of the night `[startMs, endMs]` to x coordinates `[0, widthPx]`; times outside are clamped.
 *
 * @property startMs left edge.
 * @property endMs right edge (a span below 1 ms is treated as 1 ms).
 * @property widthPx drawing width.
 */
data class TimeAxis(
    val startMs: Long,
    val endMs: Long,
    val widthPx: Float,
) {
    private val spanMs: Double get() = max(1L, endMs - startMs).toDouble()

    /** The x coordinate of [timeMs]. */
    fun x(timeMs: Long): Float = ((timeMs - startMs) / spanMs * widthPx).toFloat().coerceIn(0f, max(widthPx, 0f))

    /** The time at [x] (inverse of [x], clamped to the night). */
    fun time(x: Float): Long {
        if (widthPx <= 0f) return startMs
        val share = (x / widthPx).coerceIn(0f, 1f)
        return startMs + (share * spanMs).roundToLong()
    }
}

/**
 * A rectangle of the hypnogram.
 *
 * @property x0 left edge.
 * @property x1 right edge (at least [MIN_BAR_PX] right of [x0]).
 * @property stage the stage; [SleepStage.UNKNOWN] for gaps (no stage, or written as UNKNOWN).
 * @property row the row of a scored stage, null for gaps.
 */
data class HypnogramBar(
    val x0: Float,
    val x1: Float,
    val stage: SleepStage,
    val row: Int?,
)

/** Narrowest bar drawn, so that one-epoch bouts stay visible on long nights. */
const val MIN_BAR_PX: Float = 1f

/**
 * The bars of [stages] on [axis]: adjacent intervals of the same stage are merged, and every part of the night
 * covered by no interval becomes an UNKNOWN gap bar like the intervals written as UNKNOWN, so gaps are visible.
 */
fun hypnogramBars(
    stages: List<StageInterval>,
    axis: TimeAxis,
): List<HypnogramBar> {
    val filled = withGaps(stages.sortedBy { it.startMs }, axis.startMs, axis.endMs)
    return mergeAdjacent(filled).map { interval ->
        val x0 = axis.x(interval.startMs)
        HypnogramBar(x0, max(axis.x(interval.endMs), x0 + MIN_BAR_PX), interval.stage, hypnogramRow(interval.stage))
    }
}

/** [sorted] clipped to `[startMs, endMs)`, with UNKNOWN intervals filling every uncovered part. */
fun withGaps(
    sorted: List<StageInterval>,
    startMs: Long,
    endMs: Long,
): List<StageInterval> {
    val result = mutableListOf<StageInterval>()
    var cursor = startMs
    sorted.forEach { interval ->
        val from = max(interval.startMs, cursor)
        val to = min(interval.endMs, endMs)
        if (to <= from) return@forEach
        if (from > cursor) result += StageInterval(cursor, from, SleepStage.UNKNOWN)
        result += StageInterval(from, to, interval.stage)
        cursor = to
    }
    if (cursor < endMs) result += StageInterval(cursor, endMs, SleepStage.UNKNOWN)
    return result
}

/** Adjacent intervals of the same stage merged into one. */
fun mergeAdjacent(intervals: List<StageInterval>): List<StageInterval> =
    intervals.fold(mutableListOf()) { merged, interval ->
        val last = merged.lastOrNull()
        if (last != null && last.stage == interval.stage && last.endMs == interval.startMs) {
            merged[merged.lastIndex] = last.copy(endMs = interval.endMs)
        } else {
            merged += interval
        }
        merged
    }

/** The stage of [stages] at [timeMs], or null when no interval covers it. */
fun stageAt(
    stages: List<StageInterval>,
    timeMs: Long,
): SleepStage? = stages.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }?.stage

/**
 * Index of the epoch of [epochStarts] (sorted) that contains [timeMs], each epoch lasting [epochMs]; null when the
 * time is before the first or after the last epoch.
 */
fun epochIndexAt(
    epochStarts: List<Long>,
    epochMs: Long,
    timeMs: Long,
): Int? {
    if (epochStarts.isEmpty() || timeMs < epochStarts.first()) return null
    var low = 0
    var high = epochStarts.lastIndex
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (epochStarts[mid] <= timeMs) low = mid else high = mid - 1
    }
    return low.takeIf { timeMs < epochStarts[low] + epochMs }
}

private const val HALF = 0.5f

package io.github.alien2003.pulsestages.ui.nights

import io.github.alien2003.pulsestages.algo.model.EpochResult
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/*
 * Pure geometry of the heart-rate curve and the time axis of the night charts (no Compose, tested on the JVM); the
 * x coordinates come from the TimeAxis shared with the hypnogram.
 */

/**
 * Value range of the heart-rate curve: the minimum and maximum of every non-null value, widened by [paddingBpm] and
 * rounded outwards to multiples of [stepBpm]; null without values.
 */
fun hrRange(
    values: List<Double?>,
    paddingBpm: Double = 2.0,
    stepBpm: Double = 5.0,
): ClosedFloatingPointRange<Double>? {
    val finite = values.filterNotNull().filter { it.isFinite() }
    if (finite.isEmpty()) return null
    val low = floor((finite.min() - paddingBpm) / stepBpm) * stepBpm
    val high = ceil((finite.max() + paddingBpm) / stepBpm) * stepBpm
    return low..max(high, low + stepBpm)
}

/** The y coordinate of [bpm] in a drawing [heightPx] high showing [range] (higher values further up). */
fun valueY(
    bpm: Double,
    range: ClosedFloatingPointRange<Double>,
    heightPx: Float,
): Float {
    val span = (range.endInclusive - range.start).takeIf { it > 0 } ?: 1.0
    return (heightPx * (1 - (bpm - range.start) / span)).toFloat().coerceIn(0f, heightPx)
}

/**
 * Points of a curve over the epochs, one per epoch midpoint; runs of epochs without a value (null or not finite) split
 * the curve, so gaps stay visible.
 *
 * @param epochs the epochs.
 * @param epochMs epoch length.
 * @param value the plotted value of an epoch.
 * @return the polylines as (time, value) points.
 */
fun curveSegments(
    epochs: List<EpochResult>,
    epochMs: Long,
    value: (EpochResult) -> Double?,
): List<List<Pair<Long, Double>>> {
    val segments = mutableListOf<List<Pair<Long, Double>>>()
    var current = mutableListOf<Pair<Long, Double>>()
    epochs.forEach { epoch ->
        val v = value(epoch)?.takeIf { it.isFinite() }
        if (v == null) {
            if (current.isNotEmpty()) segments += current
            current = mutableListOf()
        } else {
            current += (epoch.startMs + epochMs / 2) to v
        }
    }
    if (current.isNotEmpty()) segments += current
    return segments
}

/** Full local hours (epoch ms) strictly inside `(startMs, endMs)` for the zone offset [offsetSec]. */
fun hourTicks(
    startMs: Long,
    endMs: Long,
    offsetSec: Int,
): List<Long> {
    val offsetMs = offsetSec * MILLIS_PER_SECOND
    val firstLocalHour = Math.floorDiv(startMs + offsetMs, MILLIS_PER_HOUR) + 1
    return generateSequence(firstLocalHour * MILLIS_PER_HOUR - offsetMs) { it + MILLIS_PER_HOUR }
        .takeWhile { it < endMs }
        .toList()
}

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_HOUR = 3_600_000L

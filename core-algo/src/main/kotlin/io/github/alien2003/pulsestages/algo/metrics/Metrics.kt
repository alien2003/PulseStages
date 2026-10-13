package io.github.alien2003.pulsestages.algo.metrics

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import kotlin.math.abs

/**
 * Minutes per scored stage.
 *
 * @property awake minutes of AWAKE.
 * @property light minutes of LIGHT.
 * @property deep minutes of DEEP.
 * @property rem minutes of REM.
 */
data class StageMinutes(
    val awake: Double,
    val light: Double,
    val deep: Double,
    val rem: Double,
) {
    /** Minutes of [stage] (0 for UNKNOWN). */
    operator fun get(stage: SleepStage): Double =
        when (stage) {
            SleepStage.AWAKE -> awake
            SleepStage.LIGHT -> light
            SleepStage.DEEP -> deep
            SleepStage.REM -> rem
            SleepStage.UNKNOWN -> 0.0
        }

    /** Constructors. */
    companion object {
        /** From a vector in [SleepStage.index] order. */
        fun of(values: DoubleArray): StageMinutes =
            StageMinutes(
                awake = values[SleepStage.AWAKE.index],
                light = values[SleepStage.LIGHT.index],
                deep = values[SleepStage.DEEP.index],
                rem = values[SleepStage.REM.index],
            )
    }
}

/**
 * Per-night absolute minute errors `|estimate − truth|`.
 *
 * @property deep error of the DEEP minutes.
 * @property rem error of the REM minutes.
 * @property awake error of the AWAKE minutes.
 */
data class StageMinutesError(
    val deep: Double,
    val rem: Double,
    val awake: Double,
) {
    /** Constructors. */
    companion object {
        /** The errors between [truth] and [predicted]. */
        fun between(
            truth: StageMinutes,
            predicted: StageMinutes,
        ): StageMinutesError =
            StageMinutesError(
                deep = abs(predicted.deep - truth.deep),
                rem = abs(predicted.rem - truth.rem),
                awake = abs(predicted.awake - truth.awake),
            )
    }
}

/** Agreement metrics between hypnograms. */
object Metrics {
    private const val MS_PER_MINUTE = 60_000.0

    /**
     * Confusion matrix of two aligned label sequences (same length); pairs with UNKNOWN on either side are skipped
     * and counted in [ConfusionMatrix.skipped].
     */
    fun confusion(
        truth: List<SleepStage>,
        predicted: List<SleepStage>,
    ): ConfusionMatrix {
        require(truth.size == predicted.size) { "sequences differ in length: ${truth.size} vs ${predicted.size}" }
        val counts = Array(SleepStage.COUNT) { LongArray(SleepStage.COUNT) }
        var skipped = 0L
        for (i in truth.indices) {
            if (truth[i] == SleepStage.UNKNOWN || predicted[i] == SleepStage.UNKNOWN) {
                skipped++
            } else {
                counts[truth[i].index][predicted[i].index]++
            }
        }
        return ConfusionMatrix(counts, skipped)
    }

    /** Cohen's kappa of two aligned label sequences (see [ConfusionMatrix.kappa]). */
    fun cohenKappa(
        truth: List<SleepStage>,
        predicted: List<SleepStage>,
    ): Double = confusion(truth, predicted).kappa

    /** Total minutes per stage of [intervals] (UNKNOWN ignored). */
    fun stageMinutes(intervals: List<StageInterval>): StageMinutes {
        val minutes = DoubleArray(SleepStage.COUNT)
        for (interval in intervals) {
            if (interval.stage != SleepStage.UNKNOWN) minutes[interval.stage.index] += (interval.endMs - interval.startMs) / MS_PER_MINUTE
        }
        return StageMinutes.of(minutes)
    }
}

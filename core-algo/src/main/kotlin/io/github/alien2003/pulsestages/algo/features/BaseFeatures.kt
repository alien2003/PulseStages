package io.github.alien2003.pulsestages.algo.features

import io.github.alien2003.pulsestages.algo.preprocess.EpochGrid

/**
 * Heart-rate derived features (position-indexed, NaN where undefined).
 *
 * @property hrNorm `(hrSmooth − P10) / denominator` (see [NightStatistics.normDenominator]).
 * @property hrVar std of the unsmoothed series in a centred window, divided by its median over the session.
 * @property hrSlope least-squares slope of the smoothed series, bpm per minute.
 */
class HeartRateFeatures(
    val hrNorm: DoubleArray,
    val hrVar: DoubleArray,
    val hrSlope: DoubleArray,
)

/**
 * Ultradian-cycle features.
 *
 * @property minima positions of the detected heart-rate minima (ascending).
 * @property phase position between consecutive minima in `[0, 1)`; NaN outside the first/last minimum or when
 *   fewer than two minima were found.
 */
class CycleFeatures(
    val minima: IntArray,
    val phase: DoubleArray,
) {
    /** Whether at least two minima were found, i.e. the cycle prior can be used. */
    val available: Boolean get() = minima.size >= 2
}

/**
 * Movement and externally forced wake.
 *
 * @property steps steps per epoch (sample counts spread uniformly over their interval).
 * @property forcedAwake epochs whose midpoint lies inside one of [io.github.alien2003.pulsestages.algo.model.NightInput.forcedAwake].
 */
class ActivityFeatures(
    val steps: DoubleArray,
    val forcedAwake: BooleanArray,
)

/**
 * Optional channels; arrays are all-NaN when the channel is absent or disabled.
 *
 * @property spo2Drop rolling SpO2 median minus the epoch's SpO2 (percentage points).
 * @property hrvVar `median_night(RMSSD) / RMSSD` of the nearest HRV sample (high = REM/AWAKE-like, as hrVar).
 * @property respVar std of the respiratory rate in a centred window divided by its session median.
 */
class OptionalChannelFeatures(
    val spo2Drop: DoubleArray,
    val hrvVar: DoubleArray,
    val respVar: DoubleArray,
)

/**
 * Per-epoch features of one night over all grid positions (session + context); see section 2 of the
 * specification. Arrays must not be modified.
 *
 * @property grid the epoch grid.
 * @property heartRate hrNorm, hrVar and hrSlope.
 * @property t fraction of the session elapsed at the epoch midpoint, clipped to `[0, 1]`.
 * @property cycle ultradian-cycle minima and phase.
 * @property activity steps and forced-awake epochs.
 * @property optional SpO2, HRV and respiratory-rate channels.
 */
class BaseFeatures(
    val grid: EpochGrid,
    val heartRate: HeartRateFeatures,
    val t: DoubleArray,
    val cycle: CycleFeatures,
    val activity: ActivityFeatures,
    val optional: OptionalChannelFeatures,
) {
    /** Feature values at [position] in [NAMES] order (booleans as 0/1, NaN where undefined). */
    fun valuesAt(position: Int): List<Double> =
        listOf(
            heartRate.hrNorm[position],
            heartRate.hrVar[position],
            heartRate.hrSlope[position],
            t[position],
            cycle.phase[position],
            activity.steps[position],
            if (activity.forcedAwake[position]) 1.0 else 0.0,
            optional.spo2Drop[position],
            optional.hrvVar[position],
            optional.respVar[position],
        )

    /** Feature names. */
    companion object {
        /**
         * Names of the values returned by [valuesAt], used as CSV columns and as
         * [io.github.alien2003.pulsestages.algo.model.NightEstimate.featureNames].
         */
        val NAMES: List<String> =
            listOf("hrNorm", "hrVar", "hrSlope", "t", "cyclePhase", "steps", "forcedAwake", "spo2Drop", "hrvVar", "respVar")
    }
}

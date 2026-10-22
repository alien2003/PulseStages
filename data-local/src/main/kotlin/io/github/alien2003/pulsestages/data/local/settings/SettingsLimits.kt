// This file is the single place where the numeric limits of the settings are defined (validator and UI bounds).
@file:Suppress("MagicNumber")

package io.github.alien2003.pulsestages.data.local.settings

/**
 * Inclusive limits enforced by [SettingsValidator]. The UI uses the same values as input bounds, so a value that
 * can be entered can always be saved.
 */
object SettingsLimits {
    /** `sources.minSessionMin`, minutes. */
    val minSessionMin: IntRange = 10..720

    /** `sources.mergeGapMin`, minutes. */
    val mergeGapMin: IntRange = 0..240

    /** `sources.continuationGapMin`, minutes (0 = off). */
    val continuationGapMin: IntRange = 0..240

    /** `sources.backfillDays` (0 = all available history). */
    val backfillDays: IntRange = 0..36_500

    /** `schedule.dailyTimeMinutes`, minutes after local midnight. */
    val dailyTimeMinutes: IntRange = 0..1439

    /**
     * Allowed `schedule.periodicMinutes` values, in display order: 15 and 30 minutes (WorkManager's minimum period is 15
     * minutes) and every whole hour up to 24 (each value the hour-based setting allowed).
     */
    val periodicMinutes: Set<Int> = setOf(15, 30) + (1..24).map { it * 60 }

    /** Maximum length of `write.titleTemplate` (Health Connect limits titles to 1000 characters). */
    const val TITLE_TEMPLATE_MAX_LENGTH: Int = 1000

    /** Allowed `algo.preprocess.epochSec` values: each divides 3600 and is at least 30 s. */
    val epochSec: Set<Int> = setOf(30, 60, 120)

    /** `algo.preprocess.maxInterpGapMin`, minutes. */
    val maxInterpGapMin: ClosedFloatingPointRange<Double> = 0.0..60.0

    /** `algo.preprocess.smoothWindowEpochs` (odd). */
    val smoothWindowEpochs: IntRange = 1..31

    /** `algo.preprocess.medianWindowEpochs` (odd). */
    val medianWindowEpochs: IntRange = 1..31

    /** `algo.preprocess.hampelWindow`, samples (odd). */
    val hampelWindow: IntRange = 3..31

    /** `algo.preprocess.contextMarginMin`, minutes. */
    val contextMarginMin: IntRange = 0..240

    /** Any share / probability / coverage value. */
    val unitInterval: ClosedFloatingPointRange<Double> = 0.0..1.0

    /** `algo.features.normLowPercentile` and `normHighPercentile`. */
    val percentile: ClosedFloatingPointRange<Double> = 0.0..100.0

    /** `algo.features.varHalfWindowEpochs` and `slopeHalfWindowEpochs`. */
    val halfWindowEpochs: IntRange = 1..60

    /** `algo.features.baselineNights` (a baseline needs at least 3 nights). */
    val baselineNights: IntRange = 3..365

    /** `algo.napMode.maxSessionMin`, minutes (0 = no naps, every session is a night; 12 h like [minSessionMin]). */
    val napMaxSessionMin: ClosedFloatingPointRange<Double> = 0.0..720.0

    /** `algo.hmm.emIterations` and `algo.post.calibrationMaxIterations`. */
    val iterations: IntRange = 0..50

    /** `algo.hmm.awakeStepThreshold`, steps per epoch. */
    val awakeStepThreshold: IntRange = 1..10_000

    /** `algo.hybrid.maxBoutMin`, minutes. */
    val maxBoutMin: IntRange = 10..600

    /** `algo.personal.emEveryNights`. */
    val emEveryNights: IntRange = 1..3650
}

package io.github.alien2003.pulsestages.algo.post

import io.github.alien2003.pulsestages.algo.model.EstimationWarning
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.params.PostprocessParams
import io.github.alien2003.pulsestages.algo.preprocess.EpochGrid
import java.util.Locale

/**
 * Labels (stage indices) and posteriors `[epoch][state]` of a decoder over the session epochs.
 */
internal class Decoding(
    val labels: IntArray,
    val posteriors: Array<DoubleArray>,
)

/** Decodes the night with additive per-state log-prior offsets (all zero for the normal decode). */
internal fun interface EpochDecoder {
    /** Decodes with [logPriorOffsets] (one per scored stage, [SleepStage.index] order). */
    fun decode(logPriorOffsets: DoubleArray): Decoding
}

/**
 * Night facts needed by the post-processing.
 *
 * @property hasData session epochs with heart-rate data.
 * @property forced session epochs forced to AWAKE (never relabelled).
 * @property checkProportions run the sanity check and the proportion calibration (false for sessions shorter than
 *   `post.proportionsMinSessionMin`: whole-night stage shares do not describe a nap).
 */
internal class PostprocessContext(
    val grid: EpochGrid,
    val hasData: BooleanArray,
    val forced: BooleanArray,
    val coverage: Double,
    val checkProportions: Boolean = true,
)

/**
 * Final labels of the post-processing.
 *
 * @property stages one label per session epoch (UNKNOWN only from the gap policy).
 * @property posteriors posteriors of the decoding that produced [stages].
 * @property intervals merged hypnogram covering exactly the session.
 * @property sanityFailed the sanity check flagged implausible proportions.
 * @property calibrated proportion calibration changed the labels (a calibration that changes nothing is discarded).
 */
internal class PostprocessResult(
    val stages: Array<SleepStage>,
    val posteriors: Array<DoubleArray>,
    val intervals: List<StageInterval>,
    val warnings: List<EstimationWarning>,
    val sanityFailed: Boolean,
    val calibrated: Boolean,
)

/**
 * Post-processing shared by all estimators (section 4 of the specification), in this order: (1) low-confidence
 * policy, (2) gap policy, (3) minimum bouts, (4) sanity check, (5) optional proportion calibration with re-decoding
 * (re-running 1–4), (6) merging into intervals. Proportions are shares of scored (non-UNKNOWN) time; (4) and (5) run
 * only when [PostprocessContext.checkProportions] (not for naps).
 *
 * The minimum bouts run after the gap policy (the design notes listed them before it) so that the minimum lengths of
 * the specification hold in the output: the gap policy relabels the no-data part of a decoded bout, and the remaining
 * part could otherwise be shorter than the minimum.
 */
internal object Postprocessor {
    private class Labelled(
        val decoding: Decoding,
        val stages: Array<SleepStage>,
        val shares: StageShares,
        val sanity: List<EstimationWarning>,
    )

    /** Runs the whole post-processing with [decoder]. */
    fun run(
        decoder: EpochDecoder,
        context: PostprocessContext,
        params: PostprocessParams,
    ): PostprocessResult {
        var offsets = DoubleArray(SleepStage.COUNT)
        val initial = label(decoder.decode(offsets), context, params)
        var current = initial
        var iterations = 0
        if (params.calibrateProportions && context.checkProportions) {
            while (iterations < params.calibrationMaxIterations && current.shares.hasScoredTime) {
                val next = calibrationStep(offsets, current.shares, params) ?: break
                offsets = next
                current = label(decoder.decode(offsets), context, params)
                iterations++
            }
        }
        val calibrated = iterations > 0 && !current.stages.contentEquals(initial.stages)
        // A calibration that changed no label is discarded entirely (labels, posteriors and sanity of the plain decode).
        val kept = if (calibrated) current else initial
        val warnings = kept.sanity.toMutableList()
        if (calibrated) {
            val detail =
                "iterations=$iterations offsets=" +
                    offsets.joinToString(prefix = "[", postfix = "]") { String.format(Locale.ROOT, "%.2f", it) } +
                    " shares=${current.shares}"
            warnings += EstimationWarning(WarningCode.PROPORTIONS_CALIBRATED, detail)
        }
        return PostprocessResult(
            stages = kept.stages,
            posteriors = kept.decoding.posteriors,
            intervals = IntervalMerger.merge(kept.stages, context.grid),
            warnings = warnings,
            sanityFailed = kept.sanity.isNotEmpty(),
            calibrated = calibrated,
        )
    }

    /** Steps 1–4 on one decoding. */
    private fun label(
        decoding: Decoding,
        context: PostprocessContext,
        params: PostprocessParams,
    ): Labelled {
        val grid = context.grid
        var stages = Array(decoding.labels.size) { SleepStage.ofIndex(decoding.labels[it]) }
        stages = LabelRules.lowConfidence(stages, decoding.posteriors, context.forced, params)
        stages = LabelRules.gapPolicy(stages, context.hasData, context.forced, params.gapPolicy)
        stages = LabelRules.absorbShortBouts(stages, decoding.posteriors, context.forced, grid.epochMin, params)
        val lastIsPartial = grid.clippedDurationMs(grid.sessionEpochs - 1) < grid.epochMs
        stages = LabelRules.mergePartialLastEpoch(stages, lastIsPartial)
        val shares = StageShares.of(stages, grid)
        val sanity = if (context.checkProportions) SanityCheck.check(shares, context.coverage, params) else emptyList()
        return Labelled(decoding, stages, shares, sanity)
    }

    /**
     * Next offsets moving the shares towards the target corridors (DEEP and REM both ways, AWAKE down only), or
     * null when every share is already inside its corridor.
     */
    fun calibrationStep(
        offsets: DoubleArray,
        shares: StageShares,
        params: PostprocessParams,
    ): DoubleArray? {
        val next = offsets.copyOf()
        val step = params.calibrationStep
        val deep = shares[SleepStage.DEEP]
        val rem = shares[SleepStage.REM]
        if (deep < params.targetDeepMin) next[SleepStage.DEEP.index] += step
        if (deep > params.targetDeepMax) next[SleepStage.DEEP.index] -= step
        if (rem < params.targetRemMin) next[SleepStage.REM.index] += step
        if (rem > params.targetRemMax) next[SleepStage.REM.index] -= step
        if (shares[SleepStage.AWAKE] > params.targetAwakeMax) next[SleepStage.AWAKE.index] -= step
        return if (next.contentEquals(offsets)) null else next
    }
}

/** Sanity check of the final proportions (step 4). */
internal object SanityCheck {
    /** Warnings for implausible shares; none when [coverage] ≤ sanityMinCoverage or nothing is scored. */
    fun check(
        shares: StageShares,
        coverage: Double,
        params: PostprocessParams,
    ): List<EstimationWarning> {
        if (coverage <= params.sanityMinCoverage || !shares.hasScoredTime) return emptyList()
        val deep = shares[SleepStage.DEEP]
        val rem = shares[SleepStage.REM]
        return buildList {
            if (deep >
                params.sanityDeepMax
            ) {
                add(EstimationWarning(WarningCode.SANITY_DEEP_HIGH, "DEEP share ${pct(deep)} > ${pct(params.sanityDeepMax)}"))
            }
            if (rem >
                params.sanityRemMax
            ) {
                add(EstimationWarning(WarningCode.SANITY_REM_HIGH, "REM share ${pct(rem)} > ${pct(params.sanityRemMax)}"))
            }
            if (deep <
                params.sanityDeepMin
            ) {
                add(EstimationWarning(WarningCode.SANITY_DEEP_LOW, "DEEP share ${pct(deep)} < ${pct(params.sanityDeepMin)}"))
            }
        }
    }

    private fun pct(share: Double): String = String.format(Locale.ROOT, "%.1f %%", share * PERCENT)

    private const val PERCENT = 100.0
}

/** Merging of per-epoch labels into the output hypnogram (step 6). */
internal object IntervalMerger {
    /**
     * Merges runs of equal labels into intervals on the epoch grid; the last interval ends exactly at the session
     * end, so the intervals are sorted, contiguous and cover `[sessionStart, sessionEnd)` exactly.
     */
    fun merge(
        stages: Array<SleepStage>,
        grid: EpochGrid,
    ): List<StageInterval> =
        LabelRules.bouts(stages).map { bout ->
            StageInterval(grid.startMs(bout.from), grid.clippedEndMs(bout.to), bout.stage)
        }
}

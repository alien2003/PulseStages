package io.github.alien2003.pulsestages.algo.metrics

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval

/**
 * One compared piece of time.
 *
 * @property startMs chunk start (inside a truth interval).
 * @property endMs chunk end (exclusive).
 * @property truth stage of the truth interval.
 * @property predicted predicted stage at the chunk midpoint (UNKNOWN if no predicted interval covers it).
 */
data class ScoredChunk(
    val startMs: Long,
    val endMs: Long,
    val truth: SleepStage,
    val predicted: SleepStage,
)

/**
 * Scores of one night.
 *
 * @property confusion chunk-level confusion matrix (UNKNOWN pairs skipped).
 * @property truthMinutes minutes per stage of the truth over the chunks scored on both sides.
 * @property predictedMinutes minutes per stage of the prediction over the same chunks.
 */
data class NightScore(
    val confusion: ConfusionMatrix,
    val truthMinutes: StageMinutes,
    val predictedMinutes: StageMinutes,
) {
    /** Absolute minute errors of DEEP, REM and AWAKE. */
    val minutesError: StageMinutesError get() = StageMinutesError.between(truthMinutes, predictedMinutes)
}

/**
 * Interval-vs-interval comparison used by algo-cli and the comparison screen: every truth interval is split into
 * chunks of [DEFAULT_CHUNK_MS] (the last chunk of an interval may be shorter) and the prediction is sampled at each
 * chunk midpoint.
 */
object IntervalScoring {
    /** 30 s, the PSG scoring epoch. */
    const val DEFAULT_CHUNK_MS: Long = 30_000L

    private const val MS_PER_MINUTE = 60_000.0

    /** The compared chunks, in time order. [predicted] must be sorted and non-overlapping. */
    fun chunks(
        truth: List<StageInterval>,
        predicted: List<StageInterval>,
        chunkMs: Long = DEFAULT_CHUNK_MS,
    ): List<ScoredChunk> {
        require(chunkMs > 0) { "chunkMs must be positive" }
        val starts = LongArray(predicted.size) { predicted[it].startMs }
        val out = ArrayList<ScoredChunk>()
        for (interval in truth.sortedBy { it.startMs }) {
            var from = interval.startMs
            while (from < interval.endMs) {
                val to = minOf(from + chunkMs, interval.endMs)
                out += ScoredChunk(from, to, interval.stage, stageAt(predicted, starts, from + (to - from) / 2))
                from = to
            }
        }
        return out
    }

    /** Confusion matrix and stage minutes of [predicted] against [truth]. */
    fun score(
        truth: List<StageInterval>,
        predicted: List<StageInterval>,
        chunkMs: Long = DEFAULT_CHUNK_MS,
    ): NightScore {
        val chunks = chunks(truth, predicted, chunkMs)
        val confusion = Metrics.confusion(chunks.map { it.truth }, chunks.map { it.predicted })
        val truthMinutes = DoubleArray(SleepStage.COUNT)
        val predictedMinutes = DoubleArray(SleepStage.COUNT)
        for (chunk in chunks) {
            if (chunk.truth != SleepStage.UNKNOWN && chunk.predicted != SleepStage.UNKNOWN) {
                val minutes = (chunk.endMs - chunk.startMs) / MS_PER_MINUTE
                truthMinutes[chunk.truth.index] += minutes
                predictedMinutes[chunk.predicted.index] += minutes
            }
        }
        return NightScore(confusion, StageMinutes.of(truthMinutes), StageMinutes.of(predictedMinutes))
    }

    private fun stageAt(
        intervals: List<StageInterval>,
        starts: LongArray,
        timeMs: Long,
    ): SleepStage {
        val found = starts.binarySearch(timeMs)
        val index = if (found >= 0) found else -found - 2
        val interval = intervals.getOrNull(index)
        return if (interval != null && timeMs < interval.endMs) interval.stage else SleepStage.UNKNOWN
    }
}

package io.github.alien2003.pulsestages.data.hc.fake

import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightFlags
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.data.hc.pipeline.NightEstimator
import java.security.MessageDigest

/**
 * A small deterministic [NightEstimator] for pipeline tests (not an algorithm): epochs of `epochSec` on the session
 * grid; an epoch has data when a heart-rate sample lies within half of `maxInterpGapMin` of it; forced-awake epochs
 * are AWAKE, epochs without data UNKNOWN, otherwise the mean bpm decides (< 52 DEEP, < 58 LIGHT, < 66 REM, else
 * AWAKE). Coverage below `rulesMinCoverage` → NotEstimated(LOW_COVERAGE); no samples → NotEstimated(NO_HR_DATA).
 * The output obeys the core-algo contract: sorted, contiguous, exactly the session, every interval ≥ one epoch
 * (a final partial epoch joins the previous interval).
 */
class FakeNightEstimator(
    override val modelId: String? = "fake-model",
) : NightEstimator {
    /** Every input received, in order. */
    val inputs: MutableList<NightInput> = mutableListOf()

    /** Throws the returned error for an input (per-night failure injection). */
    var failWhen: (NightInput) -> Throwable? = { null }

    /** Runs before every estimate (e.g. to suspend, to observe concurrency or to cancel something mid-run). */
    var beforeEstimate: suspend (NightInput) -> Unit = {}

    /** Replaces the computed stages (e.g. to produce an invalid hypnogram). */
    var stagesOverride: ((NightInput, List<StageInterval>) -> List<StageInterval>)? = null

    /** Estimator type reported in the estimate. */
    var reportedType: EstimatorType = EstimatorType.HYBRID

    override suspend fun estimate(
        input: NightInput,
        params: AlgoParams,
    ): EstimationOutcome {
        inputs += input
        beforeEstimate(input)
        failWhen(input)?.let { throw it }
        val inSession = input.heartRate.filter { it.timeMs in input.sessionStartMs until input.sessionEndMs }
        val epochMs = params.preprocess.epochSec * SECOND_MS
        val (labels, coverage) = if (inSession.isEmpty()) emptyList<SleepStage>() to 0.0 else label(input, params, epochMs)
        return when {
            inSession.isEmpty() -> {
                EstimationOutcome.NotEstimated(NotEstimatedReason.NO_HR_DATA, 0.0, "no heart-rate samples in the session")
            }

            coverage < params.preprocess.rulesMinCoverage -> {
                EstimationOutcome.NotEstimated(
                    NotEstimatedReason.LOW_COVERAGE,
                    coverage,
                    "coverage $coverage below ${params.preprocess.rulesMinCoverage}",
                )
            }

            else -> {
                val computed = intervals(labels, input.sessionStartMs, input.sessionEndMs, epochMs)
                val stages = stagesOverride?.invoke(input, computed) ?: computed
                EstimationOutcome.Estimated(
                    NightEstimate(
                        stages = stages,
                        coverage = coverage,
                        confidence = CONFIDENCE_BASE + CONFIDENCE_SLOPE * coverage,
                        perEpoch = emptyList(),
                        params = params,
                        algoVersion = ALGO_VERSION,
                        paramsHash = paramsHash(params, ""),
                        estimator = reportedType,
                        warnings = emptyList(),
                        flags = NightFlags(),
                        featureNames = emptyList(),
                        nadirBpm = inSession.minOf { it.bpm },
                        hrSampleCount = inSession.size,
                    ),
                )
            }
        }
    }

    /** Per-epoch labels of the session and the share of epochs with data. */
    private fun label(
        input: NightInput,
        params: AlgoParams,
        epochMs: Long,
    ): Pair<List<SleepStage>, Double> {
        val reachMs = (params.preprocess.maxInterpGapMin * MINUTE_MS / 2).toLong()
        val epochs = ((input.sessionEndMs - input.sessionStartMs + epochMs - 1) / epochMs).toInt()
        var withData = 0
        val labels =
            (0 until epochs).map { k ->
                val from = input.sessionStartMs + k * epochMs
                val to = minOf(from + epochMs, input.sessionEndMs)
                val mid = (from + to) / 2
                val near = input.heartRate.filter { it.timeMs in (from - reachMs)..(to + reachMs) }
                if (near.isNotEmpty()) withData++
                when {
                    input.forcedAwake.any { mid >= it.startMs && mid < it.endMs } -> SleepStage.AWAKE
                    near.isEmpty() -> SleepStage.UNKNOWN
                    else -> stageOf(near.map { it.bpm }.average())
                }
            }
        return labels to withData.toDouble() / epochs
    }

    override fun paramsHash(
        params: AlgoParams,
        extra: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$params|model=$modelId|$extra".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') }
    }

    private fun stageOf(bpm: Double): SleepStage =
        when {
            bpm < DEEP_BELOW -> SleepStage.DEEP
            bpm < LIGHT_BELOW -> SleepStage.LIGHT
            bpm < REM_BELOW -> SleepStage.REM
            else -> SleepStage.AWAKE
        }

    private fun intervals(
        labels: List<SleepStage>,
        startMs: Long,
        endMs: Long,
        epochMs: Long,
    ): List<StageInterval> {
        val result = mutableListOf<StageInterval>()
        labels.forEachIndexed { k, stage ->
            val from = startMs + k * epochMs
            val to = minOf(from + epochMs, endMs)
            val last = result.lastOrNull()
            result +=
                if (last != null && last.stage == stage) {
                    result.removeAt(result.lastIndex)
                    last.copy(endMs = to)
                } else {
                    StageInterval(from, to, stage)
                }
        }
        val last = result.last()
        if (result.size > 1 && last.endMs - last.startMs < epochMs) {
            result.removeAt(result.lastIndex)
            result[result.lastIndex] = result.last().copy(endMs = last.endMs)
        }
        return result
    }

    private companion object {
        const val SECOND_MS = 1_000L
        const val MINUTE_MS = 60_000.0
        const val DEEP_BELOW = 52.0
        const val LIGHT_BELOW = 58.0
        const val REM_BELOW = 66.0
        const val CONFIDENCE_BASE = 0.2
        const val CONFIDENCE_SLOPE = 0.6
        const val BYTE_MASK = 0xff
        const val HEX_RADIX = 16
    }
}

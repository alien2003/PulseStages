package io.github.alien2003.pulsestages.data.hc.fake

import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.algo.model.EpochResult
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightFlags
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.model.StageProbabilities
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.data.hc.pipeline.NightEstimator
import java.security.MessageDigest

/**
 * A small deterministic [NightEstimator] for pipeline tests (not an algorithm): epochs of `epochSec` on the session
 * grid; an epoch has data when a heart-rate sample lies within half of `maxInterpGapMin` of it; forced-awake epochs
 * are AWAKE, epochs without data UNKNOWN, otherwise the mean bpm decides (< 52 DEEP, < 58 LIGHT, < 66 REM, else
 * AWAKE). Coverage below `rulesMinCoverage` → NotEstimated(LOW_COVERAGE); no samples → NotEstimated(NO_HR_DATA).
 * The output obeys the core-algo contract: sorted, contiguous, exactly the session, every interval ≥ one epoch
 * (a final partial epoch joins the previous interval). Every estimate carries per-epoch results (mean bpm as the
 * single feature `meanBpm`, a posterior of 0.7 on the chosen stage). [estimateWith] skips the coverage gates; for
 * [EstimatorType.RULES] every threshold is [RULES_SHIFT_BPM] higher, so RULES disagrees with the default on some
 * epochs. Modes as in core-algo: the estimate carries the session's mode (`params.modeOf`), a nap under
 * `napMode.outputPolicy` SKIP is NotEstimated(NAP_SKIPPED), and [reportedType] is reported for nights and naps alike.
 */
class FakeNightEstimator(
    override val modelId: String? = "fake-model",
) : NightEstimator {
    /** Every input received by [estimate], in order. */
    val inputs: MutableList<NightInput> = mutableListOf()

    /** Throws the returned error for an input (per-night failure injection). */
    var failWhen: (NightInput) -> Throwable? = { null }

    /** Runs before every estimate (e.g. to suspend, to observe concurrency or to cancel something mid-run). */
    var beforeEstimate: suspend (NightInput) -> Unit = {}

    /** Replaces the computed stages (e.g. to produce an invalid hypnogram). */
    var stagesOverride: ((NightInput, List<StageInterval>) -> List<StageInterval>)? = null

    /** Estimator type reported in the estimate. */
    var reportedType: EstimatorType = EstimatorType.HYBRID

    /** Extra text mixed into [paramsHash] (stands in for a personal-model fingerprint). */
    var hashSalt: String = ""

    override suspend fun estimate(
        input: NightInput,
        params: AlgoParams,
    ): EstimationOutcome {
        inputs += input
        beforeEstimate(input)
        failWhen(input)?.let { throw it }
        val inSession = input.heartRate.filter { it.timeMs in input.sessionStartMs until input.sessionEndMs }
        val epochMs = params.preprocess.epochSec * SECOND_MS
        val labelled = if (inSession.isEmpty()) null else label(input, params, epochMs, shiftBpm = 0.0)
        val coverage = labelled?.coverage ?: 0.0
        val nap = params.modeOf(input.sessionEndMs - input.sessionStartMs) == SessionMode.NAP
        return when {
            nap && params.napMode.outputPolicy == NapOutputPolicy.SKIP -> {
                EstimationOutcome.NotEstimated(NotEstimatedReason.NAP_SKIPPED, coverage, "naps are not estimated")
            }

            labelled == null -> {
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
                val computed = intervals(labelled.epochs.map { it.stage }, input.sessionStartMs, input.sessionEndMs, epochMs)
                val stages = stagesOverride?.invoke(input, computed) ?: computed
                EstimationOutcome.Estimated(nightEstimate(input, params, labelled, stages, reportedType))
            }
        }
    }

    override suspend fun estimateWith(
        type: EstimatorType,
        input: NightInput,
        params: AlgoParams,
    ): NightEstimate {
        failWhen(input)?.let { throw it }
        val epochMs = params.preprocess.epochSec * SECOND_MS
        val labelled = label(input, params, epochMs, shiftBpm = if (type == EstimatorType.RULES) RULES_SHIFT_BPM else 0.0)
        val stages = intervals(labelled.epochs.map { it.stage }, input.sessionStartMs, input.sessionEndMs, epochMs)
        return nightEstimate(input, params, labelled, stages, type)
    }

    private suspend fun nightEstimate(
        input: NightInput,
        params: AlgoParams,
        labelled: Labelled,
        stages: List<StageInterval>,
        type: EstimatorType,
    ): NightEstimate {
        val inSession = input.heartRate.filter { it.timeMs in input.sessionStartMs until input.sessionEndMs }
        return NightEstimate(
            stages = stages,
            coverage = labelled.coverage,
            confidence = CONFIDENCE_BASE + CONFIDENCE_SLOPE * labelled.coverage,
            perEpoch = labelled.epochs,
            params = params,
            algoVersion = ALGO_VERSION,
            paramsHash = paramsHash(params, ""),
            estimator = type,
            warnings = emptyList(),
            flags = NightFlags(),
            featureNames = listOf(FEATURE_NAME),
            nadirBpm = inSession.minOfOrNull { it.bpm },
            hrSampleCount = inSession.size,
            mode = params.modeOf(input.sessionEndMs - input.sessionStartMs),
        )
    }

    /** Per-epoch results of the session and the share of epochs with data. */
    private class Labelled(
        val epochs: List<EpochResult>,
        val coverage: Double,
    )

    private fun label(
        input: NightInput,
        params: AlgoParams,
        epochMs: Long,
        shiftBpm: Double,
    ): Labelled {
        val reachMs = (params.preprocess.maxInterpGapMin * MINUTE_MS / 2).toLong()
        val count = ((input.sessionEndMs - input.sessionStartMs + epochMs - 1) / epochMs).toInt()
        var withData = 0
        val epochs =
            (0 until count).map { k ->
                val from = input.sessionStartMs + k * epochMs
                val to = minOf(from + epochMs, input.sessionEndMs)
                val mid = (from + to) / 2
                val near = input.heartRate.filter { it.timeMs in (from - reachMs)..(to + reachMs) }
                if (near.isNotEmpty()) withData++
                val bpm = if (near.isEmpty()) null else near.map { it.bpm }.average()
                val stage =
                    when {
                        input.forcedAwake.any { mid >= it.startMs && mid < it.endMs } -> SleepStage.AWAKE
                        bpm == null -> SleepStage.UNKNOWN
                        else -> stageOf(bpm - shiftBpm)
                    }
                EpochResult(
                    index = k,
                    startMs = from,
                    hasData = bpm != null,
                    hrRaw = bpm,
                    hrSmooth = bpm,
                    features = listOf(bpm ?: Double.NaN),
                    posterior = posteriorOf(stage),
                    stage = stage,
                )
            }
        return Labelled(epochs, if (count == 0) 0.0 else withData.toDouble() / count)
    }

    override suspend fun paramsHash(
        params: AlgoParams,
        extra: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$params|model=$modelId|$hashSalt|$extra".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') }
    }

    private fun stageOf(bpm: Double): SleepStage =
        when {
            bpm < DEEP_BELOW -> SleepStage.DEEP
            bpm < LIGHT_BELOW -> SleepStage.LIGHT
            bpm < REM_BELOW -> SleepStage.REM
            else -> SleepStage.AWAKE
        }

    private fun posteriorOf(stage: SleepStage): StageProbabilities {
        if (stage == SleepStage.UNKNOWN) return StageProbabilities.UNIFORM
        val values = DoubleArray(SleepStage.COUNT) { OTHER_POSTERIOR }
        values[stage.index] = CHOSEN_POSTERIOR
        return StageProbabilities.of(values)
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

    /** Constants. */
    companion object {
        /** How much higher every bpm threshold is for [EstimatorType.RULES] in [estimateWith]. */
        const val RULES_SHIFT_BPM: Double = 3.0

        /** Name of the single per-epoch feature (the mean bpm of the epoch). */
        const val FEATURE_NAME: String = "meanBpm"

        private const val SECOND_MS = 1_000L
        private const val MINUTE_MS = 60_000.0
        private const val DEEP_BELOW = 52.0
        private const val LIGHT_BELOW = 58.0
        private const val REM_BELOW = 66.0
        private const val CONFIDENCE_BASE = 0.2
        private const val CONFIDENCE_SLOPE = 0.6
        private const val CHOSEN_POSTERIOR = 0.7
        private const val OTHER_POSTERIOR = 0.1
        private const val BYTE_MASK = 0xff
        private const val HEX_RADIX = 16
    }
}

package io.github.alien2003.pulsestages.algo.rules

import io.github.alien2003.pulsestages.algo.StageEstimator
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.night.EpochEvidence
import io.github.alien2003.pulsestages.algo.night.EstimateAssembler
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.night.PreparedNight
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.post.Decoding
import io.github.alien2003.pulsestages.algo.post.EpochDecoder
import io.github.alien2003.pulsestages.algo.post.Postprocessor

/**
 * Threshold estimator (section 6 of the specification), used for comparison and for low-coverage nights. On the
 * same features as the HMM, per session epoch: AWAKE if forced or at least one whole step
 * ([EpochEvidence.MIN_STEPS]; fractions left by spread step records do not count, as in the HMM and HYBRID) or
 * hrNorm > awakeMinHrNorm (+ the elevated-night shift); DEEP if hrNorm < deepMaxHrNorm and hrVar < deepMaxHrVar; REM
 * if hrNorm > remMinHrNorm and hrVar > remMinHrVar and t > remMinT; LIGHT otherwise and for epochs without heart-rate
 * data (before the gap policy).
 *
 * Posteriors are pseudo-posteriors: [io.github.alien2003.pulsestages.algo.params.RulesParams.pseudoPosterior] for the
 * chosen stage and the rest shared equally by the others. The rules have no state priors, so the proportion
 * calibration's per-stage log-prior offsets δ move the hrNorm thresholds instead (by
 * [io.github.alien2003.pulsestages.algo.params.RulesParams.calibrationThresholdShift] per unit of δ, in favour of
 * the stage): deepMaxHrNorm + c·δ_DEEP, remMinHrNorm − c·δ_REM, awakeMinHrNorm − c·δ_AWAKE.
 */
class RulesEstimator : StageEstimator {
    override val type: EstimatorType = EstimatorType.RULES

    override fun estimate(
        input: NightInput,
        params: AlgoParams,
    ): NightEstimate = estimatePrepared(NightPreparer.prepare(input, params))

    /** Estimates an already prepared night (its parameters are used). */
    internal fun estimatePrepared(night: PreparedNight): NightEstimate {
        val chosen =
            night.params.rules.pseudoPosterior
                .coerceIn(1.0 / SleepStage.COUNT, 1.0)
        val other = (1 - chosen) / (SleepStage.COUNT - 1)
        val decoder =
            EpochDecoder { offsets ->
                val labels = classify(night, offsets)
                val posteriors = Array(labels.size) { k -> DoubleArray(SleepStage.COUNT) { if (it == labels[k].index) chosen else other } }
                Decoding(IntArray(labels.size) { labels[it].index }, posteriors)
            }
        val post = Postprocessor.run(decoder, EstimateAssembler.context(night), night.params.post)
        return EstimateAssembler.assemble(night, type, post, emptyList())
    }

    /**
     * The rule label of every session epoch, with the hrNorm thresholds shifted by the calibration [offsets]
     * (per-stage log-prior offsets in [SleepStage.index] order; all zero for the plain rules).
     */
    internal fun classify(
        night: PreparedNight,
        offsets: DoubleArray = DoubleArray(SleepStage.COUNT),
    ): Array<SleepStage> {
        val params = night.params
        val rules = params.rules
        val features = night.features
        val shift = rules.calibrationThresholdShift
        val elevated = if (night.statistics.elevated) params.features.elevatedAwakeShift else 0.0
        val awakeThreshold = rules.awakeMinHrNorm + elevated - shift * offsets[SleepStage.AWAKE.index]
        val deepThreshold = rules.deepMaxHrNorm + shift * offsets[SleepStage.DEEP.index]
        val remThreshold = rules.remMinHrNorm - shift * offsets[SleepStage.REM.index]
        return Array(night.grid.sessionEpochs) { k ->
            val p = night.grid.position(k)
            val hrNorm = features.heartRate.hrNorm[p]
            val hrVar = features.heartRate.hrVar[p]
            when {
                night.isForcedAwake(k) || EpochEvidence.hasSteps(features.activity.steps[p]) -> SleepStage.AWAKE
                !night.sessionHasData(k) -> SleepStage.LIGHT
                hrNorm > awakeThreshold -> SleepStage.AWAKE
                hrNorm < deepThreshold && hrVar < rules.deepMaxHrVar -> SleepStage.DEEP
                hrNorm > remThreshold && hrVar > rules.remMinHrVar && features.t[p] > rules.remMinT -> SleepStage.REM
                else -> SleepStage.LIGHT
            }
        }
    }
}

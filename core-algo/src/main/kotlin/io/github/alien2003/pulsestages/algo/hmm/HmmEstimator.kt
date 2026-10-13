package io.github.alien2003.pulsestages.algo.hmm

import io.github.alien2003.pulsestages.algo.StageEstimator
import io.github.alien2003.pulsestages.algo.model.EstimationWarning
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.night.EstimateAssembler
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.night.PreparedNight
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.EmissionTable
import io.github.alien2003.pulsestages.algo.post.Decoding
import io.github.alien2003.pulsestages.algo.post.EpochDecoder
import io.github.alien2003.pulsestages.algo.post.Postprocessor

/**
 * Generative HMM estimator with physiological priors (section 3 of the specification): diagonal-Gaussian
 * emissions over (hrNorm, hrVar, hrSlope), time-dependent transitions with the REM lockout, the ultradian-cycle
 * prior, step rules, optional per-night EM adaptation of μ/σ, Viterbi decoding and forward–backward posteriors,
 * followed by the shared post-processing.
 */
class HmmEstimator : StageEstimator {
    override val type: EstimatorType = EstimatorType.HMM

    override fun estimate(
        input: NightInput,
        params: AlgoParams,
    ): NightEstimate = estimatePrepared(NightPreparer.prepare(input, params))

    /**
     * Estimates an already prepared night (its parameters are used). [emissionTable] is the starting emission table
     * (`params.hmm.emissions`, or that table with a personal model's μ/σ applied).
     */
    internal fun estimatePrepared(
        night: PreparedNight,
        emissionTable: EmissionTable = night.params.hmm.emissions,
    ): NightEstimate {
        val params = night.params
        val warnings = mutableListOf<EstimationWarning>()
        if (params.hmm.cyclePriorWeight != 0.0 && !night.features.cycle.available) {
            warnings += EstimationWarning(WarningCode.CYCLE_PRIOR_DISABLED, "${night.features.cycle.minima.size} heart-rate minima found")
        }
        val emissions = EmissionModel(night, params)
        val model = TransitionModel(params.hmm, night.grid).logHmm()
        val table = adaptedTable(night, emissionTable, emissions, model, warnings)
        val decoder =
            EpochDecoder { offsets ->
                val logEmission = emissions.logEmissions(table, offsets)
                Decoding(HmmMath.viterbi(model, logEmission), HmmMath.forwardBackward(model, logEmission).posteriors)
            }
        val post = Postprocessor.run(decoder, EstimateAssembler.context(night), params.post)
        return EstimateAssembler.assemble(night, type, post, warnings)
    }

    /** [emissionTable] with μ_AWAKE,hrNorm raised by elevatedAwakeShift on elevated-HR nights (section 2). */
    internal fun startTable(
        night: PreparedNight,
        emissionTable: EmissionTable,
    ): EmissionTable {
        val shift = if (night.statistics.elevated) night.params.features.elevatedAwakeShift else 0.0
        return emissionTable.withAwakeHrNormShift(shift)
    }

    /** The starting table ([startTable]), adapted by EM when enabled. */
    private fun adaptedTable(
        night: PreparedNight,
        startTable: EmissionTable,
        emissions: EmissionModel,
        model: LogHmm,
        warnings: MutableList<EstimationWarning>,
    ): EmissionTable {
        val params = night.params
        val start = startTable(night, startTable)
        if (!params.hmm.emAdapt || params.hmm.emIterations <= 0 || night.sessionDataMask.none { it }) return start
        val noOffsets = DoubleArray(SleepStage.COUNT)
        val result =
            EmAdaptation.adapt(start, emissions.observations, params.hmm.emIterations, params.hmm.emPriorStrength) { table ->
                HmmMath.forwardBackward(model, emissions.logEmissions(table, noOffsets)).posteriors
            }
        if (result.orderViolated) {
            warnings +=
                EstimationWarning(
                    WarningCode.EM_ORDER_VIOLATION,
                    "iteration ${result.iterations + 1} broke μ_DEEP < μ_LIGHT < μ_REM < μ_AWAKE; starting table used",
                )
        }
        return result.table
    }
}

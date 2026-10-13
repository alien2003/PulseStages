package io.github.alien2003.pulsestages.algo.hmm

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.params.EmissionTable
import io.github.alien2003.pulsestages.algo.params.Gaussian
import io.github.alien2003.pulsestages.algo.params.StateEmission

/** Emission dimensions of the HMM, in this order. */
internal object EmissionDims {
    const val HR_NORM = 0
    const val VARIABILITY = 1
    const val SLOPE = 2
    const val COUNT = 3
}

/** The emission of the state with [SleepStage.index] [index]. */
internal fun EmissionTable.forState(index: Int): StateEmission =
    when (index) {
        SleepStage.AWAKE.index -> awake
        SleepStage.LIGHT.index -> light
        SleepStage.DEEP.index -> deep
        SleepStage.REM.index -> rem
        else -> throw IllegalArgumentException("no scored stage with index $index")
    }

/** Gaussian of dimension [dim] (see [EmissionDims]). */
internal fun StateEmission.dim(dim: Int): Gaussian =
    when (dim) {
        EmissionDims.HR_NORM -> hrNorm
        EmissionDims.VARIABILITY -> hrVar
        EmissionDims.SLOPE -> hrSlope
        else -> throw IllegalArgumentException("no emission dimension $dim")
    }

/** Builds a table from a function of (state index, dimension). */
internal fun emissionTableOf(gaussian: (state: Int, dim: Int) -> Gaussian): EmissionTable {
    fun state(index: Int) =
        StateEmission(
            hrNorm = gaussian(index, EmissionDims.HR_NORM),
            hrVar = gaussian(index, EmissionDims.VARIABILITY),
            hrSlope = gaussian(index, EmissionDims.SLOPE),
        )
    return EmissionTable(
        awake = state(SleepStage.AWAKE.index),
        light = state(SleepStage.LIGHT.index),
        deep = state(SleepStage.DEEP.index),
        rem = state(SleepStage.REM.index),
    )
}

/** The table with μ_AWAKE,hrNorm shifted by [shift] (elevated-HR nights). */
internal fun EmissionTable.withAwakeHrNormShift(shift: Double): EmissionTable =
    if (shift == 0.0) this else copy(awake = awake.copy(hrNorm = awake.hrNorm.copy(mean = awake.hrNorm.mean + shift)))

/** Whether μ_DEEP < μ_LIGHT < μ_REM < μ_AWAKE on hrNorm. */
internal fun EmissionTable.isHrNormOrdered(): Boolean =
    deep.hrNorm.mean < light.hrNorm.mean && light.hrNorm.mean < rem.hrNorm.mean && rem.hrNorm.mean < awake.hrNorm.mean

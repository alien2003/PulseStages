package io.github.alien2003.pulsestages.algo.night

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import kotlin.math.ln

/** Per-epoch evidence rules shared by every estimator (steps and SpO2 desaturations). */
internal object EpochEvidence {
    /**
     * Smallest per-epoch step count that counts as movement ("1 … 4 steps", "steps → AWAKE"). The steps of a record
     * are spread uniformly over its interval, so one long, coarse record leaves a fraction of a step in every epoch
     * it covers; such fractions are not steps.
     */
    const val MIN_STEPS: Double = 1.0

    /** Whether an epoch with [steps] (spread) steps has at least one whole step. */
    fun hasSteps(steps: Double): Boolean = steps >= MIN_STEPS

    /**
     * Log likelihood multipliers `[state]` ([SleepStage.index] order) of session epoch [k] of [night] with [params]:
     * ln(fewStepsAwakeFactor) for AWAKE with [MIN_STEPS] … awakeStepThreshold − 1 steps (more steps force AWAKE, see
     * [PreparedNight]) and ln(spo2AwakeLightFactor) for AWAKE and LIGHT where the SpO2 drop is at least spo2DropPct.
     * Non-positive factors are ignored.
     */
    fun logFactors(
        night: PreparedNight,
        k: Int,
        params: AlgoParams,
    ): DoubleArray {
        val p = night.grid.position(k)
        val terms = DoubleArray(SleepStage.COUNT)
        val steps = night.features.activity.steps[p]
        if (hasSteps(steps) && steps < params.hmm.awakeStepThreshold) terms[AWAKE] += logFactor(params.hmm.fewStepsAwakeFactor)
        val drop = night.features.optional.spo2Drop[p]
        if (!drop.isNaN() && drop >= params.features.spo2DropPct) {
            val boost = logFactor(params.features.spo2AwakeLightFactor)
            terms[AWAKE] += boost
            terms[LIGHT] += boost
        }
        return terms
    }

    /** `ln(factor)`, or 0 for a non-positive factor. */
    fun logFactor(factor: Double): Double = if (factor > 0) ln(factor) else 0.0

    private val AWAKE = SleepStage.AWAKE.index
    private val LIGHT = SleepStage.LIGHT.index
}

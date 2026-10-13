package io.github.alien2003.pulsestages.algo.params

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The HMM time-dependent priors are routed through [TrainedDefaults.HMM_TIME_PRIOR] like the other trained HMM
 * defaults; [HmmParams.PHYSIOLOGY] must always carry the specification's values, whatever a training run adopts.
 */
class HmmTimePriorTest {
    private fun HmmParams.timePrior() =
        HmmTimePrior(
            deepTimeIntercept = deepTimeIntercept,
            deepTimeSlope = deepTimeSlope,
            deepTimeFloor = deepTimeFloor,
            remTimeIntercept = remTimeIntercept,
            remTimeSlope = remTimeSlope,
            remLockoutMin = remLockoutMin,
            remLockoutFactor = remLockoutFactor,
            initialAwake = initialAwake,
            initialLight = initialLight,
        )

    @Test
    fun `the specification's time prior`() {
        val spec = HmmTimePrior()
        assertEquals(listOf(1.6, 1.2, 0.3, 0.4, 1.2, 45.0, 0.05, 0.5, 0.5), spec.toList())
        assertEquals(spec, HmmParams.PHYSIOLOGY.timePrior())
        assertEquals(EmissionTable.PHYSIOLOGY, HmmParams.PHYSIOLOGY.emissions)
        assertEquals(MeanBouts(), HmmParams.PHYSIOLOGY.meanBouts)
        assertEquals(TransitionWeights(), HmmParams.PHYSIOLOGY.transitions)
    }

    @Test
    fun `defaults come from TrainedDefaults and withTimePrior replaces exactly the time prior`() {
        assertEquals(TrainedDefaults.HMM_TIME_PRIOR, HmmParams().timePrior())
        val estimated = HmmTimePrior(2.05, 2.0, 0.04, 0.54, 0.89, 45.0, 0.77, 0.98, 0.02)
        val base = HmmParams.PHYSIOLOGY.copy(cyclePriorWeight = 0.7, emIterations = 2)
        val replaced = base.withTimePrior(estimated)
        assertEquals(estimated, replaced.timePrior())
        assertEquals(base, replaced.withTimePrior(HmmTimePrior()))
        assertEquals(emptyList<ParamProblem>(), AlgoParamsValidator.problems(AlgoParams(hmm = replaced)))
    }

    private fun HmmTimePrior.toList() =
        listOf(
            deepTimeIntercept,
            deepTimeSlope,
            deepTimeFloor,
            remTimeIntercept,
            remTimeSlope,
            remLockoutMin,
            remLockoutFactor,
            initialAwake,
            initialLight,
        )
}

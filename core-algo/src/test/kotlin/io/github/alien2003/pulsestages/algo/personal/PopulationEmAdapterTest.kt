package io.github.alien2003.pulsestages.algo.personal

import io.github.alien2003.pulsestages.algo.hmm.isHrNormOrdered
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.EmissionTable
import io.github.alien2003.pulsestages.algo.params.HmmParams
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticOptions
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticPerson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class PopulationEmAdapterTest {
    private val global = EmissionTable.PHYSIOLOGY

    /** Defaults with the specification's HMM, whose table is [global] (the shipped HMM defaults may differ). */
    private val physiology = AlgoParams(hmm = HmmParams.PHYSIOLOGY)
    private val rem = SleepStage.REM.index
    private val hrNorm = 0
    private val person = SyntheticPerson(remOffsetBpm = 11.0, awakeOffsetBpm = 20.0)

    private fun nights(
        count: Int,
        first: Long = 4001,
    ): List<NightInput> = (0 until count).map { SyntheticNightGenerator(first + it).generate(SyntheticOptions(person = person)).input }

    private fun withStrength(tau: Double): AlgoParams = physiology.let { it.copy(personal = it.personal.copy(emPriorStrength = tau)) }

    private fun distance(
        a: StageDimValues,
        b: StageDimValues,
    ): Double = a.all().zip(b.all()).sumOf { (x, y) -> abs(x - y) }

    @Test
    fun `map update follows the shrinkage formulas`() {
        val statistics = SufficientStatistics()
        val gamma = doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        for (x in listOf(2.0, 2.4, 2.2)) statistics.add(doubleArrayOf(x, Double.NaN, 0.1), gamma)
        val tau = 3.0

        val table = PopulationEmAdapter.mapUpdate(global, statistics, tau)

        val mu0 = global.rem.hrNorm.mean
        val sd0 = global.rem.hrNorm.sd
        val mean = (6.6 + tau * mu0) / (3 + tau)
        val spread = listOf(2.0, 2.4, 2.2).sumOf { (it - mean) * (it - mean) }
        assertEquals(mean, table.rem.hrNorm.mean, 1e-12)
        assertEquals(sqrt((spread + tau * (sd0 * sd0 + (mu0 - mean) * (mu0 - mean))) / (3 + tau)), table.rem.hrNorm.sd, 1e-12)
        assertEquals(global.rem.hrVar.mean, table.rem.hrVar.mean, 1e-12, "NaN dimensions carry no weight")
        assertEquals(global.rem.hrVar.sd, table.rem.hrVar.sd, 1e-12)
        assertEquals(global.deep.hrNorm.mean, table.deep.hrNorm.mean, 1e-12, "states without weight keep the global values")
        assertEquals(global.deep.hrSlope.sd, table.deep.hrSlope.sd, 1e-12)
        assertEquals(
            6.6 / 3,
            PopulationEmAdapter
                .mapUpdate(global, statistics, 0.0)
                .rem.hrNorm.mean,
            1e-12,
        )
    }

    @Test
    fun `an order violation rolls back to the starting table`() {
        val start = global.copy(light = global.light.copy(hrNorm = global.light.hrNorm.copy(mean = 1.05)))
        val result =
            PopulationEmAdapter.iterate(start, global, 200.0, 3) {
                SufficientStatistics().also { s ->
                    s.weight[rem][hrNorm] = 1000.0
                    s.sum[rem][hrNorm] = 500.0
                    s.sumSquares[rem][hrNorm] = 260.0
                }
            }

        assertTrue(result.orderViolated)
        assertEquals(0, result.iterations)
        assertSame(start, result.table)
    }

    @Test
    fun `population EM moves towards the person and respects the order`() {
        val result = PopulationEmAdapter.adapt(nights(12), withStrength(200.0))
        val model = checkNotNull(result.model)
        val adapted = model.applyTo(global)

        assertEquals(12, result.nightsUsed)
        assertEquals(3, result.iterations)
        assertTrue(!result.orderViolated && adapted.isHrNormOrdered())
        assertEquals(PersonalModelKind.EM, model.provenance.kind)
        assertEquals(12, model.provenance.nightsUsed)
        assertTrue(abs(adapted.rem.hrNorm.mean - global.rem.hrNorm.mean) > 0.05, "REM μ should adapt: ${adapted.rem.hrNorm}")
        assertTrue(model.hmmSds != null)
    }

    @Test
    fun `stronger shrinkage stays closer to the global table`() {
        val data = nights(6, first = 4101)
        val globalMeans = StageDimValues.meansOf(global)
        val loose = checkNotNull(PopulationEmAdapter.adapt(data, withStrength(20.0)).model)
        val tight = checkNotNull(PopulationEmAdapter.adapt(data, withStrength(5000.0)).model)
        val frozen = checkNotNull(PopulationEmAdapter.adapt(data, withStrength(1e12)).model)

        assertTrue(distance(checkNotNull(tight.hmmMeans), globalMeans) < distance(checkNotNull(loose.hmmMeans), globalMeans))
        assertEquals(0.0, distance(checkNotNull(frozen.hmmMeans), globalMeans), 1e-6)
        assertEquals(0.0, distance(checkNotNull(frozen.hmmSds), StageDimValues.sdsOf(global)), 1e-6)
    }

    @Test
    fun `unusable nights are skipped and the base model's deltas are kept`() {
        val empty = NightInput(0L, 8 * 3_600_000L, emptyList())
        val base =
            PersonalModel(
                biasDeltas = listOf(0.1, -0.1, 0.0, 0.0),
                baseModelFingerprint = "f".repeat(64),
                provenance = PersonalProvenance(PersonalModelKind.CALIBRATED, 3),
            )

        val result = PopulationEmAdapter.adapt(nights(3, first = 4201) + empty, physiology, base)

        assertEquals(3, result.nightsUsed)
        assertEquals(1, result.nightsSkipped)
        assertEquals(base.biasDeltas, checkNotNull(result.model).biasDeltas)
        assertEquals(base.baseModelFingerprint, checkNotNull(result.model).baseModelFingerprint)
        assertNull(PopulationEmAdapter.adapt(listOf(empty), physiology).model)
        val noIterations = physiology.let { it.copy(hmm = it.hmm.copy(emIterations = 0)) }
        assertNull(PopulationEmAdapter.adapt(nights(1), noIterations).model)
    }

    @Test
    fun `naps are skipped, the population EM uses nights only`() {
        val nights = nights(3)
        val naps = nights.map { it.copy(sessionEndMs = it.sessionStartMs + 90 * 60_000L) }

        val withNaps = PopulationEmAdapter.adapt(nights + naps, physiology)
        val nightsOnly = PopulationEmAdapter.adapt(nights, physiology)

        assertEquals(3, withNaps.nightsUsed)
        assertEquals(3, withNaps.nightsSkipped)
        assertEquals(nightsOnly.model?.hmmMeans, withNaps.model?.hmmMeans)
        val noNapMode = physiology.copy(napMode = physiology.napMode.copy(maxSessionMin = 0.0))
        assertEquals(6, PopulationEmAdapter.adapt(nights + naps, noNapMode).nightsUsed)
    }
}

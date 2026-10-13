package io.github.alien2003.pulsestages.algo.hmm

import io.github.alien2003.pulsestages.algo.params.EmissionTable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class EmAdaptationTest {
    private val awake = 0
    private val light = 1
    private val deep = 2
    private val start = EmissionTable.PHYSIOLOGY

    private fun observations(vararg hrNorm: Double) =
        HmmObservations(
            values = Array(hrNorm.size) { doubleArrayOf(hrNorm[it], 1.0, 0.0) },
            variabilitySdScale = DoubleArray(hrNorm.size) { 1.0 },
            hasData = BooleanArray(hrNorm.size) { true },
        )

    private fun oneHot(vararg states: Int) = Array(states.size) { k -> DoubleArray(4) { if (it == states[k]) 1.0 else 0.0 } }

    @Test
    fun `map update follows the shrinkage formulas`() {
        val obs = observations(0.2, 0.4, 1.5, 3.0)
        val gamma = oneHot(deep, deep, light, awake)
        val tau = 2.0

        val updated = EmAdaptation.mapUpdate(start, obs, gamma, tau)

        val mu0 = start.deep.hrNorm.mean
        val sd0 = start.deep.hrNorm.sd
        val mean = (0.2 + 0.4 + tau * mu0) / (2 + tau)
        val variance =
            ((0.2 - mean) * (0.2 - mean) + (0.4 - mean) * (0.4 - mean) + tau * (sd0 * sd0 + (mu0 - mean) * (mu0 - mean))) / (2 + tau)
        assertEquals(mean, updated.deep.hrNorm.mean, 1e-12)
        assertEquals(sqrt(variance), updated.deep.hrNorm.sd, 1e-12)
        // REM has no responsibility: it stays at the prior.
        assertEquals(start.rem.hrNorm.mean, updated.rem.hrNorm.mean, 1e-12)
        assertEquals(start.rem.hrNorm.sd, updated.rem.hrNorm.sd, 1e-12)
    }

    @Test
    fun `adapted sd has a lower bound`() {
        val obs = observations(0.5, 0.5, 0.5)
        val updated = EmAdaptation.mapUpdate(start, obs, oneHot(deep, deep, deep), priorStrength = 0.0)

        assertEquals(0.5, updated.deep.hrNorm.mean, 1e-12)
        assertEquals(EmAdaptation.MIN_SD, updated.deep.hrNorm.sd, 0.0)
    }

    @Test
    fun `unavailable dimensions and epochs without data do not contribute`() {
        val obs =
            HmmObservations(
                values = arrayOf(doubleArrayOf(0.3, Double.NaN, 0.0), doubleArrayOf(9.0, 9.0, 9.0)),
                variabilitySdScale = doubleArrayOf(1.0, 1.0),
                hasData = booleanArrayOf(true, false),
            )

        val updated = EmAdaptation.mapUpdate(start, obs, oneHot(deep, deep), priorStrength = 0.0)

        assertEquals(0.3, updated.deep.hrNorm.mean, 1e-12)
        assertEquals(start.deep.hrVar, updated.deep.hrVar)
    }

    @Test
    fun `order violation rolls back to the starting table`() {
        // All observations equal: with no shrinkage every state's mean collapses onto the same value.
        val obs = observations(*DoubleArray(20) { 0.5 })
        val uniform = Array(20) { DoubleArray(4) { 0.25 } }

        val result = EmAdaptation.adapt(start, obs, iterations = 3, priorStrength = 0.0) { uniform }

        assertTrue(result.orderViolated)
        assertSame(start, result.table)
        assertEquals(0, result.iterations)
    }

    @Test
    fun `adaptation keeps the order and moves means towards the data`() {
        val values = DoubleArray(40) { if (it < 20) 0.3 else 1.2 }
        val obs = observations(*values)
        val gamma = Array(40) { k -> DoubleArray(4) { s -> if (s == (if (k < 20) deep else light)) 0.97 else 0.01 } }

        val result = EmAdaptation.adapt(start, obs, iterations = 2, priorStrength = 20.0) { gamma }

        assertFalse(result.orderViolated)
        assertEquals(2, result.iterations)
        assertNotEquals(start, result.table)
        assertTrue(result.table.deep.hrNorm.mean > start.deep.hrNorm.mean)
        assertTrue(result.table.light.hrNorm.mean > start.light.hrNorm.mean)
        assertTrue(result.table.isHrNormOrdered())
        assertEquals(start.awake.hrNorm.mean, result.table.awake.hrNorm.mean, 0.2)
        assertEquals(start.rem.hrNorm.mean, result.table.rem.hrNorm.mean, 0.3)
        assertTrue(result.table.awake.hrNorm.mean > result.table.rem.hrNorm.mean)
    }
}

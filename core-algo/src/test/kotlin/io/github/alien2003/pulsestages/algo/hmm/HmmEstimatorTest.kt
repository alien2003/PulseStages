package io.github.alien2003.pulsestages.algo.hmm

import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.HrvSample
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.RespiratoryRateSample
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.Spo2Sample
import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.algo.model.TimeSpan
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.numeric.LogMath
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

class HmmEstimatorTest {
    private val minute = 60_000L
    private val estimator = HmmEstimator()
    private val night = SyntheticNightGenerator(21).generate()
    private val zero = DoubleArray(4)

    @Test
    fun `forced awake spans and many steps force AWAKE`() {
        val start = night.input.sessionStartMs
        val input =
            night.input.copy(
                forcedAwake = listOf(TimeSpan(start + 200 * minute, start + 215 * minute)),
                steps = listOf(StepsSample(start + 300 * minute, start + 301 * minute, 40)),
            )

        val estimate = estimator.estimate(input, AlgoParams())

        (200 until 215).forEach {
            assertEquals(SleepStage.AWAKE, estimate.perEpoch[it].stage)
            assertEquals(1.0, estimate.perEpoch[it].posterior.awake, 1e-12)
        }
        assertEquals(SleepStage.AWAKE, estimate.perEpoch[300].stage)
    }

    @Test
    fun `few steps raise the AWAKE emission by the configured factor`() {
        val start = night.input.sessionStartMs
        val params = AlgoParams()
        val withSteps = night.input.copy(steps = listOf(StepsSample(start + 120 * minute, start + 121 * minute, 3)))
        val plain = EmissionModel(NightPreparer.prepare(night.input.copy(steps = emptyList()), params), params)
        val stepped = EmissionModel(NightPreparer.prepare(withSteps, params), params)

        val a = plain.logEmissions(params.hmm.emissions, zero)[120]
        val b = stepped.logEmissions(params.hmm.emissions, zero)[120]

        assertEquals(a[0] + ln(3.0), b[0], 1e-9)
        assertEquals(a[1], b[1], 1e-12)
        assertFalse(stepped.forced[120])
    }

    @Test
    fun `cycle prior, SpO2, HRV and respiratory channels enter the emissions as specified`() {
        val start = night.input.sessionStartMs
        val params = AlgoParams()
        val base = night.input.copy(steps = emptyList())
        val k = 150
        val withChannels =
            base.copy(
                spo2 = (0 until 400).map { Spo2Sample(start + it * minute + 1_000, if (it == k) 90.0 else 96.0) },
                hrv = listOf(HrvSample(start + k * minute + 30_000, 25.0), HrvSample(start + 10 * minute, 50.0)),
                respiratoryRate = (0 until 400).map { RespiratoryRateSample(start + it * minute + 2_000, 14.0 + (it % 4)) },
            )
        val plainNight = NightPreparer.prepare(base, params)
        val channelNight = NightPreparer.prepare(withChannels, params)
        val plain = EmissionModel(plainNight, params).logEmissions(params.hmm.emissions, zero)[k]
        val channels = EmissionModel(channelNight, params).logEmissions(params.hmm.emissions, zero)[k]
        val p = channelNight.grid.position(k)
        val f = channelNight.features
        val table = params.hmm.emissions

        val hrvValue = f.optional.hrvVar[p]
        assertEquals(37.5 / 25.0, hrvValue, 1e-12)
        val resp = f.optional.respVar[p]
        for ((s, emission) in listOf(table.awake, table.light, table.deep, table.rem).withIndex()) {
            var expected = plain[s]
            expected -= LogMath.logNormalPdf(f.heartRate.hrVar[p], emission.hrVar.mean, emission.hrVar.sd)
            expected += LogMath.logNormalPdf(hrvValue, emission.hrVar.mean, emission.hrVar.sd / params.features.hrvWeight)
            expected += params.features.respiratoryRateWeight * LogMath.logNormalPdf(resp, emission.hrVar.mean, emission.hrVar.sd)
            if (s == 0 || s == 1) expected += ln(params.features.spo2AwakeLightFactor)
            assertEquals(expected, channels[s], 1e-9, "state $s")
        }
        val phase = f.cycle.phase[p]
        assertFalse(phase.isNaN(), "epoch $k must lie between two cycle minima")
        val withoutPrior =
            EmissionModel(
                plainNight,
                params.copy(hmm = params.hmm.copy(cyclePriorWeight = 0.0)),
            ).logEmissions(table, zero)[k]
        assertEquals(withoutPrior[2] + 0.5 * cos(2 * PI * phase), plain[2], 1e-9)
        assertEquals(withoutPrior[3] + 0.5 * cos(2 * PI * (phase - 0.7)), plain[3], 1e-9)
        assertEquals(withoutPrior[1], plain[1], 1e-12)
    }

    @Test
    fun `epochs without heart-rate data have uninformative Gaussian terms`() {
        val start = night.input.sessionStartMs
        val input =
            night.input.copy(
                steps = emptyList(),
                heartRate =
                    night.input.heartRate.filter {
                        it.timeMs !in
                            start + 100 * minute until start + 160 * minute
                    },
            )
        val params = AlgoParams(hmm = AlgoParams().hmm.copy(cyclePriorWeight = 0.0))
        val prepared = NightPreparer.prepare(input, params)
        val k = 130

        assertFalse(prepared.sessionHasData(k))
        assertTrue(EmissionModel(prepared, params).logEmissions(params.hmm.emissions, zero)[k].all { it == 0.0 })
    }

    @Test
    fun `EM order violation is reported and the starting table is used`() {
        val input =
            NightInput(
                night.input.sessionStartMs,
                night.input.sessionStartMs + 300 * minute,
                (0 until 150).map {
                    HrSample(night.input.sessionStartMs + it * 2 * minute, 60.0)
                },
            )
        val params = AlgoParams().let { it.copy(hmm = it.hmm.copy(emPriorStrength = 0.0)) }

        val estimate = estimator.estimate(input, params)

        assertTrue(estimate.warnings.any { it.code == WarningCode.EM_ORDER_VIOLATION })
        assertTrue(estimate.warnings.any { it.code == WarningCode.LOW_HR_RANGE })
    }

    @Test
    fun `cycle prior is disabled without two minima`() {
        val start = night.input.sessionStartMs
        val input = NightInput(start, start + 120 * minute, (0 until 60).map { HrSample(start + it * 2 * minute, 70.0 - it * 0.2) })

        val estimate = estimator.estimate(input, AlgoParams())

        assertTrue(estimate.warnings.any { it.code == WarningCode.CYCLE_PRIOR_DISABLED })
        val noPrior = estimator.estimate(input, AlgoParams().let { it.copy(hmm = it.hmm.copy(cyclePriorWeight = 0.0)) })
        assertTrue(noPrior.warnings.none { it.code == WarningCode.CYCLE_PRIOR_DISABLED })
    }

    @Test
    fun `elevated nights are flagged and lose confidence`() {
        val normal = estimator.estimate(night.input, AlgoParams())
        val elevated = estimator.estimate(night.input.copy(baselineNadirBpm = 30.0), AlgoParams())

        assertTrue(elevated.flags.elevatedHr)
        assertTrue(elevated.warnings.any { it.code == WarningCode.ELEVATED_HR })
        assertFalse(normal.flags.elevatedHr)
        assertTrue(elevated.confidence < normal.confidence)
    }

    @Test
    fun `elevated nights raise the AWAKE hrNorm mean by elevatedAwakeShift`() {
        val params = AlgoParams()
        val table = params.hmm.emissions
        val normal = NightPreparer.prepare(night.input, params)
        val elevated = NightPreparer.prepare(night.input.copy(baselineNadirBpm = 30.0), params)

        assertEquals(table, estimator.startTable(normal, table))
        val raisedTable = estimator.startTable(elevated, table)
        assertEquals(table.awake.hrNorm.mean + params.features.elevatedAwakeShift, raisedTable.awake.hrNorm.mean, 1e-12)
        assertEquals(table.awake.hrNorm.sd, raisedTable.awake.hrNorm.sd, 0.0)
        assertEquals(listOf(table.light, table.deep, table.rem), listOf(raisedTable.light, raisedTable.deep, raisedTable.rem))

        // The decoder uses the raised mean: without EM the AWAKE posterior mass on ordinary epochs falls.
        val noEm = params.copy(hmm = params.hmm.copy(emAdapt = false))
        val plain = estimator.estimate(night.input, noEm)
        val raised = estimator.estimate(night.input.copy(baselineNadirBpm = 30.0), noEm)
        val ordinary = plain.perEpoch.filter { it.hasData && it.features[0] < 2.5 }.map { it.index }
        assertTrue(ordinary.size > 100)
        assertTrue(ordinary.sumOf { raised.perEpoch[it].posterior.awake } < ordinary.sumOf { plain.perEpoch[it].posterior.awake })
    }

    @Test
    fun `rem lockout keeps REM out of the first minutes`() {
        val estimate = estimator.estimate(night.input, AlgoParams())
        val lockoutEpochs = 45

        assertTrue(estimate.perEpoch.take(lockoutEpochs).none { it.stage == SleepStage.REM })
    }
}

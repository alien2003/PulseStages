package io.github.alien2003.pulsestages.algo.features

import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.HrvSample
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.RespiratoryRateSample
import io.github.alien2003.pulsestages.algo.model.Spo2Sample
import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.algo.model.TimeSpan
import io.github.alien2003.pulsestages.algo.numeric.Stats
import io.github.alien2003.pulsestages.algo.params.FeatureParams
import io.github.alien2003.pulsestages.algo.params.PreprocessParams
import io.github.alien2003.pulsestages.algo.preprocess.Preprocessor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos

class BaseFeatureExtractorTest {
    private val minute = 60_000L
    private val start = 1_760_000_000_000L
    private val noSmoothing = PreprocessParams(contextMarginMin = 0, medianWindowEpochs = 1, smoothWindowEpochs = 1, hampelWindow = 1)

    private fun everyMinute(
        minutes: Int,
        bpm: (Int) -> Double,
    ) = (0 until minutes).map { HrSample(start + it * minute + 30_000, bpm(it)) }

    private fun extract(
        input: NightInput,
        pre: PreprocessParams = noSmoothing,
        features: FeatureParams = FeatureParams(),
    ): Pair<BaseFeatures, NightStatistics> {
        val night = Preprocessor.preprocess(input, pre)
        val stats = NightStatistics.compute(night, features, input.baselineNadirBpm)
        return BaseFeatureExtractor.extract(input, night, stats, features) to stats
    }

    @Test
    fun `statistics use percentiles of session epochs and flag a low range`() {
        val input = NightInput(start, start + 10 * minute, everyMinute(10) { 50.0 + it })
        val (_, stats) = extract(input)

        assertEquals(50.9, stats.lowPercentileBpm, 1e-12)
        assertEquals(54.5, stats.highPercentileBpm, 1e-12)
        assertEquals(50.0, stats.nadirBpm, 0.0)
        assertFalse(stats.lowRange)

        val flat = NightInput(start, start + 10 * minute, everyMinute(10) { 60.0 + (it % 2) * 0.5 })
        val (features, flatStats) = extract(flat)
        assertTrue(flatStats.lowRange)
        assertEquals(2.0, flatStats.normDenominator, 0.0)
        assertEquals((60.5 - flatStats.lowPercentileBpm) / 2.0, features.heartRate.hrNorm[1], 1e-12)
    }

    @Test
    fun `elevated night is detected against the baseline nadir`() {
        val hr = everyMinute(30) { 60.0 + it % 5 }
        assertTrue(extract(NightInput(start, start + 30 * minute, hr, baselineNadirBpm = 53.0)).second.elevated)
        assertFalse(extract(NightInput(start, start + 30 * minute, hr, baselineNadirBpm = 54.0)).second.elevated)
        assertFalse(extract(NightInput(start, start + 30 * minute, hr)).second.elevated)
    }

    @Test
    fun `hrNorm, hrVar, hrSlope and t follow their definitions`() {
        val input = NightInput(start, start + 20 * minute, everyMinute(20) { 50.0 + 2.0 * it + if (it % 2 == 0) 1.0 else 0.0 })
        val (features, stats) = extract(input)
        val hr = features.heartRate
        val raw = DoubleArray(20) { 50.0 + 2.0 * it + if (it % 2 == 0) 1.0 else 0.0 }

        assertEquals((raw[7] - stats.lowPercentileBpm) / stats.normDenominator, hr.hrNorm[7], 1e-12)
        val stds = DoubleArray(20) { i -> Stats.populationStd(raw.copyOfRange(maxOf(0, i - 3), minOf(19, i + 3) + 1)) }
        assertEquals(stds[10] / Stats.median(stds), hr.hrVar[10], 1e-12)
        // Slope over ±2 epochs of 1 minute: [raw[8..12]] -> least squares.
        assertEquals(Stats.slope(doubleArrayOf(-2.0, -1.0, 0.0, 1.0, 2.0), raw.copyOfRange(8, 13)), hr.hrSlope[10], 1e-12)
        assertEquals(0.5 / 20, features.t[0], 1e-12)
        assertEquals(19.5 / 20, features.t[19], 1e-12)
    }

    @Test
    fun `hrVar falls back to a unit median for a perfectly flat night`() {
        val (features, _) = extract(NightInput(start, start + 15 * minute, everyMinute(15) { 60.0 }))

        assertEquals(0.0, features.heartRate.hrVar[5], 0.0)
    }

    @Test
    fun `slope is in bpm per minute for longer epochs`() {
        val samples = (0 until 20).map { HrSample(start + it * 2 * minute + 60_000, 50.0 + it) }
        val input = NightInput(start, start + 40 * minute, samples)
        val (features, _) = extract(input, pre = noSmoothing.copy(epochSec = 120))

        assertEquals(0.5, features.heartRate.hrSlope[10], 1e-12)
    }

    @Test
    fun `t is clipped in the context`() {
        val input = NightInput(start, start + 10 * minute, everyMinute(10) { 60.0 })
        val (features, _) = extract(input, pre = noSmoothing.copy(contextMarginMin = 5))

        assertEquals(0.0, features.t[0], 0.0)
        assertEquals(1.0, features.t[features.grid.totalEpochs - 1], 0.0)
    }

    @Test
    fun `cycle phase runs from zero to one between consecutive minima`() {
        // A 90-minute cosine with minima at 45, 135 and 225 minutes of a 300-minute session.
        val input = NightInput(start, start + 300 * minute, everyMinute(300) { 60.0 - 5.0 * cos(2 * PI * (it - 45) / 90.0) })
        val (features, _) = extract(input)
        val cycle = features.cycle

        assertTrue(cycle.available)
        assertEquals(listOf(45, 135, 225), cycle.minima.map { features.grid.epochAt(it) })
        assertEquals(0.0, cycle.phase[45], 1e-12)
        assertEquals(0.5, cycle.phase[90], 1e-12)
        assertTrue(cycle.phase[44].isNaN())
        assertTrue(cycle.phase[226].isNaN())
    }

    @Test
    fun `fewer than two minima disables the cycle phase`() {
        val (features, _) = extract(NightInput(start, start + 100 * minute, everyMinute(100) { 70.0 - it * 0.1 }))

        assertFalse(features.cycle.available)
        assertTrue(features.cycle.phase.all { it.isNaN() })
    }

    @Test
    fun `steps are spread uniformly and forced awake uses epoch midpoints`() {
        val input =
            NightInput(
                sessionStartMs = start,
                sessionEndMs = start + 10 * minute,
                heartRate = everyMinute(10) { 60.0 },
                steps = listOf(StepsSample(start + 30_000, start + 150_000, 12), StepsSample(start + 5 * minute, start + 5 * minute, 7)),
                forcedAwake = listOf(TimeSpan(start + 7 * minute + 30_000, start + 9 * minute)),
            )
        val (features, _) = extract(input)
        val activity = features.activity

        assertEquals(listOf(3.0, 6.0, 3.0, 0.0, 0.0, 7.0), activity.steps.take(6))
        assertEquals(listOf(false, false, false, false, false, false, false, true, true, false), activity.forcedAwake.toList())
    }

    @Test
    fun `optional channels are computed only when present and enabled`() {
        val base = NightInput(start, start + 30 * minute, everyMinute(30) { 60.0 })
        val (none, _) = extract(base)
        assertTrue(none.optional.spo2Drop.all { it.isNaN() })
        assertTrue(none.optional.hrvVar.all { it.isNaN() })
        assertTrue(none.optional.respVar.all { it.isNaN() })

        val withChannels =
            base.copy(
                spo2 = (0 until 30).map { Spo2Sample(start + it * minute + 1_000, if (it == 10) 90.0 else 96.0) },
                hrv = listOf(HrvSample(start + 5 * minute, 40.0), HrvSample(start + 20 * minute, 20.0)),
                respiratoryRate = (0 until 30).map { RespiratoryRateSample(start + it * minute + 2_000, 14.0 + it % 3) },
            )
        val (features, _) = extract(withChannels)
        val optional = features.optional
        assertEquals(6.0, optional.spo2Drop[10], 1e-12)
        assertEquals(0.0, optional.spo2Drop[11], 1e-12)
        assertEquals(30.0 / 40.0, optional.hrvVar[5], 1e-12)
        assertEquals(30.0 / 20.0, optional.hrvVar[22], 1e-12)
        assertTrue(optional.hrvVar[12].isNaN(), "no HRV sample within 5 minutes")
        assertFalse(optional.respVar[15].isNaN())

        val (disabled, _) = extract(withChannels, features = FeatureParams(useSpo2 = false, useHrv = false, useRespiratoryRate = false))
        assertTrue(disabled.optional.spo2Drop.all { it.isNaN() })
        assertTrue(disabled.optional.hrvVar.all { it.isNaN() })
        assertTrue(disabled.optional.respVar.all { it.isNaN() })
    }

    @Test
    fun `feature values are reported in name order`() {
        val (features, _) = extract(NightInput(start, start + 10 * minute, everyMinute(10) { 60.0 + it }))
        val values = features.valuesAt(3)

        assertEquals(BaseFeatures.NAMES.size, values.size)
        assertEquals(features.heartRate.hrNorm[3], values[BaseFeatures.NAMES.indexOf("hrNorm")], 0.0)
        assertEquals(features.t[3], values[BaseFeatures.NAMES.indexOf("t")], 0.0)
    }
}

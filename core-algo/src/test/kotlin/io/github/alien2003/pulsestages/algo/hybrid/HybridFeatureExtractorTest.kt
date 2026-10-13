package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticOptions
import io.github.alien2003.pulsestages.algo.testing.MinimalBundles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ln
import kotlin.math.sqrt

class HybridFeatureExtractorTest {
    private val minute = 60_000L
    private val names = HybridFeatureExtractor.FEATURE_NAMES
    private val start = SyntheticOptions.DEFAULT_SESSION_START_MS

    private fun column(name: String): Int = names.indexOf(name).also { check(it >= 0) { "no feature $name" } }

    /** A night whose heart rate rises linearly by 0.1 bpm per minute (one sample per minute incl. the context). */
    private fun rampNight(): NightInput {
        val samples = (-30..510).map { m -> HrSample(start + m * minute, 50.0 + 0.1 * (m + 30)) }
        val at = start + 200 * minute
        return NightInput(start, start + 480 * minute, samples, steps = listOf(StepsSample(at, at + minute, 3)))
    }

    @Test
    fun `feature set is fixed`() {
        assertEquals(1, HybridFeatureExtractor.FEATURE_SET_VERSION)
        assertEquals(40, names.size)
        assertEquals(names.size, names.toSet().size)
        assertEquals(listOf("hrNorm", "hrVar", "hrSlope", "t"), names.take(4))
    }

    @Test
    fun `base columns equal the HMM features`() {
        val night = NightPreparer.prepare(SyntheticNightGenerator(3).generate().input, AlgoParams())
        val x = HybridFeatureExtractor.extract(night)

        assertEquals(night.grid.sessionEpochs, x.size)
        assertTrue(x.all { it.size == names.size })
        for (k in x.indices) {
            val p = night.grid.position(k)
            assertEquals(night.features.heartRate.hrNorm[p], x[k][column("hrNorm")], 0.0)
            assertEquals(night.features.heartRate.hrVar[p], x[k][column("hrVar")], 0.0)
            assertEquals(night.features.t[p], x[k][column("t")], 0.0)
            assertEquals(if (night.sessionHasData(k)) 1.0 else 0.0, x[k][column("hasData")])
            assertEquals(ln(1 + night.features.activity.steps[p]), x[k][column("stepsLog1p")], 1e-12)
        }
    }

    @Test
    fun `window statistics, autocorrelation and time on a linear ramp`() {
        val night = NightPreparer.prepare(rampNight(), AlgoParams())
        val x = HybridFeatureExtractor.extract(night)
        val k = 100
        val nadir = night.statistics.nadirBpm

        assertEquals(53.0, nadir, 1e-9)
        assertEquals(50.0 + 0.1 * (k + 30) - nadir, x[k][column("hrRelRaw")], 1e-9)
        assertEquals(10.0, x[k][column("relMean5")], 1e-9)
        assertEquals(9.8, x[k][column("relMin5")], 1e-9)
        assertEquals(10.2, x[k][column("relMax5")], 1e-9)
        assertEquals(sqrt(0.02), x[k][column("relStd5")], 1e-9)
        assertEquals(10.0, x[k][column("relMean60")], 1e-9)
        for (w in listOf(5, 15, 30, 60)) assertEquals(0.1, x[k][column("slope$w")], 1e-9, "slope$w")
        for (lag in 1..3) assertEquals(1.0, x[k][column("acfLag$lag")], 1e-9, "acfLag$lag")
        assertEquals(180.0, x[k][column("minToHrMin")], "a monotone series has no minimum")
        assertEquals(180.0, x[k][column("minToHrMax")])
        assertEquals(0.0, x[k][column("cycleAvailable")])
        assertTrue(x[k][column("cycleSin")].isNaN())
        assertEquals((k + 0.5) / 60, x[k][column("hoursSinceStart")], 1e-12)
        assertEquals((480 - k - 0.5) / 60, x[k][column("hoursToEnd")], 1e-12)
        assertEquals(0.0, x[k][column("noDataFrac15")])
        assertEquals(ln(4.0), x[200][column("stepsLog1p")], 1e-12)
        assertEquals(ln(4.0), x[195][column("stepsWin5Log1p")], 1e-12)
        assertEquals(ln(4.0), x[205][column("stepsWin5Log1p")], 1e-12)
        assertEquals(0.0, x[194][column("stepsWin5Log1p")], 1e-12)
    }

    @Test
    fun `constant heart rate and long gaps give undefined or neutral values`() {
        val flat = NightPreparer.prepare(SyntheticNightGenerator(5).generate(SyntheticOptions(constantBpm = 60.0)).input, AlgoParams())
        val f = HybridFeatureExtractor.extract(flat)
        val k = f.size / 2
        assertEquals(0.0, f[k][column("relStd15")], 1e-12)
        assertEquals(0.0, f[k][column("slope30")], 1e-12)
        assertTrue(f[k][column("acfLag1")].isNaN(), "constant windows have no correlation")

        val gapNight = SyntheticNightGenerator(6).generate(SyntheticOptions(longGapMin = 60))
        val gap = NightPreparer.prepare(gapNight.input, AlgoParams())
        val g = HybridFeatureExtractor.extract(gap)
        val middle = g.size / 2
        assertEquals(0.0, g[middle][column("hasData")])
        assertEquals(1.0, g[middle][column("noDataFrac15")], 1e-12)
        assertTrue(g[middle][column("relMean5")].isNaN())
        assertTrue(g[middle][column("hrNorm")].isNaN())
    }

    @Test
    fun `features are cached per preparation and shared with decoder-only parameter changes`() {
        val params = AlgoParams()
        val night = NightPreparer.prepare(SyntheticNightGenerator(8).generate().input, params)
        val first = HybridFeatureExtractor.cached(night)

        assertSame(first, HybridFeatureExtractor.cached(night))
        val decoderOnly = night.withParams(params.copy(hybrid = params.hybrid.copy(timePriorWeight = 0.3)))
        assertSame(first, HybridFeatureExtractor.cached(decoderOnly))
        assertSame(night.features, decoderOnly.features)
        val otherFeatures = night.withParams(params.copy(features = params.features.copy(varHalfWindowEpochs = 5)))
        assertNotSame(first, HybridFeatureExtractor.cached(otherFeatures))
        assertSame(night, night.withParams(params))
    }

    @Test
    fun `extract hands out a copy, so callers cannot change later estimates`() {
        val params = AlgoParams()
        val input = SyntheticNightGenerator(8).generate().input
        val stager = SleepStager(MinimalBundles.logreg())
        val night = NightPreparer.prepare(input, params)
        val before = stager.estimateWith(EstimatorType.HYBRID, night)

        val copy = HybridFeatureExtractor.extract(night)
        assertNotSame(HybridFeatureExtractor.cached(night), copy)
        for (row in copy) row.fill(0.0)
        val again = HybridFeatureExtractor.extract(night)

        assertTrue(again.zip(HybridFeatureExtractor.cached(night)).all { (a, b) -> a.contentEquals(b) && a !== b })
        assertTrue(again.any { row -> row.any { it != 0.0 } })
        // The same preparation (and its cache, shared with withParams copies) still gives the same estimate.
        assertEquals(before, stager.estimateWith(EstimatorType.HYBRID, night))
        assertEquals(
            before.stages,
            stager.estimateWith(EstimatorType.HYBRID, night.withParams(params.copy(estimator = EstimatorType.HMM))).stages,
        )
    }
}

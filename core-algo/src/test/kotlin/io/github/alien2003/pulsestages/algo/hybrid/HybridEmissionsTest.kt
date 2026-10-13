package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.algo.model.TimeSpan
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.night.PreparedNight
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.testing.MinimalBundles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

/** The hybrid log emissions written out independently of the implementation (spec section 8). */
class HybridEmissionsTest {
    private val minute = 60_000L
    private val hrNormWeight = listOf(1.5, 0.0, -1.5, 0.7)
    private val bias = listOf(-1.0, 0.5, 0.0, -0.3)
    private val model = MinimalBundles.logreg(hrNormWeight, bias)
    private val synthetic = SyntheticNightGenerator(12).generate()
    private val zero = DoubleArray(SleepStage.COUNT)

    /** log softmax of the minimal bundle's logits `w·hrNorm + b (+ delta)`; hrNorm NaN standardizes to 0. */
    private fun logPosterior(
        hrNorm: Double,
        delta: DoubleArray?,
    ): DoubleArray {
        val x = if (hrNorm.isNaN()) 0.0 else hrNorm
        val logits = DoubleArray(SleepStage.COUNT) { hrNormWeight[it] * x + bias[it] + (delta?.get(it) ?: 0.0) }
        val norm = ln(logits.sumOf { exp(it) })
        return DoubleArray(SleepStage.COUNT) { logits[it] - norm }
    }

    /** `timePriorWeight · (ln max(P(s | bin), ε) − ln prior_s)` with the last bin used past the end of the table. */
    private fun timeTerm(
        k: Int,
        s: Int,
        params: AlgoParams,
    ): Double {
        val table = model.timePrior.table
        val bin = floor((k + 0.5) / model.timePrior.binMinutes).toInt().coerceAtMost(table.size - 1)
        return params.hybrid.timePriorWeight * (ln(maxOf(table[bin][s], params.hmm.epsilon)) - ln(model.classPriors[s]))
    }

    @Test
    fun `scaled likelihoods divide the posteriors and the time prior by the class priors`() {
        val start = synthetic.input.sessionStartMs
        val input =
            synthetic.input.copy(
                steps = emptyList(),
                heartRate = synthetic.input.heartRate.filter { it.timeMs !in start + 100 * minute until start + 140 * minute },
            )
        val params = AlgoParams().let { it.copy(hybrid = it.hybrid.copy(emissionWeight = 1.3, timePriorWeight = 0.7)) }
        val night = NightPreparer.prepare(input, params)
        val deltas = doubleArrayOf(0.2, -0.1, 0.3, 0.0)

        for (delta in listOf(null, deltas)) {
            val rows = HybridEmissions(night, model, DenseNetwork(model), delta).withOffsets(zero)
            for (k in rows.indices) assertRow(night, params, delta, k, rows[k])
        }
        assertTrue((0 until night.grid.sessionEpochs).count { !night.sessionHasData(it) } >= 30, "epochs without data")
        // Epochs past the 3 bins of the table (90 min) use the last row.
        assertTrue(night.grid.sessionEpochs > 300)
    }

    private fun assertRow(
        night: PreparedNight,
        params: AlgoParams,
        delta: DoubleArray?,
        k: Int,
        row: DoubleArray,
        bundle: HybridModel = model,
    ) {
        val posterior = logPosterior(night.features.heartRate.hrNorm[night.grid.position(k)], delta)
        val beta = params.hybrid.priorExponent
        for (s in 0 until SleepStage.COUNT) {
            val bias = bundle.stageLogBias?.get(s) ?: 0.0
            val weight = params.hybrid.emissionWeight
            val scaled = if (night.sessionHasData(k)) weight * (posterior[s] - beta * ln(bundle.classPriors[s])) + bias else 0.0
            assertEquals(scaled + timeTerm(k, s, params), row[s], 1e-9, "k=$k s=$s delta=${delta?.toList()}")
        }
    }

    @Test
    fun `the prior exponent and the bundle's stage log-bias act on the epochs with heart-rate data`() {
        val start = synthetic.input.sessionStartMs
        val input =
            synthetic.input.copy(
                steps = emptyList(),
                heartRate = synthetic.input.heartRate.filter { it.timeMs !in start + 100 * minute until start + 140 * minute },
            )
        val biased = model.copy(stageLogBias = listOf(0.4, -0.1, -0.7, 0.25))
        for (beta in listOf(0.0, 0.35, 1.0, 1.6)) {
            val params =
                AlgoParams().let { it.copy(hybrid = it.hybrid.copy(emissionWeight = 0.8, timePriorWeight = 0.3, priorExponent = beta)) }
            val night = NightPreparer.prepare(input, params)
            for (bundle in listOf(model, biased)) {
                val rows = HybridEmissions(night, bundle, DenseNetwork(bundle), null).withOffsets(zero)
                for (k in rows.indices) assertRow(night, params, null, k, rows[k], bundle)
            }
        }
    }

    @Test
    fun `a change of the prior exponent is a stage log-bias of w_e times the change times ln prior`() {
        // e(beta) = w (ln p - beta ln pi) = e(1) + w (1 - beta) ln pi: both are per-stage constants on the epochs
        // with heart-rate data, so a fitted stage log-bias absorbs any beta (ALGORITHM.md section 7).
        val weight = 0.5
        val beta = 0.25
        val withBeta = AlgoParams().let { it.copy(hybrid = it.hybrid.copy(emissionWeight = weight, priorExponent = beta)) }
        val asBias = withBeta.copy(hybrid = withBeta.hybrid.copy(priorExponent = 1.0))
        val bias = model.classPriors.map { weight * (1 - beta) * ln(it) }
        val biased = model.copy(stageLogBias = bias)
        val nightBeta = NightPreparer.prepare(synthetic.input, withBeta)
        val nightBias = NightPreparer.prepare(synthetic.input, asBias)
        val a = HybridEmissions(nightBeta, model, DenseNetwork(model), null).withOffsets(zero)
        val b = HybridEmissions(nightBias, biased, DenseNetwork(biased), null).withOffsets(zero)
        for (k in a.indices) for (s in 0 until SleepStage.COUNT) assertEquals(a[k][s], b[k][s], 1e-12, "k=$k s=$s")
        val stagesBeta = HybridEstimator(model).estimate(synthetic.input, withBeta).stages
        val stagesBias = HybridEstimator(biased).estimate(synthetic.input, asBias).stages
        assertTrue(stagesBeta.size > 1)
        assertEquals(stagesBeta, stagesBias)
    }

    @Test
    fun `offsets, steps, elevated nights and forced epochs`() {
        val start = synthetic.input.sessionStartMs
        val plainInput = synthetic.input.copy(steps = emptyList())
        val input =
            plainInput.copy(
                steps =
                    listOf(
                        StepsSample(start + 50 * minute, start + 51 * minute, 3),
                        StepsSample(
                            start + 60 * minute,
                            start + 61 * minute,
                            40,
                        ),
                    ),
                forcedAwake = listOf(TimeSpan(start + 70 * minute, start + 72 * minute)),
                baselineNadirBpm = 30.0,
            )
        val params = AlgoParams()
        val plain = HybridEmissions(NightPreparer.prepare(plainInput, params), model, DenseNetwork(model), null).withOffsets(zero)
        val night = NightPreparer.prepare(input, params)
        assertTrue(night.statistics.elevated)
        val offsets = doubleArrayOf(0.1, -0.2, 0.3, 0.4)
        val rows = HybridEmissions(night, model, DenseNetwork(model), null).withOffsets(offsets)
        val elevated = ln(params.hybrid.elevatedAwakeFactor)

        for (k in listOf(10, 50, 200)) {
            val fewSteps = if (k == 50) ln(params.hmm.fewStepsAwakeFactor) else 0.0
            assertEquals(plain[k][0] + offsets[0] + elevated + fewSteps, rows[k][0], 1e-9, "AWAKE at $k")
            for (s in 1 until SleepStage.COUNT) assertEquals(plain[k][s] + offsets[s], rows[k][s], 1e-9, "state $s at $k")
        }
        for (k in listOf(60, 70, 71)) {
            assertTrue(night.isForcedAwake(k))
            assertTrue(rows[k][0].isFinite())
            for (s in 1 until SleepStage.COUNT) assertEquals(Double.NEGATIVE_INFINITY, rows[k][s], "forced epoch $k")
        }
        assertFalse(night.isForcedAwake(50))
    }

    @Test
    fun `fractions of a step from a long record are not steps`() {
        val start = synthetic.input.sessionStartMs
        val plainInput = synthetic.input.copy(steps = emptyList())
        // 20 steps over 8 hours: 0.04 steps per epoch.
        val input = plainInput.copy(steps = listOf(StepsSample(start, start + 480 * minute, 20)))
        val params = AlgoParams()

        val plain = HybridEmissions(NightPreparer.prepare(plainInput, params), model, DenseNetwork(model), null).withOffsets(zero)
        val sparse = HybridEmissions(NightPreparer.prepare(input, params), model, DenseNetwork(model), null).withOffsets(zero)

        for (k in plain.indices step 37) {
            for (s in 0 until SleepStage.COUNT) {
                // The minimal bundle puts no weight on the step features, so only the step rule could differ.
                assertEquals(plain[k][s], sparse[k][s], 1e-9, "k=$k s=$s")
            }
        }
    }
}

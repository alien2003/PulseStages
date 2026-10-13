package io.github.alien2003.pulsestages.algo.testing

import io.github.alien2003.pulsestages.algo.hybrid.Activation
import io.github.alien2003.pulsestages.algo.hybrid.ClassifierSpec
import io.github.alien2003.pulsestages.algo.hybrid.ClassifierType
import io.github.alien2003.pulsestages.algo.hybrid.DenseLayer
import io.github.alien2003.pulsestages.algo.hybrid.GammaDuration
import io.github.alien2003.pulsestages.algo.hybrid.HybridFeatureExtractor
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.algo.hybrid.StageDurations
import io.github.alien2003.pulsestages.algo.hybrid.Standardization
import io.github.alien2003.pulsestages.algo.hybrid.TimePrior
import io.github.alien2003.pulsestages.algo.hybrid.TrainingInfo
import io.github.alien2003.pulsestages.algo.metrics.EpochLabels
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNight
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Test-only training of a hybrid bundle on synthetic nights, standing in for the Python pipeline in tools/train:
 * a multinomial logistic regression on [HybridFeatureExtractor] features (full-batch Nesterov gradient descent with a
 * small L2 penalty), gamma durations by the method of moments on the truth bouts, bout transitions, initial stage and
 * a 30-minute time prior counted from the truth.
 */
object SyntheticBundleTrainer {
    private const val L2 = 1e-4
    private const val ITERATIONS = 400
    private const val MOMENTUM = 0.9
    private const val POWER_ITERATIONS = 30
    private const val BIN_MINUTES = 30.0
    private const val MS_PER_MINUTE = 60_000.0
    private const val SMOOTHING = 0.5

    /** The training samples of [nights]: standardization input, features per labelled epoch and labels. */
    private class Samples(
        val features: List<DoubleArray>,
        val labels: IntArray,
    )

    /** Trains a bundle on [nights] with the preprocessing/feature parameters of [params]. */
    fun train(
        nights: List<SyntheticNight>,
        params: AlgoParams = AlgoParams(),
        modelId: String = "synthetic-test",
    ): HybridModel {
        val samples = samples(nights, params)
        val names = HybridFeatureExtractor.FEATURE_NAMES
        val mean =
            DoubleArray(names.size) { j ->
                samples.features
                    .map { it[j] }
                    .filter { !it.isNaN() }
                    .average()
                    .orZero()
            }
        val std =
            DoubleArray(names.size) { j ->
                val values = samples.features.map { it[j] }.filter { !it.isNaN() }
                val m = mean[j]
                val sd = sqrt(values.sumOf { (it - m) * (it - m) } / values.size.coerceAtLeast(1))
                if (sd > 1e-9 && sd.isFinite()) sd else 1.0
            }
        val z = samples.features.map { x -> DoubleArray(x.size) { j -> ((x[j] - mean[j]) / std[j]).let { if (it.isNaN()) 0.0 else it } } }
        val (weights, bias) = softmaxRegression(z, samples.labels)
        val counts = IntArray(SleepStage.COUNT).also { c -> samples.labels.forEach { c[it]++ } }
        val truths = nights.map { it.truth }
        return HybridModel(
            schemaVersion = HybridModel.SCHEMA_VERSION,
            modelId = modelId,
            featureSetVersion = HybridFeatureExtractor.FEATURE_SET_VERSION,
            featureNames = names,
            standardization = Standardization(mean.toList(), std.toList()),
            classOrder = SleepStage.SCORED.map { it.name },
            classifier =
                ClassifierSpec(
                    ClassifierType.LOGREG,
                    listOf(DenseLayer(weights.map { it.toList() }, bias.toList(), Activation.SOFTMAX)),
                ),
            classPriors = normalized(counts.map { it + SMOOTHING }),
            durations = durations(truths),
            boutTransitions = boutTransitions(truths),
            initialStage = normalized(SleepStage.SCORED.map { s -> truths.count { it.first().stage == s } + SMOOTHING }),
            timePrior = timePrior(truths),
            training = TrainingInfo(dataset = "synthetic", nights = nights.size, notes = "test fixture"),
        )
    }

    private fun samples(
        nights: List<SyntheticNight>,
        params: AlgoParams,
    ): Samples {
        val features = ArrayList<DoubleArray>()
        val labels = ArrayList<Int>()
        for (night in nights) {
            val prepared = NightPreparer.prepare(night.input, params)
            val x = HybridFeatureExtractor.extract(prepared)
            val stages = EpochLabels.majority(night.truth, prepared.grid)
            for (k in x.indices) {
                val stage = stages[k]
                if (stage != null && prepared.sessionHasData(k)) {
                    features += x[k]
                    labels += stage.index
                }
            }
        }
        return Samples(features, labels.toIntArray())
    }

    /** Softmax regression `W (4×F), b` by Nesterov-accelerated gradient descent on the mean cross-entropy. */
    private fun softmaxRegression(
        z: List<DoubleArray>,
        labels: IntArray,
    ): Pair<Array<DoubleArray>, DoubleArray> {
        val f = z.first().size
        val k = SleepStage.COUNT
        val step = 1.0 / (0.5 * (largestEigenvalue(z) + 1) + 2 * L2)
        val theta = Array(k) { DoubleArray(f + 1) }
        val velocity = Array(k) { DoubleArray(f + 1) }
        repeat(ITERATIONS) {
            val lookahead = Array(k) { c -> DoubleArray(f + 1) { theta[c][it] + MOMENTUM * velocity[c][it] } }
            val gradient = gradient(lookahead, z, labels)
            for (c in 0 until k) {
                for (j in 0..f) {
                    velocity[c][j] = MOMENTUM * velocity[c][j] - step * gradient[c][j]
                    theta[c][j] += velocity[c][j]
                }
            }
        }
        return Array(k) { c -> theta[c].copyOf(f) } to DoubleArray(k) { theta[it][f] }
    }

    private fun gradient(
        theta: Array<DoubleArray>,
        z: List<DoubleArray>,
        labels: IntArray,
    ): Array<DoubleArray> {
        val f = z.first().size
        val g = Array(theta.size) { DoubleArray(f + 1) }
        val logits = DoubleArray(theta.size)
        for (i in z.indices) {
            val x = z[i]
            for (c in theta.indices) {
                var v = theta[c][f]
                for (j in 0 until f) v += theta[c][j] * x[j]
                logits[c] = v
            }
            val max = logits.max()
            val norm = logits.sumOf { exp(it - max) }
            for (c in theta.indices) {
                val residual = exp(logits[c] - max) / norm - if (labels[i] == c) 1.0 else 0.0
                for (j in 0 until f) g[c][j] += residual * x[j]
                g[c][f] += residual
            }
        }
        for (c in g.indices) {
            for (j in 0..f) g[c][j] = g[c][j] / z.size + if (j < f) 2 * L2 * theta[c][j] else 0.0
        }
        return g
    }

    /** Largest eigenvalue of `XᵀX / n` by power iteration (bounds the curvature of the cross-entropy). */
    private fun largestEigenvalue(z: List<DoubleArray>): Double {
        val f = z.first().size
        var v = DoubleArray(f) { 1.0 / sqrt(f.toDouble()) }
        var lambda = 1.0
        repeat(POWER_ITERATIONS) {
            val w = DoubleArray(f)
            for (x in z) {
                var dot = 0.0
                for (j in 0 until f) dot += x[j] * v[j]
                for (j in 0 until f) w[j] += dot * x[j] / z.size
            }
            lambda = sqrt(w.sumOf { it * it })
            v = DoubleArray(f) { w[it] / lambda }
        }
        return lambda
    }

    private fun durations(truths: List<List<StageInterval>>): StageDurations {
        fun gamma(stage: SleepStage): GammaDuration {
            val minutes = truths.flatten().filter { it.stage == stage }.map { (it.endMs - it.startMs) / MS_PER_MINUTE }
            val m = minutes.average()
            val v = minutes.sumOf { (it - m) * (it - m) } / minutes.size
            return if (v > 0) GammaDuration(shape = m * m / v, scale = v / m) else GammaDuration(shape = 50.0, scale = m / 50)
        }
        return StageDurations(gamma(SleepStage.AWAKE), gamma(SleepStage.LIGHT), gamma(SleepStage.DEEP), gamma(SleepStage.REM))
    }

    private fun boutTransitions(truths: List<List<StageInterval>>): List<List<Double>> {
        val counts = Array(SleepStage.COUNT) { DoubleArray(SleepStage.COUNT) }
        for (truth in truths) truth.zipWithNext().forEach { (a, b) -> counts[a.stage.index][b.stage.index] += 1.0 }
        return counts.mapIndexed { i, row ->
            if (row.sum() > 0) normalized(row.toList()) else normalized(List(SleepStage.COUNT) { if (it == i) 0.0 else 1.0 })
        }
    }

    private fun timePrior(truths: List<List<StageInterval>>): TimePrior {
        val bins = ArrayList<DoubleArray>()
        for (truth in truths) {
            val start = truth.first().startMs
            for (interval in truth) countMinutes(interval, start, bins)
        }
        return TimePrior(BIN_MINUTES, bins.map { normalized(it.toList()) })
    }

    private fun countMinutes(
        interval: StageInterval,
        sessionStart: Long,
        bins: MutableList<DoubleArray>,
    ) {
        var t = interval.startMs
        while (t < interval.endMs) {
            val bin = ((t - sessionStart) / MS_PER_MINUTE / BIN_MINUTES).toInt()
            while (bins.size <= bin) bins += DoubleArray(SleepStage.COUNT) { SMOOTHING }
            bins[bin][interval.stage.index] += 1.0
            t += MS_PER_MINUTE.toLong()
        }
    }

    private fun normalized(values: List<Double>): List<Double> {
        val sum = values.sum()
        return values.map { it / sum }
    }

    private fun Double.orZero(): Double = if (isNaN()) 0.0 else this
}

/** Bundles trained once per test JVM. */
object SyntheticHybridModels {
    /** Seeds of the training nights (disjoint from every evaluation seed used by the tests). */
    val TRAINING_SEEDS: LongRange = 1001L..1040L

    /** A bundle trained on the default synthetic person, nights [TRAINING_SEEDS]. */
    val default: HybridModel by lazy {
        SyntheticBundleTrainer.train(TRAINING_SEEDS.map { SyntheticNightGenerator(it).generate() })
    }
}

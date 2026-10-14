package io.github.alien2003.pulsestages.algo.testing

import io.github.alien2003.pulsestages.algo.hybrid.Activation
import io.github.alien2003.pulsestages.algo.hybrid.ClassifierSpec
import io.github.alien2003.pulsestages.algo.hybrid.ClassifierType
import io.github.alien2003.pulsestages.algo.hybrid.DenseLayer
import io.github.alien2003.pulsestages.algo.hybrid.GammaDuration
import io.github.alien2003.pulsestages.algo.hybrid.HybridFeatureExtractor
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.algo.hybrid.NapSubModel
import io.github.alien2003.pulsestages.algo.hybrid.StageDurations
import io.github.alien2003.pulsestages.algo.hybrid.Standardization
import io.github.alien2003.pulsestages.algo.hybrid.TimePrior
import io.github.alien2003.pulsestages.algo.hybrid.TrainingInfo
import io.github.alien2003.pulsestages.algo.model.SleepStage

/** Small hand-made bundles for unit tests. */
object MinimalBundles {
    /**
     * A valid logreg bundle over [HybridFeatureExtractor.FEATURE_NAMES] whose classifier puts weight [hrNormWeight] on
     * hrNorm for each class (AWAKE, LIGHT, DEEP, REM) plus [bias].
     */
    fun logreg(
        hrNormWeight: List<Double> = listOf(1.5, 0.0, -1.5, 0.7),
        bias: List<Double> = listOf(-1.0, 0.5, 0.0, -0.3),
        featureNames: List<String> = HybridFeatureExtractor.FEATURE_NAMES,
    ): HybridModel {
        val f = featureNames.size
        val hrNorm = featureNames.indexOf("hrNorm").coerceAtLeast(0)
        return HybridModel(
            schemaVersion = HybridModel.SCHEMA_VERSION,
            modelId = "minimal",
            featureSetVersion = HybridFeatureExtractor.FEATURE_SET_VERSION,
            featureNames = featureNames,
            standardization = Standardization(List(f) { 0.0 }, List(f) { 1.0 }),
            classOrder = SleepStage.SCORED.map { it.name },
            classifier =
                ClassifierSpec(
                    ClassifierType.LOGREG,
                    listOf(
                        DenseLayer(
                            weights = List(SleepStage.COUNT) { c -> List(f) { j -> if (j == hrNorm) hrNormWeight[c] else 0.0 } },
                            bias = bias,
                            activation = Activation.SOFTMAX,
                        ),
                    ),
                ),
            classPriors = listOf(0.1, 0.5, 0.2, 0.2),
            durations =
                StageDurations(
                    awake = GammaDuration(1.5, 4.0),
                    light = GammaDuration(2.0, 12.0),
                    deep = GammaDuration(3.0, 8.0),
                    rem = GammaDuration(2.5, 8.0),
                ),
            boutTransitions =
                listOf(
                    listOf(0.0, 0.8, 0.05, 0.15),
                    listOf(0.2, 0.0, 0.45, 0.35),
                    listOf(0.1, 0.85, 0.0, 0.05),
                    listOf(0.3, 0.7, 0.0, 0.0),
                ),
            initialStage = listOf(0.7, 0.3, 0.0, 0.0),
            timePrior =
                TimePrior(
                    binMinutes = 30.0,
                    table = listOf(listOf(0.5, 0.4, 0.1, 0.0), listOf(0.1, 0.5, 0.25, 0.15), listOf(0.1, 0.5, 0.15, 0.25)),
                ),
        )
    }
}

/** Nap sections ([NapSubModel]) for tests. */
object NapModels {
    /** A nap section with the components of [model] and the log-bias [stageLogBias] (default: the model's own). */
    fun from(
        model: HybridModel,
        stageLogBias: List<Double>? = model.stageLogBias,
    ): NapSubModel =
        NapSubModel(
            featureSetVersion = model.featureSetVersion,
            featureNames = model.featureNames,
            standardization = model.standardization,
            classifier = model.classifier,
            classPriors = model.classPriors,
            durations = model.durations,
            boutTransitions = model.boutTransitions,
            initialStage = model.initialStage,
            timePrior = model.timePrior,
            stageLogBias = stageLogBias,
            training = TrainingInfo(notes = "test nap model from ${model.modelId}"),
        )
}

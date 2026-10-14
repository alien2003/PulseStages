package io.github.alien2003.pulsestages.algo.hybrid

import io.github.alien2003.pulsestages.algo.Fingerprints
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.params.EmissionTable
import io.github.alien2003.pulsestages.algo.params.MeanBouts
import io.github.alien2003.pulsestages.algo.params.TransitionWeights
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs

/** A model bundle that cannot be parsed or is internally inconsistent. */
class HybridModelException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Feature standardization of the training set: `z = (x − mean) / std`.
 *
 * @property mean per feature, in [HybridModel.featureNames] order.
 * @property std per feature, positive.
 */
@Serializable
data class Standardization(
    val mean: List<Double>,
    val std: List<Double>,
)

/** Kind of emission classifier. */
@Serializable
enum class ClassifierType {
    /** Multinomial logistic regression: one softmax layer. */
    @SerialName("logreg")
    LOGREG,

    /** Multi-layer perceptron: relu hidden layers and a softmax output layer. */
    @SerialName("mlp")
    MLP,
}

/** Activation of a dense layer. */
@Serializable
enum class Activation {
    /** `max(0, x)`. */
    @SerialName("relu")
    RELU,

    /** Normalized exponential (output layer only). */
    @SerialName("softmax")
    SOFTMAX,
}

/**
 * A dense layer `activation(W·x + b)`.
 *
 * @property weights `out × in` matrix (one row per output unit).
 * @property bias one entry per output unit.
 * @property activation relu (hidden layers) or softmax (output layer).
 */
@Serializable
data class DenseLayer(
    val weights: List<List<Double>>,
    val bias: List<Double>,
    val activation: Activation,
)

/**
 * The emission classifier: [layers] applied in order to the standardized features.
 *
 * @property type logreg (one layer) or mlp.
 * @property layers the dense layers.
 */
@Serializable
data class ClassifierSpec(
    val type: ClassifierType,
    val layers: List<DenseLayer>,
)

/**
 * Gamma distribution of bout lengths in minutes (mean = shape · scale).
 *
 * @property shape shape k > 0.
 * @property scale scale θ > 0, minutes.
 */
@Serializable
data class GammaDuration(
    val shape: Double,
    val scale: Double,
)

/**
 * Bout-duration distributions per stage.
 *
 * @property awake gamma bout-length distribution of AWAKE bouts (minutes).
 * @property light gamma bout-length distribution of LIGHT bouts (minutes).
 * @property deep gamma bout-length distribution of DEEP bouts (minutes).
 * @property rem gamma bout-length distribution of REM bouts (minutes).
 */
@Serializable
data class StageDurations(
    @SerialName("AWAKE") val awake: GammaDuration,
    @SerialName("LIGHT") val light: GammaDuration,
    @SerialName("DEEP") val deep: GammaDuration,
    @SerialName("REM") val rem: GammaDuration,
) {
    /** Distributions in [SleepStage.index] order. */
    fun toList(): List<GammaDuration> = listOf(awake, light, deep, rem)
}

/**
 * Stage distribution per time bin since the session start.
 *
 * @property binMinutes bin width in minutes.
 * @property table one row `[pA, pL, pD, pR]` per bin; times past the last bin use the last row.
 */
@Serializable
data class TimePrior(
    val binMinutes: Double,
    val table: List<List<Double>>,
)

/**
 * Supervised MLE estimates for the HMM estimator, as fitted by the training scripts. They are informational: the
 * HMM uses `AlgoParams.hmm` (which the training pipeline may set from these values).
 *
 * @property emissions Gaussian emission table (μ/σ of hrNorm, hrVar, hrSlope per stage), or null.
 * @property meanBouts mean bout durations in minutes, or null.
 * @property transitions shares of the non-self transition mass, or null.
 */
@Serializable
data class HmmEstimates(
    val emissions: EmissionTable? = null,
    val meanBouts: MeanBouts? = null,
    val transitions: TransitionWeights? = null,
)

/**
 * Provenance of the bundle (free text shown in the UI and the reports).
 *
 * @property dataset name of the training dataset(s).
 * @property license license of the training data.
 * @property subjects number of training subjects.
 * @property nights number of training nights.
 * @property variants heart-rate emulation variants used for training (e.g. "instantaneous", "mean2min").
 * @property createdAt creation time, ISO-8601.
 * @property notes free-text notes.
 */
@Serializable
data class TrainingInfo(
    val dataset: String? = null,
    val license: String? = null,
    val subjects: Int? = null,
    val nights: Int? = null,
    val variants: List<String>? = null,
    val createdAt: String? = null,
    val notes: String? = null,
)

/**
 * The trained parts of a hybrid model that the decoder uses: the emission classifier with its standardization and
 * feature list, the class priors, the gamma bout durations, the bout transitions, the initial bout distribution, the
 * time-of-night prior and the optional per-stage log-bias. [HybridModel] (the main model of a bundle, fitted on whole
 * nights) and [NapSubModel] (the bundle's optional nap model) both have them; every vector is in [SleepStage.index]
 * order, and both are validated by the same rules.
 */
interface HybridComponents {
    /** Must equal [HybridFeatureExtractor.FEATURE_SET_VERSION] to be usable. */
    val featureSetVersion: Int

    /** Must equal [HybridFeatureExtractor.FEATURE_NAMES] to be usable. */
    val featureNames: List<String>

    /** Training-set feature means and standard deviations. */
    val standardization: Standardization

    /** Dense layers of the emission classifier. */
    val classifier: ClassifierSpec

    /** Training class frequencies; posteriors are divided by them. */
    val classPriors: List<Double>

    /** Gamma bout-length distributions in minutes. */
    val durations: StageDurations

    /** `P(next bout stage | current bout stage)`, row = from. */
    val boutTransitions: List<List<Double>>

    /** Distribution of the first bout's stage. */
    val initialStage: List<Double>

    /** `P(stage | time bin since the session start)`. */
    val timePrior: TimePrior

    /** Optional per-stage additive log-bias `[bA, bL, bD, bR]` of the scaled likelihoods (null = no bias). */
    val stageLogBias: List<Double>?

    /**
     * Why these components cannot be used with this build ([HybridFeatureExtractor.FEATURE_SET_VERSION] or
     * [HybridFeatureExtractor.FEATURE_NAMES] differ), or null when they are compatible.
     */
    fun compatibilityProblem(): String? =
        when {
            featureSetVersion != HybridFeatureExtractor.FEATURE_SET_VERSION -> {
                "featureSetVersion $featureSetVersion != ${HybridFeatureExtractor.FEATURE_SET_VERSION}"
            }

            featureNames != HybridFeatureExtractor.FEATURE_NAMES -> {
                val index = featureNames.indices.firstOrNull { featureNames[it] != HybridFeatureExtractor.FEATURE_NAMES.getOrNull(it) }
                val position = index ?: featureNames.size
                "featureNames differ at index $position: '${featureNames.getOrNull(position)}' != " +
                    "'${HybridFeatureExtractor.FEATURE_NAMES.getOrNull(position)}'"
            }

            else -> {
                null
            }
        }
}

/**
 * The optional nap model of a bundle (key `nap`, docs/INTERCHANGE.md §3): a complete second set of [HybridComponents]
 * fitted on nap-length sessions, used for sessions of [io.github.alien2003.pulsestages.algo.model.SessionMode.NAP]
 * instead of the main model (which then also replaces its `stageLogBiasShort` by this model's [stageLogBias]). It shares
 * the bundle's `schemaVersion`, `classOrder` and `modelId` and is part of the bundle's fingerprint. Personal classifier
 * deltas are fitted for the main classifier and never apply to it.
 *
 * @property featureSetVersion must equal [HybridFeatureExtractor.FEATURE_SET_VERSION] to be usable.
 * @property featureNames must equal [HybridFeatureExtractor.FEATURE_NAMES] to be usable.
 * @property standardization training-set feature means and standard deviations.
 * @property classifier dense layers of the nap emission classifier.
 * @property classPriors training class frequencies of the nap classifier.
 * @property durations gamma bout-length distributions of naps, in minutes.
 * @property boutTransitions bout transitions of naps, row = from.
 * @property initialStage distribution of a nap's first bout stage.
 * @property timePrior `P(stage | time bin since the session start)` of naps.
 * @property stageLogBias optional per-stage log-bias of the nap classifier's scaled likelihoods (null = no bias).
 * @property training optional provenance of the nap model.
 */
@Serializable
data class NapSubModel(
    override val featureSetVersion: Int,
    override val featureNames: List<String>,
    override val standardization: Standardization,
    override val classifier: ClassifierSpec,
    override val classPriors: List<Double>,
    override val durations: StageDurations,
    override val boutTransitions: List<List<Double>>,
    override val initialStage: List<Double>,
    override val timePrior: TimePrior,
    override val stageLogBias: List<Double>? = null,
    val training: TrainingInfo? = null,
) : HybridComponents

/**
 * The hybrid model bundle (docs/INTERCHANGE.md §3): standardization and weights of the emission classifier, class
 * priors, gamma bout durations, bout transitions, the initial bout distribution, the time-of-night prior and an
 * optional per-stage log-bias of the scaled likelihoods (the main model, [HybridComponents]), plus an optional nap
 * model ([nap]).
 *
 * Construction validates the structure (dimensions, probability vectors summing to 1, positive gamma parameters and
 * standard deviations, finite weights) and throws [HybridModelException] otherwise. Whether the bundle fits this
 * build's feature extractor is a separate question ([compatibilityProblem]): an incompatible bundle is still a valid
 * object, and the estimator falls back to the HMM with `HYBRID_MODEL_UNAVAILABLE`.
 *
 * All probability vectors are in [SleepStage.index] order; [classOrder] must be `["AWAKE", "LIGHT", "DEEP", "REM"]`.
 *
 * @property schemaVersion bundle format version ([SCHEMA_VERSION]).
 * @property modelId free-text id shown in the UI and stored with the estimates.
 * @property featureSetVersion must equal [HybridFeatureExtractor.FEATURE_SET_VERSION] to be usable.
 * @property featureNames must equal [HybridFeatureExtractor.FEATURE_NAMES] to be usable.
 * @property standardization training-set feature means and standard deviations.
 * @property classOrder output order of the classifier.
 * @property classifier dense layers of the emission classifier.
 * @property classPriors training class frequencies; posteriors are divided by them.
 * @property durations gamma bout-length distributions in minutes.
 * @property boutTransitions `P(next bout stage | current bout stage)`, row = from.
 * @property initialStage distribution of the first bout's stage.
 * @property timePrior `P(stage | time bin since the session start)`.
 * @property hmm optional supervised estimates for the HMM (informational).
 * @property training optional provenance.
 * @property stageLogBias optional per-stage additive log-bias `[bA, bL, bD, bR]` of the scaled likelihoods (added to
 *   the emission score of every epoch with heart-rate data; null = no bias). Fitted by the training scripts on
 *   out-of-fold decodes of the training nights so that the decoded stage shares match the true ones (proportion
 *   matching); it belongs to the classifier it corrects, hence to the bundle and its fingerprint.
 * @property stageLogBiasShort optional per-stage log-bias `[bA, bL, bD, bR]` used INSTEAD of [stageLogBias] on
 *   naps (sessions shorter than `napMode.maxSessionMin` minutes) when the bundle has no [nap] model (null = naps keep
 *   [stageLogBias]). Fitted like [stageLogBias], on out-of-fold decodes of nap-length sessions cut from the training
 *   nights.
 * @property nap optional nap model ([NapSubModel]): when present, naps are decoded with it instead of the main model
 *   and [stageLogBiasShort] is not used; null = naps use the main model with [stageLogBiasShort].
 */
@Serializable
data class HybridModel(
    val schemaVersion: Int,
    val modelId: String,
    override val featureSetVersion: Int,
    override val featureNames: List<String>,
    override val standardization: Standardization,
    val classOrder: List<String>,
    override val classifier: ClassifierSpec,
    override val classPriors: List<Double>,
    override val durations: StageDurations,
    override val boutTransitions: List<List<Double>>,
    override val initialStage: List<Double>,
    override val timePrior: TimePrior,
    val hmm: HmmEstimates? = null,
    val training: TrainingInfo? = null,
    override val stageLogBias: List<Double>? = null,
    val stageLogBiasShort: List<Double>? = null,
    val nap: NapSubModel? = null,
) : HybridComponents {
    init {
        HybridModelValidator.validate(this)
    }

    private val fingerprintValue: String by lazy {
        val tree = JSON.encodeToJsonElement(serializer(), this).jsonObject
        Fingerprints.of(JsonObject(tree.filterNot { (key, value) -> key in OMITTED_WHEN_ABSENT && value is JsonNull }))
    }

    /**
     * SHA-256 (lower-case hex) of the canonical JSON of the bundle (every field, object keys sorted, no whitespace;
     * the [nap] model included). It identifies the model in the params hash. Optional fields introduced after the
     * first bundles ([stageLogBias], [stageLogBiasShort], [nap]) are left out when absent, so a bundle without them
     * keeps the fingerprint it always had (and personal models fitted for it stay valid).
     */
    fun fingerprint(): String = fingerprintValue

    /**
     * Why the nap model cannot be used with this build ([HybridComponents.compatibilityProblem] of [nap]), or null
     * when it is compatible or absent.
     */
    fun napCompatibilityProblem(): String? = nap?.compatibilityProblem()

    /** The bundle as pretty-printed JSON (the format read by [parse]). */
    fun toJson(): String = PRETTY.encodeToString(serializer(), this)

    /** Parsing. */
    companion object {
        /** The only supported [schemaVersion]. */
        const val SCHEMA_VERSION: Int = 1

        /** Optional keys that do not enter the fingerprint when they are null (added after schema 1 shipped). */
        private val OMITTED_WHEN_ABSENT: Set<String> = setOf("stageLogBias", "stageLogBiasShort", "nap")

        private val JSON: Json =
            Json {
                encodeDefaults = true
                explicitNulls = true
                ignoreUnknownKeys = true
            }

        private val PRETTY: Json =
            Json(JSON) {
                prettyPrint = true
                explicitNulls = false
            }

        /**
         * Parses and validates a bundle. Unknown keys are ignored (forward compatibility).
         *
         * @throws HybridModelException if the JSON is malformed or the bundle is inconsistent.
         */
        fun parse(json: String): HybridModel =
            try {
                JSON.decodeFromString(serializer(), json)
            } catch (e: SerializationException) {
                throw HybridModelException("malformed hybrid model bundle: ${e.message}", e)
            }
    }
}

/** Structural checks of [HybridModel] (see its KDoc). */
internal object HybridModelValidator {
    /** Probability vectors must sum to 1 within this tolerance. */
    const val SUM_TOLERANCE = 1e-3

    private val CLASS_ORDER = SleepStage.SCORED.map { it.name }

    fun validate(model: HybridModel) {
        ensure(model.schemaVersion == HybridModel.SCHEMA_VERSION) { "unsupported schemaVersion ${model.schemaVersion}" }
        ensure(model.classOrder == CLASS_ORDER) { "classOrder must be $CLASS_ORDER, was ${model.classOrder}" }
        validateComponents(model, prefix = "")
        model.hmm?.emissions?.let { validateEmissions(it) }
        stageBias("stageLogBiasShort", model.stageLogBiasShort)
        model.nap?.let { validateComponents(it, prefix = "nap.") }
    }

    /** The checks shared by the main model and the nap model; [prefix] names the section in the messages. */
    private fun validateComponents(
        model: HybridComponents,
        prefix: String,
    ) {
        ensure(model.featureNames.isNotEmpty()) { "${prefix}featureNames is empty" }
        ensure(model.featureNames.toSet().size == model.featureNames.size) { "${prefix}featureNames contains duplicates" }
        validateStandardization(model.standardization, model.featureNames.size, prefix)
        validateClassifier(model.classifier, model.featureNames.size, prefix)
        probabilities("${prefix}classPriors", model.classPriors, strictlyPositive = true)
        for ((stage, gamma) in SleepStage.SCORED.zip(model.durations.toList())) {
            ensure(gamma.shape > 0 && gamma.shape.isFinite() && gamma.scale > 0 && gamma.scale.isFinite()) {
                "${prefix}durations.$stage needs positive finite shape and scale, was $gamma"
            }
        }
        ensure(model.boutTransitions.size == SleepStage.COUNT) { "${prefix}boutTransitions must have ${SleepStage.COUNT} rows" }
        model.boutTransitions.forEachIndexed { i, row -> probabilities("${prefix}boutTransitions[$i]", row) }
        probabilities("${prefix}initialStage", model.initialStage)
        ensure(model.timePrior.binMinutes > 0 && model.timePrior.binMinutes.isFinite()) { "${prefix}timePrior.binMinutes must be positive" }
        ensure(model.timePrior.table.isNotEmpty()) { "${prefix}timePrior.table is empty" }
        model.timePrior.table.forEachIndexed { i, row -> probabilities("${prefix}timePrior.table[$i]", row) }
        stageBias("${prefix}stageLogBias", model.stageLogBias)
    }

    private fun stageBias(
        name: String,
        bias: List<Double>?,
    ) {
        if (bias == null) return
        ensure(bias.size == SleepStage.COUNT) { "$name must have ${SleepStage.COUNT} entries, has ${bias.size}" }
        ensure(bias.all { it.isFinite() }) { "$name entries must be finite: $bias" }
    }

    private fun validateStandardization(
        standardization: Standardization,
        features: Int,
        prefix: String,
    ) {
        ensure(standardization.mean.size == features && standardization.std.size == features) {
            "${prefix}standardization needs $features means and stds, has ${standardization.mean.size} and ${standardization.std.size}"
        }
        ensure(standardization.mean.all { it.isFinite() }) { "${prefix}standardization.mean must be finite" }
        ensure(standardization.std.all { it > 0 && it.isFinite() }) { "${prefix}standardization.std must be positive and finite" }
    }

    private fun validateClassifier(
        classifier: ClassifierSpec,
        features: Int,
        prefix: String,
    ) {
        val layers = classifier.layers
        ensure(layers.isNotEmpty()) { "${prefix}classifier has no layers" }
        ensure(classifier.type != ClassifierType.LOGREG || layers.size == 1) {
            "${prefix}logreg must have exactly one layer, has ${layers.size}"
        }
        var inputs = features
        layers.forEachIndexed { index, layer ->
            val outputs = layer.bias.size
            ensure(outputs > 0 && layer.weights.size == outputs) {
                "${prefix}layer $index: ${layer.weights.size} weight rows for $outputs biases"
            }
            ensure(layer.weights.all { it.size == inputs }) { "${prefix}layer $index: every weight row needs $inputs inputs" }
            ensure(layer.bias.all { it.isFinite() } && layer.weights.all { row -> row.all { it.isFinite() } }) {
                "${prefix}layer $index: weights and biases must be finite"
            }
            val expected = if (index == layers.lastIndex) Activation.SOFTMAX else Activation.RELU
            ensure(layer.activation == expected) { "${prefix}layer $index: activation must be $expected, was ${layer.activation}" }
            inputs = outputs
        }
        ensure(inputs == SleepStage.COUNT) { "${prefix}the output layer must have ${SleepStage.COUNT} units, has $inputs" }
    }

    private fun validateEmissions(table: EmissionTable) {
        val gaussians = listOf(table.awake, table.light, table.deep, table.rem).flatMap { listOf(it.hrNorm, it.hrVar, it.hrSlope) }
        ensure(
            gaussians.all { it.mean.isFinite() && it.sd > 0 && it.sd.isFinite() },
        ) { "hmm.emissions needs finite means and positive sds" }
    }

    private fun probabilities(
        name: String,
        values: List<Double>,
        strictlyPositive: Boolean = false,
    ) {
        ensure(values.size == SleepStage.COUNT) { "$name must have ${SleepStage.COUNT} entries, has ${values.size}" }
        ensure(values.all { it.isFinite() && (if (strictlyPositive) it > 0 else it >= 0) }) {
            "$name entries must be ${if (strictlyPositive) "positive" else "non-negative"}: $values"
        }
        ensure(abs(values.sum() - 1) <= SUM_TOLERANCE) { "$name must sum to 1, sums to ${values.sum()}" }
    }

    private inline fun ensure(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) throw HybridModelException(message())
    }
}

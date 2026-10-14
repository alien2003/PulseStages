package io.github.alien2003.pulsestages.algo.personal

import io.github.alien2003.pulsestages.algo.Fingerprints
import io.github.alien2003.pulsestages.algo.hmm.EmissionDims
import io.github.alien2003.pulsestages.algo.hmm.dim
import io.github.alien2003.pulsestages.algo.hmm.emissionTableOf
import io.github.alien2003.pulsestages.algo.hmm.forState
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.params.EmissionTable
import io.github.alien2003.pulsestages.algo.params.Gaussian
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** How a [PersonalModel] was obtained. */
@Serializable
enum class PersonalModelKind {
    /** Supervised calibration on reference nights with true stages ([PersonalCalibrator]). */
    CALIBRATED,

    /** Unsupervised EM over many of the user's nights ([PopulationEmAdapter]). */
    EM,
}

/**
 * One value per HMM emission dimension.
 *
 * @property hrNorm value for hrNorm.
 * @property hrVar value for the variability dimension (hrVar, or the RMSSD feature where it replaces hrVar).
 * @property hrSlope value for hrSlope.
 */
@Serializable
data class DimValues(
    val hrNorm: Double,
    val hrVar: Double,
    val hrSlope: Double,
) {
    /** The value of [EmissionDims] dimension [dim]. */
    internal operator fun get(dim: Int): Double =
        when (dim) {
            EmissionDims.HR_NORM -> hrNorm
            EmissionDims.VARIABILITY -> hrVar
            EmissionDims.SLOPE -> hrSlope
            else -> throw IllegalArgumentException("no emission dimension $dim")
        }

    internal fun all(): List<Double> = listOf(hrNorm, hrVar, hrSlope)
}

/**
 * [DimValues] per scored stage (μ or σ overrides of the HMM emission table).
 *
 * @property awake values of AWAKE.
 * @property light values of LIGHT.
 * @property deep values of DEEP.
 * @property rem values of REM.
 */
@Serializable
data class StageDimValues(
    val awake: DimValues,
    val light: DimValues,
    val deep: DimValues,
    val rem: DimValues,
) {
    /** The values of the stage with [SleepStage.index] [state]. */
    internal fun forState(state: Int): DimValues =
        when (state) {
            SleepStage.AWAKE.index -> awake
            SleepStage.LIGHT.index -> light
            SleepStage.DEEP.index -> deep
            SleepStage.REM.index -> rem
            else -> throw IllegalArgumentException("no scored stage with index $state")
        }

    internal fun all(): List<Double> = listOf(awake, light, deep, rem).flatMap { it.all() }

    /** Constructors. */
    companion object {
        /** The means of [table]. */
        fun meansOf(table: EmissionTable): StageDimValues = of { state, dim -> table.forState(state).dim(dim).mean }

        /** The standard deviations of [table]. */
        fun sdsOf(table: EmissionTable): StageDimValues = of { state, dim -> table.forState(state).dim(dim).sd }

        internal fun of(value: (state: Int, dim: Int) -> Double): StageDimValues {
            fun dims(state: Int) =
                DimValues(value(state, EmissionDims.HR_NORM), value(state, EmissionDims.VARIABILITY), value(state, EmissionDims.SLOPE))
            return StageDimValues(
                awake = dims(SleepStage.AWAKE.index),
                light = dims(SleepStage.LIGHT.index),
                deep = dims(SleepStage.DEEP.index),
                rem = dims(SleepStage.REM.index),
            )
        }
    }
}

/**
 * Where a personal model comes from.
 *
 * @property kind calibration on reference nights or population EM.
 * @property nightsUsed nights the parameters were fitted on.
 * @property kappaBefore Cohen's kappa of the global model on the held-out reference nights (calibration only).
 * @property kappaAfter kappa with this personal model on the same nights (calibration only).
 * @property heldOutNights number of held-out reference nights behind the kappas.
 * @property estimator estimator the kappas were measured with.
 */
@Serializable
data class PersonalProvenance(
    val kind: PersonalModelKind,
    val nightsUsed: Int,
    val kappaBefore: Double? = null,
    val kappaAfter: Double? = null,
    val heldOutNights: Int = 0,
    val estimator: EstimatorType? = null,
)

/**
 * Personal adjustments of the global models (section 10 of the specification), stored by the app as JSON
 * ([toJson]/[fromJson]) and applied by [io.github.alien2003.pulsestages.algo.SleepStager] when
 * `AlgoParams.personal.enabled`. Its [fingerprint] enters the params hash; it never changes the algorithm version.
 *
 * @property biasDeltas additive deltas of the hybrid classifier's output logits ([SleepStage.index] order), or null.
 * @property baseModelFingerprint the night model the deltas were fitted for: its
 *   [io.github.alien2003.pulsestages.algo.hybrid.HybridModel.nightModelFingerprint] (models stored before it existed
 *   hold the whole bundle's fingerprint, which still matches that bundle); the deltas are ignored with any other model
 *   (null = any model).
 * @property hmmMeans HMM emission μ replacing those of `AlgoParams.hmm.emissions`, or null.
 * @property hmmSds HMM emission σ replacing those of `AlgoParams.hmm.emissions`, or null.
 * @property schemaVersion JSON format version ([SCHEMA_VERSION]).
 * @property provenance how the model was obtained.
 */
@Serializable
data class PersonalModel(
    val schemaVersion: Int = SCHEMA_VERSION,
    val biasDeltas: List<Double>? = null,
    val baseModelFingerprint: String? = null,
    val hmmMeans: StageDimValues? = null,
    val hmmSds: StageDimValues? = null,
    val provenance: PersonalProvenance,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "unsupported personal model schemaVersion $schemaVersion" }
        require(biasDeltas == null || (biasDeltas.size == SleepStage.COUNT && biasDeltas.all { it.isFinite() })) {
            "biasDeltas must have ${SleepStage.COUNT} finite values"
        }
        require(hmmMeans == null || hmmMeans.all().all { it.isFinite() }) { "hmmMeans must be finite" }
        require(hmmSds == null || hmmSds.all().all { it > 0 && it.isFinite() }) { "hmmSds must be positive and finite" }
    }

    private val fingerprintValue: String by lazy { Fingerprints.of(JSON.encodeToJsonElement(serializer(), this)) }

    /** SHA-256 (lower-case hex) of the canonical JSON of this model (part of the params hash). */
    fun fingerprint(): String = fingerprintValue

    /** Whether the model changes anything. */
    val isEmpty: Boolean get() = biasDeltas == null && hmmMeans == null && hmmSds == null

    /** The JSON form stored by the app. */
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    /** [table] with the μ and σ overrides applied. */
    fun applyTo(table: EmissionTable): EmissionTable {
        if (hmmMeans == null && hmmSds == null) return table
        return emissionTableOf { state, dim ->
            val g = table.forState(state).dim(dim)
            Gaussian(mean = hmmMeans?.forState(state)?.get(dim) ?: g.mean, sd = hmmSds?.forState(state)?.get(dim) ?: g.sd)
        }
    }

    /**
     * The bias deltas if they apply to a hybrid bundle identified by any of [modelFingerprints] (its night-model
     * fingerprint and its whole fingerprint), else null.
     */
    internal fun biasDeltasFor(vararg modelFingerprints: String): DoubleArray? =
        biasDeltas?.takeIf { baseModelFingerprint == null || baseModelFingerprint in modelFingerprints }?.toDoubleArray()

    /** Serialization. */
    companion object {
        /** Current schema version. */
        const val SCHEMA_VERSION: Int = 1

        private val JSON: Json =
            Json {
                encodeDefaults = true
                explicitNulls = true
                ignoreUnknownKeys = true
            }

        /**
         * Parses a stored personal model.
         *
         * @throws IllegalArgumentException if the JSON is malformed or invalid.
         */
        fun fromJson(json: String): PersonalModel =
            try {
                JSON.decodeFromString(serializer(), json)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("malformed personal model: ${e.message}", e)
            }
    }
}

package io.github.alien2003.pulsestages.algo.personal

import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.hmm.EmissionDims
import io.github.alien2003.pulsestages.algo.hmm.EmissionModel
import io.github.alien2003.pulsestages.algo.hmm.dim
import io.github.alien2003.pulsestages.algo.hmm.forState
import io.github.alien2003.pulsestages.algo.hybrid.DenseNetwork
import io.github.alien2003.pulsestages.algo.hybrid.HybridFeatureExtractor
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.algo.metrics.ConfusionMatrix
import io.github.alien2003.pulsestages.algo.metrics.EpochLabels
import io.github.alien2003.pulsestages.algo.metrics.IntervalScoring
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.night.PreparedNight
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * A night of the user with reference stages (another device, Sleep as Android, …).
 *
 * @property input the night's data, as for estimation.
 * @property truth the reference hypnogram.
 */
data class ReferenceNight(
    val input: NightInput,
    val truth: List<StageInterval>,
)

/**
 * Outcome of [PersonalCalibrator.calibrate].
 *
 * @property model the calibrated personal model (its provenance repeats the counts and kappas).
 * @property trainingNights reference nights the parameters were fitted on (the earliest ones).
 * @property heldOutNights the latest reference nights, used only for the kappas.
 * @property kappaBefore pooled Cohen's kappa of the global models on the held-out nights (null without held-out
 *   nights or when it is undefined).
 * @property kappaAfter the same with [model] applied.
 * @property biasDeltasFitted whether classifier bias deltas were fitted (needs a compatible hybrid model).
 * @property hmmMeansFitted whether HMM μ overrides were kept (false when they broke μ_DEEP < μ_LIGHT < μ_REM < μ_AWAKE).
 */
data class CalibrationResult(
    val model: PersonalModel,
    val trainingNights: Int,
    val heldOutNights: Int,
    val kappaBefore: Double?,
    val kappaAfter: Double?,
    val biasDeltasFitted: Boolean,
    val hmmMeansFitted: Boolean,
)

/**
 * Supervised personal calibration on reference nights (section 10 of the specification, the "Calibrate" button).
 *
 * 1. Split: the reference nights are ordered by session start; the latest `round(n · calibrationHoldout)` (at least
 *    one and at most n − 1 when n ≥ 2 and the share is positive) are held out, the rest are training nights.
 * 2. Training epochs: session epochs with heart-rate data, a reference stage ([EpochLabels.majority]) and not forced
 *    awake.
 * 3. Classifier bias deltas δ (with a compatible [hybridModel]): minimize
 *    `J(δ) = (1/E)·Σ_i CE(softmax(z_i + δ), y_i) + λ·‖δ‖²` by gradient descent from δ = 0 with step 1/L (L bounds the
 *    curvature), where z_i are the classifier logits, E the epochs per hour and λ = `personal.calibrationRegularization`
 *    — the cross-entropy is counted per hour of data, so λ is the strength in hours of evidence.
 * 4. HMM emission μ per state and dimension (elevated-HR nights excluded): `μ = (Σx + κ·μ0)/(n + κ)` over the training
 *    epochs of the state, with the global μ0 (`params.hmm.emissions`) and κ = λ·E pseudo-epochs; kept only if
 *    μ_DEEP < μ_LIGHT < μ_REM < μ_AWAKE on hrNorm.
 * 5. Kappa before (global models) and after (with the personal model) on the held-out nights with the configured
 *    estimator, through [SleepStager.run] (nights that are not estimated are skipped), pooled over the nights.
 */
class PersonalCalibrator(
    private val hybridModel: HybridModel? = null,
) {
    /**
     * Calibrates on [nights] with [params].
     *
     * @throws IllegalArgumentException if the training nights contain no usable labelled epoch.
     */
    fun calibrate(
        nights: List<ReferenceNight>,
        params: AlgoParams,
    ): CalibrationResult {
        val ordered = nights.sortedBy { it.input.sessionStartMs }
        val heldOutCount = heldOutCount(ordered.size, params.personal.calibrationHoldout)
        val training = ordered.subList(0, ordered.size - heldOutCount)
        val heldOut = ordered.subList(ordered.size - heldOutCount, ordered.size)
        val labelled = training.map { LabelledNight.of(it, params) }
        require(labelled.any { it.epochs.isNotEmpty() }) { "the training reference nights have no labelled epochs with heart-rate data" }
        val strength = params.personal.calibrationRegularization.coerceAtLeast(0.0)
        val compatible = hybridModel?.takeIf { it.compatibilityProblem() == null }
        val deltas = compatible?.let { BiasFit(it, labelled, strength).fit() }
        val means = fitHmmMeans(labelled, params, strength)
        val provenance =
            PersonalProvenance(
                kind = PersonalModelKind.CALIBRATED,
                nightsUsed = training.size,
                heldOutNights = heldOut.size,
                estimator = params.estimator,
            )
        val candidate =
            PersonalModel(
                biasDeltas = deltas?.toList(),
                baseModelFingerprint = compatible?.fingerprint(),
                hmmMeans = means,
                provenance = provenance,
            )
        val before = heldOutKappa(heldOut, params, null)
        val after = heldOutKappa(heldOut, params, candidate)
        val model = candidate.copy(provenance = provenance.copy(kappaBefore = before, kappaAfter = after))
        return CalibrationResult(model, training.size, heldOut.size, before, after, deltas != null, means != null)
    }

    private fun heldOutKappa(
        nights: List<ReferenceNight>,
        params: AlgoParams,
        personal: PersonalModel?,
    ): Double? {
        if (nights.isEmpty()) return null
        val stager = SleepStager(hybridModel, personal)
        val effective = params.copy(personal = params.personal.copy(enabled = personal != null))
        var pooled = ConfusionMatrix.EMPTY
        for (night in nights) {
            val outcome = stager.run(night.input, effective)
            if (outcome is EstimationOutcome.Estimated) pooled += IntervalScoring.score(night.truth, outcome.estimate.stages).confusion
        }
        return pooled.kappa.takeIf { it.isFinite() }
    }

    /** One prepared training night with its labelled epochs (`epochs[i]` has label `labels[i]`). */
    private class LabelledNight(
        val night: PreparedNight,
        val epochs: IntArray,
        val labels: IntArray,
    ) {
        companion object {
            fun of(
                reference: ReferenceNight,
                params: AlgoParams,
            ): LabelledNight {
                val night = NightPreparer.prepare(reference.input, params)
                val stages = EpochLabels.majority(reference.truth, night.grid)
                val epochs = stages.indices.filter { k -> stages[k] != null && night.sessionHasData(k) && !night.forcedAwakeMask[k] }
                return LabelledNight(
                    night,
                    epochs.toIntArray(),
                    IntArray(epochs.size) { i -> checkNotNull(stages[epochs[i]]).index },
                )
            }
        }
    }

    /** Gradient descent for the L2-regularized multinomial cross-entropy of the bias deltas. */
    private class BiasFit(
        model: HybridModel,
        nights: List<LabelledNight>,
        private val strength: Double,
    ) {
        private val logits: List<DoubleArray>
        private val labels: IntArray
        private val epochsPerHour: Double =
            SECONDS_PER_HOUR /
                nights
                    .first()
                    .night.params.preprocess.epochSec

        init {
            val network = DenseNetwork(model)
            val z = ArrayList<DoubleArray>()
            val y = ArrayList<Int>()
            for (labelled in nights) {
                val features = HybridFeatureExtractor.extract(labelled.night)
                val all = labelled.night.cache.logits(model.fingerprint()) { Array(features.size) { network.logits(features[it]) } }
                labelled.epochs.forEachIndexed { i, k ->
                    z += all[k]
                    y += labelled.labels[i]
                }
            }
            logits = z
            labels = y.toIntArray()
        }

        fun fit(): DoubleArray {
            val delta = DoubleArray(SleepStage.COUNT)
            // Each sample's softmax Hessian is bounded by 1/2 · I, so L = M/(2E) + 2λ bounds the curvature of J.
            val step = 1 / (labels.size / (2 * epochsPerHour) + 2 * strength)
            repeat(MAX_ITERATIONS) {
                val gradient = gradient(delta)
                if (gradient.all { abs(it) < GRADIENT_TOLERANCE }) return delta
                for (s in delta.indices) delta[s] -= step * gradient[s]
            }
            return delta
        }

        private fun gradient(delta: DoubleArray): DoubleArray {
            val g = DoubleArray(SleepStage.COUNT)
            for (i in logits.indices) {
                val p = DenseNetwork.logSoftmax(logits[i], delta)
                for (s in g.indices) g[s] += exp(p[s]) - if (labels[i] == s) 1.0 else 0.0
            }
            for (s in g.indices) g[s] = g[s] / epochsPerHour + 2 * strength * delta[s]
            return g
        }
    }

    private fun fitHmmMeans(
        nights: List<LabelledNight>,
        params: AlgoParams,
        strength: Double,
    ): StageDimValues? {
        val sums = Array(SleepStage.COUNT) { DoubleArray(EmissionDims.COUNT) }
        val counts = Array(SleepStage.COUNT) { IntArray(EmissionDims.COUNT) }
        for (labelled in nights.filter { !it.night.statistics.elevated }) {
            val values = EmissionModel(labelled.night, params).observations.values
            labelled.epochs.forEachIndexed { i, k -> accumulate(values[k], labelled.labels[i], sums, counts) }
        }
        val kappa = strength * SECONDS_PER_HOUR / params.preprocess.epochSec
        val global = params.hmm.emissions
        val means =
            StageDimValues.of { state, dim ->
                val mu0 = global.forState(state).dim(dim).mean
                val n = counts[state][dim]
                if (n + kappa <= 0.0) mu0 else (sums[state][dim] + kappa * mu0) / (n + kappa)
            }
        val ordered =
            means.deep.hrNorm < means.light.hrNorm && means.light.hrNorm < means.rem.hrNorm && means.rem.hrNorm < means.awake.hrNorm
        return means.takeIf { ordered }
    }

    private fun accumulate(
        x: DoubleArray,
        state: Int,
        sums: Array<DoubleArray>,
        counts: Array<IntArray>,
    ) {
        for (d in 0 until EmissionDims.COUNT) {
            if (!x[d].isNaN()) {
                sums[state][d] += x[d]
                counts[state][d]++
            }
        }
    }

    private companion object {
        const val SECONDS_PER_HOUR = 3600.0
        const val MAX_ITERATIONS = 2000
        const val GRADIENT_TOLERANCE = 1e-10

        fun heldOutCount(
            nights: Int,
            share: Double,
        ): Int = if (nights < 2 || share <= 0.0) 0 else (nights * share).roundToInt().coerceIn(1, nights - 1)
    }
}

package io.github.alien2003.pulsestages.algo.personal

import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.DurationModel
import io.github.alien2003.pulsestages.algo.params.HmmParams
import io.github.alien2003.pulsestages.algo.params.HybridParams
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticOptions
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticPerson
import io.github.alien2003.pulsestages.algo.testing.SyntheticHybridModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Supervised calibration on reference nights of a "shifted person" whose heart rate differs from the generator the
 * global synthetic bundle was trained on: higher baseline, REM nearly as high as wake, shallower DEEP.
 *
 * Observed after the review fixes (12 nights, 8 training / 4 held out, HYBRID): kappa 0.6980 → 0.7071 with the
 * default regularization (λ = 10 h), → 0.7373 with λ = 0.5 h (first run: 0.6736 → 0.6817 / 0.7255).
 *
 * The parameters are pinned to the pre-training decoder values and the physiology HMM ([pinned]) with which these
 * numbers were measured; the shipped defaults were chosen on real nights for the real bundle, not for this generator.
 */
class PersonalCalibratorTest {
    private val day = 86_400_000L
    private val model = SyntheticHybridModels.default

    /** Defaults with the decoder and HMM of the synthetic reference measurements (see the class KDoc). */
    private val pinned =
        AlgoParams(
            hmm = HmmParams.PHYSIOLOGY,
            hybrid = HybridParams(durationModel = DurationModel.EXPLICIT, timePriorWeight = 1.0, emissionWeight = 1.0),
        )

    private val shifted = SyntheticPerson(baselineBpm = 65.0, deepOffsetBpm = -3.5, remOffsetBpm = 12.0, awakeOffsetBpm = 15.0)

    private fun referenceNights(
        count: Int,
        person: SyntheticPerson = shifted,
    ): List<ReferenceNight> =
        (0 until count).map { i ->
            val options = SyntheticOptions(sessionStartMs = SyntheticOptions.DEFAULT_SESSION_START_MS + i * day, person = person)
            val night = SyntheticNightGenerator(3001L + i).generate(options)
            ReferenceNight(night.input, night.truth)
        }

    private fun regularized(
        params: AlgoParams,
        strength: Double,
    ): AlgoParams = params.copy(personal = params.personal.copy(calibrationRegularization = strength))

    @Test
    fun `calibration on a shifted person does not decrease held-out kappa`() {
        val nights = referenceNights(12)
        val params = pinned.copy(estimator = EstimatorType.HYBRID)

        val result = PersonalCalibrator(model).calibrate(nights, params)
        println(
            "calibration HYBRID shifted person: before=${result.kappaBefore} after=${result.kappaAfter} deltas=${result.model.biasDeltas}",
        )

        assertEquals(8, result.trainingNights)
        assertEquals(4, result.heldOutNights)
        assertTrue(result.biasDeltasFitted && result.hmmMeansFitted)
        val before = checkNotNull(result.kappaBefore)
        val after = checkNotNull(result.kappaAfter)
        assertTrue(after >= before, "calibration decreased held-out kappa: $before → $after")
        assertEquals(PersonalModelKind.CALIBRATED, result.model.provenance.kind)
        assertEquals(before, result.model.provenance.kappaBefore)
        assertEquals(after, result.model.provenance.kappaAfter)
        assertEquals(model.fingerprint(), result.model.baseModelFingerprint)
        assertEquals(0.0, checkNotNull(result.model.biasDeltas).sum(), 1e-9, "softmax-invariant direction stays at zero")

        val weak = PersonalCalibrator(model).calibrate(nights, regularized(params, 0.5))
        println("calibration HYBRID shifted person, lambda=0.5: before=${weak.kappaBefore} after=${weak.kappaAfter}")
        assertEquals(before, weak.kappaBefore)
        assertTrue(checkNotNull(weak.kappaAfter) >= before + 0.03, "weakly regularized calibration should clearly help: ${weak.kappaAfter}")
    }

    @Test
    fun `strong regularization keeps the global model`() {
        val nights = referenceNights(6)
        val params = regularized(pinned.copy(estimator = EstimatorType.HYBRID), 1e7)

        val result = PersonalCalibrator(model).calibrate(nights, params)

        assertTrue(checkNotNull(result.model.biasDeltas).all { abs(it) < 1e-4 }, "${result.model.biasDeltas}")
        val means = checkNotNull(result.model.hmmMeans)
        val global = StageDimValues.meansOf(params.hmm.emissions)
        means.all().zip(global.all()).forEach { (personal, base) -> assertEquals(base, personal, 1e-3) }
        assertEquals(checkNotNull(result.kappaBefore), checkNotNull(result.kappaAfter), 0.01)

        val weak = PersonalCalibrator(model).calibrate(nights, regularized(params, 0.1))
        val moved = { r: CalibrationResult -> checkNotNull(r.model.biasDeltas).sumOf { abs(it) } }
        assertTrue(moved(weak) > moved(result) + 0.05, "weaker regularization must move the deltas further")
    }

    @Test
    fun `HMM-only calibration fits the emission means`() {
        val nights = referenceNights(8)
        val params = pinned.copy(estimator = EstimatorType.HMM)

        val result = PersonalCalibrator().calibrate(nights, params)
        println("calibration HMM shifted person: before=${result.kappaBefore} after=${result.kappaAfter}")

        assertFalse(result.biasDeltasFitted)
        assertNull(result.model.biasDeltas)
        assertTrue(result.hmmMeansFitted)
        val means = checkNotNull(result.model.hmmMeans)
        val global = params.hmm.emissions
        assertTrue(means.rem.hrNorm > global.rem.hrNorm.mean, "REM is further above the nadir for this person")
        assertEquals(EstimatorType.HMM, result.model.provenance.estimator)
        assertNotNull(result.kappaAfter)
    }

    @Test
    fun `the split is deterministic by night order`() {
        val nights = referenceNights(7)
        val params = pinned.copy(estimator = EstimatorType.HYBRID)

        val a = PersonalCalibrator(model).calibrate(nights, params)
        val b = PersonalCalibrator(model).calibrate(nights.reversed(), params)

        assertEquals(a.model, b.model)
        assertEquals(a.model.fingerprint(), b.model.fingerprint())
        assertEquals(2, a.heldOutNights)
        val single = PersonalCalibrator(model).calibrate(nights.take(1), params)
        assertEquals(0, single.heldOutNights)
        assertNull(single.kappaBefore)
        assertNull(single.kappaAfter)
    }

    @Test
    fun `nights without usable labels are rejected`() {
        val nights = referenceNights(2).map { it.copy(truth = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { PersonalCalibrator(model).calibrate(nights, AlgoParams()) }
        val unknown = referenceNights(2).map { r -> r.copy(truth = r.truth.map { it.copy(stage = SleepStage.UNKNOWN) }) }
        assertThrows(IllegalArgumentException::class.java) { PersonalCalibrator(model).calibrate(unknown, AlgoParams()) }
    }
}

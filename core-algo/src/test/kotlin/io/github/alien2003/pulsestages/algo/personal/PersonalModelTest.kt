package io.github.alien2003.pulsestages.algo.personal

import io.github.alien2003.pulsestages.algo.Invariants
import io.github.alien2003.pulsestages.algo.ParamsHasher
import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.hmm.HmmEstimator
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.night.NightPreparer
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.EmissionTable
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersonalModelTest {
    private val table = EmissionTable.PHYSIOLOGY
    private val means =
        StageDimValues.meansOf(table).let { it.copy(rem = it.rem.copy(hrNorm = 2.0), deep = it.deep.copy(hrVar = 0.4)) }
    private val model =
        PersonalModel(
            biasDeltas = listOf(0.2, -0.1, 0.0, -0.1),
            baseModelFingerprint = "a".repeat(64),
            hmmMeans = means,
            provenance = PersonalProvenance(PersonalModelKind.CALIBRATED, nightsUsed = 5, kappaBefore = 0.3, kappaAfter = 0.35),
        )
    private val input = SyntheticNightGenerator(91).generate().input

    @Test
    fun `round-trips through JSON with a content fingerprint`() {
        val parsed = PersonalModel.fromJson(model.toJson())

        assertEquals(model, parsed)
        assertEquals(model.fingerprint(), parsed.fingerprint())
        assertNotEquals(model.fingerprint(), model.copy(biasDeltas = listOf(0.0, 0.0, 0.0, 0.0)).fingerprint())
        assertNotEquals(model.fingerprint(), model.copy(provenance = model.provenance.copy(nightsUsed = 6)).fingerprint())
        assertThrows(IllegalArgumentException::class.java) { PersonalModel.fromJson("{\"provenance\": 3}") }
    }

    @Test
    fun `invalid values are rejected`() {
        val provenance = PersonalProvenance(PersonalModelKind.EM, 1)
        assertThrows(IllegalArgumentException::class.java) { PersonalModel(biasDeltas = listOf(0.0, 1.0), provenance = provenance) }
        assertThrows(IllegalArgumentException::class.java) {
            PersonalModel(hmmSds = StageDimValues.sdsOf(table).let { it.copy(rem = it.rem.copy(hrSlope = 0.0)) }, provenance = provenance)
        }
        assertThrows(IllegalArgumentException::class.java) { PersonalModel(schemaVersion = 2, provenance = provenance) }
    }

    @Test
    fun `overrides replace only the given values`() {
        val applied = model.applyTo(table)

        assertEquals(2.0, applied.rem.hrNorm.mean)
        assertEquals(table.rem.hrNorm.sd, applied.rem.hrNorm.sd)
        assertEquals(0.4, applied.deep.hrVar.mean)
        assertEquals(table.awake, applied.awake)
        assertSame(table, PersonalModel(provenance = model.provenance).applyTo(table))
        assertTrue(PersonalModel(provenance = model.provenance).isEmpty)
        assertEquals(listOf(0.2, -0.1, 0.0, -0.1), model.biasDeltasFor("a".repeat(64))?.toList())
        assertNull(model.biasDeltasFor("b".repeat(64)))
        assertEquals(4, model.copy(baseModelFingerprint = null).biasDeltasFor("b".repeat(64))?.size)
    }

    @Test
    fun `the stager applies the HMM overrides only when personalization is enabled`() {
        val params = AlgoParams(estimator = EstimatorType.HMM)
        val stager = SleepStager(null as HybridModel?, model)

        val personal = stager.estimateWith(EstimatorType.HMM, input, params)
        val expected = HmmEstimator().estimatePrepared(NightPreparer.prepare(input, params), model.applyTo(params.hmm.emissions))
        assertEquals(expected.copy(paramsHash = personal.paramsHash), personal)
        assertEquals(ParamsHasher.hash(params, null, model.fingerprint()), personal.paramsHash)
        assertEquals(ParamsHasher.hash(params, null, model.fingerprint(), "x"), stager.paramsHash(params, "x"))
        Invariants.check(personal, input)

        val disabled = params.copy(personal = params.personal.copy(enabled = false))
        assertEquals(
            SleepStager().estimateWith(EstimatorType.HMM, input, disabled),
            stager.estimateWith(EstimatorType.HMM, input, disabled),
        )

        val fallback = stager.estimateWith(EstimatorType.HYBRID, input, params)
        assertTrue(fallback.warnings.any { it.code == WarningCode.HYBRID_MODEL_UNAVAILABLE })
        assertEquals(personal.stages, fallback.stages, "the HMM fallback of HYBRID uses the personal table too")
    }
}

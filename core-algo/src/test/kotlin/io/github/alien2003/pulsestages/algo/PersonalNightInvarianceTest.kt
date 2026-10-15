package io.github.alien2003.pulsestages.algo

import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.personal.PersonalModel
import io.github.alien2003.pulsestages.algo.personal.PersonalModelKind
import io.github.alien2003.pulsestages.algo.personal.PersonalProvenance
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Personalized nights across a bundle update (docs/prereg/prereg-naps-realistic.md, section 15 item 6): a calibration
 * stored for the previous bundle ([PreviousBundle]) is keyed by its night-model fingerprint as the base build computed
 * it, so it must keep applying, with identical results, under the bundle the app ships now
 * (`app/src/main/assets/models/hybrid_model.json`). Synthetic nights are decoded with personalization enabled and
 * non-zero bias deltas under both bundles; the estimate digests must be equal and must differ from the unpersonalized
 * decode (the deltas are applied under both). Until a new bundle is installed both are the same file; after an
 * installation this is the check that a nap-only change kept every personal calibration valid. algo-cli never decodes
 * with a personal model, so its night-by-night checks alone would not see a changed key.
 */
class PersonalNightInvarianceTest {
    private val asset: HybridModel by lazy {
        val file = File(checkNotNull(System.getProperty("pulsestages.hybridModelAsset")) { "system property not set by Gradle" })
        HybridModel.parse(file.readText())
    }

    /** Non-zero classifier bias deltas keyed to the previous bundle's night model as the base build computed it. */
    private val personal =
        PersonalModel(
            biasDeltas = listOf(0.2, -0.1, 0.15, -0.05),
            baseModelFingerprint = PreviousBundle.NIGHT_MODEL_FINGERPRINT,
            provenance = PersonalProvenance(PersonalModelKind.CALIBRATED, nightsUsed = 5),
        )

    /** Canonical text of an outcome without the params hash (it contains the bundle fingerprint, which may change). */
    private fun describe(outcome: EstimationOutcome): String {
        val e = assertInstanceOf(EstimationOutcome.Estimated::class.java, outcome).estimate
        assertEquals(SessionMode.NIGHT, e.mode)
        return buildString {
            append("${e.estimator}|${e.coverage}|${e.confidence}|${e.nadirBpm}|${e.hrSampleCount}|${e.flags}|")
            append(e.warnings.joinToString(",") { it.code.name })
            e.stages.forEach { append("|${it.startMs},${it.endMs},${it.stage}") }
            e.perEpoch.forEach { epoch ->
                val p = epoch.posterior
                append("|${epoch.stage}:${p.awake},${p.light},${p.deep},${p.rem}")
            }
        }
    }

    private fun digests(
        model: HybridModel,
        personalModel: PersonalModel?,
    ): List<String> {
        val stager = SleepStager(model, personalModel)
        return NIGHT_SEEDS.map { seed ->
            ParamsHasher.sha256Hex(describe(stager.run(SyntheticNightGenerator(seed).generate().input, AlgoParams())))
        }
    }

    @Test
    fun `a calibration keyed to the previous bundle's night model applies identically under the shipped bundle`() {
        val previous = PreviousBundle.model
        assertEquals(PreviousBundle.NIGHT_MODEL_FINGERPRINT, previous.nightModelFingerprint())
        assertEquals(previous.nightModelFingerprint(), asset.nightModelFingerprint(), "the shipped bundle keeps the night model")
        assertTrue(AlgoParams().personal.enabled, "personalization is on by default")

        val personalPrevious = digests(previous, personal)
        val personalAsset = digests(asset, personal)
        assertEquals(personalPrevious, personalAsset, "personalized nights decode identically under both bundles")
        // The deltas are applied under both bundles: the unpersonalized decodes differ on every night.
        val plainPrevious = digests(previous, null)
        val plainAsset = digests(asset, null)
        assertEquals(plainPrevious, plainAsset)
        for (i in NIGHT_SEEDS.indices) {
            assertNotEquals(plainPrevious[i], personalPrevious[i], "deltas applied under the previous bundle, night $i")
            assertNotEquals(plainAsset[i], personalAsset[i], "deltas applied under the shipped bundle, night $i")
        }
    }

    private companion object {
        val NIGHT_SEEDS: List<Long> = listOf(611L, 612L, 613L, 614L)
    }
}

package io.github.alien2003.pulsestages.work.estimate

import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticPerson
import io.github.alien2003.pulsestages.algo.testing.MinimalBundles
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.pipeline.OutputOptions
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addSyntheticNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.hc.usecase.EmRunResult
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.hc.usecase.PersonalizationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Population EM with the production estimator: with the default HYBRID estimator and a compatible bundle an EM model
 * would only override HMM emissions that are never used, yet its fingerprint would enter the params hash and mark every
 * written night stale. It is therefore not run; without a usable bundle (HMM fallback) it is, and then it does change
 * the estimates and the hash.
 */
class PopulationEmParamsHashTest {
    @TempDir
    lateinit var dir: File

    private val person = SyntheticPerson(baselineBpm = 60.0, remOffsetBpm = 11.0, awakeOffsetBpm = 20.0)

    private suspend fun PipelineHarness.fourEstimatedNights() {
        settings.set { it.copy(algo = it.algo.copy(personal = it.algo.personal.copy(emEveryNights = 4))) }
        (4 downTo 1).forEach { daysAgo ->
            repository.addSyntheticNight(OHEALTH, nightStart(daysAgo), seed = 700L + daysAgo, person = person, outlierRate = 0.0)
        }
        backfill.run(inBackground = false)
    }

    private fun PipelineHarness.personalization(bundle: HybridModel?): PersonalizationService =
        PersonalizationService(modelStore, settings, syncState, ledgerInputs, HybridModelProvider { bundle }, estimationDispatcher, logger)

    private suspend fun PipelineHarness.hashWith(bundle: HybridModel?): String =
        SleepStagerNightEstimator(HybridModelProvider { bundle }, modelStore)
            .paramsHash(settings.current.algo, OutputOptions.hashExtra(settings.current, repository.granted))

    @Test
    fun `with a compatible bundle EM is not run and no written night becomes stale`() =
        harnessTest(dir) { h ->
            h.fourEstimatedNights()
            val bundle = MinimalBundles.logreg()
            val before = h.hashWith(bundle)

            assertEquals(EmRunResult.NotUsed, h.personalization(bundle).maybeRunPopulationEm(inBackground = false))

            assertNull(h.modelStore.active(), "no personal model was stored")
            assertEquals(before, h.hashWith(bundle), "the params hash is unchanged: nothing to recompute")
        }

    @Test
    fun `without a usable bundle HYBRID falls back to the HMM, where EM adapts the emissions and changes the hash`() =
        harnessTest(dir) { h ->
            h.fourEstimatedNights()
            val before = h.hashWith(bundle = null)

            assertInstanceOf(EmRunResult.Adapted::class.java, h.personalization(bundle = null).maybeRunPopulationEm(inBackground = false))

            assertNotEquals(before, h.hashWith(bundle = null))
        }
}

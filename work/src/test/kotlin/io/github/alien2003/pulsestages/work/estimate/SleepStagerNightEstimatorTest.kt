package io.github.alien2003.pulsestages.work.estimate

import io.github.alien2003.pulsestages.algo.ParamsHasher
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.algo.personal.PersonalModel
import io.github.alien2003.pulsestages.algo.personal.PersonalModelKind
import io.github.alien2003.pulsestages.algo.personal.PersonalProvenance
import io.github.alien2003.pulsestages.algo.personal.StageDimValues
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.testing.MinimalBundles
import io.github.alien2003.pulsestages.algo.testing.NapModels
import io.github.alien2003.pulsestages.data.hc.fake.InMemoryLedger
import io.github.alien2003.pulsestages.data.hc.fake.RecordingLogger
import io.github.alien2003.pulsestages.data.hc.fake.TestClock
import io.github.alien2003.pulsestages.data.hc.pipeline.OutputOptions
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.hc.usecase.PersonalModelStore
import io.github.alien2003.pulsestages.data.local.db.PersonalModelEntity
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.settings.WriteSettings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import io.github.alien2003.pulsestages.data.local.db.PersonalModelKind as StoredKind

class SleepStagerNightEstimatorTest {
    private val ledger = InMemoryLedger()
    private val logger = RecordingLogger()
    private val store = PersonalModelStore(ledger.personalModelDao, TestClock(0), logger)
    private val night = SyntheticNightGenerator(42).generate()
    private val params = AlgoParams()
    private val extra = OutputOptions.hashExtra(WriteSettings())

    private fun estimator(bundle: HybridModel?): SleepStagerNightEstimator =
        SleepStagerNightEstimator(HybridModelProvider { bundle }, store)

    private fun NightEstimate.has(code: WarningCode): Boolean = warnings.any { it.code == code }

    private val personalModel =
        PersonalModel(
            hmmMeans = StageDimValues.meansOf(params.hmm.emissions),
            provenance = PersonalProvenance(PersonalModelKind.EM, nightsUsed = 30),
        )

    @Test
    fun `without the bundle HYBRID falls back to HMM with a warning`() =
        runTest {
            val estimator = estimator(bundle = null)

            val estimate = assertInstanceOf(EstimationOutcome.Estimated::class.java, estimator.estimate(night.input, params)).estimate

            assertEquals(EstimatorType.HMM, estimate.estimator)
            assertTrue(estimate.has(WarningCode.HYBRID_MODEL_UNAVAILABLE))
            assertTrue(estimate.perEpoch.isNotEmpty())
            assertNull(estimator.modelId)
            assertEquals(
                ParamsHasher.hash(params, modelFingerprint = null, personalFingerprint = null, extra = extra),
                estimator.paramsHash(params, extra),
            )
        }

    @Test
    fun `a nap is estimated as a nap, with the bundle's nap model when it has one, and SKIP declines it`() =
        runTest {
            val nap = night.input.copy(sessionEndMs = night.input.sessionStartMs + 90 * 60_000L)
            val main = MinimalBundles.logreg()
            val withNap = main.copy(nap = NapModels.from(MinimalBundles.logreg(hrNormWeight = listOf(0.5, 1.0, -0.5, 0.2))))

            val plain = assertInstanceOf(EstimationOutcome.Estimated::class.java, estimator(main).estimate(nap, params)).estimate
            val napModel = assertInstanceOf(EstimationOutcome.Estimated::class.java, estimator(withNap).estimate(nap, params)).estimate

            assertEquals(SessionMode.NAP, plain.mode)
            assertEquals(SessionMode.NAP, napModel.mode)
            assertEquals(EstimatorType.HYBRID, napModel.estimator)
            assertTrue(plain.perEpoch.map { it.posterior } != napModel.perEpoch.map { it.posterior }, "the nap model decodes the nap")
            val skip = params.copy(napMode = params.napMode.copy(outputPolicy = NapOutputPolicy.SKIP))
            val skipped = assertInstanceOf(EstimationOutcome.NotEstimated::class.java, estimator(withNap).estimate(nap, skip))
            assertEquals(NotEstimatedReason.NAP_SKIPPED, skipped.reason)
            assertTrue(logger.lines.isEmpty() || logger.lines.none { it.level >= LogLevel.WARN })
        }

    @Test
    fun `with the bundle HYBRID uses it and the hash covers its fingerprint`() =
        runTest {
            val bundle = MinimalBundles.logreg()
            val estimator = estimator(bundle)

            val estimate = assertInstanceOf(EstimationOutcome.Estimated::class.java, estimator.estimate(night.input, params)).estimate

            assertEquals(EstimatorType.HYBRID, estimate.estimator)
            assertTrue(!estimate.has(WarningCode.HYBRID_MODEL_UNAVAILABLE))
            assertEquals("minimal", estimator.modelId)
            val hash = estimator.paramsHash(params, extra)
            assertEquals(ParamsHasher.hash(params, bundle.fingerprint(), null, extra), hash)
            assertNotEquals(estimator(null).paramsHash(params, extra), hash)
            assertEquals(
                ParamsHasher.hash(params, bundle.fingerprint(), null, ""),
                estimate.paramsHash,
                "the estimate's own hash has no extra",
            )
        }

    @Test
    fun `the active personal model changes the hash while personalization is enabled`() =
        runTest {
            val estimator = estimator(bundle = null)
            val global = estimator.paramsHash(params, extra)

            store.saveActive(personalModel, StoredKind.EM)
            val personal = estimator.paramsHash(params, extra)

            assertNotEquals(global, personal)
            assertEquals(ParamsHasher.hash(params, null, personalModel.fingerprint(), extra), personal)
            val off = params.copy(personal = params.personal.copy(enabled = false))
            assertEquals(ParamsHasher.hash(off, null, null, extra), estimator.paramsHash(off, extra), "not applied when switched off")

            store.deactivateAll()
            assertEquals(global, estimator.paramsHash(params, extra), "back to the global models")
        }

    @Test
    fun `output options that change the written data change the hash`() =
        runTest {
            val estimator = estimator(bundle = null)

            assertNotEquals(
                estimator.paramsHash(params, OutputOptions.hashExtra(WriteSettings(awakeAsLight = false))),
                estimator.paramsHash(params, OutputOptions.hashExtra(WriteSettings(awakeAsLight = true))),
            )
            assertEquals(
                estimator.paramsHash(params, OutputOptions.hashExtra(WriteSettings(titleTemplate = "a"))),
                estimator.paramsHash(params, OutputOptions.hashExtra(WriteSettings(titleTemplate = "b"))),
                "the title is not part of the hash",
            )
        }

    @Test
    fun `estimateWith runs the requested estimator without gates`() =
        runTest {
            val rules = estimator(MinimalBundles.logreg()).estimateWith(EstimatorType.RULES, night.input, params)

            assertEquals(EstimatorType.RULES, rules.estimator)
            assertEquals(night.input.sessionStartMs, rules.stages.first().startMs)
            assertEquals(night.input.sessionEndMs, rules.stages.last().endMs)
        }

    @Test
    fun `an unreadable stored personal model falls back to the global models`() =
        runTest {
            val estimator = estimator(bundle = null)
            val global = estimator.paramsHash(params, extra)
            ledger.personalModelDao.insert(
                PersonalModelEntity(
                    createdAt = 0,
                    kind = StoredKind.EM,
                    json = "{not json",
                    fingerprint = "f",
                    active = true,
                    nightsUsed = 1,
                ),
            )

            assertEquals(global, estimator.paramsHash(params, extra))
            assertTrue(logger.atLeast(LogLevel.WARN).any { it.message.contains("unreadable") })
        }

    @Test
    fun `the bundled model is read once, absent and invalid bundles give null with a warning`() =
        runTest {
            var reads = 0
            val absent =
                BundledHybridModelProvider({
                    reads++
                    null
                }, logger, StandardTestDispatcher(testScheduler))
            assertNull(absent.hybridModel())
            assertNull(absent.hybridModel())
            assertEquals(1, reads, "loaded once, also when absent")
            assertTrue(logger.lines.any { it.level == LogLevel.WARN && it.message.contains(BundledHybridModelProvider.ASSET_PATH) })

            val invalid = BundledHybridModelProvider({ "{\"schemaVersion\": 1}" }, logger, StandardTestDispatcher(testScheduler))
            assertNull(invalid.hybridModel())

            val failing = BundledHybridModelProvider({ throw IOException("disk") }, logger, StandardTestDispatcher(testScheduler))
            assertNull(failing.hybridModel())

            val bundle = MinimalBundles.logreg()
            val valid =
                BundledHybridModelProvider({ path ->
                    bundle.toJson().takeIf {
                        path == "models/hybrid_model.json"
                    }
                }, logger, StandardTestDispatcher(testScheduler))
            val loaded = checkNotNull(valid.hybridModel())
            assertEquals(bundle.fingerprint(), loaded.fingerprint())
            assertSame(loaded, valid.hybridModel())
        }
}

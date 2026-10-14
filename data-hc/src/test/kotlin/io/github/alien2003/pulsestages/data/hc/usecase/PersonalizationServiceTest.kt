package io.github.alien2003.pulsestages.data.hc.usecase

import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.personal.PersonalModelKind
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticPerson
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addSyntheticNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.hcStagesOf
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import io.github.alien2003.pulsestages.data.local.db.PersonalModelKind as StoredKind

class PersonalizationServiceTest {
    @TempDir
    lateinit var dir: File

    /** A steady person (fixed baseline, no outliers) so that no night counts as elevated and EM uses every night. */
    private val person = SyntheticPerson(baselineBpm = 60.0, remOffsetBpm = 11.0, awakeOffsetBpm = 20.0)

    /** Adds OHealth nights [fromDaysAgo] down to [toDaysAgo] and estimates them (backfill). */
    private suspend fun PipelineHarness.estimateNights(
        fromDaysAgo: Int,
        toDaysAgo: Int = 1,
    ) {
        (fromDaysAgo downTo toDaysAgo).forEach { daysAgo ->
            repository.addSyntheticNight(OHEALTH, nightStart(daysAgo), seed = 500L + daysAgo, person = person, outlierRate = 0.0)
        }
        backfill.run(inBackground = false)
    }

    private suspend fun PipelineHarness.emEvery(nights: Int) {
        settings.set { it.copy(algo = it.algo.copy(personal = it.algo.personal.copy(emEveryNights = nights))) }
    }

    private suspend fun PipelineHarness.lastEmCount(): Int = syncState.state.first().lastEmAdaptationNightCount

    private suspend fun PipelineHarness.referenceNights(count: Int): List<ReferenceNight> {
        (count downTo 1).forEach { daysAgo ->
            repository.addSyntheticNight(
                OTHER_APP,
                nightStart(daysAgo),
                seed = 900L + daysAgo,
                person = person,
                heartRatePackage = OHEALTH,
                outlierRate = 0.0,
                stages = { hcStagesOf(it.truth) },
            )
        }
        return when (val search = referenceComparison.findReferenceNights(NOON - 60 * DAY_MS, NOON)) {
            is UseCaseResult.Success -> search.value.nights
            else -> fail("no reference nights: $search")
        }
    }

    @Test
    fun `population EM runs once every emEveryNights newly estimated nights and activates an EM model`() =
        harnessTest(dir) { h ->
            h.estimateNights(fromDaysAgo = 30, toDaysAgo = 2)
            assertEquals(29, h.ledger.allNights().count { it.status == NightStatus.WRITTEN })

            assertEquals(EmRunResult.NotDue(29, 30), h.personalization.maybeRunPopulationEm(inBackground = true))
            assertTrue(h.ledger.allModels().isEmpty())

            h.estimateNights(fromDaysAgo = 1)
            val adapted = assertInstanceOf(EmRunResult.Adapted::class.java, h.personalization.maybeRunPopulationEm(inBackground = true))

            assertEquals(30, adapted.result.nightsUsed)
            val row = checkNotNull(h.ledger.personalModelDao.getActive())
            assertEquals(adapted.modelRowId, row.id)
            assertEquals(StoredKind.EM, row.kind)
            assertEquals(30, row.nightsUsed)
            val model = checkNotNull(h.personalization.activeModel().first())
            assertEquals(PersonalModelKind.EM, model.provenance.kind)
            assertNotNull(model.hmmMeans)
            assertEquals(model.fingerprint(), row.fingerprint)
            assertEquals(30, h.lastEmCount())

            assertEquals(EmRunResult.NotDue(0, 30), h.personalization.maybeRunPopulationEm(inBackground = true), "not again before 30 more")
            assertEquals(1, h.ledger.allModels().size)
        }

    @Test
    fun `the next adaptation starts from the active EM model`() =
        harnessTest(dir) { h ->
            h.emEvery(4)
            h.estimateNights(fromDaysAgo = 8, toDaysAgo = 5)
            val first = assertInstanceOf(EmRunResult.Adapted::class.java, h.personalization.maybeRunPopulationEm(inBackground = false))

            h.estimateNights(fromDaysAgo = 4)
            val second = assertInstanceOf(EmRunResult.Adapted::class.java, h.personalization.maybeRunPopulationEm(inBackground = false))

            assertTrue(second.modelRowId != first.modelRowId)
            assertEquals(listOf(false, true), h.ledger.allModels().map { it.active }, "only the newest model is active")
            assertEquals(8, h.lastEmCount())
        }

    @Test
    fun `quota errors postpone EM without advancing the counter`() =
        harnessTest(dir) { h ->
            h.emEvery(4)
            h.estimateNights(fromDaysAgo = 4)
            h.repository.failOn(Operation.READ_HEART_RATE, HealthDataException.QuotaExceeded("quota"), afterCalls = 1)

            val interrupted =
                assertInstanceOf(EmRunResult.Interrupted::class.java, h.personalization.maybeRunPopulationEm(inBackground = true))

            assertTrue(interrupted.retryable)
            assertEquals(0, h.lastEmCount())
            assertTrue(h.ledger.allModels().isEmpty())
            assertInstanceOf(EmRunResult.Adapted::class.java, h.personalization.maybeRunPopulationEm(inBackground = true), "tried again")
        }

    @Test
    fun `a background run without the background permission reads nothing`() =
        harnessTest(dir) { h ->
            h.emEvery(4)
            h.estimateNights(fromDaysAgo = 4)
            h.repository.revoke(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
            h.repository.inBackground = true
            h.repository.clearCalls()

            val result = h.personalization.maybeRunPopulationEm(inBackground = true)

            assertEquals(EmRunResult.Blocked(BlockedReason.BACKGROUND_PERMISSION_MISSING), result)
            assertEquals(0, h.repository.callCount(Operation.READ_HEART_RATE))
            assertEquals(0, h.lastEmCount())
        }

    @Test
    fun `personalization switched off never runs EM and deleted nights restart the count`() =
        harnessTest(dir) { h ->
            h.emEvery(4)
            h.estimateNights(fromDaysAgo = 4)
            h.settings.set { it.copy(algo = it.algo.copy(personal = it.algo.personal.copy(enabled = false))) }
            assertEquals(EmRunResult.Disabled, h.personalization.maybeRunPopulationEm(inBackground = false))

            h.settings.set { it.copy(algo = it.algo.copy(personal = it.algo.personal.copy(enabled = true))) }
            h.syncState.setLastEmAdaptationNightCount(50)
            assertEquals(EmRunResult.NotDue(0, 4), h.personalization.maybeRunPopulationEm(inBackground = false))
            assertEquals(4, h.lastEmCount())
        }

    @Test
    fun `EM is not run while the estimates do not use the HMM emissions, and runs as soon as they do`() =
        harnessTest(dir) { h ->
            h.emEvery(4)
            h.estimateNights(fromDaysAgo = 4)
            h.settings.set { it.copy(algo = it.algo.withEstimator(EstimatorType.RULES)) }

            assertEquals(EmRunResult.NotUsed, h.personalization.maybeRunPopulationEm(inBackground = false))
            assertTrue(h.ledger.allModels().isEmpty(), "no model: the params hash stays, no night becomes stale")
            assertEquals(0, h.lastEmCount(), "the count is kept for when the HMM is used again")

            h.settings.set { it.copy(algo = it.algo.withEstimator(EstimatorType.HYBRID)) }
            assertInstanceOf(
                EmRunResult.Adapted::class.java,
                h.personalization.maybeRunPopulationEm(inBackground = false),
                "HYBRID without a bundle falls back to the HMM",
            )
        }

    @Test
    fun `the HMM emissions are used by HMM and by HYBRID without a usable bundle only, for nights or naps`() {
        val params = AlgoParams()
        assertTrue(PersonalizationService.usesHmmEmissions(params.withEstimator(EstimatorType.HMM), bundle = null))
        assertTrue(PersonalizationService.usesHmmEmissions(params.withEstimator(EstimatorType.HYBRID), bundle = null))
        assertFalse(PersonalizationService.usesHmmEmissions(params.withEstimator(EstimatorType.RULES), bundle = null))
        // The naps' estimator counts as well: RULES nights with HMM naps use the HMM emissions.
        val hmmNaps = params.withEstimator(EstimatorType.RULES).let { it.copy(napMode = it.napMode.copy(estimator = EstimatorType.HMM)) }
        assertTrue(PersonalizationService.usesHmmEmissions(hmmNaps, bundle = null))
    }

    @Test
    fun `calibration is refused while personalization is switched off`() =
        harnessTest(dir) { h ->
            val nights = h.referenceNights(3)
            h.settings.set { it.copy(algo = it.algo.copy(personal = it.algo.personal.copy(enabled = false))) }

            assertEquals(CalibrationOutcome.PersonalizationDisabled, h.personalization.calibrate(nights))
            assertTrue(h.ledger.allModels().isEmpty(), "no model that would never be applied")
        }

    @Test
    fun `calibration stores an active calibrated model with kappa before and after, and reset goes back to global`() =
        harnessTest(dir) { h ->
            val nights = h.referenceNights(6)
            assertEquals(6, nights.size)

            val outcome = assertInstanceOf(CalibrationOutcome.Calibrated::class.java, h.personalization.calibrate(nights))

            val result = outcome.result
            assertEquals(4, result.trainingNights)
            assertEquals(2, result.heldOutNights)
            assertNotNull(result.kappaBefore)
            assertNotNull(result.kappaAfter)
            assertFalse(result.biasDeltasFitted, "no hybrid bundle: only the HMM means can be calibrated")
            val row = checkNotNull(h.ledger.personalModelDao.getActive())
            assertEquals(StoredKind.CALIBRATED, row.kind)
            assertEquals(result.kappaBefore, row.kappaBefore)
            assertEquals(result.kappaAfter, row.kappaAfter)
            assertEquals(
                PersonalModelKind.CALIBRATED,
                h.personalization
                    .activeModel()
                    .first()
                    ?.provenance
                    ?.kind,
            )

            h.personalization.resetToGlobal()

            assertNull(h.personalization.activeModel().first())
            assertNull(h.ledger.personalModelDao.getActive())
        }

    @Test
    fun `EM is skipped while a calibrated model is active, and calibration needs reference nights`() =
        harnessTest(dir) { h ->
            assertInstanceOf(CalibrationOutcome.NotCalibrated::class.java, h.personalization.calibrate(emptyList()))
            assertInstanceOf(CalibrationOutcome.Calibrated::class.java, h.personalization.calibrate(h.referenceNights(3)))
            h.emEvery(4)
            h.estimateNights(fromDaysAgo = 14, toDaysAgo = 11)

            assertEquals(EmRunResult.SkippedCalibrated, h.personalization.maybeRunPopulationEm(inBackground = false))

            assertEquals(4, h.lastEmCount(), "the counter advances so the skip is not repeated every run")
            assertEquals(
                StoredKind.CALIBRATED,
                h.ledger.personalModelDao
                    .getActive()
                    ?.kind,
            )
        }

    @Test
    fun `calibration and population EM use nights only`() =
        harnessTest(dir) { h ->
            val nights = h.referenceNights(3)
            // Every reference session is a nap when the nap threshold is above the night length.
            h.settings.set { it.copy(algo = it.algo.copy(napMode = it.algo.napMode.copy(maxSessionMin = 720.0))) }
            val outcome = assertInstanceOf(CalibrationOutcome.NotCalibrated::class.java, h.personalization.calibrate(nights))
            assertTrue(outcome.detail.contains("nap"), outcome.detail)
            assertTrue(h.ledger.allModels().isEmpty())

            // Written naps neither count towards emEveryNights nor take part.
            h.settings.set { it.copy(algo = it.algo.withEstimator(EstimatorType.HMM).copy(napMode = AlgoParams().napMode)) }
            h.emEvery(2)
            h.estimateNights(fromDaysAgo = 1)
            val written = h.ledger.allNights().single { it.status == NightStatus.WRITTEN }
            h.ledger.putNight(written.copy(mode = SessionMode.NAP))
            assertEquals(EmRunResult.NotDue(0, 2), h.personalization.maybeRunPopulationEm(inBackground = false))
        }
}

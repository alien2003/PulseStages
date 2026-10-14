package io.github.alien2003.pulsestages.demo

import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.metrics.IntervalScoring
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.algo.personal.CalibrationResult
import io.github.alien2003.pulsestages.algo.personal.PersonalCalibrator
import io.github.alien2003.pulsestages.algo.personal.PersonalModel
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.di.EstimationDispatcher
import io.github.alien2003.pulsestages.data.hc.pipeline.StageMapping
import io.github.alien2003.pulsestages.data.hc.usecase.CalibrationOutcome
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceNight
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceNightScore
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceReport
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceSearch
import io.github.alien2003.pulsestages.data.hc.usecase.SpecialAccess
import io.github.alien2003.pulsestages.data.hc.usecase.UseCaseResult
import io.github.alien2003.pulsestages.data.local.db.PersonalModelEntity
import io.github.alien2003.pulsestages.data.local.db.PersonalModelKind
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.source.ReferenceSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug builds only: [ReferenceSource] over [DemoData]'s synthetic reference nights (their "real" stages are the
 * generator's true hypnograms). Scoring uses core-algo's [SleepStager] and [IntervalScoring]; "Calibrate" runs core-algo's
 * [PersonalCalibrator] and keeps the model in memory only (nothing is stored in the ledger or written anywhere).
 */
@Singleton
class DemoReferenceSource
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val hybridModels: HybridModelProvider,
        @param:EstimationDispatcher private val estimationDispatcher: CoroutineDispatcher,
        private val clock: Clock,
    ) : ReferenceSource {
        private class ActiveModel(
            val model: PersonalModel,
            val row: PersonalModelEntity,
        )

        private val active = MutableStateFlow<ActiveModel?>(null)
        private val zone: ZoneId = ZoneId.systemDefault()

        override val synthetic: Boolean = true

        override suspend fun historyAccess(): SpecialAccess = SpecialAccess.GRANTED

        override suspend fun findReferenceNights(
            fromMs: Long,
            toMs: Long,
        ): UseCaseResult<ReferenceSearch> {
            val today = LocalDate.now(clock.withZone(zone))
            val nights =
                withContext(estimationDispatcher) {
                    val whole =
                        (0 until DemoData.REFERENCE_COUNT).map { index ->
                            val daysAgo = DemoData.REFERENCE_COUNT - index + REFERENCE_DAYS_OFFSET
                            referenceNight(index, DemoData.sessionStart(daysAgo, today, zone))
                        }
                    // A short reference session, compared as a nap.
                    val napStart = DemoNaps.start(REFERENCE_DAYS_OFFSET, today, zone)
                    whole + referenceNight(DemoData.REFERENCE_COUNT, napStart, DemoData.REFERENCE_NAP_MINUTES)
                }
            return UseCaseResult.Success(ReferenceSearch(nights, emptyList(), emptyList(), fromMs, toMs))
        }

        override suspend fun compare(nights: List<ReferenceNight>): ReferenceReport {
            val params = settingsRepository.settings.first().algo
            val stager = SleepStager(hybridModels.hybridModel(), active.value?.model)
            return withContext(estimationDispatcher) {
                ReferenceReport(
                    nights.map { night ->
                        val mode = params.modeOf(night.input.sessionEndMs - night.input.sessionStartMs)
                        when (val outcome = stager.run(night.input, params)) {
                            is EstimationOutcome.Estimated -> {
                                val score = IntervalScoring.score(night.truth, outcome.estimate.stages)
                                ReferenceNightScore(night, outcome.estimate.estimator, score, null, mode)
                            }

                            is EstimationOutcome.NotEstimated -> {
                                ReferenceNightScore(night, null, null, "${outcome.reason}: ${outcome.detail}", mode)
                            }
                        }
                    },
                )
            }
        }

        override fun activeModel(): Flow<PersonalModelEntity?> = active.map { it?.row }

        override fun personalizationEnabled(): Flow<Boolean> =
            settingsRepository.settings.map { it.algo.personal.enabled }.distinctUntilChanged()

        override suspend fun calibrate(nights: List<ReferenceNight>): CalibrationOutcome {
            val params = settingsRepository.settings.first().algo
            return when {
                !params.personal.enabled -> CalibrationOutcome.PersonalizationDisabled
                nights.isEmpty() -> CalibrationOutcome.NotCalibrated("no reference nights")
                else -> calibrateOn(nights, params)
            }
        }

        private suspend fun calibrateOn(
            nights: List<ReferenceNight>,
            params: AlgoParams,
        ): CalibrationOutcome {
            val calibrator = PersonalCalibrator(hybridModels.hybridModel())
            return try {
                val result = withContext(estimationDispatcher) { calibrator.calibrate(nights.map { it.toCalibrationNight() }, params) }
                active.value = ActiveModel(result.model, rowOf(result))
                CalibrationOutcome.Calibrated(DEMO_MODEL_ROW_ID, result)
            } catch (e: IllegalArgumentException) {
                CalibrationOutcome.NotCalibrated(e.message.orEmpty())
            }
        }

        override suspend fun resetToGlobal() {
            active.value = null
        }

        private fun rowOf(result: CalibrationResult): PersonalModelEntity =
            PersonalModelEntity(
                id = DEMO_MODEL_ROW_ID,
                createdAt = clock.millis(),
                kind = PersonalModelKind.CALIBRATED,
                json = result.model.toJson(),
                fingerprint = result.model.fingerprint(),
                active = true,
                nightsUsed = result.trainingNights,
                kappaBefore = result.kappaBefore,
                kappaAfter = result.kappaAfter,
            )

        private fun referenceNight(
            index: Int,
            startMs: Long,
            durationMin: Int? = null,
        ): ReferenceNight {
            val synthetic = DemoData.referenceNight(index, startMs, durationMin)
            val input = synthetic.input.copy(baselineNadirBpm = synthetic.baselineBpm - BASELINE_BELOW_BPM)
            val session =
                HcSleepSession(
                    uuid = "demo-reference-$index",
                    packageName = DEMO_REFERENCE_PACKAGE,
                    startMs = input.sessionStartMs,
                    endMs = input.sessionEndMs,
                    startZoneOffsetSec = DemoData.offsetSec(input.sessionStartMs, zone),
                    endZoneOffsetSec = DemoData.offsetSec(input.sessionEndMs, zone),
                    stages = synthetic.truth.map { HcStage(it.startMs, it.endMs, StageMapping.toHcType(it.stage, awakeAsLight = false)) },
                    title = null,
                    notes = null,
                    clientRecordId = null,
                    clientRecordVersion = 0,
                    lastModifiedMs = clock.millis(),
                )
            return ReferenceNight(session, synthetic.truth, input, heartRatePackages = null)
        }

        private companion object {
            const val DEMO_MODEL_ROW_ID = 1L
            const val REFERENCE_DAYS_OFFSET = 20
            const val BASELINE_BELOW_BPM = 6.0
        }
    }

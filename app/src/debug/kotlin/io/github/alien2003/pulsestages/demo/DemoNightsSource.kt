package io.github.alien2003.pulsestages.demo

import io.github.alien2003.pulsestages.algo.SleepStager
import io.github.alien2003.pulsestages.algo.export.CsvExporter
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.data.hc.di.EstimationDispatcher
import io.github.alien2003.pulsestages.data.hc.pipeline.DeleteNightResult
import io.github.alien2003.pulsestages.data.hc.usecase.CsvExport
import io.github.alien2003.pulsestages.data.hc.usecase.EstimatorComparison
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.hc.usecase.NightInspection
import io.github.alien2003.pulsestages.data.hc.usecase.StageAgreement
import io.github.alien2003.pulsestages.data.hc.usecase.UseCaseResult
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.NightSummary
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.source.NightsSource
import io.github.alien2003.pulsestages.work.run.OutcomeKind
import io.github.alien2003.pulsestages.work.run.RunReport
import io.github.alien2003.pulsestages.work.run.RunSummary
import io.github.alien2003.pulsestages.work.schedule.JobState
import io.github.alien2003.pulsestages.work.schedule.WorkStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug builds only: [NightsSource] over [DemoData]'s synthetic nights, estimated in memory with core-algo's
 * [SleepStager] (the bundled hybrid model, the current settings). Nothing is read from or written to Health Connect or
 * the ledger: "delete" and "recompute" change only the in-memory rows, so the screens behave as with real data.
 */
@Singleton
class DemoNightsSource
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val hybridModels: HybridModelProvider,
        @param:EstimationDispatcher private val estimationDispatcher: CoroutineDispatcher,
        private val clock: Clock,
    ) : NightsSource {
        private class DemoNight(
            val daysAgo: Int,
            val entity: EstimatedNightEntity,
            val input: NightInput,
        )

        private val mutex = Mutex()
        private val nights = MutableStateFlow<List<DemoNight>?>(null)
        private val recomputes = mutableMapOf<String, MutableStateFlow<WorkStatus>>()
        private val zone: ZoneId = ZoneId.systemDefault()

        override val synthetic: Boolean = true

        override fun nights(): Flow<List<NightSummary>> =
            flow {
                ensureGenerated()
                emitAll(
                    nights.filterNotNull().map { list ->
                        list.map { DemoData.summaryOf(it.entity) }.sortedByDescending { it.sessionStartMs }
                    },
                )
            }

        override fun night(clientRecordId: String): Flow<EstimatedNightEntity?> =
            flow {
                ensureGenerated()
                emitAll(nights.filterNotNull().map { list -> list.firstOrNull { it.entity.clientRecordId == clientRecordId }?.entity })
            }

        override fun currentParamsHash(): Flow<String> = settingsRepository.settings.map { stager().paramsHash(it.algo, HASH_EXTRA) }

        override fun overwritePolicy(): Flow<OverwritePolicy> = settingsRepository.settings.map { it.write.overwritePolicy }

        override suspend fun inspect(clientRecordId: String): UseCaseResult<NightInspection> {
            val night = find(clientRecordId) ?: return UseCaseResult.NotFound
            val params = params()
            val stager = stager()
            val outcome = withContext(estimationDispatcher) { stager.run(night.input, params) }
            return UseCaseResult.Success(NightInspection(night.entity, night.input, params, outcome, stager.paramsHash(params, HASH_EXTRA)))
        }

        override suspend fun compareWithRules(inspection: NightInspection): UseCaseResult<EstimatorComparison> {
            val outcome = inspection.outcome
            if (outcome is EstimationOutcome.NotEstimated) return UseCaseResult.NotEstimated(outcome)
            val primary = checkNotNull(inspection.estimate) { "an estimated inspection has an estimate" }
            val stager = stager()
            val rules = withContext(estimationDispatcher) { stager.estimateWith(EstimatorType.RULES, inspection.input, inspection.params) }
            return UseCaseResult.Success(EstimatorComparison(primary, rules, StageAgreement.between(primary.stages, rules.stages)))
        }

        override fun exportCsv(inspection: NightInspection): UseCaseResult<CsvExport> {
            val outcome = inspection.outcome
            if (outcome is EstimationOutcome.NotEstimated) return UseCaseResult.NotEstimated(outcome)
            val estimate = checkNotNull(inspection.estimate) { "an estimated inspection has an estimate" }
            val night = inspection.night
            val start = Instant.ofEpochMilli(night.sessionStartMs).atOffset(ZoneOffset.ofTotalSeconds(night.startZoneOffsetSec))
            return UseCaseResult.Success(CsvExport("pulsestages-demo-${FILE_TIME.format(start)}.csv", CsvExporter.export(estimate)))
        }

        override suspend fun deleteNight(clientRecordId: String): DeleteNightResult {
            val night = find(clientRecordId)
            return when {
                night == null -> {
                    DeleteNightResult.NOT_FOUND
                }

                night.entity.status != NightStatus.WRITTEN -> {
                    DeleteNightResult.NO_OUTPUT
                }

                else -> {
                    val now = clock.millis()
                    replace(night.daysAgo) { it.copy(status = NightStatus.DELETED, statusReason = DELETED_REASON, updatedAt = now) }
                    DeleteNightResult.DELETED
                }
            }
        }

        override suspend fun recomputeNight(clientRecordId: String) {
            val night = find(clientRecordId) ?: return
            val status = statusFlow(clientRecordId)
            status.value = WorkStatus(JobState.RUNNING)
            val params = params()
            val stager = stager()
            val outcome = withContext(estimationDispatcher) { stager.run(night.input, params) }
            val updated =
                DemoData.entity(
                    daysAgo = night.daysAgo,
                    input = night.input,
                    outcome = outcome,
                    paramsHash = stager.paramsHash(params, HASH_EXTRA),
                    configured = params.estimator,
                    modelId = stager.modelId,
                    zone = zone,
                    nowMs = clock.millis(),
                    recomputeCount = night.entity.recomputeCount + 1,
                )
            replace(night.daysAgo) { updated.copy(createdAt = it.createdAt) }
            val estimated = if (outcome is EstimationOutcome.Estimated) 1 else 0
            val summary = RunSummary(found = 1, estimated = estimated, skipped = 1 - estimated)
            val report = RunReport(OutcomeKind.COMPLETED, summary, blockedReason = null, retryCause = null, detail = null)
            status.value = WorkStatus(JobState.SUCCEEDED, lastOutput = report)
        }

        override fun recomputeStatus(clientRecordId: String): Flow<WorkStatus> = statusFlow(clientRecordId)

        private fun statusFlow(clientRecordId: String): MutableStateFlow<WorkStatus> =
            synchronized(recomputes) { recomputes.getOrPut(clientRecordId) { MutableStateFlow(WorkStatus.NOT_SCHEDULED) } }

        private suspend fun find(clientRecordId: String): DemoNight? =
            ensureGenerated().firstOrNull { it.entity.clientRecordId == clientRecordId }

        private fun replace(
            daysAgo: Int,
            transform: (EstimatedNightEntity) -> EstimatedNightEntity,
        ) {
            nights.update { list ->
                list?.map { night ->
                    if (night.daysAgo == daysAgo) DemoNight(night.daysAgo, transform(night.entity), night.input) else night
                }
            }
        }

        private suspend fun ensureGenerated(): List<DemoNight> =
            nights.value ?: mutex.withLock { nights.value ?: generate().also { nights.value = it } }

        private suspend fun generate(): List<DemoNight> {
            val params = params()
            val stager = stager()
            val hash = stager.paramsHash(params, HASH_EXTRA)
            val today = LocalDate.now(clock.withZone(zone))
            val now = clock.millis()
            return withContext(estimationDispatcher) {
                (1..DemoData.NIGHT_COUNT).map { daysAgo ->
                    val input = DemoData.nightInput(daysAgo, DemoData.sessionStart(daysAgo, today, zone))
                    val outcome = stager.run(input, params)
                    DemoNight(daysAgo, DemoData.entity(daysAgo, input, outcome, hash, params.estimator, stager.modelId, zone, now), input)
                }
            }
        }

        private suspend fun params(): AlgoParams = settingsRepository.settings.first().algo

        private suspend fun stager(): SleepStager = SleepStager(hybridModels.hybridModel())

        private companion object {
            /** The demo nights are never written, so no output option is part of their hash. */
            const val HASH_EXTRA = "demo"
            const val DELETED_REASON = "deleted by the user (demo mode: nothing was written)"
            val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
        }
    }

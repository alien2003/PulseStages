package io.github.alien2003.pulsestages.source

import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.data.hc.pipeline.DeleteNightResult
import io.github.alien2003.pulsestages.data.hc.pipeline.MyDataEraser
import io.github.alien2003.pulsestages.data.hc.usecase.CsvExport
import io.github.alien2003.pulsestages.data.hc.usecase.EstimatorComparison
import io.github.alien2003.pulsestages.data.hc.usecase.HomeStatusAggregator
import io.github.alien2003.pulsestages.data.hc.usecase.NightInspection
import io.github.alien2003.pulsestages.data.hc.usecase.NightInspector
import io.github.alien2003.pulsestages.data.hc.usecase.UseCaseResult
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightDao
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.NightSummary
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.work.schedule.WorkScheduler
import io.github.alien2003.pulsestages.work.schedule.WorkStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * What the Nights tab and the night detail read and do. The production implementation is [LedgerNightsSource] (the
 * Room ledger, Health Connect through the data-hc use cases, WorkManager); debug builds can swap in synthetic demo
 * nights for screenshots (see docs/SCREENSHOTS.md). The ViewModels only talk to this interface.
 */
interface NightsSource {
    /** True when the nights are synthetic demo data (debug builds only); the screens say so. */
    val synthetic: Boolean

    /** The nights of the list, newest first (superseded outputs are left out). */
    fun nights(): Flow<List<NightSummary>>

    /** The night with [clientRecordId], or null when it is not (or no longer) known. */
    fun night(clientRecordId: String): Flow<EstimatedNightEntity?>

    /** The params hash of the current settings; WRITTEN nights with another hash are stale. */
    fun currentParamsHash(): Flow<String>

    /** The overwrite policy of the settings (NEVER: a recompute does not rewrite a night that has a session). */
    fun overwritePolicy(): Flow<OverwritePolicy>

    /** Re-estimates the night with the current settings without writing anything (posteriors, features, HR). */
    suspend fun inspect(clientRecordId: String): UseCaseResult<NightInspection>

    /** Runs the RULES estimator on the input of [inspection] and measures its agreement with the estimate. */
    suspend fun compareWithRules(inspection: NightInspection): UseCaseResult<EstimatorComparison>

    /** The per-epoch CSV of [inspection]. */
    fun exportCsv(inspection: NightInspection): UseCaseResult<CsvExport>

    /**
     * Deletes our session of the night from Health Connect ("Delete my session"). Throws
     * [io.github.alien2003.pulsestages.data.hc.HealthDataException] when Health Connect refuses or fails.
     */
    suspend fun deleteNight(clientRecordId: String): DeleteNightResult

    /** Starts the recompute of the night (a WorkManager job); [recomputeStatus] follows it. */
    suspend fun recomputeNight(clientRecordId: String)

    /** Status of the recompute of the night [clientRecordId]. */
    fun recomputeStatus(clientRecordId: String): Flow<WorkStatus>
}

/**
 * [NightsSource] over the ledger (`estimated_nights`), the night inspector (re-reads Health Connect, never writes),
 * the eraser, the WorkManager scheduler and the settings. The current params hash comes from the Home status aggregator, which
 * follows the settings, the granted optional channels and the active personal model.
 */
class LedgerNightsSource
    @Inject
    constructor(
        private val nightDao: EstimatedNightDao,
        private val homeStatus: HomeStatusAggregator,
        private val inspector: NightInspector,
        private val eraser: MyDataEraser,
        private val scheduler: WorkScheduler,
        private val settingsRepository: SettingsRepository,
    ) : NightsSource {
        override val synthetic: Boolean = false

        override fun nights(): Flow<List<NightSummary>> = nightDao.observeSummariesWithStatus(LISTED_STATUSES)

        override fun night(clientRecordId: String): Flow<EstimatedNightEntity?> = nightDao.observeByClientRecordId(clientRecordId)

        override fun currentParamsHash(): Flow<String> = homeStatus.status().map { it.paramsHash }.distinctUntilChanged()

        override fun overwritePolicy(): Flow<OverwritePolicy> =
            settingsRepository.settings.map { it.write.overwritePolicy }.distinctUntilChanged()

        override suspend fun inspect(clientRecordId: String): UseCaseResult<NightInspection> = inspector.inspect(clientRecordId)

        override suspend fun compareWithRules(inspection: NightInspection): UseCaseResult<EstimatorComparison> =
            inspector.compareWith(inspection, EstimatorType.RULES)

        override fun exportCsv(inspection: NightInspection): UseCaseResult<CsvExport> = inspector.exportCsv(inspection)

        override suspend fun deleteNight(clientRecordId: String): DeleteNightResult = eraser.deleteNight(clientRecordId)

        override suspend fun recomputeNight(clientRecordId: String) {
            scheduler.recomputeNight(clientRecordId)
        }

        override fun recomputeStatus(clientRecordId: String): Flow<WorkStatus> = scheduler.nightRecomputeStatus(clientRecordId)

        /** Constants. */
        companion object {
            /** Every status except SUPERSEDED (an output replaced by a merged or changed night). */
            val LISTED_STATUSES: List<NightStatus> = NightStatus.entries.filter { it != NightStatus.SUPERSEDED }
        }
    }

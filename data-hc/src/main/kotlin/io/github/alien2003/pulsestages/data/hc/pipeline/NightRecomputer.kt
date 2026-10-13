package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightDao
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Which nights an explicit "recompute all" covers. */
enum class RecomputeScope {
    /** WRITTEN nights with another algorithm version or params hash ("N nights are stale — recompute?"). */
    STALE,

    /**
     * Every night that has (or should have) an output: WRITTEN, NOT_ESTIMATED and FAILED ("Recompute all"). Nights
     * the user deleted, superseded nights and nights whose sources are gone are not touched.
     */
    ALL,
}

/**
 * Progress of an explicit recompute.
 *
 * @property done nights processed so far.
 * @property total nights to process.
 */
data class RecomputeProgress(
    val done: Int,
    val total: Int,
)

/**
 * Explicit recompute requests (the only way stale nights are recomputed under SKIP_SAME_VERSION): the selected
 * nights, every stale WRITTEN night, or every night. Each night is rebuilt from its current source sessions (read
 * again, nights close to each other share one read) and processed with [PipelineTrigger.RECOMPUTE]: the user asked,
 * so up-to-date and stale outputs are overwritten, but `overwritePolicy = NEVER` is still obeyed. Never throws except
 * for cancellation and [io.github.alien2003.pulsestages.data.hc.HealthDataException]s of the session reads.
 *
 * Resumable: with `notUpdatedSinceMs` only nights whose ledger row was last changed before that time are processed,
 * so a retried run (after a quota error) skips the nights the interrupted run already recomputed.
 */
class NightRecomputer
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val nightDao: EstimatedNightDao,
        private val windowReader: NightWindowReader,
        private val pipeline: NightPipeline,
        private val guard: HealthAccessGuard,
        private val logger: AppLogger,
    ) {
        /** Recomputes every WRITTEN night with another algorithm version or params hash. */
        suspend fun recomputeStale(inBackground: Boolean): PipelineReport = recomputeAll(RecomputeScope.STALE, inBackground)

        /**
         * Recomputes the nights of [scope] (only those last changed before [notUpdatedSinceMs], when given), oldest
         * first; [onProgress] is told after every group of nearby nights.
         */
        suspend fun recomputeAll(
            scope: RecomputeScope,
            inBackground: Boolean,
            notUpdatedSinceMs: Long? = null,
            onProgress: suspend (RecomputeProgress) -> Unit = {},
        ): PipelineReport {
            val settings = settingsRepository.settings.first()
            // The access comes first: which nights are stale depends on the granted optional channels (params hash).
            val access = guard.check(inBackground)
            access.blockedReason?.let { return PipelineReport.blocked(it) }
            val nights =
                when (scope) {
                    RecomputeScope.STALE -> nightDao.findStale(ALGO_VERSION, pipeline.currentParamsHash(settings, access.granted))
                    RecomputeScope.ALL -> nightDao.findOverlapping(Long.MIN_VALUE, Long.MAX_VALUE, NightRun.REPLACEABLE.toList())
                }.filter { notUpdatedSinceMs == null || it.updatedAt < notUpdatedSinceMs }
            logger.i(TAG, "recomputing ${nights.size} night(s) ($scope)")
            return recomputeAllowed(nights, settings, access.granted, onProgress)
        }

        /** Recomputes the nights with [clientRecordIds] (unknown ids are ignored). */
        suspend fun recompute(
            clientRecordIds: Collection<String>,
            inBackground: Boolean,
            onProgress: suspend (RecomputeProgress) -> Unit = {},
        ): PipelineReport {
            val nights = clientRecordIds.distinct().mapNotNull { nightDao.findByClientRecordId(it) }
            if (nights.isEmpty()) return recomputeAllowed(nights, settingsRepository.settings.first(), emptySet(), onProgress)
            val settings = settingsRepository.settings.first()
            val access = guard.check(inBackground)
            val blocked = access.blockedReason
            return if (blocked != null) PipelineReport.blocked(blocked) else recomputeAllowed(nights, settings, access.granted, onProgress)
        }

        /** Recomputes [nights] after the access check let the run read with [granted]. */
        private suspend fun recomputeAllowed(
            nights: List<EstimatedNightEntity>,
            settings: AppSettings,
            granted: Set<String>,
            onProgress: suspend (RecomputeProgress) -> Unit,
        ): PipelineReport {
            if (nights.isEmpty()) {
                onProgress(RecomputeProgress(0, 0))
                return PipelineReport()
            }
            return recomputeClusters(nights, settings, granted, onProgress)
        }

        private suspend fun recomputeClusters(
            nights: List<EstimatedNightEntity>,
            settings: AppSettings,
            granted: Set<String>,
            onProgress: suspend (RecomputeProgress) -> Unit,
        ): PipelineReport {
            var report = PipelineReport()
            var done = 0
            onProgress(RecomputeProgress(done, nights.size))
            val remaining = clusters(nights.sortedBy { it.sessionStartMs }).iterator()
            while (!report.stoppedEarly && remaining.hasNext()) {
                val cluster = remaining.next()
                val uuids = cluster.flatMapTo(mutableSetOf()) { it.sourceUuids }
                val set =
                    windowReader
                        .read(
                            cluster.first().sessionStartMs - NightWindowReader.OVERLAP_MS,
                            cluster.maxOf { it.sessionEndMs } + NightWindowReader.OVERLAP_MS,
                            settings,
                        ).containingAny(uuids)
                report += pipeline.process(set, settings, PipelineTrigger.RECOMPUTE, granted)
                if (!report.stoppedEarly) {
                    done += cluster.size
                    onProgress(RecomputeProgress(done, nights.size))
                }
            }
            return report
        }

        /** Groups nights (sorted by start) whose starts lie within one backfill window of the group's first night. */
        private fun clusters(sorted: List<EstimatedNightEntity>): List<List<EstimatedNightEntity>> {
            val result = mutableListOf<MutableList<EstimatedNightEntity>>()
            sorted.forEach { night ->
                val current = result.lastOrNull()
                if (current != null && night.sessionStartMs - current.first().sessionStartMs < CLUSTER_SPAN_MS) {
                    current += night
                } else {
                    result += mutableListOf(night)
                }
            }
            return result
        }

        private companion object {
            const val TAG = "NightRecomputer"
            const val CLUSTER_SPAN_MS = 7L * 24 * 60 * 60 * 1000
        }
    }

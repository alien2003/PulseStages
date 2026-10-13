package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightDao
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Explicit recompute requests (the only way stale nights are recomputed under SKIP_SAME_VERSION): the selected
 * nights, or every stale WRITTEN night. Each night is rebuilt from its current source sessions (read again, nights
 * close to each other share one read) and processed with [PipelineTrigger.RECOMPUTE]. Never throws except for
 * cancellation and [io.github.alien2003.pulsestages.data.hc.HealthDataException]s of the session reads.
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
        suspend fun recomputeStale(inBackground: Boolean): PipelineReport {
            val settings = settingsRepository.settings.first()
            val stale = nightDao.findStale(ALGO_VERSION, pipeline.currentParamsHash(settings))
            logger.i(TAG, "recomputing ${stale.size} stale night(s)")
            return recomputeNights(stale, inBackground)
        }

        /** Recomputes the nights with [clientRecordIds] (unknown ids are ignored). */
        suspend fun recompute(
            clientRecordIds: Collection<String>,
            inBackground: Boolean,
        ): PipelineReport = recomputeNights(clientRecordIds.distinct().mapNotNull { nightDao.findByClientRecordId(it) }, inBackground)

        private suspend fun recomputeNights(
            nights: List<EstimatedNightEntity>,
            inBackground: Boolean,
        ): PipelineReport {
            if (nights.isEmpty()) return PipelineReport()
            val settings = settingsRepository.settings.first()
            val access = guard.check(inBackground)
            val blocked = access.blockedReason
            return if (blocked != null) PipelineReport.blocked(blocked) else recomputeClusters(nights, settings, access.granted)
        }

        private suspend fun recomputeClusters(
            nights: List<EstimatedNightEntity>,
            settings: AppSettings,
            granted: Set<String>,
        ): PipelineReport {
            var report = PipelineReport()
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

package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightDao
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

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
 * for cancellation and [io.github.alien2003.pulsestages.data.hc.HealthDataException]s of the session reads; the
 * sessions written before either are counted in the [WriteCount] the caller passes.
 *
 * Resumable: with `notUpdatedSinceMs` only nights whose ledger row was last changed before that time are processed,
 * so a retried run (after a quota error) skips the nights the interrupted run already recomputed.
 *
 * The user's deletions win over a pending or running recompute: a night deleted after the recompute was requested
 * (`requestedAtMs`, the run's start when not given) is not written again, and [cancel] ("delete all my sessions")
 * stops every recompute requested before it — a running one before its next night, one that has not begun yet at
 * once.
 */
@Singleton
class NightRecomputer
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val nightDao: EstimatedNightDao,
        private val windowReader: NightWindowReader,
        private val pipeline: NightPipeline,
        private val guard: HealthAccessGuard,
        private val logger: AppLogger,
        private val clock: Clock,
    ) {
        /** How many times [cancel] was called and when it was last called (epoch ms). */
        private data class CancelMark(
            val count: Int,
            val atMs: Long,
        )

        private val cancels = AtomicReference(CancelMark(count = 0, atMs = Long.MIN_VALUE))

        /**
         * Stops every recompute requested before now: a running one stops before its next night, one that has not
         * begun yet does nothing. A recompute requested afterwards runs normally.
         */
        fun cancel() {
            val now = clock.millis()
            cancels.updateAndGet { CancelMark(it.count + 1, maxOf(now, it.atMs)) }
            logger.i(TAG, "recomputes requested so far are cancelled")
        }

        /** Recomputes every WRITTEN night with another algorithm version or params hash. */
        suspend fun recomputeStale(inBackground: Boolean): PipelineReport = recomputeAll(RecomputeScope.STALE, inBackground)

        /**
         * Recomputes the nights of [scope] (only those last changed before [notUpdatedSinceMs], when given), oldest
         * first; [onProgress] is told after every group of nearby nights. [notUpdatedSinceMs] is also the time the
         * user asked for the recompute (see the class documentation; the run's start when null). Every session
         * rewritten is counted in [writes] as it is written.
         */
        suspend fun recomputeAll(
            scope: RecomputeScope,
            inBackground: Boolean,
            notUpdatedSinceMs: Long? = null,
            writes: WriteCount = WriteCount(),
            onProgress: suspend (RecomputeProgress) -> Unit = {},
        ): PipelineReport {
            val request = Request(notUpdatedSinceMs, writes)
            return if (request.cancelled()) cancelledBeforeStart() else recomputeScope(scope, inBackground, request, onProgress)
        }

        private suspend fun recomputeScope(
            scope: RecomputeScope,
            inBackground: Boolean,
            request: Request,
            onProgress: suspend (RecomputeProgress) -> Unit,
        ): PipelineReport {
            val settings = settingsRepository.settings.first()
            // The access comes first: which nights are stale depends on the granted optional channels (params hash).
            val access = guard.check(inBackground)
            access.blockedReason?.let { return PipelineReport.blocked(it) }
            val notUpdatedSinceMs = request.explicitAtMs
            val nights =
                when (scope) {
                    RecomputeScope.STALE -> nightDao.findStale(ALGO_VERSION, pipeline.currentParamsHash(settings, access.granted))
                    RecomputeScope.ALL -> nightDao.findOverlapping(Long.MIN_VALUE, Long.MAX_VALUE, NightRun.REPLACEABLE.toList())
                }.filter { notUpdatedSinceMs == null || it.updatedAt < notUpdatedSinceMs }
            logger.i(TAG, "recomputing ${nights.size} night(s) ($scope)")
            return recomputeAllowed(nights, settings, access.granted, request, onProgress)
        }

        /**
         * Recomputes the nights with [clientRecordIds] (unknown ids are ignored), as requested by the user at
         * [requestedAtMs] (the run's start when null; see the class documentation). Every session rewritten is counted
         * in [writes] as it is written.
         */
        suspend fun recompute(
            clientRecordIds: Collection<String>,
            inBackground: Boolean,
            onProgress: suspend (RecomputeProgress) -> Unit = {},
            requestedAtMs: Long? = null,
            writes: WriteCount = WriteCount(),
        ): PipelineReport {
            val request = Request(requestedAtMs, writes)
            return if (request.cancelled()) cancelledBeforeStart() else recomputeNights(clientRecordIds, inBackground, request, onProgress)
        }

        private suspend fun recomputeNights(
            clientRecordIds: Collection<String>,
            inBackground: Boolean,
            request: Request,
            onProgress: suspend (RecomputeProgress) -> Unit,
        ): PipelineReport {
            val nights = clientRecordIds.distinct().mapNotNull { nightDao.findByClientRecordId(it) }
            if (nights.isEmpty()) return recomputeAllowed(nights, settingsRepository.settings.first(), emptySet(), request, onProgress)
            val settings = settingsRepository.settings.first()
            val access = guard.check(inBackground)
            val blocked = access.blockedReason
            return if (blocked != null) {
                PipelineReport.blocked(blocked)
            } else {
                recomputeAllowed(nights, settings, access.granted, request, onProgress)
            }
        }

        /**
         * One recompute run: when it was requested ([explicitAtMs], or now), the [cancel] count at its start and what it
         * wrote ([writes]). A cancel applies to it when it came after the start, or (with an explicit request time) at or
         * after the request.
         */
        private inner class Request(
            /** The request time the caller gave, or null. */
            val explicitAtMs: Long?,
            /** The sessions this run wrote so far. */
            val writes: WriteCount,
        ) {
            private val explicit = explicitAtMs != null
            private val atStart = cancels.get()

            /** When the user asked for this run. */
            val atMs: Long = explicitAtMs ?: clock.millis()

            fun cancelled(): Boolean {
                val mark = cancels.get()
                return if (explicit) mark.count > 0 && mark.atMs >= atMs else mark.count != atStart.count
            }
        }

        private fun cancelledBeforeStart(): PipelineReport {
            logger.i(TAG, "recompute not started: cancelled after it was requested")
            return PipelineReport(cancelled = true)
        }

        /** Recomputes [nights] after the access check let the run read with [granted]. */
        private suspend fun recomputeAllowed(
            nights: List<EstimatedNightEntity>,
            settings: AppSettings,
            granted: Set<String>,
            request: Request,
            onProgress: suspend (RecomputeProgress) -> Unit,
        ): PipelineReport {
            if (nights.isEmpty()) {
                onProgress(RecomputeProgress(0, 0))
                return PipelineReport()
            }
            return recomputeClusters(nights, settings, granted, request, onProgress)
        }

        private suspend fun recomputeClusters(
            nights: List<EstimatedNightEntity>,
            settings: AppSettings,
            granted: Set<String>,
            request: Request,
            onProgress: suspend (RecomputeProgress) -> Unit,
        ): PipelineReport {
            var report = PipelineReport()
            var done = 0
            onProgress(RecomputeProgress(done, nights.size))
            val remaining = clusters(nights.sortedBy { it.sessionStartMs }).iterator()
            while (!report.stoppedEarly && remaining.hasNext()) {
                if (request.cancelled()) {
                    logger.i(TAG, "recompute stopped: cancelled")
                    return report.copy(cancelled = true)
                }
                val cluster = remaining.next()
                val uuids = cluster.flatMapTo(mutableSetOf()) { it.sourceUuids }
                val set =
                    windowReader
                        .read(
                            cluster.first().sessionStartMs - NightWindowReader.OVERLAP_MS,
                            cluster.maxOf { it.sessionEndMs } + NightWindowReader.OVERLAP_MS,
                            settings,
                        ).containingAny(uuids)
                report +=
                    pipeline.process(
                        set,
                        settings,
                        PipelineTrigger.RECOMPUTE,
                        granted,
                        shouldStop = request::cancelled,
                        requestedAtMs = request.atMs,
                        writes = request.writes,
                    )
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

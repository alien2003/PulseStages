package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.di.EstimationDispatcher
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.SourceOutcome
import io.github.alien2003.pulsestages.data.local.db.SourceSessionEntity
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Clock
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** The estimator produced a hypnogram that cannot be written (a bug upstream); the night is marked FAILED. */
class InvalidStagesException(
    /** Every problem found by [StageMapping.validate]. */
    val problems: List<String>,
) : IllegalStateException("invalid stages: ${problems.joinToString("; ")}")

/**
 * Nights affected by deleted source sessions.
 *
 * @property removed nights whose every source was deleted: our record was deleted, status SOURCE_DELETED.
 * @property toRebuild nights of which only some sources were deleted; the caller rebuilds them from the remaining
 *   sessions and then calls [NightPipeline.retireRemnants].
 * @property removedNights the [removed] nights (as they were before); the caller re-reads their time span so that a
 *   night of another source that was a lower-priority duplicate of them can take their place.
 */
data class DeletionOutcome(
    val removed: Int,
    val toRebuild: List<EstimatedNightEntity>,
    val removedNights: List<EstimatedNightEntity> = emptyList(),
)

/**
 * Estimates nights and writes them to Health Connect idempotently, keeping the Room ledger in sync. Every public
 * call that changes the ledger holds [LedgerLock], so concurrent sync / backfill / recompute runs never interleave.
 *
 * Per night (failures are isolated: one failing night is logged, marked FAILED and the others continue):
 * - existing WRITTEN night with the same clientRecordId: NEVER → skip; changed source sessions → recompute;
 *   RECOMPUTE_ALL or an explicit recompute → recompute; same algorithm version and params hash → skip; otherwise it
 *   is left stale (no silent recompute);
 * - nights with another clientRecordId that share a source uuid or overlap it (same package, or another package by
 *   more than half of the shorter night) are superseded after the write: their records are deleted by
 *   clientRecordId and they are marked SUPERSEDED, so a night never has two outputs (NEVER: they are left alone and
 *   the new night is skipped); our own records of the night that the ledger does not know (from before a reinstall)
 *   are deleted too;
 * - a night the user deleted (DELETED, also when it shares a source uuid with the night) is only written again by a
 *   trigger that [recreates deleted nights][PipelineTrigger.recreatesDeleted];
 * - zone offsets missing in the source are taken from the ledger for a known night (the device zone is only the
 *   first guess), so a later zone change does not count as a change of the sources;
 * - clientRecordVersion = ALGO_VERSION·1000 + recomputeCount, where recomputeCount grows by one on every rewrite and
 *   the version is never below the one already stored in Health Connect + 1;
 * - a [HealthDataException.QuotaExceeded] (after the repository's retries) stops the run with `quotaHit`, a refused
 *   permission or unavailable Health Connect stops it with a [BlockedReason].
 *
 * After the nights, outputs of source sessions that meanwhile got their own stages are retired (SUPERSEDED), then the
 * skipped groups (naps, sessions with stages, duplicates) and our own sessions are recorded in `source_sessions`, so
 * that rows of nights superseded in the same run get their new outcome.
 */
class NightPipeline
    @Inject
    constructor(
        private val repository: HealthRepository,
        private val estimator: NightEstimator,
        private val ledger: NightLedger,
        titles: DefaultTitleProvider,
        private val logger: AppLogger,
        private val clock: Clock,
        @EstimationDispatcher estimationDispatcher: CoroutineDispatcher,
    ) {
        private val deps = PipelineDeps(repository, estimator, ledger, titles, logger, clock, estimationDispatcher)

        /** The params hash of [settings] (algorithm parameters, model fingerprints and output options). */
        fun currentParamsHash(settings: AppSettings): String = estimator.paramsHash(settings.algo, OutputOptions.hashExtra(settings.write))

        /**
         * Processes [nights] with [settings]. [granted] are the granted Health Connect permissions (optional channels
         * are only read when granted). [shouldStop] is checked between nights (cooperative cancellation, e.g. a
         * cancelled backfill); coroutine cancellation is honoured as well.
         */
        suspend fun process(
            nights: CandidateSet,
            settings: AppSettings,
            trigger: PipelineTrigger,
            granted: Set<String>,
            shouldStop: () -> Boolean = { false },
        ): PipelineReport =
            LedgerLock.withLock {
                val run = NightRun(deps, settings, trigger, granted, currentParamsHash(settings), nights.candidates)
                var report = PipelineReport(found = nights.nightCount, skipped = nights.skipped.size)
                val remaining = nights.candidates.iterator()
                var stopped = false
                while (!stopped && remaining.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    val step = if (shouldStop()) Step.Cancelled else run.step(remaining.next())
                    report = report.add(step, run.takeSuperseded())
                    stopped = step.stopsRun
                }
                if (!stopped) run.retireStagedOutputs(nights.skipped)?.let { report = report.add(it, run.takeSuperseded()) }
                recordSkipped(nights)
                report
            }

        /**
         * Repairs nights left with two outputs: every WRITTEN night whose source session now belongs to another
         * WRITTEN night (the newer one was written, superseding the older one failed or was interrupted) is
         * superseded now. Meant to run at the start of every sync; a failure is logged and reported, never thrown.
         */
        suspend fun supersedeLeftovers(
            settings: AppSettings,
            granted: Set<String>,
        ): PipelineReport =
            LedgerLock.withLock {
                val run = NightRun(deps, settings, PipelineTrigger.NEW_NIGHTS, granted, currentParamsHash(settings), emptyList())
                PipelineReport().add(run.supersedeLeftovers(), run.takeSuperseded())
            }

        /**
         * NOT_ESTIMATED / FAILED nights (no output) that end after [sinceMs] and were last processed before
         * [processedBeforeMs]: candidates for a retry, e.g. when their heart rate arrived after the sleep session.
         */
        suspend fun unfinishedNights(
            sinceMs: Long,
            processedBeforeMs: Long,
        ): List<EstimatedNightEntity> =
            ledger.nights
                .findOverlapping(sinceMs, Long.MAX_VALUE, listOf(NightStatus.NOT_ESTIMATED, NightStatus.FAILED))
                .filter { it.updatedAt < processedBeforeMs }

        /**
         * Source uuids of the ledger's nights (with or without an output) of [packages] starting in `[fromMs, toMs)`
         * that are not among [present] (the uuids a fresh read of that range found): sessions deleted while the
         * change log was not followed (a new changes token, an expired one). The caller applies them like
         * DeletionChanges.
         */
        suspend fun missingSourceUuids(
            fromMs: Long,
            toMs: Long,
            packages: Set<String>,
            present: Set<String>,
        ): List<String> =
            ledger.nights
                .findStartingIn(fromMs, toMs)
                .filter { it.status in NightRun.REPLACEABLE && it.sourcePackage in packages }
                .flatMap { it.sourceUuids }
                .filter { it !in present }
                .distinct()

        /**
         * Applies deleted source sessions ([deletedUuids], DeletionChange ids): marks them DELETED in
         * `source_sessions`; nights whose every source is gone lose our record (deleted by clientRecordId) and become
         * SOURCE_DELETED; nights with remaining sources are returned for rebuilding.
         */
        suspend fun applyDeletions(deletedUuids: Collection<String>): DeletionOutcome =
            LedgerLock.withLock {
                val deleted = deletedUuids.toSet()
                if (deleted.isEmpty()) return@withLock DeletionOutcome(0, emptyList())
                val now = clock.millis()
                ledger.sources.markDeleted(deleted, "deleted from Health Connect", now)
                val affected = ledger.nights.findBySourceUuids(deleted).filter { it.status in NightRun.REPLACEABLE }
                val (gone, partial) = affected.partition { night -> night.sourceUuids.all { it in deleted } }
                val withOutput = gone.filter { it.status == NightStatus.WRITTEN }.map { it.clientRecordId }
                if (withOutput.isNotEmpty()) repository.deleteOwnSessions(withOutput)
                if (gone.isNotEmpty()) {
                    ledger.nights.markSourceDeleted(gone.map { it.clientRecordId }, now)
                    gone.forEach { logger.i(TAG, "all source sessions deleted; our session was removed", nightKey = it.clientRecordId) }
                }
                DeletionOutcome(gone.size, partial, removedNights = gone)
            }

        /**
         * After rebuilding [nights] (returned by [applyDeletions]) from their remaining sources as [rebuilt]: any of
         * them that is still current loses its record and is marked SOURCE_DELETED, and the source rows of its
         * remaining sessions get the outcome of the rebuild (nap, duplicate, not estimated, ...). Exception: under
         * [OverwritePolicy.NEVER] a night whose remaining sessions still form a night keeps its output (NEVER never
         * replaces an output, so the rebuilt night was not written). Returns the number of nights retired.
         */
        suspend fun retireRemnants(
            nights: List<EstimatedNightEntity>,
            rebuilt: CandidateSet = CandidateSet.EMPTY,
            policy: OverwritePolicy = OverwritePolicy.SKIP_SAME_VERSION,
        ): Int =
            LedgerLock.withLock {
                val still =
                    nights
                        .mapNotNull { ledger.nights.findByClientRecordId(it.clientRecordId) }
                        .filter { it.status in NightRun.REPLACEABLE }
                val formsNight = { night: EstimatedNightEntity ->
                    rebuilt.candidates.any { c -> c.sourceUuids.any { it in night.sourceUuids } }
                }
                val (kept, retired) =
                    still.partition {
                        policy == OverwritePolicy.NEVER && it.status == NightStatus.WRITTEN &&
                            formsNight(it)
                    }
                kept.forEach { logger.i(TAG, "NEVER policy: some sources were deleted; keeping our session", nightKey = it.clientRecordId) }
                if (retired.isEmpty()) return@withLock 0
                val withOutput = retired.filter { it.status == NightStatus.WRITTEN }.map { it.clientRecordId }
                if (withOutput.isNotEmpty()) repository.deleteOwnSessions(withOutput)
                val now = clock.millis()
                retired.forEach { night ->
                    val reason =
                        if (formsNight(night)) {
                            "some source sessions were deleted and the remaining ones could not be estimated"
                        } else {
                            "some source sessions were deleted and the remaining ones do not form a night"
                        }
                    ledger.nights.updateStatus(
                        listOf(night.clientRecordId),
                        NightStatus.SOURCE_DELETED,
                        reason,
                        clearOutput = true,
                        now = now,
                    )
                    ledger.baselines.delete(night.clientRecordId)
                    relinkRemainingSources(night, rebuilt, now)
                    logger.i(TAG, "$reason; our session was removed", nightKey = night.clientRecordId)
                }
                retired.size
            }

        /**
         * Gives the source rows still pointing at the retired [night] the outcome of the [rebuilt] nights (they were
         * protected while the night had an output).
         */
        private suspend fun relinkRemainingSources(
            night: EstimatedNightEntity,
            rebuilt: CandidateSet,
            now: Long,
        ) {
            val rows =
                ledger.sources.findByNight(night.clientRecordId).filter { it.outcome != SourceOutcome.DELETED }.map { row ->
                    val skipped = rebuilt.skipped.firstOrNull { group -> group.sessions.any { it.uuid == row.uuid } }
                    val candidate = rebuilt.candidates.firstOrNull { row.uuid in it.sourceUuids }
                    when {
                        skipped != null -> row.relinked(skipped.outcome, skipped.reason, null, now)
                        candidate != null -> row.relinkedTo(candidate, now)
                        else -> row.relinked(SourceOutcome.NOT_ESTIMATED, "no longer part of a night", null, now)
                    }
                }
            if (rows.isNotEmpty()) ledger.sources.upsertAll(rows)
        }

        private suspend fun SourceSessionEntity.relinkedTo(
            candidate: NightCandidate,
            now: Long,
        ): SourceSessionEntity {
            val night = ledger.nights.findByClientRecordId(candidate.clientRecordId)
            val outcome = if (night?.status == NightStatus.FAILED) SourceOutcome.FAILED else SourceOutcome.NOT_ESTIMATED
            return relinked(outcome, night?.statusReason, candidate.clientRecordId, now)
        }

        private fun SourceSessionEntity.relinked(
            outcome: SourceOutcome,
            reason: String?,
            night: String?,
            now: Long,
        ): SourceSessionEntity = copy(outcome = outcome, reason = reason, nightClientRecordId = night, lastSeenAt = now)

        /** Records the skipped groups and our own sessions in `source_sessions`. */
        @Suppress("TooGenericExceptionCaught") // Bookkeeping must not turn a successful run into a failure.
        private suspend fun recordSkipped(nights: CandidateSet) {
            try {
                val now = clock.millis()
                nights.skipped.forEach { group ->
                    val protected = ledger.protectedUuids(group.sessions.map { it.uuid }, ownNight = null)
                    val rows =
                        group.sessions.filter { it.uuid !in protected }.map {
                            it.toSourceRow(
                                group.outcome,
                                group.reason,
                                null,
                                now,
                            )
                        }
                    if (rows.isNotEmpty()) ledger.sources.upsertAll(rows)
                    logger.d(TAG, "skipped ${group.sessions.size} session(s) of ${group.packageName}: ${group.reason}")
                }
                if (nights.own.isNotEmpty()) {
                    ledger.sources.upsertAll(
                        nights.own.map { it.toSourceRow(SourceOutcome.OWN, "written by PulseStages", it.clientRecordId, now) },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "could not record skipped sessions", throwable = e)
            }
        }

        private fun PipelineReport.add(
            step: Step,
            supersededNow: Int,
        ): PipelineReport {
            val base = copy(superseded = superseded + supersededNow)
            return when (step) {
                is Step.Done -> base.count(step.result)
                Step.Failed -> base.copy(failed = failed + 1)
                Step.Cancelled -> base.copy(cancelled = true)
                is Step.Stop -> base.copy(quotaHit = step.quotaHit, blockedReason = step.reason)
            }
        }

        private fun PipelineReport.count(result: NightResult): PipelineReport =
            when (result) {
                NightResult.WRITTEN -> copy(estimated = estimated + 1)
                NightResult.STALE_LEFT -> copy(skipped = skipped + 1, stale = stale + 1)
                NightResult.NOT_ESTIMATED, NightResult.NOT_ESTIMATED_KEPT -> copy(skipped = skipped + 1, notEstimated = notEstimated + 1)
                NightResult.UP_TO_DATE -> copy(skipped = skipped + 1, upToDate = upToDate + 1)
                NightResult.NEVER_SKIPPED, NightResult.DELETED_SKIPPED -> copy(skipped = skipped + 1)
                NightResult.REPAIRED -> this
            }

        private companion object {
            const val TAG = "NightPipeline"
        }
    }

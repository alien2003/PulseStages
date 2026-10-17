package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.TimeSpan
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.SleepChanges
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.data.local.sync.ChangesTokenState
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import io.github.alien2003.pulsestages.data.local.sync.SyncStateStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Result of [NewNightsSync.run].
 *
 * @property outcome how the run ended (also stored in [SyncStateStore]).
 * @property pipeline nights processed.
 * @property tokenRenewed a new changes token was obtained (first run, new package set, expired or too old token).
 * @property rereadFromMs start of the time-range re-read done instead of / in addition to the Changes API, if any.
 * @property pages Changes-API pages processed.
 * @property deletedSessions deleted source sessions seen: DeletionChange ids, plus the sources a re-read no longer found.
 * @property error English detail of an unexpected error, if any.
 * @property retryable a [SyncOutcome.FAILED] run stopped on a transient Health Connect error (IPC / IO): running again
 *   later is expected to succeed; false for invalid data and unexpected errors.
 */
data class SyncReport(
    val outcome: SyncOutcome,
    val pipeline: PipelineReport = PipelineReport(),
    val tokenRenewed: Boolean = false,
    val rereadFromMs: Long? = null,
    val pages: Int = 0,
    val deletedSessions: Int = 0,
    val error: String? = null,
    val retryable: Boolean = false,
)

/**
 * Discovers and processes new / changed / deleted nights through the Changes API.
 *
 * - Every run first repairs nights left with two outputs ([NightPipeline.supersedeLeftovers]).
 * - The changes token (SleepSessionRecord, filtered to the enabled packages) lives in [SyncStateStore] and is only
 *   valid for the exact package set it was created for. Without a token for the current set (first run, sources
 *   changed), with `changesTokenExpired`, or when the last sync is older than [TOKEN_MAX_AGE_MS], a new token is
 *   taken and the recent nights are re-read by time range instead: the last 7 days, extended back to the last
 *   successful sync when that is known (at most [MAX_REREAD_MS]). The old token (and with it the start of the
 *   re-read) is kept until the re-read has completed, so a re-read that stops early is repeated over the same range.
 *   A re-read also applies the deletions the abandoned change log would have reported: sources of the ledger's nights
 *   in the range that Health Connect no longer has.
 * - Pages are processed while `hasMore`; the token is persisted after every fully processed page (never after a
 *   page that stopped early, so the page is fetched again next time — processing is idempotent).
 * - Upserted sessions: the window [min start − 1 day, max end + 1 day] is re-read so fragments merge, and only the
 *   nights containing a changed uuid are processed.
 * - Deleted sessions: nights whose every source is gone lose our record (SOURCE_DELETED) and their time span is
 *   re-read (a lower-priority duplicate of another source takes their place); nights with remaining sources are
 *   rebuilt from them (the old output is superseded), or retired if the rest is no night.
 * - Nights without an estimate (NOT_ESTIMATED / FAILED) of an enabled source are retried after a complete run: their
 *   heart rate may arrive after the sleep session, which the Changes API does not report, and a failure may have been
 *   transient. Nights that ended in the last [RETRY_RECENT_MS] are retried by every late-data pass (below); nights that
 *   arrived late (first seen in the last [RETRY_RECENT_MS], at most [LATE_ARRIVAL_MAX_MS] after they ended) are retried
 *   with growing gaps.
 * - Written nights are refreshed after that, on the same schedule: heart rate (or steps) that arrives after a night was
 *   written would otherwise leave it estimated from part of its data. At most [MAX_REFRESH_NIGHTS_PER_RUN] up-to-date
 *   WRITTEN nights per run have their input read again; a night is estimated again only when its input fingerprint
 *   changed, and rewritten (version + 1) only when its record would change ([NightPipeline.refresh]). Nothing is
 *   refreshed under OverwritePolicy.NEVER; stale nights are left to an explicit recompute.
 * - The retry and the refresh form one late-data pass, which runs at most every [MIN_RECHECK_GAP_MS] (55 min, measured
 *   from the start of the run whose pass last completed, kept in [SyncStateStore]): about hourly also with a 15- or
 *   30-minute check interval, whatever an attempt records, so that each pass does what one hourly run does. A run the
 *   user asked for ("Estimate new now") runs it at once.
 * - Runs in the background never read without READ_HEALTH_DATA_IN_BACKGROUND ([HealthAccessGuard]).
 *
 * [run] never throws except for cancellation; every failure is reported in [SyncReport]. The sessions it writes are
 * also counted in the [WriteCount] passed to [run] as they are written, so the caller keeps them after a cancellation
 * or a Health Connect error that ended the run.
 */
class NewNightsSync
    @Inject
    constructor(
        private val repository: HealthRepository,
        private val settingsRepository: SettingsRepository,
        private val syncState: SyncStateStore,
        private val windowReader: NightWindowReader,
        private val pipeline: NightPipeline,
        private val logger: AppLogger,
        private val clock: Clock,
    ) {
        private val guard = HealthAccessGuard(repository)

        /**
         * Runs one sync; [inBackground] is true for WorkManager runs. [userRequested] is true for a run the user asked for
         * ("Estimate new now"): it runs the late-data pass at once, also less than [MIN_RECHECK_GAP_MS] after the last one.
         * Every session the run writes or rewrites is counted in [writes] as it is written.
         */
        @Suppress("TooGenericExceptionCaught") // The sync reports every failure instead of throwing (workers must not crash).
        suspend fun run(
            inBackground: Boolean,
            userRequested: Boolean = false,
            writes: WriteCount = WriteCount(),
        ): SyncReport {
            val report =
                try {
                    syncState.markRunStarted()
                    sync(inBackground, userRequested, writes)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { recordFinished(SyncReport(SyncOutcome.CANCELLED, error = "cancelled")) }
                    throw e
                } catch (e: HealthDataException) {
                    logger.w(TAG, "sync stopped: ${e.message}", throwable = e)
                    failureReport(e)
                } catch (e: Exception) {
                    logger.e(TAG, "sync failed unexpectedly", throwable = e)
                    SyncReport(SyncOutcome.FAILED, error = "${e.javaClass.simpleName}: ${e.message}")
                }
            recordFinished(report)
            logger.i(TAG, "sync finished: ${report.outcome} ${report.detail()}")
            return report
        }

        /** Stores the outcome of the run; a storage failure is logged, never thrown. */
        @Suppress("TooGenericExceptionCaught") // Bookkeeping must not turn a finished run into a crash.
        private suspend fun recordFinished(report: SyncReport) {
            try {
                syncState.markRunFinished(report.outcome, report.detail())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "could not store the sync outcome", throwable = e)
            }
        }

        private suspend fun sync(
            inBackground: Boolean,
            userRequested: Boolean,
            writes: WriteCount,
        ): SyncReport {
            val settings = settingsRepository.settings.first()
            val access = guard.check(inBackground)
            val blocked = access.blockedReason
            val packages = windowReader.enabledPackages(settings)
            return when {
                blocked != null -> {
                    val report = PipelineReport.blocked(blocked)
                    SyncReport(outcomeOf(report), report, error = access.detail)
                }

                packages.isEmpty() -> {
                    SyncReport(SyncOutcome.SUCCESS)
                }

                else -> {
                    syncPackages(packages, settings, access.granted, userRequested, writes)
                }
            }
        }

        private suspend fun syncPackages(
            packages: Set<String>,
            settings: AppSettings,
            granted: Set<String>,
            userRequested: Boolean,
            writes: WriteCount,
        ): SyncReport {
            val now = clock.millis()
            val repaired = pipeline.supersedeLeftovers(settings, granted)
            if (repaired.stoppedEarly) return SyncReport(outcomeOf(repaired), repaired)
            val synced = syncChanges(packages, settings, granted, now, writes)
            val lateData =
                if (synced.pipeline.stoppedEarly) PipelineReport() else lateDataPass(settings, granted, now, userRequested, writes)
            val combined = repaired + synced.pipeline + lateData
            return synced.copy(outcome = outcomeOf(combined), pipeline = combined)
        }

        /**
         * The late-data pass of a run that started at [runStartMs]: [retryUnfinished], then [refreshWritten]. It runs when
         * [userRequested], when no pass has completed yet, or when the last one was at least [MIN_RECHECK_GAP_MS] ago; a
         * stored time after [runStartMs] (the clock was set back) does not hold it off either. A pass in which neither part
         * stopped early is stored as complete; one that stopped early, or ended with an exception, is tried again by the
         * next run. Because the whole pass is gated, a night whose attempt records nothing (its source now has its own
         * stages, or a written night can no longer be estimated) is still read only once per pass.
         */
        private suspend fun lateDataPass(
            settings: AppSettings,
            granted: Set<String>,
            runStartMs: Long,
            userRequested: Boolean,
            writes: WriteCount,
        ): PipelineReport {
            val last = syncState.state.first().lateDataPassAtMs
            val due = userRequested || last == null || last > runStartMs || runStartMs - last >= MIN_RECHECK_GAP_MS
            if (!due) return PipelineReport()
            val retried = retryUnfinished(settings, granted, runStartMs, writes)
            val refreshed = if (retried.stoppedEarly) PipelineReport() else refreshWritten(settings, granted, runStartMs, writes)
            val report = retried + refreshed
            if (!report.stoppedEarly) syncState.markLateDataPass(runStartMs)
            return report
        }

        /** Follows the Changes API with the stored token, or re-reads by time range when there is no usable token. */
        private suspend fun syncChanges(
            packages: Set<String>,
            settings: AppSettings,
            granted: Set<String>,
            now: Long,
            writes: WriteCount,
        ): SyncReport {
            val stored = syncState.tokenFor(packages)
            return if (stored == null || now - stored.updatedAtMs > TOKEN_MAX_AGE_MS) {
                val lastSync =
                    stored?.updatedAtMs ?: syncState.state
                        .first()
                        .changesToken
                        ?.updatedAtMs
                reread(packages, settings, granted, rereadFrom(lastSync, now), writes)
            } else {
                followChanges(stored, packages, settings, granted, writes)
            }
        }

        /**
         * Retries nights that have no output yet (NOT_ESTIMATED / FAILED) and were not processed in this run: the
         * Changes API only reports sleep sessions, so heart rate that arrives after its session would otherwise never be
         * picked up automatically, and a night that failed transiently would stay failed. See [isDueForRetry]. Only nights
         * of an enabled source: the window read finds no other, so a night of a source that was disabled would never be
         * processed, never move its last attempt, and cost a sleep-session read on every pass.
         */
        private suspend fun retryUnfinished(
            settings: AppSettings,
            granted: Set<String>,
            runStartMs: Long,
            writes: WriteCount,
        ): PipelineReport {
            val now = clock.millis()
            val packages = windowReader.enabledPackages(settings)
            val nights =
                pipeline
                    .unfinishedNights(sinceMs = now - RETRY_RECENT_MS - LATE_ARRIVAL_MAX_MS, processedBeforeMs = runStartMs)
                    .filter { it.sourcePackage in packages && isDueForRetry(it, now) }
            if (nights.isEmpty()) return PipelineReport()
            logger.d(TAG, "retrying ${nights.size} recent night(s) without an estimate")
            val uuids = nights.flatMapTo(mutableSetOf()) { it.sourceUuids }
            val set =
                windowReader
                    .read(
                        nights.minOf { it.sessionStartMs } - NightWindowReader.OVERLAP_MS,
                        nights.maxOf { it.sessionEndMs } + NightWindowReader.OVERLAP_MS,
                        settings,
                    ).containingAny(uuids)
            return pipeline.process(set, settings, PipelineTrigger.NEW_NIGHTS, granted, writes = writes)
        }

        /**
         * Refreshes recent WRITTEN nights whose heart rate (or other input) may have grown since they were written: the
         * Changes API only reports sleep sessions. Due are the up-to-date nights ([NightPipeline.refreshableNights]) on the
         * schedule of [isDue], where the last attempt is the last write or refresh check. At most
         * [MAX_REFRESH_NIGHTS_PER_RUN] are checked per run (nights that ended recently first, then the longest unchecked
         * late arrivals); the rest are left to later runs. Never under OverwritePolicy.NEVER (no read at all).
         */
        private suspend fun refreshWritten(
            settings: AppSettings,
            granted: Set<String>,
            runStartMs: Long,
            writes: WriteCount,
        ): PipelineReport {
            val due =
                if (settings.write.overwritePolicy == OverwritePolicy.NEVER) emptyList() else dueForRefresh(settings, granted, runStartMs)
            if (due.isEmpty()) return PipelineReport()
            val nights = due.take(MAX_REFRESH_NIGHTS_PER_RUN)
            if (due.size > nights.size) {
                logger.i(TAG, "${due.size - nights.size} more written night(s) are due for a refresh; later runs check them")
            }
            logger.d(TAG, "refreshing ${nights.size} recent written night(s): reading their input again")
            val uuids = nights.flatMapTo(mutableSetOf()) { it.sourceUuids }
            return try {
                val set =
                    windowReader
                        .read(
                            nights.minOf { it.sessionStartMs } - NightWindowReader.OVERLAP_MS,
                            nights.maxOf { it.sessionEndMs } + NightWindowReader.OVERLAP_MS,
                            settings,
                        ).containingAny(uuids)
                pipeline.refresh(set, settings, granted, nights.mapTo(HashSet()) { it.clientRecordId }, writes)
            } catch (e: HealthDataException) {
                refreshStopped(e)
            }
        }

        /**
         * A Health Connect error outside the per-night isolation of the refresh (the read of its window): only the refresh
         * stops, and the run keeps the report of what it did before. A quota error or a refused access stops the run as
         * anywhere else; a transient or invalid read is logged and left to the next run.
         */
        private suspend fun refreshStopped(e: HealthDataException): PipelineReport {
            logger.w(TAG, "refresh of written nights stopped: ${e.message}", throwable = e)
            return when (e) {
                is HealthDataException.QuotaExceeded -> PipelineReport(quotaHit = true)
                is HealthDataException.PermissionDenied -> PipelineReport.blocked(guard.reasonForDenial())
                is HealthDataException.Unavailable -> PipelineReport.blocked(BlockedReason.HEALTH_CONNECT_UNAVAILABLE)
                is HealthDataException.Transient, is HealthDataException.InvalidData -> PipelineReport()
            }
        }

        /**
         * The up-to-date WRITTEN nights due for a refresh ([isDue]) that this run did not process, in [refreshOrder]. Only
         * nights of an enabled source: the window read finds no other, so a night of a source that was disabled would
         * never be checked and, never moving back in the order, would take a place of the per-run limit on every run.
         */
        private suspend fun dueForRefresh(
            settings: AppSettings,
            granted: Set<String>,
            runStartMs: Long,
        ): List<EstimatedNightEntity> {
            val now = clock.millis()
            val packages = windowReader.enabledPackages(settings)
            return pipeline
                .refreshableNights(
                    sinceMs = now - RETRY_RECENT_MS - LATE_ARRIVAL_MAX_MS,
                    processedBeforeMs = runStartMs,
                    paramsHash = pipeline.currentParamsHash(settings, granted),
                ).filter { it.sourcePackage in packages && isDue(it, now, lastAttemptMs = lastCheck(it)) }
                .sortedWith(refreshOrder(now))
        }

        /** The last write or refresh check of a written night. */
        private fun lastCheck(night: EstimatedNightEntity): Long = maxOf(night.updatedAt, night.inputCheckedAt ?: Long.MIN_VALUE)

        /**
         * Nights that ended in the last [RETRY_RECENT_MS] first, then late arrivals; within each, the night checked longest
         * ago first (then the newest), so that the per-run limit rotates through all due nights.
         */
        private fun refreshOrder(nowMs: Long): Comparator<EstimatedNightEntity> =
            compareBy<EstimatedNightEntity> { it.sessionEndMs <= nowMs - RETRY_RECENT_MS }
                .thenBy { lastCheck(it) }
                .thenByDescending { it.sessionEndMs }

        /** [isDue] for a night without an output: its last attempt is its last processing. */
        private fun isDueForRetry(
            night: EstimatedNightEntity,
            nowMs: Long,
        ): Boolean = isDue(night, nowMs, lastAttemptMs = night.updatedAt)

        /**
         * Whether a late-data pass ([lateDataPass]) checks [night]. A night that ended in the last [RETRY_RECENT_MS] is due
         * on every pass. A night first seen in the last [RETRY_RECENT_MS] that ended at most [LATE_ARRIVAL_MAX_MS] before
         * it was first seen (a late arrival through the Changes API, or a recent night of a backfill) is due when the time
         * since its last attempt ([lastAttemptMs]) is at least the time between its first and its last attempt: about 1,
         * 2, 4, 8, 16 and 32 hours after it was first seen with hourly passes. Older nights are left to an explicit
         * recompute or a backfill.
         */
        private fun isDue(
            night: EstimatedNightEntity,
            nowMs: Long,
            lastAttemptMs: Long,
        ): Boolean {
            if (night.sessionEndMs > nowMs - RETRY_RECENT_MS) return true
            val firstSeen = night.createdAt
            val lateArrival = firstSeen >= nowMs - RETRY_RECENT_MS && firstSeen - night.sessionEndMs <= LATE_ARRIVAL_MAX_MS
            return lateArrival && nowMs - lastAttemptMs >= lastAttemptMs - firstSeen
        }

        /** Progress through the Changes-API pages of one run; [writes] counts what the run writes. */
        private class ChangesProgress(
            var token: String,
            val writes: WriteCount,
        ) {
            var report = PipelineReport()
            var pages = 0
            var deletions = 0
            var hasMore = true
            var expired = false

            val continueReading: Boolean get() = hasMore && !expired && !report.stoppedEarly
        }

        private suspend fun followChanges(
            stored: ChangesTokenState,
            packages: Set<String>,
            settings: AppSettings,
            granted: Set<String>,
            writes: WriteCount,
        ): SyncReport {
            val progress = ChangesProgress(stored.token, writes)
            while (progress.continueReading) {
                val page = repository.sleepChanges(progress.token)
                if (page.tokenExpired) progress.expired = true else consumePage(page, progress, packages, settings, granted)
            }
            if (!progress.expired) {
                return SyncReport(outcomeOf(progress.report), progress.report, pages = progress.pages, deletedSessions = progress.deletions)
            }
            // The expired token stays stored until the re-read completes: its updatedAtMs anchors the re-read, so a
            // re-read that stops early (quota, permission, an error) is repeated over the same range next time.
            logger.i(TAG, "changes token expired; re-reading recent nights")
            val reread = reread(packages, settings, granted, rereadFrom(stored.updatedAtMs, clock.millis()), writes)
            return reread.copy(
                pipeline = progress.report + reread.pipeline,
                pages = progress.pages,
                deletedSessions = progress.deletions + reread.deletedSessions,
            )
        }

        /** Processes one page; the token is advanced only when the page was processed completely. */
        private suspend fun consumePage(
            page: SleepChanges,
            progress: ChangesProgress,
            packages: Set<String>,
            settings: AppSettings,
            granted: Set<String>,
        ) {
            progress.deletions += page.deletedIds.size
            progress.report += processPage(page, packages, settings, granted, progress.writes)
            if (progress.report.stoppedEarly) return
            progress.pages++
            if (page.nextToken.isNotBlank()) {
                syncState.advanceToken(packages, page.nextToken)
                progress.token = page.nextToken
            }
            progress.hasMore = page.hasMore
        }

        /**
         * Takes a new token, then re-reads the nights starting in `[fromMs, now)`; the token is stored only on success.
         * Sources of the ledger's nights in that range that the read no longer finds were deleted while the change log
         * was not followed: they are applied as deletions first.
         */
        private suspend fun reread(
            packages: Set<String>,
            settings: AppSettings,
            granted: Set<String>,
            fromMs: Long,
            writes: WriteCount,
        ): SyncReport {
            val token = repository.sleepChangesToken(packages)
            val toMs = clock.millis()
            val read = windowReader.read(fromMs - NightWindowReader.OVERLAP_MS, toMs + NightWindowReader.OVERLAP_MS, settings)
            val missing = pipeline.missingSourceUuids(fromMs, toMs, packages, read.sourceUuids)
            if (missing.isNotEmpty()) logger.i(TAG, "${missing.size} source session(s) were deleted while the change log was not followed")
            var report = if (missing.isEmpty()) PipelineReport() else processDeletions(missing, settings, granted, writes)
            if (!report.stoppedEarly) {
                report += pipeline.process(read.ownedBy(fromMs, toMs), settings, PipelineTrigger.NEW_NIGHTS, granted, writes = writes)
            }
            if (!report.stoppedEarly) syncState.saveNewToken(packages, token)
            return SyncReport(outcomeOf(report), report, tokenRenewed = true, rereadFromMs = fromMs, deletedSessions = missing.size)
        }

        private suspend fun processPage(
            page: SleepChanges,
            packages: Set<String>,
            settings: AppSettings,
            granted: Set<String>,
            writes: WriteCount,
        ): PipelineReport {
            var report = PipelineReport()
            if (page.deletedIds.isNotEmpty()) {
                report += processDeletions(page.deletedIds, settings, granted, writes)
                if (report.stoppedEarly) return report
            }
            val upserted = page.upserted.filter { it.packageName in packages }
            if (upserted.isNotEmpty()) {
                val fromMs = upserted.minOf { it.startMs } - NightWindowReader.OVERLAP_MS
                val toMs = upserted.maxOf { it.endMs } + NightWindowReader.OVERLAP_MS
                val changed = upserted.mapTo(mutableSetOf()) { it.uuid }
                val nights = windowReader.read(fromMs, toMs, settings).containingAny(changed)
                report += pipeline.process(nights, settings, PipelineTrigger.NEW_NIGHTS, granted, writes = writes)
            }
            return report
        }

        private suspend fun processDeletions(
            deletedIds: List<String>,
            settings: AppSettings,
            granted: Set<String>,
            writes: WriteCount,
        ): PipelineReport {
            val outcome = pipeline.applyDeletions(deletedIds)
            var report = PipelineReport(superseded = outcome.removed)
            // Partly deleted nights are rebuilt from their remaining sessions; the span of fully deleted ones (and of
            // deleted sessions with their own stages) is re-read too, so that a night of another source that was their
            // lower-priority duplicate is written now.
            val spans =
                (outcome.toRebuild + outcome.removedNights).map { TimeSpan(it.sessionStartMs, it.sessionEndMs) } +
                    outcome.removedStagedSpans
            if (spans.isEmpty()) return report
            var rebuild = CandidateSet.EMPTY
            spans.distinct().forEach { span ->
                rebuild = rebuild + windowReader.nightsAround(span.startMs, span.endMs, settings)
            }
            report += pipeline.process(rebuild, settings, PipelineTrigger.NEW_NIGHTS, granted, writes = writes)
            if (!report.stoppedEarly && outcome.toRebuild.isNotEmpty()) {
                val retired = pipeline.retireRemnants(outcome.toRebuild, rebuild, settings.write.overwritePolicy)
                report = report.copy(superseded = report.superseded + retired)
            }
            return report
        }

        private fun rereadFrom(
            lastSyncMs: Long?,
            nowMs: Long,
        ): Long {
            val week = nowMs - REREAD_MS
            if (lastSyncMs == null) return week
            return minOf(week, maxOf(lastSyncMs - NightWindowReader.OVERLAP_MS, nowMs - MAX_REREAD_MS))
        }

        private suspend fun failureReport(e: HealthDataException): SyncReport =
            when (e) {
                is HealthDataException.QuotaExceeded -> {
                    SyncReport(SyncOutcome.QUOTA_EXCEEDED, PipelineReport(quotaHit = true), error = e.message)
                }

                is HealthDataException.PermissionDenied -> {
                    // E.g. the app left the foreground during a run without the background permission.
                    val blocked = PipelineReport.blocked(guard.reasonForDenial())
                    SyncReport(outcomeOf(blocked), blocked, error = e.message)
                }

                is HealthDataException.Unavailable -> {
                    SyncReport(
                        SyncOutcome.HEALTH_CONNECT_UNAVAILABLE,
                        PipelineReport.blocked(BlockedReason.HEALTH_CONNECT_UNAVAILABLE),
                        error = e.message,
                    )
                }

                is HealthDataException.Transient -> {
                    SyncReport(SyncOutcome.FAILED, error = e.message, retryable = true)
                }

                is HealthDataException.InvalidData -> {
                    SyncReport(SyncOutcome.FAILED, error = e.message)
                }
            }

        private fun SyncReport.detail(): String =
            "found=${pipeline.found} estimated=${pipeline.estimated} skipped=${pipeline.skipped} stale=${pipeline.stale} " +
                "failed=${pipeline.failed} superseded=${pipeline.superseded} refreshChecks=${pipeline.inputChecks} " +
                "refreshed=${pipeline.refreshed} pages=$pages deletions=$deletedSessions" +
                (rereadFromMs?.let { " rereadFrom=$it" } ?: "") + (error?.let { " error=$it" } ?: "")

        /** Constants and the outcome mapping. */
        companion object {
            private const val TAG = "NewNightsSync"
            private const val DAY_MS = 24L * 60 * 60 * 1000

            /** Re-read window after a token reset: 7 days. */
            const val REREAD_MS: Long = 7 * DAY_MS

            /** Longest re-read window (Health Connect keeps change logs for about 30 days). */
            const val MAX_REREAD_MS: Long = 30 * DAY_MS

            /** A token not advanced for this long is treated as expired (tokens live 30 days). */
            const val TOKEN_MAX_AGE_MS: Long = 25 * DAY_MS

            /**
             * Nights without an estimate that ended (or, arriving late, were first seen) this recently are retried, and
             * written nights refreshed: those that ended this recently by every late-data pass ([MIN_RECHECK_GAP_MS]).
             */
            const val RETRY_RECENT_MS: Long = 36L * 60 * 60 * 1000

            /**
             * The retry and the refresh of recent nights (the late-data pass) run at most this often, measured from the
             * start of the run whose pass last completed, so that a 15- or 30-minute check interval keeps the hourly cadence
             * of the late-data work, also for attempts that record nothing; 55 rather than 60 min so that an hourly run is
             * never skipped by a few minutes of jitter. A run the user asked for runs the pass at once, and the next
             * scheduled pass waits this long from it.
             */
            const val MIN_RECHECK_GAP_MS: Long = 55L * 60 * 1000

            /** Nights first seen at most this long after they ended are retried as late arrivals (see [RETRY_RECENT_MS]). */
            const val LATE_ARRIVAL_MAX_MS: Long = 30 * DAY_MS

            /**
             * Most written nights whose input one run reads again. Each costs about two Health Connect read calls (heart rate
             * and steps with their 24 h left padding, one page each with OHealth's records), at worst about nine (dense
             * heart rate and all three optional channels), plus a verification read when it is rewritten; so a run stays far
             * below the background read quota (1000 calls per 15 min) even when a backfill made many nights due at once.
             */
            const val MAX_REFRESH_NIGHTS_PER_RUN: Int = 8

            /** The [SyncOutcome] of a pipeline [report]. */
            fun outcomeOf(report: PipelineReport): SyncOutcome =
                when {
                    report.blockedReason == BlockedReason.BACKGROUND_PERMISSION_MISSING -> SyncOutcome.BACKGROUND_READ_BLOCKED
                    report.blockedReason == BlockedReason.PERMISSION_MISSING -> SyncOutcome.PERMISSION_DENIED
                    report.blockedReason == BlockedReason.HEALTH_CONNECT_UNAVAILABLE -> SyncOutcome.HEALTH_CONNECT_UNAVAILABLE
                    report.quotaHit -> SyncOutcome.QUOTA_EXCEEDED
                    report.cancelled -> SyncOutcome.CANCELLED
                    report.failed > 0 -> SyncOutcome.PARTIAL_FAILURE
                    else -> SyncOutcome.SUCCESS
                }
        }
    }

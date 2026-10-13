package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.SleepChanges
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
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
 */
data class SyncReport(
    val outcome: SyncOutcome,
    val pipeline: PipelineReport = PipelineReport(),
    val tokenRenewed: Boolean = false,
    val rereadFromMs: Long? = null,
    val pages: Int = 0,
    val deletedSessions: Int = 0,
    val error: String? = null,
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
 * - Nights without an estimate (NOT_ESTIMATED / FAILED) are retried after every complete run: their heart rate may
 *   arrive after the sleep session, which the Changes API does not report, and a failure may have been transient.
 *   Nights that ended in the last [RETRY_RECENT_MS] are retried by every run; nights that arrived late (first seen in
 *   the last [RETRY_RECENT_MS], at most [LATE_ARRIVAL_MAX_MS] after they ended) are retried with growing gaps.
 * - Runs in the background never read without READ_HEALTH_DATA_IN_BACKGROUND ([HealthAccessGuard]).
 *
 * [run] never throws except for cancellation; every failure is reported in [SyncReport].
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

        /** Runs one sync; [inBackground] is true for WorkManager runs. */
        @Suppress("TooGenericExceptionCaught") // The sync reports every failure instead of throwing (workers must not crash).
        suspend fun run(inBackground: Boolean): SyncReport {
            val report =
                try {
                    syncState.markRunStarted()
                    sync(inBackground)
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

        private suspend fun sync(inBackground: Boolean): SyncReport {
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
                    syncPackages(packages, settings, access.granted)
                }
            }
        }

        private suspend fun syncPackages(
            packages: Set<String>,
            settings: AppSettings,
            granted: Set<String>,
        ): SyncReport {
            val now = clock.millis()
            val repaired = pipeline.supersedeLeftovers(settings, granted)
            if (repaired.stoppedEarly) return SyncReport(outcomeOf(repaired), repaired)
            val synced = syncChanges(packages, settings, granted, now)
            val retried = if (synced.pipeline.stoppedEarly) PipelineReport() else retryUnfinished(settings, granted, runStartMs = now)
            val combined = repaired + synced.pipeline + retried
            return synced.copy(outcome = outcomeOf(combined), pipeline = combined)
        }

        /** Follows the Changes API with the stored token, or re-reads by time range when there is no usable token. */
        private suspend fun syncChanges(
            packages: Set<String>,
            settings: AppSettings,
            granted: Set<String>,
            now: Long,
        ): SyncReport {
            val stored = syncState.tokenFor(packages)
            return if (stored == null || now - stored.updatedAtMs > TOKEN_MAX_AGE_MS) {
                val lastSync =
                    stored?.updatedAtMs ?: syncState.state
                        .first()
                        .changesToken
                        ?.updatedAtMs
                reread(packages, settings, granted, rereadFrom(lastSync, now))
            } else {
                followChanges(stored, packages, settings, granted)
            }
        }

        /**
         * Retries nights that have no output yet (NOT_ESTIMATED / FAILED) and were not processed in this run: the
         * Changes API only reports sleep sessions, so heart rate that arrives after its session would otherwise never be
         * picked up automatically, and a night that failed transiently would stay failed. See [isDueForRetry].
         */
        private suspend fun retryUnfinished(
            settings: AppSettings,
            granted: Set<String>,
            runStartMs: Long,
        ): PipelineReport {
            val now = clock.millis()
            val nights =
                pipeline
                    .unfinishedNights(sinceMs = now - RETRY_RECENT_MS - LATE_ARRIVAL_MAX_MS, processedBeforeMs = runStartMs)
                    .filter { isDueForRetry(it, now) }
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
            return pipeline.process(set, settings, PipelineTrigger.NEW_NIGHTS, granted)
        }

        /**
         * A night that ended in the last [RETRY_RECENT_MS] is retried by every run. A night first seen in the last
         * [RETRY_RECENT_MS] that ended at most [LATE_ARRIVAL_MAX_MS] before it was first seen (a late arrival through the
         * Changes API, or a recent night of a backfill) is retried when the time since its last attempt is at least the
         * time between its first and its last attempt: about 1, 2, 4, 8, 16 and 32 hours after it was first seen with
         * hourly syncs. Older nights are left to an explicit recompute or a backfill.
         */
        private fun isDueForRetry(
            night: EstimatedNightEntity,
            nowMs: Long,
        ): Boolean {
            if (night.sessionEndMs > nowMs - RETRY_RECENT_MS) return true
            val firstSeen = night.createdAt
            val lateArrival = firstSeen >= nowMs - RETRY_RECENT_MS && firstSeen - night.sessionEndMs <= LATE_ARRIVAL_MAX_MS
            return lateArrival && nowMs - night.updatedAt >= night.updatedAt - firstSeen
        }

        /** Progress through the Changes-API pages of one run. */
        private class ChangesProgress(
            var token: String,
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
        ): SyncReport {
            val progress = ChangesProgress(stored.token)
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
            val reread = reread(packages, settings, granted, rereadFrom(stored.updatedAtMs, clock.millis()))
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
            progress.report += processPage(page, packages, settings, granted)
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
        ): SyncReport {
            val token = repository.sleepChangesToken(packages)
            val toMs = clock.millis()
            val read = windowReader.read(fromMs - NightWindowReader.OVERLAP_MS, toMs + NightWindowReader.OVERLAP_MS, settings)
            val missing = pipeline.missingSourceUuids(fromMs, toMs, packages, read.sourceUuids)
            if (missing.isNotEmpty()) logger.i(TAG, "${missing.size} source session(s) were deleted while the change log was not followed")
            var report = if (missing.isEmpty()) PipelineReport() else processDeletions(missing, settings, granted)
            if (!report.stoppedEarly) report += pipeline.process(read.ownedBy(fromMs, toMs), settings, PipelineTrigger.NEW_NIGHTS, granted)
            if (!report.stoppedEarly) syncState.saveNewToken(packages, token)
            return SyncReport(outcomeOf(report), report, tokenRenewed = true, rereadFromMs = fromMs, deletedSessions = missing.size)
        }

        private suspend fun processPage(
            page: SleepChanges,
            packages: Set<String>,
            settings: AppSettings,
            granted: Set<String>,
        ): PipelineReport {
            var report = PipelineReport()
            if (page.deletedIds.isNotEmpty()) {
                report += processDeletions(page.deletedIds, settings, granted)
                if (report.stoppedEarly) return report
            }
            val upserted = page.upserted.filter { it.packageName in packages }
            if (upserted.isNotEmpty()) {
                val fromMs = upserted.minOf { it.startMs } - NightWindowReader.OVERLAP_MS
                val toMs = upserted.maxOf { it.endMs } + NightWindowReader.OVERLAP_MS
                val changed = upserted.mapTo(mutableSetOf()) { it.uuid }
                val nights = windowReader.read(fromMs, toMs, settings).containingAny(changed)
                report += pipeline.process(nights, settings, PipelineTrigger.NEW_NIGHTS, granted)
            }
            return report
        }

        private suspend fun processDeletions(
            deletedIds: List<String>,
            settings: AppSettings,
            granted: Set<String>,
        ): PipelineReport {
            val outcome = pipeline.applyDeletions(deletedIds)
            var report = PipelineReport(superseded = outcome.removed)
            // Partly deleted nights are rebuilt from their remaining sessions; the span of fully deleted ones is
            // re-read too, so that a night of another source that was their lower-priority duplicate is written now.
            val affected = outcome.toRebuild + outcome.removedNights
            if (affected.isEmpty()) return report
            var rebuild = CandidateSet.EMPTY
            affected.forEach { night ->
                rebuild = rebuild + windowReader.nightsAround(night.sessionStartMs, night.sessionEndMs, settings)
            }
            report += pipeline.process(rebuild, settings, PipelineTrigger.NEW_NIGHTS, granted)
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

                is HealthDataException.Transient, is HealthDataException.InvalidData -> {
                    SyncReport(SyncOutcome.FAILED, error = e.message)
                }
            }

        private fun SyncReport.detail(): String =
            "found=${pipeline.found} estimated=${pipeline.estimated} skipped=${pipeline.skipped} stale=${pipeline.stale} " +
                "failed=${pipeline.failed} superseded=${pipeline.superseded} pages=$pages deletions=$deletedSessions" +
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

            /** Nights without an estimate that ended (or, arriving late, were first seen) this recently are retried. */
            const val RETRY_RECENT_MS: Long = 36L * 60 * 60 * 1000

            /** Nights first seen at most this long after they ended are retried as late arrivals (see [RETRY_RECENT_MS]). */
            const val LATE_ARRIVAL_MAX_MS: Long = 30 * DAY_MS

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

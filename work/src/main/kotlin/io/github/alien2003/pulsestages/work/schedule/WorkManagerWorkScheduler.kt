package io.github.alien2003.pulsestages.work.schedule

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeScope
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.work.run.BackfillRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * [WorkScheduler] over WorkManager. WorkManager is looked up lazily (the app initializes it on demand through its
 * `Configuration.Provider`, after Hilt injected the worker factory).
 *
 * The schedule tags of the enqueued periodic works tell whether they match the settings; the daily work is also pinned
 * again when its next run drifted more than [DailySchedule.DRIFT_TOLERANCE_MS] from the configured time (and it is
 * not overdue, retrying, or running).
 */
@Singleton
class WorkManagerWorkScheduler
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val settingsRepository: SettingsRepository,
        private val backfill: BackfillRunner,
        private val clock: Clock,
        private val zone: LocalZone,
        private val logger: AppLogger,
    ) : WorkScheduler {
        private val workManager: WorkManager get() = WorkManager.getInstance(context)

        override suspend fun ensureScheduled(schedule: ScheduleSettings) {
            if (needsEnqueue(WorkRequests.Names.PERIODIC, WorkRequests.periodicTag(schedule), expectedNextMs = null)) {
                workManager.enqueueUniquePeriodicWork(
                    WorkRequests.Names.PERIODIC,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    WorkRequests.periodic(schedule),
                )
                logger.i(
                    TAG,
                    "periodic check every ${schedule.periodicMinutes} min (battery not low: ${schedule.runOnlyWhenNotLowBattery})",
                )
            }
            val zoneId = zone.current()
            val next = DailySchedule.nextRunMs(clock.millis(), schedule.dailyTimeMinutes, zoneId)
            if (needsEnqueue(WorkRequests.Names.DAILY, WorkRequests.dailyTag(schedule, zoneId), expectedNextMs = next)) {
                enqueueDaily(schedule, zoneId)
            }
        }

        override suspend fun keepScheduled() {
            settingsRepository.settings
                .map { it.schedule }
                .distinctUntilChanged()
                .collect { schedule -> ensureScheduledLogged(schedule) }
        }

        override suspend fun realignDaily() {
            enqueueDaily(settingsRepository.settings.first().schedule, zone.current())
        }

        override suspend fun estimateNow() {
            enqueueUserRequest(WorkRequests.Names.NOW, WorkRequests.estimateNow())
        }

        override fun startBackfill() {
            workManager.enqueueUniqueWork(WorkRequests.Names.BACKFILL, ExistingWorkPolicy.KEEP, WorkRequests.backfill(clock.millis()))
        }

        override suspend fun cancelBackfill() {
            backfill.cancel()
            val running =
                workManager
                    .getWorkInfosForUniqueWorkFlow(WorkRequests.Names.BACKFILL)
                    .first()
                    .any { it.state == WorkInfo.State.RUNNING }
            // A backfill inside its window loop stops by itself before its next night (cancelling it in WorkManager
            // would interrupt the night in progress). One that is waiting, or whose worker has not reached the engine
            // yet, is removed from WorkManager (it would not process anything anyway: the cancel is durable).
            if (!running || !backfill.isRunning) workManager.cancelUniqueWork(WorkRequests.Names.BACKFILL)
        }

        override fun recomputeAll(scope: RecomputeScope) {
            workManager.enqueueUniqueWork(
                WorkRequests.Names.RECOMPUTE_ALL,
                ExistingWorkPolicy.KEEP,
                WorkRequests.recomputeAll(scope, clock.millis()),
            )
        }

        override suspend fun recomputeNight(clientRecordId: String) {
            enqueueUserRequest(
                WorkRequests.Names.recomputeNight(clientRecordId),
                WorkRequests.recomputeNight(clientRecordId, clock.millis()),
            )
        }

        /**
         * Enqueues [request], something the user asked for right now, as the unique work [name]: a pending work of that
         * name (waiting for its backoff after a retry, which can take hours, or not started yet) is replaced, so the
         * request runs now; a running one is kept.
         */
        private suspend fun enqueueUserRequest(
            name: String,
            request: OneTimeWorkRequest,
        ) {
            val infos = workManager.getWorkInfosForUniqueWorkFlow(name).first()
            val running = infos.any { it.state == WorkInfo.State.RUNNING }
            val pending = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            val policy = if (pending && !running) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            workManager.enqueueUniqueWork(name, policy, request)
            if (policy == ExistingWorkPolicy.REPLACE) logger.i(TAG, "$name: replaced the pending work by a new request")
        }

        override fun status(job: WorkJob): Flow<WorkStatus> = statusOf(job.uniqueName)

        override fun nightRecomputeStatus(clientRecordId: String): Flow<WorkStatus> =
            statusOf(WorkRequests.Names.recomputeNight(clientRecordId))

        private fun statusOf(uniqueName: String): Flow<WorkStatus> =
            workManager.getWorkInfosForUniqueWorkFlow(uniqueName).map { WorkStatus.of(it) }.distinctUntilChanged()

        private fun enqueueDaily(
            schedule: ScheduleSettings,
            zoneId: ZoneId,
        ) {
            val request = WorkRequests.daily(schedule, zoneId, clock.millis())
            workManager.enqueueUniquePeriodicWork(WorkRequests.Names.DAILY, ExistingPeriodicWorkPolicy.UPDATE, request)
            logger.i(TAG, "daily run at ${schedule.dailyTimeMinutes} min after midnight ($zoneId)")
        }

        /** Whether the unfinished work [name] is missing, has another schedule [tag], or (daily) drifted from [expectedNextMs]. */
        private suspend fun needsEnqueue(
            name: String,
            tag: String,
            expectedNextMs: Long?,
        ): Boolean {
            val current = workManager.getWorkInfosForUniqueWorkFlow(name).first().firstOrNull { !it.state.isFinished }
            return when {
                current == null || tag !in current.tags -> true
                expectedNextMs == null -> false
                else -> drifted(current, expectedNextMs)
            }
        }

        private fun drifted(
            current: WorkInfo,
            expectedNextMs: Long,
        ): Boolean {
            val next = current.nextScheduleTimeMillis
            val settled = current.state == WorkInfo.State.ENQUEUED && current.runAttemptCount == 0 && next > clock.millis()
            return settled && abs(next - expectedNextMs) > DailySchedule.DRIFT_TOLERANCE_MS
        }

        @Suppress("TooGenericExceptionCaught") // Following the settings must survive a failed enqueue (logged).
        private suspend fun ensureScheduledLogged(schedule: ScheduleSettings) {
            try {
                ensureScheduled(schedule)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "could not schedule the background work", throwable = e)
            }
        }

        private companion object {
            const val TAG = "WorkScheduler"
        }
    }

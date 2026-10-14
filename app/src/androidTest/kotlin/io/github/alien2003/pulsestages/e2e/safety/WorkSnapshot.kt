package io.github.alien2003.pulsestages.e2e.safety

import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.alien2003.pulsestages.e2e.E2e
import io.github.alien2003.pulsestages.work.schedule.WorkJob
import io.github.alien2003.pulsestages.work.schedule.WorkRequests
import kotlinx.serialization.Serializable

/**
 * One WorkManager work of the app, as WorkManager reports it.
 *
 * @property uniqueName the unique work name it was found under, or null when it was only found by the app's tag.
 * @property id work id.
 * @property state WorkInfo.State.
 * @property tags its tags (worker class, job tag, schedule tag).
 * @property runAttemptCount runs of the current request so far.
 * @property generation how often the request was updated (ExistingPeriodicWorkPolicy.UPDATE).
 * @property nextScheduleTimeMillis next run, epoch ms (Long.MAX_VALUE when not scheduled).
 * @property initialDelayMillis initial delay of the request.
 * @property repeatIntervalMillis repeat interval of a periodic work, or null.
 * @property flexIntervalMillis flex interval of a periodic work, or null.
 * @property requiresBatteryNotLow constraint.
 * @property requiresCharging constraint.
 * @property requiresDeviceIdle constraint.
 * @property requiresStorageNotLow constraint.
 * @property requiredNetworkType constraint.
 * @property stopReason WorkInfo.stopReason.
 */
@Serializable
data class WorkEntry(
    val uniqueName: String?,
    val id: String,
    val state: String,
    val tags: List<String>,
    val runAttemptCount: Int,
    val generation: Int,
    val nextScheduleTimeMillis: Long,
    val initialDelayMillis: Long,
    val repeatIntervalMillis: Long?,
    val flexIntervalMillis: Long?,
    val requiresBatteryNotLow: Boolean,
    val requiresCharging: Boolean,
    val requiresDeviceIdle: Boolean,
    val requiresStorageNotLow: Boolean,
    val requiredNetworkType: String,
    val stopReason: Int,
)

/**
 * Reads the app's WorkManager works. READ-ONLY: only `getWorkInfosForUniqueWork` and `getWorkInfosByTag` are called
 * (queries of WorkManager's database); nothing is enqueued, cancelled, updated or run.
 */
object WorkSnapshot {
    /** Every unique work of the app ([WorkJob]) plus every other work carrying the app's tag (night recomputes). */
    fun take(): List<WorkEntry> {
        val workManager = WorkManager.getInstance(E2e.app)
        val byName =
            WorkJob.entries.flatMap { job ->
                workManager.getWorkInfosForUniqueWork(job.uniqueName).get().map { entry(job.uniqueName, it) }
            }
        val known = byName.mapTo(HashSet()) { it.id }
        val tagged =
            workManager
                .getWorkInfosByTag(WorkRequests.TAG_ALL)
                .get()
                .filter { it.id.toString() !in known }
                .map { entry(null, it) }
        return (byName + tagged).sortedWith(compareBy({ it.uniqueName ?: "~" }, { it.id }))
    }

    private fun entry(
        uniqueName: String?,
        info: WorkInfo,
    ): WorkEntry {
        val constraints = info.constraints
        return WorkEntry(
            uniqueName = uniqueName,
            id = info.id.toString(),
            state = info.state.name,
            tags = info.tags.sorted(),
            runAttemptCount = info.runAttemptCount,
            generation = info.generation,
            nextScheduleTimeMillis = info.nextScheduleTimeMillis,
            initialDelayMillis = info.initialDelayMillis,
            repeatIntervalMillis = info.periodicityInfo?.repeatIntervalMillis,
            flexIntervalMillis = info.periodicityInfo?.flexIntervalMillis,
            requiresBatteryNotLow = constraints.requiresBatteryNotLow(),
            requiresCharging = constraints.requiresCharging(),
            requiresDeviceIdle = constraints.requiresDeviceIdle(),
            requiresStorageNotLow = constraints.requiresStorageNotLow(),
            requiredNetworkType = constraints.requiredNetworkType.name,
            stopReason = info.stopReason,
        )
    }
}

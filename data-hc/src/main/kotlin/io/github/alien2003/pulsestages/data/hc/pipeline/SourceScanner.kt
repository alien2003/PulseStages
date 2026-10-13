package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.SleepOriginStats
import io.github.alien2003.pulsestages.data.hc.di.OwnPackageName
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.Clock
import javax.inject.Inject

/**
 * One row of the "Sources" screen (the app adds the label and icon from PackageManager).
 *
 * @property packageName the data origin.
 * @property sessionCount sleep sessions it wrote in the scanned range (0 for a manually added package).
 * @property lastSessionEndMs end of its latest session in the range, or null.
 * @property enabled whether it is an enabled sleep source.
 * @property priority position in `sourcePriority` (0 = highest), or null when it is not listed.
 * @property manual whether it was added by name.
 * @property isOwn whether it is PulseStages itself (shown for completeness; never usable as a source).
 */
data class SourceEntry(
    val packageName: String,
    val sessionCount: Int,
    val lastSessionEndMs: Long?,
    val enabled: Boolean,
    val priority: Int?,
    val manual: Boolean,
    val isOwn: Boolean,
)

/**
 * Result of [SourceScanner.scan].
 *
 * @property sources every origin found plus every listed / manual package, in priority order (listed packages first,
 *   then the others by session count).
 * @property fullHistory true when the whole history was scanned (READ_HEALTH_DATA_HISTORY granted), false for the
 *   last [SourceScanner.RECENT_DAYS] days.
 * @property fromMs start of the scanned range.
 * @property toMs end of the scanned range.
 */
data class SourceScanResult(
    val sources: List<SourceEntry>,
    val fullHistory: Boolean,
    val fromMs: Long,
    val toMs: Long,
)

/**
 * Finds every app that wrote sleep sessions to Health Connect: the last [RECENT_DAYS] days, or the full history when
 * READ_HEALTH_DATA_HISTORY is granted. Throws [io.github.alien2003.pulsestages.data.hc.HealthDataException] on
 * failure.
 */
class SourceScanner
    @Inject
    constructor(
        private val repository: HealthRepository,
        private val settingsRepository: SettingsRepository,
        private val clock: Clock,
        @param:OwnPackageName private val ownPackage: String,
    ) {
        /** Scans the sleep origins and merges them with the source settings. */
        suspend fun scan(): SourceScanResult {
            val settings = settingsRepository.settings.first().sources
            val now = clock.millis()
            val fullHistory = HealthPermissions.READ_HEALTH_DATA_HISTORY in repository.grantedPermissions()
            val fromMs = if (fullHistory) 0L else now - RECENT_DAYS * DAY_MS
            val stats = repository.scanSleepOrigins(fromMs, now + 1).associateBy { it.packageName }

            val listed = (settings.sourcePriority + settings.sourcePackages + settings.manualPackages).distinct()
            val others =
                stats.values
                    .filter { it.packageName !in listed }
                    .sortedWith(compareByDescending<SleepOriginStats> { it.sessionCount }.thenBy { it.packageName })
            val ordered = listed + others.map { it.packageName }
            val entries =
                ordered.map { packageName ->
                    val stat = stats[packageName]
                    SourceEntry(
                        packageName = packageName,
                        sessionCount = stat?.sessionCount ?: 0,
                        lastSessionEndMs = stat?.lastSessionEndMs,
                        enabled = packageName in settings.sourcePackages && packageName != ownPackage,
                        priority = settings.sourcePriority.indexOf(packageName).takeIf { it >= 0 },
                        manual = packageName in settings.manualPackages,
                        isOwn = packageName == ownPackage,
                    )
                }
            return SourceScanResult(entries, fullHistory, fromMs, now)
        }

        /** Constants. */
        companion object {
            /** Days scanned without the history permission. */
            const val RECENT_DAYS: Long = 90
            private const val DAY_MS = 24L * 60 * 60 * 1000
        }
    }

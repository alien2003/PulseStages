package io.github.alien2003.pulsestages.data.local.settings

import io.github.alien2003.pulsestages.algo.params.AlgoParams
import kotlinx.serialization.Serializable

/** OHealth (OPPO/HeyTap Health) package — the default and initially the only enabled sleep source. */
const val OHEALTH_PACKAGE: String = "com.heytap.health.international"

/** Default daily run time: 09:00 local time, in minutes after midnight. */
const val DEFAULT_DAILY_TIME_MINUTES: Int = 540

/**
 * All user settings. Persisted in DataStore as JSON, exported/imported as the same JSON.
 * Only ADD fields with defaults; never rename (old exports must keep importing).
 */
@Serializable
data class AppSettings(
    val sources: SourceSettings = SourceSettings(),
    val schedule: ScheduleSettings = ScheduleSettings(),
    val write: WriteSettings = WriteSettings(),
    val algo: AlgoParams = AlgoParams(),
    val debug: DebugSettings = DebugSettings(),
)

/** Where heart rate and steps are read from. */
@Serializable
enum class HrSourcesMode { SAME_AS_SLEEP, ANY }

/** Source selection ("Sources" screen). */
@Serializable
data class SourceSettings(
    /** Enabled sleep-session source packages. */
    val sourcePackages: List<String> = listOf(OHEALTH_PACKAGE),
    /** All known packages in priority order (first = highest); enabled or not. */
    val sourcePriority: List<String> = listOf(OHEALTH_PACKAGE),
    /** Packages added manually by name (kept even if they never wrote sleep yet). */
    val manualPackages: List<String> = emptyList(),
    val hrSources: HrSourcesMode = HrSourcesMode.SAME_AS_SLEEP,
    /** Sessions shorter than this (after merging fragments) are naps and skipped. */
    val minSessionMin: Int = 90,
    /** Fragments of one night from one source with a smaller gap are merged. */
    val mergeGapMin: Int = 30,
    /** History depth for backfill; 0 = all available history. */
    val backfillDays: Int = 0,
)

/** WorkManager scheduling. */
@Serializable
data class ScheduleSettings(
    /** Daily run time, minutes after local midnight (09:00 = 540). */
    val dailyTimeMinutes: Int = DEFAULT_DAILY_TIME_MINUTES,
    /** Period of the Changes-API check. */
    val periodicHours: Int = 1,
    val runOnlyWhenNotLowBattery: Boolean = true,
)

/** What to do with nights that already have an output session. */
@Serializable
enum class OverwritePolicy {
    /** Skip nights estimated with the same algoVersion + paramsHash; others are marked stale, not recomputed. */
    SKIP_SAME_VERSION,

    /** Recompute everything, including sessions that already have stages (the "recompute all" mode). */
    RECOMPUTE_ALL,

    /** Never overwrite an existing output session. */
    NEVER,
}

/** Output session options. */
@Serializable
data class WriteSettings(
    /**
     * Title template; null = localized default ("Sleep stages from heart rate (estimate)", translated on a Russian system).
     * Placeholders: {estimator}, {version}, {confidence} (percent).
     */
    val titleTemplate: String? = null,
    val overwritePolicy: OverwritePolicy = OverwritePolicy.SKIP_SAME_VERSION,
    /** Write AWAKE as LIGHT for readers that render awakenings poorly. Part of the params hash. */
    val awakeAsLight: Boolean = false,
)

/** Internal log level. */
@Serializable
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Debugging options. */
@Serializable
data class DebugSettings(
    val logLevel: LogLevel = LogLevel.INFO,
)

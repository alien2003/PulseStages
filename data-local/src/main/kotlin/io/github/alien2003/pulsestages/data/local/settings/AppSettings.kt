package io.github.alien2003.pulsestages.data.local.settings

import io.github.alien2003.pulsestages.algo.params.AlgoParams
import kotlinx.serialization.Serializable

/** OHealth (OPPO/HeyTap Health) package — the default and initially the only enabled sleep source. */
const val OHEALTH_PACKAGE: String = "com.heytap.health.international"

/** Default daily run time: 09:00 local time, in minutes after midnight. */
const val DEFAULT_DAILY_TIME_MINUTES: Int = 540

/** Default period of the Changes-API check: hourly, in minutes. */
const val DEFAULT_PERIODIC_MINUTES: Int = 60

/**
 * All user settings. Persisted in DataStore as JSON (only the values that differ from the defaults, see
 * [SettingsJson]), exported/imported as the same JSON with every value. Only ADD fields with defaults; never rename
 * (old files and exports must keep loading). The one sanctioned exception is a renamed key that keeps loading through a
 * read migration: `schedule.periodicHours` became `schedule.periodicMinutes` ([ScheduleSettingsSerializer]).
 *
 * @property sources sleep sources ("Sources" screen).
 * @property schedule background scheduling.
 * @property write options of the written sessions.
 * @property algo every algorithm parameter (part of the params hash).
 * @property debug debugging options.
 */
@Serializable
data class AppSettings(
    val sources: SourceSettings = SourceSettings(),
    @Serializable(with = ScheduleSettingsSerializer::class)
    val schedule: ScheduleSettings = ScheduleSettings(),
    val write: WriteSettings = WriteSettings(),
    val algo: AlgoParams = AlgoParams(),
    val debug: DebugSettings = DebugSettings(),
)

/** Where heart rate and steps are read from. */
@Serializable
enum class HrSourcesMode {
    /** From the package that wrote the sleep session. */
    SAME_AS_SLEEP,

    /** From any package. */
    ANY,
}

/** Source selection ("Sources" screen). */
@Serializable
data class SourceSettings(
    /** Enabled sleep-session source packages. */
    val sourcePackages: List<String> = listOf(OHEALTH_PACKAGE),
    /** All known packages in priority order (first = highest); enabled or not. */
    val sourcePriority: List<String> = listOf(OHEALTH_PACKAGE),
    /** Packages added manually by name (kept even if they never wrote sleep yet). */
    val manualPackages: List<String> = emptyList(),
    /** Origin of heart rate and steps. */
    val hrSources: HrSourcesMode = HrSourcesMode.SAME_AS_SLEEP,
    /**
     * Sessions shorter than this (after merging fragments) are ignored; longer ones below `napMode.maxSessionMin` are
     * processed as naps.
     */
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
    /** Period of the Changes-API check, minutes (one of SettingsLimits.periodicMinutes; WorkManager's minimum is 15). */
    val periodicMinutes: Int = DEFAULT_PERIODIC_MINUTES,
    /** WorkManager constraint: run only when the battery is not low. */
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
    /** What to do with nights that already have an output session. */
    val overwritePolicy: OverwritePolicy = OverwritePolicy.SKIP_SAME_VERSION,
    /** Write AWAKE as LIGHT for readers that render awakenings poorly. Part of the params hash. */
    val awakeAsLight: Boolean = false,
    /**
     * Start every written session one second before its night (the start of its first source session), for Sleep as
     * Android: of overlapping sessions it imports the one that starts first, and it loses a tie to the source session,
     * which has no stages. Part of the params hash.
     */
    val startBeforeSource: Boolean = false,
    /**
     * After a run that wrote or rewrote a session, ask Sleep as Android (its REQUEST_SYNC broadcast) to sync with Health
     * Connect, and once more about 10 minutes after a new or recomputed session, so that it attaches heart rate and SpO2
     * to the night it imported. On by default: it does nothing while Sleep as Android is not installed, and the request
     * carries no data. Not part of the params hash.
     */
    val requestSleepAsAndroidSync: Boolean = true,
)

/** Internal log level, in increasing severity. */
@Serializable
enum class LogLevel {
    /** Detailed diagnostics. */
    DEBUG,

    /** Normal progress. */
    INFO,

    /** Something unexpected that was handled. */
    WARN,

    /** A failure. */
    ERROR,
}

/** Debugging options. */
@Serializable
data class DebugSettings(
    /** Minimum level of the internal log (and of its logcat mirror). */
    val logLevel: LogLevel = LogLevel.INFO,
)

package io.github.alien2003.pulsestages.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.alien2003.pulsestages.data.local.settings.LogLevel

/**
 * Link between an output night and one of its source sessions (table `night_sources`).
 *
 * @property clientRecordId the night's clientRecordId.
 * @property sourceUuid Health Connect id of a source session of that night.
 */
@Entity(
    tableName = "night_sources",
    primaryKeys = ["clientRecordId", "sourceUuid"],
    indices = [Index(value = ["sourceUuid"])],
)
data class NightSourceEntity(
    val clientRecordId: String,
    val sourceUuid: String,
)

/** What happened to a source session (the Home screen counters are built from these). */
enum class SourceOutcome {
    /** Part of a night that was estimated and written. */
    ESTIMATED,

    /**
     * Shorter than the source setting `minSessionMin` after merging: ignored (the name predates the nap mode; a
     * session between `minSessionMin` and `napMode.maxSessionMin` is estimated as a nap and is ESTIMATED).
     */
    NAP,

    /** Already has stages (skipped unless "recompute all"). */
    HAS_STAGES,

    /** Written by PulseStages itself. */
    OWN,

    /**
     * Overlaps a session of a higher-priority source, or lies inside a longer session of another source that is used
     * itself while covering at most half of it (a piece of that sleep, whatever the priority).
     */
    LOWER_PRIORITY_DUPLICATE,

    /** Not estimated (no heart rate / low coverage). */
    NOT_ESTIMATED,

    /** Processing failed. */
    FAILED,

    /** Deleted from Health Connect by its app or the user. */
    DELETED,
}

/**
 * A sleep session seen in Health Connect (table `source_sessions`).
 *
 * @property uuid Health Connect record id.
 * @property packageName origin package.
 * @property startMs session start, epoch ms UTC.
 * @property endMs session end, epoch ms UTC.
 * @property outcome what happened to it.
 * @property reason English detail, or null.
 * @property nightClientRecordId clientRecordId of the night it belongs to, or null.
 * @property lastSeenAt when it was last read from Health Connect.
 */
@Entity(
    tableName = "source_sessions",
    indices = [Index(value = ["startMs"]), Index(value = ["nightClientRecordId"])],
)
data class SourceSessionEntity(
    @PrimaryKey val uuid: String,
    val packageName: String,
    val startMs: Long,
    val endMs: Long,
    val outcome: SourceOutcome,
    val reason: String? = null,
    val nightClientRecordId: String? = null,
    val lastSeenAt: Long,
)

/**
 * Heart-rate nadir of an estimated night, used for the rolling baseline (table `night_baselines`). The session length
 * that decides whether the row counts (naps do not) is read from the night's `estimated_nights` row by
 * [NightBaselineDao.recentNadirs].
 *
 * @property clientRecordId the night's clientRecordId.
 * @property sessionEndMs end of the night, epoch ms UTC.
 * @property nadirBpm lowest smoothed heart rate of the night.
 */
@Entity(tableName = "night_baselines", indices = [Index(value = ["sessionEndMs"])])
data class NightBaselineEntity(
    @PrimaryKey val clientRecordId: String,
    val sessionEndMs: Long,
    val nadirBpm: Double,
)

/**
 * One internal log line (table `log_entries`, trimmed to the newest [MAX_ROWS] rows).
 *
 * @property id insertion order.
 * @property timestampMs when it was logged, epoch ms UTC.
 * @property level severity.
 * @property tag component tag.
 * @property message text (with the stack trace of a throwable, if any).
 * @property nightKey clientRecordId (or another night key) the line refers to, or null.
 */
@Entity(tableName = "log_entries", indices = [Index(value = ["timestampMs"]), Index(value = ["nightKey"])])
data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val nightKey: String? = null,
) {
    /** Limits. */
    companion object {
        /** Maximum number of rows kept. */
        const val MAX_ROWS: Int = 10_000
    }
}

/** State of the history backfill. */
enum class BackfillStatus {
    /** Never started or reset. */
    IDLE,

    /** In progress (or interrupted by process death; resumes from the cursor). */
    RUNNING,

    /** Finished. */
    DONE,

    /** Cancelled by the user. */
    CANCELLED,

    /** Stopped by an error (see `lastError`). */
    FAILED,

    /** Waiting for a retry (quota / transient error). */
    WAITING_RETRY,
}

/**
 * The single backfill row (table `backfill_state`, `id` is always [SINGLETON_ID]). Processing goes forward from
 * the oldest window to the newest so that baselines exist for later nights.
 *
 * @property id always [SINGLETON_ID].
 * @property status current status.
 * @property rangeStartMs start of the whole range, epoch ms UTC.
 * @property rangeEndMs end of the whole range, epoch ms UTC.
 * @property cursorMs start of the next window to process.
 * @property windowDays window length in days.
 * @property windowsTotal number of windows in the range.
 * @property windowsDone number of finished windows.
 * @property nightsFound nights found so far.
 * @property nightsEstimated nights estimated so far.
 * @property nightsSkipped nights skipped so far.
 * @property nightsFailed nights failed so far.
 * @property historyLimited true when the range was shortened because READ_HEALTH_DATA_HISTORY is not granted.
 * @property startedAt when the backfill started.
 * @property updatedAt last change.
 * @property lastError English error detail, or null.
 */
@Suppress("LongParameterList") // Mirrors the table: one constructor parameter per column.
@Entity(tableName = "backfill_state")
data class BackfillStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val status: BackfillStatus,
    val rangeStartMs: Long,
    val rangeEndMs: Long,
    val cursorMs: Long,
    val windowDays: Int = DEFAULT_WINDOW_DAYS,
    val windowsTotal: Int = 0,
    val windowsDone: Int = 0,
    val nightsFound: Int = 0,
    val nightsEstimated: Int = 0,
    val nightsSkipped: Int = 0,
    val nightsFailed: Int = 0,
    val historyLimited: Boolean = false,
    val startedAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
) {
    /** Constants of the single-row table. */
    companion object {
        /** Primary key of the only row. */
        const val SINGLETON_ID: Int = 0

        /** Default window length in days. */
        const val DEFAULT_WINDOW_DAYS: Int = 7
    }
}

/** How a personal model was obtained. */
enum class PersonalModelKind {
    /** Calibrated against reference nights. */
    CALIBRATED,

    /** Unsupervised population-level EM over the user's nights. */
    EM,
}

/**
 * A stored personal model (table `personal_models`). At most one row is [active] (enforced by
 * [PersonalModelDao]).
 *
 * @property id local row id.
 * @property createdAt creation time, epoch ms UTC.
 * @property kind how it was obtained.
 * @property json the core-algo PersonalModel JSON.
 * @property fingerprint the model fingerprint (part of the params hash).
 * @property active whether this model is the one in use.
 * @property nightsUsed number of nights used to build it.
 * @property kappaBefore held-out kappa before calibration, if measured.
 * @property kappaAfter held-out kappa after calibration, if measured.
 */
@Entity(tableName = "personal_models", indices = [Index(value = ["active"])])
data class PersonalModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val kind: PersonalModelKind,
    val json: String,
    val fingerprint: String,
    val active: Boolean = false,
    val nightsUsed: Int,
    val kappaBefore: Double? = null,
    val kappaAfter: Double? = null,
)

/**
 * Home screen counters. Built from `source_sessions` (found = all except OWN; estimated = ESTIMATED; skipped = NAP +
 * HAS_STAGES + LOWER_PRIORITY_DUPLICATE + NOT_ESTIMATED + FAILED) plus stale WRITTEN nights and the WRITTEN outputs
 * per mode.
 *
 * @property found source sessions found (not ours).
 * @property estimated source sessions that are part of an estimated night.
 * @property skipped source sessions skipped for any reason (failures included).
 * @property failed source sessions whose processing failed (a subset of [skipped]).
 * @property stale WRITTEN nights with another algorithm version or params hash.
 * @property nights WRITTEN outputs of nights ([io.github.alien2003.pulsestages.algo.model.SessionMode.NIGHT]).
 * @property naps WRITTEN outputs of naps ([io.github.alien2003.pulsestages.algo.model.SessionMode.NAP]).
 */
data class HomeCounters(
    val found: Int,
    val estimated: Int,
    val skipped: Int,
    val failed: Int,
    val stale: Int,
    val nights: Int = 0,
    val naps: Int = 0,
)

package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.WriteSettings

/**
 * Estimates the stages of one night. Implemented in :work by an adapter over core-algo's `SleepStager` (with the
 * bundled hybrid model and the active personal model); tests use a simple deterministic fake.
 *
 * Not a `fun interface`: besides [estimate] it exposes the params hash and the model id of the configuration it
 * runs with. The configuration may change at run time (the active personal model lives in the ledger), which is why
 * [paramsHash] suspends: it always describes the configuration the next [estimate] uses.
 */
interface NightEstimator {
    /** Id of the hybrid model in use, or null when none is loaded (yet). */
    val modelId: String?

    /** Estimates one night with the coverage gates and the configured estimator; deterministic for the same input and params. */
    suspend fun estimate(
        input: NightInput,
        params: AlgoParams,
    ): EstimationOutcome

    /**
     * Estimates one night with [type] regardless of `params.estimator` and without the coverage gates (the "compare
     * with RULES" of the night detail screen).
     */
    suspend fun estimateWith(
        type: EstimatorType,
        input: NightInput,
        params: AlgoParams,
    ): NightEstimate

    /**
     * SHA-256 hex of [params] plus the model fingerprints plus [extra] (input selection and output options that change
     * the estimate or the written data, see [OutputOptions.hashExtra]). Two nights with equal hashes and algorithm
     * versions are considered up to date.
     */
    suspend fun paramsHash(
        params: AlgoParams,
        extra: String,
    ): String
}

/**
 * What the estimation input of a night is read from besides the algorithm parameters: the origins of heart rate and
 * steps (`hrSources`: the night's own package, or any origin) and the optional channels that are both enabled in the
 * parameters (`features.useSpo2` / `useRespiratoryRate` / `useHrv`) and granted. Changing it changes the input of the
 * same night (granting SpO2 later turns that channel on), so it is part of the params hash.
 *
 * @property hrSources origins of heart rate and steps.
 * @property spo2 SpO2 is read.
 * @property respiratoryRate respiratory rate is read.
 * @property hrv HRV is read.
 */
data class InputSelection(
    val hrSources: HrSourcesMode,
    val spo2: Boolean,
    val respiratoryRate: Boolean,
    val hrv: Boolean,
) {
    /** The part of the params hash's `extra` that describes this selection. */
    fun hashPart(): String = "hrSources=$hrSources;spo2=$spo2;respiratoryRate=$respiratoryRate;hrv=$hrv"

    /** Constructors. */
    companion object {
        /** The selection of [settings] with the [granted] Health Connect permissions. */
        fun of(
            settings: AppSettings,
            granted: Set<String>,
        ): InputSelection {
            val features = settings.algo.features
            return InputSelection(
                hrSources = settings.sources.hrSources,
                spo2 = features.useSpo2 && HealthPermissions.READ_OXYGEN_SATURATION in granted,
                respiratoryRate = features.useRespiratoryRate && HealthPermissions.READ_RESPIRATORY_RATE in granted,
                hrv = features.useHrv && HealthPermissions.READ_HEART_RATE_VARIABILITY in granted,
            )
        }
    }
}

/** Input selection and output options that change what is estimated or written and therefore belong in the params hash. */
object OutputOptions {
    /** How much earlier than its night a record starts with `write.startBeforeSource`. */
    const val START_BEFORE_SOURCE_MS: Long = 1_000L

    /**
     * The output part of the params hash's `extra` for [write] (the title template is deliberately not part of it).
     * `startBeforeSource` is appended only when it is on, so the hashes of nights written without it stay the same.
     */
    fun hashExtra(write: WriteSettings): String =
        "awakeAsLight=${write.awakeAsLight}" + if (write.startBeforeSource) ";startBeforeSource=true" else ""

    /**
     * The start of the record written for a night that starts at [nightStartMs]: [START_BEFORE_SOURCE_MS] earlier with
     * `startBeforeSource`.
     */
    fun recordStartMs(
        nightStartMs: Long,
        write: WriteSettings,
    ): Long = if (write.startBeforeSource) nightStartMs - START_BEFORE_SOURCE_MS else nightStartMs

    /**
     * The `extra` part of the params hash of nights estimated with [settings] and the [granted] permissions: the
     * output options of `settings.write` and the [InputSelection].
     */
    fun hashExtra(
        settings: AppSettings,
        granted: Set<String>,
    ): String = hashExtra(settings.write) + ";" + InputSelection.of(settings, granted).hashPart()
}

/** Why the pipeline was started; decides how existing outputs are treated. */
enum class PipelineTrigger(
    /** Whether up-to-date and stale WRITTEN nights are recomputed (an explicit user request). */
    val forcesRecompute: Boolean,
    /** Whether nights whose output the user deleted (status DELETED) are written again. */
    val recreatesDeleted: Boolean,
) {
    /** Changes-API sync of new nights (periodic, daily or the "estimate new" button). */
    NEW_NIGHTS(forcesRecompute = false, recreatesDeleted = false),

    /** History backfill started by the user. */
    BACKFILL(forcesRecompute = false, recreatesDeleted = true),

    /** Explicit recompute of selected or stale nights. */
    RECOMPUTE(forcesRecompute = true, recreatesDeleted = true),
}

/** Why a run did not read anything (or stopped). */
enum class BlockedReason {
    /** Health Connect is not installed / needs an update / is not supported. */
    HEALTH_CONNECT_UNAVAILABLE,

    /** A required permission (READ_SLEEP, WRITE_SLEEP, READ_HEART_RATE, READ_STEPS) is missing or was revoked. */
    PERMISSION_MISSING,

    /**
     * The run is (or became) a background run and READ_HEALTH_DATA_IN_BACKGROUND is not granted: Health Connect
     * rejects reads of other apps' data from the background, so nothing is read (or the run stopped).
     */
    BACKGROUND_PERMISSION_MISSING,
}

/**
 * Outcome of processing a set of nights. A "night" is one merged group of source sessions of one package.
 * `found − estimated − skipped − failed` nights were not processed because the run stopped early ([quotaHit],
 * [blockedReason] or [cancelled]). The refresh checks of written nights ([inputChecks], [refreshed]) are counted outside
 * [found].
 *
 * @property found nights found (merged groups, naps and duplicates included; our own sessions excluded).
 * @property estimated nights estimated and written in this run.
 * @property skipped nights not written: naps, sessions with stages, lower-priority duplicates, not estimable,
 *   already up to date, stale ones left alone, NEVER policy, deleted by the user.
 * @property stale WRITTEN nights left stale (other algorithm version / params hash; a subset of [skipped]).
 * @property upToDate WRITTEN nights that were already up to date (a subset of [skipped]).
 * @property failed nights whose processing failed (logged; the others continued).
 * @property notEstimated nights the estimator declined (no heart rate / low coverage; a subset of [skipped]).
 * @property superseded older outputs replaced by a merged / changed night or removed because their sources were
 *   deleted.
 * @property inputChecks written nights whose input was read again by the refresh ([NightPipeline.refresh]), rewritten
 *   or not; they are not counted in [found], [skipped] or [upToDate] (a refresh check that fails is counted in [found]
 *   and [failed] like any night).
 * @property refreshed written nights rewritten because their input changed (not counted in [estimated]).
 * @property quotaHit the run stopped because Health Connect's rate limit was hit (after the in-call retries).
 * @property blockedReason why the run read nothing or stopped, if it did.
 * @property cancelled the run was cancelled between nights.
 */
data class PipelineReport(
    val found: Int = 0,
    val estimated: Int = 0,
    val skipped: Int = 0,
    val stale: Int = 0,
    val upToDate: Int = 0,
    val failed: Int = 0,
    val notEstimated: Int = 0,
    val superseded: Int = 0,
    val inputChecks: Int = 0,
    val refreshed: Int = 0,
    val quotaHit: Boolean = false,
    val blockedReason: BlockedReason? = null,
    val cancelled: Boolean = false,
) {
    /** True when the run stopped before processing everything. */
    val stoppedEarly: Boolean get() = quotaHit || blockedReason != null || cancelled

    /** Sum of both reports (flags are or-ed; the first blocked reason wins). */
    operator fun plus(other: PipelineReport): PipelineReport =
        PipelineReport(
            found = found + other.found,
            estimated = estimated + other.estimated,
            skipped = skipped + other.skipped,
            stale = stale + other.stale,
            upToDate = upToDate + other.upToDate,
            failed = failed + other.failed,
            notEstimated = notEstimated + other.notEstimated,
            superseded = superseded + other.superseded,
            inputChecks = inputChecks + other.inputChecks,
            refreshed = refreshed + other.refreshed,
            quotaHit = quotaHit || other.quotaHit,
            blockedReason = blockedReason ?: other.blockedReason,
            cancelled = cancelled || other.cancelled,
        )

    /** Factories. */
    companion object {
        /** A run that read nothing because of [reason]. */
        fun blocked(reason: BlockedReason): PipelineReport = PipelineReport(blockedReason = reason)
    }
}

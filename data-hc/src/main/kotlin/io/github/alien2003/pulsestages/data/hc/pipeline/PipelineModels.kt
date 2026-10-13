package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.data.local.settings.WriteSettings

/**
 * Estimates the stages of one night. Implemented in :work / :app by an adapter over core-algo's `SleepStager`
 * (with the bundled hybrid model and the active personal model); tests use a simple deterministic fake.
 *
 * Not a `fun interface`: besides [estimate] it exposes the params hash and the model id of the configuration it
 * runs with.
 */
interface NightEstimator {
    /** Id of the hybrid model in use, or null when none is loaded. */
    val modelId: String?

    /** Estimates one night; must be deterministic for the same input and params. */
    suspend fun estimate(
        input: NightInput,
        params: AlgoParams,
    ): EstimationOutcome

    /**
     * SHA-256 hex of [params] plus the model fingerprints plus [extra] (output options that change the written data,
     * see [OutputOptions.hashExtra]). Two nights with equal hashes and algorithm versions are considered up to date.
     */
    fun paramsHash(
        params: AlgoParams,
        extra: String,
    ): String
}

/** Output options that change what is written and therefore belong in the params hash. */
object OutputOptions {
    /** The `extra` part of the params hash for [write] (the title template is deliberately not part of it). */
    fun hashExtra(write: WriteSettings): String = "awakeAsLight=${write.awakeAsLight}"
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
 * [blockedReason] or [cancelled]).
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

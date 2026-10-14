package io.github.alien2003.pulsestages.e2e.shadow

import kotlinx.serialization.Serializable

/**
 * What the history holds and how much of it the shadow test covers.
 *
 * @property settingsSource where the "real settings" came from (the app's DataStore file, or the defaults).
 * @property settingsFileSha256 SHA-256 of the app's settings file before the test (null without a file).
 * @property sourcePackages the enabled sleep sources.
 * @property sessions sleep sessions of the enabled sources in all history.
 * @property nights candidate nights of all modes.
 * @property naps candidates processed as naps.
 * @property skippedGroups source groups that are no candidate (naps below the minimum, staged sessions, duplicates).
 * @property oldestSessionStartMs start of the oldest session, or null.
 * @property estimatedReadCalls estimated Health Connect read calls of a full-history pass.
 * @property historyDays days the scenarios cover (0 = all history).
 * @property capped the history was shortened to stay within the read budget.
 * @property coveredNights candidate nights inside [historyDays].
 * @property policyDays days the policy scenarios cover (the most recent naps, at least two weeks).
 * @property modelId id of the bundled hybrid model the estimator loaded.
 */
@Serializable
data class Census(
    val settingsSource: String,
    val settingsFileSha256: String?,
    val sourcePackages: List<String>,
    val sessions: Int,
    val nights: Int,
    val naps: Int,
    val skippedGroups: Int,
    val oldestSessionStartMs: Long?,
    val estimatedReadCalls: Int,
    val historyDays: Int,
    val capped: Boolean,
    val coveredNights: Int,
    val policyDays: Int,
    val modelId: String?,
)

/**
 * One scenario of the shadow test.
 *
 * @property name the scenario (also the phase of its captured writes).
 * @property description what it does.
 * @property outcome how its run ended (backfill / sync / recompute outcome).
 * @property found nights found.
 * @property written captured writes.
 * @property deleted captured deletes (clientRecordIds).
 * @property notEstimated nights the estimator declined.
 * @property failed nights that failed.
 * @property durationMs wall time.
 * @property stageTypes captured stage types (STAGE_TYPE_* value → number of stages).
 * @property problems invariant violations and failed expectations (they fail the test).
 * @property notes information.
 */
@Serializable
data class ScenarioReport(
    val name: String,
    val description: String,
    val outcome: String,
    val found: Int,
    val written: Int,
    val deleted: Int,
    val notEstimated: Int,
    val failed: Int,
    val durationMs: Long,
    val stageTypes: Map<Int, Int>,
    val problems: List<String>,
    val notes: List<String>,
)

/**
 * One session of the history as the isolated pipeline left it.
 *
 * @property clientRecordId the night's clientRecordId.
 * @property sourcePackage the package that wrote the source session(s).
 * @property date local date of the start (at the session's zone offset).
 * @property start local start time.
 * @property end local end time.
 * @property lengthMin length in minutes.
 * @property mode NIGHT or NAP.
 * @property outcome ledger status (WRITTEN, NOT_ESTIMATED, FAILED, ...).
 * @property reason the status reason, if any.
 * @property estimator the estimator.
 * @property coverage heart-rate coverage.
 * @property confidence night confidence.
 * @property shares stage shares of the estimated minutes (awake, light, deep, rem, unknown).
 * @property warnings warning codes.
 * @property hrSamples heart-rate samples used.
 * @property hrMedianIntervalSec median time between heart-rate samples inside the session, if known.
 * @property estimateMs time of the estimate, if it was estimated.
 * @property writtenVersion clientRecordVersion of the captured write, if any.
 * @property stageCount stages written, if any.
 * @property matchesAppRecord for a night the app has really written: whether the shadow's stages equal the stored
 *   ones (null when the app has no record of it).
 */
@Serializable
data class SessionReport(
    val clientRecordId: String,
    val sourcePackage: String,
    val date: String,
    val start: String,
    val end: String,
    val lengthMin: Long,
    val mode: String,
    val outcome: String,
    val reason: String?,
    val estimator: String,
    val coverage: Double,
    val confidence: Double,
    val shares: Map<String, Double>,
    val warnings: List<String>,
    val hrSamples: Int,
    val hrMedianIntervalSec: Double?,
    val estimateMs: Long?,
    val writtenVersion: Long?,
    val stageCount: Int?,
    val matchesAppRecord: Boolean?,
)

/**
 * The whole shadow report (`e2e/shadow/report.json`).
 *
 * @property runId id of the run.
 * @property passed no problem in any scenario and no safety violation.
 * @property census the history.
 * @property scenarios every scenario, in order.
 * @property sessions the sessions of the full-history run with the real settings.
 * @property sessionsWithDefaults the sessions of the full-history run with the default settings.
 * @property readCallsCharged read calls of the run so far (whole run, all tests).
 * @property cacheHits reads served from the cache.
 * @property cacheMisses reads that went to Health Connect.
 * @property platformQuotaErrors rate-limit errors Health Connect returned.
 * @property budgetRefusals reads the suite's budget refused.
 * @property safetyViolations writes / deletes the safety guard refused (must be 0).
 * @property healthConnectWrites writes and deletes that reached Health Connect from this test (always 0: by design).
 * @property warningsLogged warnings and errors the pipeline logged.
 */
@Serializable
data class ShadowReport(
    val runId: String,
    val passed: Boolean,
    val census: Census?,
    val scenarios: List<ScenarioReport>,
    val sessions: List<SessionReport>,
    val sessionsWithDefaults: List<SessionReport>,
    val readCallsCharged: Int,
    val cacheHits: Int,
    val cacheMisses: Int,
    val platformQuotaErrors: Int,
    val budgetRefusals: Int,
    val safetyViolations: Int,
    val healthConnectWrites: Int,
    val warningsLogged: List<String>,
)

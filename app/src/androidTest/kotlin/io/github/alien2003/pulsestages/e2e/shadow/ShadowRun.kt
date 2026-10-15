package io.github.alien2003.pulsestages.e2e.shadow

import android.os.SystemClock
import androidx.health.connect.client.records.SleepSessionRecord
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.params.GapPolicy
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.pipeline.BackfillOutcome
import io.github.alien2003.pulsestages.data.hc.pipeline.BackfillReport
import io.github.alien2003.pulsestages.data.hc.pipeline.OutputOptions
import io.github.alien2003.pulsestages.data.hc.pipeline.RecomputeScope
import io.github.alien2003.pulsestages.data.hc.pipeline.ResourceDefaultTitleProvider
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.SKIPPED_NAP_REASON_PREFIX
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import io.github.alien2003.pulsestages.e2e.E2e
import io.github.alien2003.pulsestages.e2e.safety.ReadBudget
import io.github.alien2003.pulsestages.e2e.support.AppSettingsFile
import io.github.alien2003.pulsestages.e2e.support.E2eLogger
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The scenarios of [io.github.alien2003.pulsestages.e2e.ShadowPipelineE2eTest], in this order:
 * 1. `real-settings`: the backfill engine over the history with the user's real settings (copied read-only from the
 *    app's settings file), on an isolated ledger; every captured write must satisfy [WriteInvariants], and ledger and
 *    simulated Health Connect must agree;
 * 2. `idempotency-backfill` and `idempotency-sync`: a second backfill and a Changes-API sync (with its refresh of
 *    recently written nights) on the same ledger must write and delete nothing;
 * 3. `refresh-recent` ([RefreshScenario]): the automatic refresh of recently written nights: a sync on unchanged data
 *    writes nothing; with synthetic late heart rate (in memory only) a sync rewrites only written nights, each once with
 *    clientRecordVersion + 1 and an input update, at most the per-run cap; the same sync again does not rewrite them;
 * 4. `recompute-all`: "recompute all" must rewrite every written night exactly once, with clientRecordVersion + 1;
 * 5. `defaults`: the history again with the default settings;
 * 6. policies on the most recent weeks: naps SKIP / SLEEP_WAKE, awakeAsLight, startBeforeSource, gapPolicy LIGHT, hrSources ANY.
 * Nothing is written to Health Connect ([ShadowWriteRepository]); the reads are those of the real repository.
 */
class ShadowRun(
    private val reads: CachingReadRepository,
    private val hybridModels: HybridModelProvider,
    private val logger: E2eLogger,
    private val budget: ReadBudget,
) {
    /** The census, once taken. */
    var census: Census? = null
        private set

    /** Every scenario run so far. */
    val scenarios: MutableList<ScenarioReport> = mutableListOf()

    /** The sessions of the real-settings scenario. */
    var sessions: List<SessionReport> = emptyList()
        private set

    /** The sessions of the defaults scenario. */
    var sessionsWithDefaults: List<SessionReport> = emptyList()
        private set

    private val general = mutableListOf<String>()
    private val defaultTitle: String by lazy { ResourceDefaultTitleProvider(E2e.app).defaultTitleTemplate() }
    private var appRecords: Map<String, HcSleepSession> = emptyMap()

    /** Every problem found (scenario problems are prefixed with the scenario). */
    val problems: List<String> get() = general + scenarios.flatMap { scenario -> scenario.problems.map { "${scenario.name}: $it" } }

    /** Runs every scenario. */
    suspend fun runAll() {
        val file = AppSettingsFile.read()
        file.problem?.let { general += "the app's settings file could not be decoded, the defaults are used: $it" }
        val now = System.currentTimeMillis()
        val granted = reads.grantedPermissions()
        // The budget of this step: the caller capped it, so that the later steps keep their share.
        val available = budget.remaining()
        val modelId = hybridModels.hybridModel()?.modelId
        val census = ShadowCensus.take(reads, file, granted, available, now, modelId).also { census = it }
        E2e.progress(
            "census: ${census.nights} night(s) (${census.naps} nap(s)) of ${census.sourcePackages}, " +
                "about ${census.estimatedReadCalls} read calls for all history; " +
                "covering ${if (census.historyDays == 0) "all history" else "${census.historyDays} days"} " +
                "(${census.coveredNights} nights${if (census.capped) ", capped by the read budget" else ""}); model $modelId",
        )
        appRecords =
            reads.readOwnSessions(0L, now + DAY_MS).filter { it.clientRecordId != null }.associateBy { it.clientRecordId.orEmpty() }
        val real = file.settings.withHistory(census.historyDays)
        environment("real-settings").use { env ->
            backfill(env, real, "the backfill engine over the history with the user's settings")
            sessions = ShadowChecks.sessionReports(env, real, reads, appRecords)
            idempotency(env, real)
            refreshRecent(env)
            recomputeAll(env, real)
        }
        environment("defaults").use { env ->
            val defaults = AppSettings().withHistory(census.historyDays)
            backfill(env, defaults, "the backfill engine over the history with the default settings")
            sessionsWithDefaults = ShadowChecks.sessionReports(env, defaults, reads, appRecords)
        }
        policies(file.settings, census)
        if (AppSettingsFile.currentSha256() != file.sha256) general += "the app's settings file changed during the test"
    }

    private fun environment(name: String): ShadowEnvironment = ShadowEnvironment(name, reads, hybridModels, logger)

    /**
     * Runs the backfill of [env] with [settings]. A stop by Health Connect's own rate limit is resumed after a pause (at
     * most [MAX_RESUMES] times); a stop by the suite's read budget ends the history there (reported, not a failure).
     */
    private suspend fun backfill(
        env: ShadowEnvironment,
        settings: AppSettings,
        description: String,
        expect: suspend (ShadowEnvironment, List<CapturedWrite>, MutableList<String>, MutableList<String>) -> Unit = { _, _, _, _ -> },
    ): BackfillReport {
        val applied = env.useSettings(settings)
        env.health.phase = env.name
        val started = SystemClock.elapsedRealtime()
        val refusedBefore = budget.refusedCalls
        var report = env.backfill.run(inBackground = false, onProgress = progressOf(env.name))
        var resumes = 0
        while (report.outcome == BackfillOutcome.QUOTA_EXCEEDED && budget.refusedCalls == refusedBefore && resumes < MAX_RESUMES) {
            resumes++
            E2e.progress(
                "${env.name}: Health Connect's rate limit was reached; " +
                    "resuming in ${QUOTA_PAUSE_MS / MINUTE_MS} min ($resumes/$MAX_RESUMES)",
            )
            delay(QUOTA_PAUSE_MS)
            report = env.backfill.run(inBackground = false, onProgress = progressOf(env.name))
        }
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val truncated = report.outcome == BackfillOutcome.QUOTA_EXCEEDED
        when {
            report.outcome == BackfillOutcome.DONE -> Unit
            truncated && budget.refusedCalls > refusedBefore -> notes += "history truncated: the suite's read budget was used up"
            else -> problems += "the backfill ended with ${report.outcome} (${report.blockedReason ?: report.error ?: "-"})"
        }
        val writes = env.health.writesOf(env.name)
        problems += ShadowChecks.invariantProblems(writes, applied, defaultTitle)
        problems += ShadowChecks.consistencyProblems(env)
        expect(env, writes, problems, notes)
        val state = report.state
        scenarios +=
            ScenarioReport(
                name = env.name,
                description = description,
                outcome = report.outcome.name,
                found = state?.nightsFound ?: 0,
                written = writes.size,
                deleted = env.health.deletesOf(env.name).sumOf { it.clientRecordIds.size },
                notEstimated = env.database.estimatedNightDao().countWithStatus(NightStatus.NOT_ESTIMATED),
                failed = env.database.estimatedNightDao().countWithStatus(NightStatus.FAILED),
                durationMs = SystemClock.elapsedRealtime() - started,
                stageTypes = ShadowChecks.stageTypes(writes),
                problems = problems,
                notes = notes,
            )
        E2e.progress("${env.name}: ${report.outcome}, ${writes.size} write(s) captured, ${problems.size} problem(s)")
        return report
    }

    /** A second backfill and a Changes-API sync on the same ledger write and delete nothing. */
    private suspend fun idempotency(
        env: ShadowEnvironment,
        settings: AppSettings,
    ) {
        val started = SystemClock.elapsedRealtime()
        env.health.phase = "idempotency-backfill"
        val again = env.backfill.run(inBackground = false, onProgress = progressOf("idempotency-backfill"))
        env.health.phase = "idempotency-sync"
        val sync = env.sync.run(inBackground = false)
        val problems = mutableListOf<String>()
        val notes =
            mutableListOf(
                "settings: ${settings.sources.sourcePackages}; sync up to date: ${sync.pipeline.upToDate}; " +
                    "written nights whose input the sync's refresh read again: ${sync.pipeline.inputChecks}",
            )
        // Unchanged (cached) data give the fingerprints stored at the write: the refresh rewrites nothing, whatever the policy.
        if (sync.pipeline.refreshed > 0) problems += "idempotency-sync refreshed ${sync.pipeline.refreshed} night(s) on unchanged data"
        // RECOMPUTE_ALL rewrites every night on every run by design; the other policies must write nothing now.
        val rewritesByDesign = settings.write.overwritePolicy == OverwritePolicy.RECOMPUTE_ALL
        if (rewritesByDesign) notes += "overwritePolicy RECOMPUTE_ALL rewrites every night by design: the zero-write check is skipped"
        listOf("idempotency-backfill", "idempotency-sync").filterNot { rewritesByDesign }.forEach { phase ->
            val writes = env.health.writesOf(phase)
            val deletes = env.health.deletesOf(phase)
            if (writes.isNotEmpty()) {
                problems +=
                    "$phase wrote ${writes.size} session(s): ${writes.map { it.write.clientRecordId.take(ID_PREFIX) }}"
            }
            if (deletes.isNotEmpty()) problems += "$phase deleted ${deletes.sumOf { it.clientRecordIds.size }} session(s)"
        }
        if (again.outcome != BackfillOutcome.DONE &&
            again.outcome != BackfillOutcome.QUOTA_EXCEEDED
        ) {
            problems += "the second backfill ended with ${again.outcome}"
        }
        if (sync.outcome != SyncOutcome.SUCCESS &&
            sync.outcome != SyncOutcome.QUOTA_EXCEEDED
        ) {
            problems += "the sync ended with ${sync.outcome} (${sync.error})"
        }
        problems += ShadowChecks.consistencyProblems(env)
        scenarios +=
            ScenarioReport(
                name = "idempotency",
                description =
                    "a second backfill and a Changes-API sync (re-read of the last week, refresh of recently written nights) " +
                        "on the same ledger",
                outcome = "${again.outcome} / ${sync.outcome}",
                found = (again.state?.nightsFound ?: 0) + sync.pipeline.found,
                written = env.health.writesOf("idempotency-backfill").size + env.health.writesOf("idempotency-sync").size,
                deleted =
                    (
                        env.health.deletesOf(
                            "idempotency-backfill",
                        ) + env.health.deletesOf("idempotency-sync")
                    ).sumOf { it.clientRecordIds.size },
                notEstimated = sync.pipeline.notEstimated,
                failed = sync.pipeline.failed,
                durationMs = SystemClock.elapsedRealtime() - started,
                stageTypes = emptyMap(),
                problems = problems,
                notes = notes,
            )
    }

    /** The automatic refresh of recently written nights on the ledger of [env] ([RefreshScenario]). */
    private suspend fun refreshRecent(env: ShadowEnvironment) {
        val report = RefreshScenario(env, defaultTitle).run()
        scenarios += report
        E2e.progress("${report.name}: ${report.written} rewrite(s) in three syncs, ${report.problems.size} problem(s)")
    }

    /**
     * "Recompute all" rewrites every written night exactly once, with clientRecordVersion + 1 (relative to the ledger at
     * its start, so after the rewrites of the refresh scenario).
     */
    private suspend fun recomputeAll(
        env: ShadowEnvironment,
        settings: AppSettings,
    ) {
        val started = SystemClock.elapsedRealtime()
        val before =
            env.database
                .estimatedNightDao()
                .findOverlapping(Long.MIN_VALUE, Long.MAX_VALUE, listOf(NightStatus.WRITTEN))
                .associate { it.clientRecordId to it.clientRecordVersion }
        env.health.phase = "recompute-all"
        val report = env.recomputer.recomputeAll(RecomputeScope.ALL, inBackground = false)
        val writes = env.health.writesOf("recompute-all")
        val problems = mutableListOf<String>()
        val byId = writes.groupBy { it.write.clientRecordId }
        // NEVER is obeyed by an explicit recompute too: nothing is rewritten then.
        val expected = if (settings.write.overwritePolicy == OverwritePolicy.NEVER) emptyMap() else before
        (expected.keys - byId.keys).forEach { problems += "night ${it.take(ID_PREFIX)}… was not rewritten" }
        (byId.keys - expected.keys).forEach { problems += "night ${it.take(ID_PREFIX)}… was written but was not expected to be" }
        byId.forEach { (id, list) ->
            if (list.size != 1) problems += "night ${id.take(ID_PREFIX)}… was written ${list.size} times"
            val previous = before[id]
            if (previous != null && list.any { it.write.clientRecordVersion != previous + 1 }) {
                problems += "night ${id.take(ID_PREFIX)}…: version ${list.map { it.write.clientRecordVersion }} after $previous"
            }
        }
        if (env.health.deletesOf("recompute-all").isNotEmpty()) {
            problems +=
                "recompute-all deleted sessions: ${env.health.deletesOf("recompute-all")}"
        }
        if (report.stoppedEarly) problems += "the recompute stopped early (quota ${report.quotaHit}, blocked ${report.blockedReason})"
        problems += ShadowChecks.invariantProblems(writes, settings, defaultTitle)
        problems += ShadowChecks.consistencyProblems(env)
        scenarios +=
            ScenarioReport(
                name = "recompute-all",
                description = "\"Recompute all\" on the ledger of the real-settings scenario",
                outcome = if (report.stoppedEarly) "STOPPED" else "DONE",
                found = report.found,
                written = writes.size,
                deleted = 0,
                notEstimated = report.notEstimated,
                failed = report.failed,
                durationMs = SystemClock.elapsedRealtime() - started,
                stageTypes = ShadowChecks.stageTypes(writes),
                problems = problems,
                notes = listOf("${before.size} written night(s) before"),
            )
        E2e.progress("recompute-all: ${writes.size} rewrite(s) of ${before.size} night(s), ${problems.size} problem(s)")
    }

    /** The output policies on the most recent weeks (each on its own isolated ledger; the reads come from the cache). */
    private suspend fun policies(
        user: AppSettings,
        census: Census,
    ) {
        val base = user.withHistory(census.policyDays)
        val napMode = base.algo.napMode
        val skip = base.copy(algo = base.algo.copy(napMode = napMode.copy(outputPolicy = NapOutputPolicy.SKIP)))
        policy("policy-nap-skip", "naps are not written (napMode.outputPolicy SKIP)", skip, ::expectSkippedNaps)
        // SLEEP_WAKE is the default since the realistic-nap experiment: STAGES needs its own scenario, and the
        // SLEEP_WAKE one below still covers a user whose settings say STAGES.
        val stages = base.copy(algo = base.algo.copy(napMode = napMode.copy(outputPolicy = NapOutputPolicy.STAGES)))
        policy("policy-nap-stages", "naps written with stages (napMode.outputPolicy STAGES)", stages, ::expectStagedNaps)
        val sleepWake = base.copy(algo = base.algo.copy(napMode = napMode.copy(outputPolicy = NapOutputPolicy.SLEEP_WAKE)))
        policy(
            "policy-nap-sleep-wake",
            "naps written as sleep / wake only (napMode.outputPolicy SLEEP_WAKE)",
            sleepWake,
            ::expectSleepWakeNaps,
        )
        policy(
            "policy-awake-as-light",
            "AWAKE written as LIGHT (write.awakeAsLight)",
            base.copy(write = base.write.copy(awakeAsLight = true)),
        )
        policy(
            "policy-start-before-source",
            "sessions start one second before their source (write.startBeforeSource)",
            base.copy(write = base.write.copy(startBeforeSource = true)),
            ::expectStartBeforeSource,
        )
        val gapLight = base.copy(algo = base.algo.copy(post = base.algo.post.copy(gapPolicy = GapPolicy.LIGHT)))
        policy("policy-gap-light", "data gaps written as LIGHT (algo.post.gapPolicy LIGHT)", gapLight, ::expectNoUnknownStages)
        val any = user.withHistory(minOf(census.policyDays, ANY_SOURCES_DAYS))
        policy(
            "policy-hr-any",
            "heart rate and steps of any app (sources.hrSources ANY)",
            any.copy(sources = any.sources.copy(hrSources = HrSourcesMode.ANY)),
        )
    }

    /** Under SKIP no nap is written, and every nap of the ledger is NOT_ESTIMATED with the NAP_SKIPPED reason. */
    private suspend fun expectSkippedNaps(
        env: ShadowEnvironment,
        writes: List<CapturedWrite>,
        problems: MutableList<String>,
        notes: MutableList<String>,
    ) {
        writes
            .filter {
                modeOf(
                    it,
                ) == SessionMode.NAP
            }.forEach { problems += "nap ${it.write.clientRecordId.take(ID_PREFIX)}… was written" }
        val naps = allNights(env).filter { it.mode == SessionMode.NAP }
        naps
            .filterNot { it.status == NightStatus.NOT_ESTIMATED && it.statusReason?.startsWith(SKIPPED_NAP_REASON_PREFIX) == true }
            .forEach { problems += "nap ${it.clientRecordId.take(ID_PREFIX)}… is ${it.status} (${it.statusReason})" }
        if (naps.isEmpty()) notes += "no nap in the covered weeks: the nap check is vacuous"
    }

    /** Under SLEEP_WAKE every written nap is marked sleep/wake only (the stage types are checked by [WriteInvariants]). */
    private suspend fun expectSleepWakeNaps(
        env: ShadowEnvironment,
        writes: List<CapturedWrite>,
        problems: MutableList<String>,
        notes: MutableList<String>,
    ) {
        val naps = writes.filter { modeOf(it) == SessionMode.NAP }
        if (naps.isEmpty()) notes += "no nap was written in the covered weeks: the sleep/wake check is vacuous"
        naps.forEach { captured ->
            val night = env.database.estimatedNightDao().findByClientRecordId(captured.write.clientRecordId)
            val id = captured.write.clientRecordId.take(ID_PREFIX)
            if (night?.sleepWakeOnly != true) problems += "nap $id… is not sleep/wake only in the ledger"
        }
    }

    /**
     * Under STAGES no written nap is marked sleep/wake only (that only the stage types of a night are written, never
     * the generic SLEEPING, is checked by [WriteInvariants]).
     */
    private suspend fun expectStagedNaps(
        env: ShadowEnvironment,
        writes: List<CapturedWrite>,
        problems: MutableList<String>,
        notes: MutableList<String>,
    ) {
        val naps = writes.filter { modeOf(it) == SessionMode.NAP }
        if (naps.isEmpty()) notes += "no nap was written in the covered weeks: the stages check is vacuous"
        naps.forEach { captured ->
            val night = env.database.estimatedNightDao().findByClientRecordId(captured.write.clientRecordId)
            val id = captured.write.clientRecordId.take(ID_PREFIX)
            when {
                night == null -> problems += "nap $id… is not in the ledger"
                night.sleepWakeOnly -> problems += "nap $id… is marked sleep/wake only although naps are written with stages"
            }
        }
    }

    /** With gapPolicy LIGHT no written stage is UNKNOWN. */
    @Suppress("UnusedParameter") // The shape of every expectation.
    private suspend fun expectNoUnknownStages(
        env: ShadowEnvironment,
        writes: List<CapturedWrite>,
        problems: MutableList<String>,
        notes: MutableList<String>,
    ) {
        writes
            .filter { captured -> captured.write.stages.any { it.type == SleepSessionRecord.STAGE_TYPE_UNKNOWN } }
            .forEach { problems += "${it.write.clientRecordId.take(ID_PREFIX)}… has UNKNOWN stages although gaps are LIGHT" }
    }

    /** With startBeforeSource every session starts exactly one second before its night (the source's start). */
    private suspend fun expectStartBeforeSource(
        env: ShadowEnvironment,
        writes: List<CapturedWrite>,
        problems: MutableList<String>,
        notes: MutableList<String>,
    ) {
        if (writes.isEmpty()) notes += "nothing was written in the covered weeks: the start check is vacuous"
        writes.forEach { captured ->
            val night = env.database.estimatedNightDao().findByClientRecordId(captured.write.clientRecordId)
            val id = captured.write.clientRecordId.take(ID_PREFIX)
            when {
                night == null -> {
                    problems += "$id… is not in the ledger"
                }

                captured.write.startMs != night.sessionStartMs - OutputOptions.START_BEFORE_SOURCE_MS -> {
                    problems += "$id… starts at ${captured.write.startMs}, not one second before its night (${night.sessionStartMs})"
                }
            }
        }
    }

    private suspend fun allNights(env: ShadowEnvironment) =
        env.database.estimatedNightDao().findOverlapping(Long.MIN_VALUE, Long.MAX_VALUE, NightStatus.entries.toList())

    private suspend fun policy(
        name: String,
        description: String,
        settings: AppSettings,
        expect: suspend (ShadowEnvironment, List<CapturedWrite>, MutableList<String>, MutableList<String>) -> Unit = { _, _, _, _ -> },
    ) {
        environment(name).use { env -> backfill(env, settings, description, expect) }
    }

    private fun progressOf(name: String): suspend (io.github.alien2003.pulsestages.data.local.db.BackfillStateEntity) -> Unit {
        var last = 0L
        return { state ->
            val now = SystemClock.elapsedRealtime()
            if (now - last >= PROGRESS_INTERVAL_MS || state.windowsDone == state.windowsTotal) {
                last = now
                E2e.progress(
                    "$name: week ${state.windowsDone}/${state.windowsTotal}, nights found ${state.nightsFound}, " +
                        "estimated ${state.nightsEstimated}, reads ${budget.spent()} (${budget.spentInWindow()} in 15 min)",
                )
            }
        }
    }

    private fun modeOf(captured: CapturedWrite): SessionMode? =
        runCatching {
            Json
                .parseToJsonElement(captured.write.notes)
                .jsonObject["mode"]
                ?.jsonPrimitive
                ?.content
                ?.let(SessionMode::valueOf)
        }.getOrNull()

    private fun AppSettings.withHistory(days: Int): AppSettings = copy(sources = sources.copy(backfillDays = days))

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MINUTE_MS = 60_000L
        const val MAX_RESUMES = 3
        const val QUOTA_PAUSE_MS = 15L * 60 * 1000
        const val PROGRESS_INTERVAL_MS = 20_000L
        const val ANY_SOURCES_DAYS = 14
        const val ID_PREFIX = 14
    }
}

package io.github.alien2003.pulsestages.e2e.shadow

import android.os.SystemClock
import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.data.hc.pipeline.NewNightsSync
import io.github.alien2003.pulsestages.data.hc.pipeline.NightInputReader
import io.github.alien2003.pulsestages.data.hc.pipeline.SyncReport
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import io.github.alien2003.pulsestages.e2e.E2e
import kotlinx.coroutines.flow.first

/**
 * The scenario `refresh-recent`: the automatic refresh of recently written nights (NewNightsSync reads the input of
 * a written night again and rewrites the night, version + 1, when late data changed its record) on the ledger of the
 * real-settings scenario, after the idempotency scenario. Three Changes-API syncs:
 * 1. `refresh-unchanged`: the data did not change (served from the cache): nothing is written or deleted;
 * 2. `refresh-late-hr`: synthetic late heart rate: every heart-rate sample in the input window of a written night also
 *    one second later with the same bpm ([ShadowWriteRepository.extraHeartRate]; in memory only, never written
 *    anywhere). Only nights that had an output before are rewritten, each once, with the same clientRecordId and
 *    clientRecordVersion + 1, at most [NewNightsSync.MAX_REFRESH_NIGHTS_PER_RUN] of them, each with an input update
 *    as the ledger's reason ([EstimatedNightEntity.inputUpdate]); nothing is deleted;
 * 3. `refresh-again`: the same late heart rate again: no night rewritten by phase 2 is rewritten (its input did not
 *    change since); other nights may be, under the rules of phase 2.
 * Under OverwritePolicy.NEVER nothing may be refreshed at all.
 *
 * Every row of the shadow ledger was created during the test, so every written night of the last 30 days is a late
 * arrival for NewNightsSync, which checks one again only once the time since its last check is at least the time
 * between its first sighting and that check, and never less than [NewNightsSync.MIN_RECHECK_GAP_MS] after it. Before
 * each sync the environment's clock moves forward by the age of the oldest written row, and at least by that gap
 * ([ShadowEnvironment.advanceClock]), so that every such night is due again, as at a later hourly sync; never beyond
 * [DUE_HORIZON_MS] after that row was created (the nights stay late arrivals). The late heart rate is removed at the
 * end; the ledger stays as it is ("recompute all" follows on it).
 */
class RefreshScenario(
    private val env: ShadowEnvironment,
    private val defaultTitle: String,
) {
    private val problems = mutableListOf<String>()
    private val notes = mutableListOf<String>()

    /**
     * One sync of the scenario, with what it wrote and deleted.
     *
     * @property name the phase (also of its captured writes).
     * @property before the WRITTEN rows of the ledger before the sync, by clientRecordId.
     * @property after the WRITTEN rows after it.
     * @property startedAtMs when the sync started (the environment's clock).
     * @property allDue the clock moved far enough for every written night to be due.
     * @property report the sync's report.
     */
    private inner class Phase(
        val name: String,
        val before: Map<String, EstimatedNightEntity>,
        val after: Map<String, EstimatedNightEntity>,
        val startedAtMs: Long,
        val allDue: Boolean,
        val report: SyncReport,
    ) {
        val writes: List<CapturedWrite> = env.health.writesOf(name)
        val deletes: List<CapturedDelete> = env.health.deletesOf(name)
        val writtenIds: Set<String> = writes.mapTo(LinkedHashSet()) { it.write.clientRecordId }
    }

    /** Runs the three syncs and checks them. */
    suspend fun run(): ScenarioReport {
        val started = SystemClock.elapsedRealtime()
        val settings = env.settings.settings.first()
        val unchanged = sync(UNCHANGED)
        val windows = unchanged.after.values.mapTo(HashSet()) { inputWindow(it, settings) }
        notes +=
            "late heart rate: every sample in the input windows of the ${windows.size} written night(s) also " +
            "${LATE_SAMPLE_OFFSET_MS / SECOND_MS} s later with the same bpm (in memory only)"
        val (late, again) = withLateHeartRate(windows) { sync(LATE_HEART_RATE) to sync(AGAIN) }
        val phases = listOf(unchanged, late, again)
        phases.forEach { expectCommon(it, settings) }
        val policy = settings.write.overwritePolicy
        // RECOMPUTE_ALL rewrites every night a time-range re-read finds, by design (the sync re-reads the last week when
        // its Changes token is missing or expired): the write checks of such a sync are skipped.
        val byDesign = phases.filterTo(HashSet()) { policy == OverwritePolicy.RECOMPUTE_ALL && it.report.rereadFromMs != null }
        byDesign.forEach { notes += "${it.name}: re-read of the last week under overwritePolicy RECOMPUTE_ALL: write checks skipped" }
        if (policy == OverwritePolicy.NEVER) {
            notes += "overwritePolicy NEVER: nothing may be refreshed; the rewrite checks are vacuous"
            phases.forEach { expectNothingRefreshed(it) }
        } else {
            if (unchanged !in byDesign) expectNoWrites(unchanged)
            if (late !in byDesign) {
                expectRefreshWrites(late)
                expectLateHeartRateUsed(late)
            }
            if (again !in byDesign) {
                expectRefreshWrites(again)
                expectNotRewrittenAgain(late, again)
            }
        }
        val writes = phases.flatMap { it.writes }
        return ScenarioReport(
            name = NAME,
            description =
                "automatic refresh of recently written nights on the ledger of the real-settings scenario: a sync on " +
                    "unchanged data, a sync with synthetic late heart rate (in memory only), and that sync again",
            outcome = phases.joinToString(" / ") { it.report.outcome.name },
            found = phases.sumOf { it.report.pipeline.found },
            written = writes.size,
            deleted = phases.sumOf { phase -> phase.deletes.sumOf { it.clientRecordIds.size } },
            notEstimated = phases.sumOf { it.report.pipeline.notEstimated },
            failed = phases.sumOf { it.report.pipeline.failed },
            durationMs = SystemClock.elapsedRealtime() - started,
            stageTypes = ShadowChecks.stageTypes(writes),
            problems = problems.toList(),
            notes = notes.toList(),
        )
    }

    /** Makes the written nights due, runs one sync as [name], checks the ledger against the store and notes the result. */
    private suspend fun sync(name: String): Phase {
        val before = written()
        val firstSeen = before.values.minOfOrNull { it.createdAt }
        val age = firstSeen?.let { env.nowMs() - it } ?: 0L
        val gap = NewNightsSync.MIN_RECHECK_GAP_MS
        val step = minOf(maxOf(age + SECOND_MS, gap), DUE_HORIZON_MS - age).coerceAtLeast(0L)
        env.advanceClock(step)
        env.health.phase = name
        val startedAtMs = env.nowMs()
        val report = env.sync.run(inBackground = false)
        val phase = Phase(name, before, written(), startedAtMs, allDue = step > age && step >= gap, report)
        ShadowChecks.consistencyProblems(env).forEach { problems += "$name: $it" }
        val pipeline = report.pipeline
        val changes =
            phase.writtenIds
                .mapNotNull { phase.after[it]?.inputUpdate?.change }
                .groupingBy { it.name }
                .eachCount()
        notes +=
            "$name: clock +${step / MINUTE_MS} min${if (phase.allDue) "" else " (capped: not every night was due)"}; " +
            "sync ${report.outcome}; ${pipeline.inputChecks} written night(s) checked, ${pipeline.refreshed} rewritten" +
            (if (changes.isEmpty()) "" else " $changes") + "; ${phase.writes.size} write(s)"
        E2e.progress("$NAME: $name: ${pipeline.inputChecks} written night(s) checked, ${phase.writes.size} rewritten")
        return phase
    }

    /** The outcome, no delete, and [WriteInvariants] for every write. */
    private fun expectCommon(
        phase: Phase,
        settings: AppSettings,
    ) {
        val report = phase.report
        when (report.outcome) {
            SyncOutcome.SUCCESS -> Unit
            SyncOutcome.QUOTA_EXCEEDED -> notes += "${phase.name}: Health Connect's rate limit stopped the sync early"
            else -> problems += "${phase.name}: the sync ended with ${report.outcome} (${report.error})"
        }
        val deleted = phase.deletes.sumOf { it.clientRecordIds.size }
        if (deleted > 0) problems += "${phase.name} deleted $deleted session(s)"
        ShadowChecks.invariantProblems(phase.writes, settings, defaultTitle).forEach { problems += "${phase.name}: $it" }
    }

    /** Unchanged input: nothing is written. */
    private fun expectNoWrites(phase: Phase) {
        if (phase.writes.isNotEmpty()) {
            problems += "${phase.name} wrote ${phase.writes.size} session(s) although no input changed: ${phase.writtenIds.map(::short)}"
        }
    }

    /** Under NEVER the refresh does not run: no night is checked or written. */
    private fun expectNothingRefreshed(phase: Phase) {
        val checks = phase.report.pipeline.inputChecks
        if (checks > 0) problems += "${phase.name}: $checks written night(s) were refreshed under overwritePolicy NEVER"
        if (phase.writes.isNotEmpty()) problems += "${phase.name} wrote ${phase.writes.size} session(s) under overwritePolicy NEVER"
    }

    /**
     * Rewrites of a refresh: only nights that had an output before, each once, with clientRecordVersion + 1 and an input
     * update as the ledger's reason, at most [NewNightsSync.MAX_REFRESH_NIGHTS_PER_RUN]; the sync counts each one as
     * refreshed.
     */
    private fun expectRefreshWrites(phase: Phase) {
        val name = phase.name
        phase.writes.groupBy { it.write.clientRecordId }.forEach { (id, writes) ->
            val night = "$name: night ${short(id)}"
            val previous = phase.before[id]?.clientRecordVersion
            val version = writes.first().write.clientRecordVersion
            when {
                previous == null -> problems += "$night was written but had no output before"
                writes.size != 1 -> problems += "$night was written ${writes.size} times"
                version != previous + 1 -> problems += "$night: version $version after $previous"
            }
            val row = phase.after[id]
            if (row?.inputUpdate == null) problems += "$night was rewritten without an input update (${row?.status}: ${row?.statusReason})"
        }
        val max = NewNightsSync.MAX_REFRESH_NIGHTS_PER_RUN
        if (phase.writtenIds.size > max) problems += "$name rewrote ${phase.writtenIds.size} nights, more than the $max of one run"
        val refreshed = phase.report.pipeline.refreshed
        val count = phase.writes.size
        if (refreshed != count) problems += "$name: the sync counted $refreshed refreshed night(s) for $count write(s)"
    }

    /**
     * With the late heart rate, a sync that ran to the end checked written nights (when there are any of the last 30
     * days and all were due) and rewrote at least one of the nights it checked: more samples change the record's notes.
     */
    private fun expectLateHeartRateUsed(phase: Phase) {
        if (phase.report.outcome != SyncOutcome.SUCCESS) return
        val recent = phase.before.values.count { it.createdAt - it.sessionEndMs <= NewNightsSync.LATE_ARRIVAL_MAX_MS }
        val checks = phase.report.pipeline.inputChecks
        val name = phase.name
        when {
            recent == 0 -> notes += "$name: no written night of the last 30 days: the refresh checks are vacuous"
            phase.allDue && checks == 0 -> problems += "$name: $recent written night(s) of the last 30 days were due, none was checked"
            checks > 0 && phase.writes.isEmpty() -> problems += "$name: $checks night(s) checked with more heart rate, none rewritten"
        }
    }

    /** The nights [late] rewrote are not rewritten by [again]: their input did not change since. */
    private fun expectNotRewrittenAgain(
        late: Phase,
        again: Phase,
    ) {
        val rewritten = late.writtenIds
        (again.writtenIds intersect rewritten).forEach {
            problems += "${again.name}: night ${short(it)} was rewritten again although its input did not change after ${late.name}"
        }
        val rechecked =
            (rewritten - again.writtenIds).count { (again.after[it]?.inputCheckedAt ?: Long.MIN_VALUE) >= again.startedAtMs }
        notes += "${again.name}: $rechecked of the ${rewritten.size} night(s) rewritten by ${late.name} were checked again and kept"
    }

    /** Runs [block] with the late heart rate in the input [windows] of the written nights; removed afterwards. */
    private suspend fun <T> withLateHeartRate(
        windows: Set<InputWindow>,
        block: suspend () -> T,
    ): T {
        env.health.extraHeartRate = { startMs, endMs, packages, samples ->
            if (InputWindow(startMs, endMs, packages) in windows) denser(samples, endMs) else samples
        }
        return try {
            block()
        } finally {
            env.health.extraHeartRate = null
        }
    }

    /** The heart-rate read of [night]'s input (NightInputReader: the session ± contextMarginMin, the hrSources origins). */
    private fun inputWindow(
        night: EstimatedNightEntity,
        settings: AppSettings,
    ): InputWindow {
        val marginMs = settings.algo.preprocess.contextMarginMin * MINUTE_MS
        val packages = NightInputReader.heartRatePackages(night.sourcePackage, settings)
        return InputWindow(night.sessionStartMs - marginMs, night.sessionEndMs + marginMs, packages)
    }

    /** The arguments of a heart-rate read. */
    private data class InputWindow(
        val startMs: Long,
        val endMs: Long,
        val packages: Set<String>?,
    )

    private suspend fun written(): Map<String, EstimatedNightEntity> =
        env.database
            .estimatedNightDao()
            .findOverlapping(Long.MIN_VALUE, Long.MAX_VALUE, listOf(NightStatus.WRITTEN))
            .associateBy { it.clientRecordId }

    private fun short(id: String): String = "${id.take(ID_PREFIX)}…"

    /** Names and constants. */
    companion object {
        /** Name of the scenario. */
        const val NAME: String = "refresh-recent"

        private const val UNCHANGED = "refresh-unchanged"
        private const val LATE_HEART_RATE = "refresh-late-hr"
        private const val AGAIN = "refresh-again"
        private const val SECOND_MS = 1000L
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
        private const val ID_PREFIX = 14

        /** The late copy of a heart-rate sample comes this much after it. */
        private const val LATE_SAMPLE_OFFSET_MS = SECOND_MS

        /** The clock never moves beyond this after the oldest written row was created (late arrivals: 36 h, less an hour). */
        private const val DUE_HORIZON_MS = NewNightsSync.RETRY_RECENT_MS - HOUR_MS

        private val SAMPLE_ORDER: Comparator<HrSample> = compareBy({ it.timeMs }, { it.bpm })

        /** [samples] and a copy of each [LATE_SAMPLE_OFFSET_MS] later with the same bpm (before [endMs]): a new sorted list. */
        private fun denser(
            samples: List<HrSample>,
            endMs: Long,
        ): List<HrSample> =
            (samples + samples.map { it.copy(timeMs = it.timeMs + LATE_SAMPLE_OFFSET_MS) }.filter { it.timeMs < endMs })
                .sortedWith(SAMPLE_ORDER)
    }
}

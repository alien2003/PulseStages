package io.github.alien2003.pulsestages.e2e.shadow

import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.pipeline.InputSelection
import io.github.alien2003.pulsestages.data.hc.pipeline.NightCandidate
import io.github.alien2003.pulsestages.data.hc.pipeline.NightCandidateBuilder
import io.github.alien2003.pulsestages.data.hc.pipeline.SystemZoneOffsetProvider
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.e2e.E2e
import io.github.alien2003.pulsestages.e2e.support.AppSettingsFile
import kotlin.math.ceil

/**
 * Counts the history before the shadow scenarios run and decides how much of it they cover. One paged read of the
 * enabled sources' sleep sessions of all history (served from the cache afterwards) gives the candidate nights; a
 * full-history pass costs about [readsPerNight] read calls per night plus one per 7-day window. When that does not fit
 * into [available] calls (with a margin for the other scenarios), only the most recent nights that fit are covered and
 * the report says so (`capped`); `-e e2eShadowHistoryDays N` forces N days (0 = all history).
 */
object ShadowCensus {
    /** Instrumentation argument forcing the history the scenarios cover (days; 0 = all history). */
    const val ARG_HISTORY_DAYS: String = "e2eShadowHistoryDays"

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val WINDOW_DAYS = 7

    /** Heart rate (about 3 pages with the left padding) and steps (about 2). */
    private const val BASE_READS_PER_NIGHT = 5

    /** The other scenarios (defaults, sync, recompute, policies) mostly hit the cache: 25 % on top. */
    private const val SCENARIO_MARGIN = 1.25
    private const val MIN_POLICY_DAYS = 14
    private const val MAX_POLICY_DAYS = 60
    private const val POLICY_NAPS = 3

    /** Read calls of one night: heart rate, steps and the optional channels that are enabled and granted. */
    fun readsPerNight(
        settings: AppSettings,
        granted: Set<String>,
    ): Int {
        val selection = InputSelection.of(settings, granted)
        return BASE_READS_PER_NIGHT + listOf(selection.spo2, selection.respiratoryRate, selection.hrv).count { it }
    }

    /** The census of [file]'s settings over [reads], fitting into [available] read calls. */
    @Suppress("LongParameterList") // Every input of the decision.
    suspend fun take(
        reads: HealthRepository,
        file: AppSettingsFile,
        granted: Set<String>,
        available: Int,
        nowMs: Long,
        modelId: String?,
    ): Census {
        val settings = file.settings
        val ownPackage = E2e.app.packageName
        val packages = (settings.sources.sourcePackages + AppSettings().sources.sourcePackages).filterTo(sortedSetOf()) { it != ownPackage }
        val sessions = if (packages.isEmpty()) emptyList() else reads.readSleepSessions(0L, nowMs + DAY_MS, packages)
        val builder = NightCandidateBuilder(ownPackage, SystemZoneOffsetProvider)
        val real = builder.build(sessions, settings.sources, settings.write.overwritePolicy, settings.algo)
        val defaults = builder.build(sessions, AppSettings().sources, AppSettings().write.overwritePolicy, AppSettings().algo)
        val nights = real.candidates.sortedByDescending { it.startMs }
        // Nights of the default settings from a source the user has not enabled are not cached by the first scenario.
        val extraForDefaults = defaults.candidates.count { it.packageName !in settings.sources.sourcePackages }
        val perNight = readsPerNight(settings, granted)
        val oldest = sessions.minOfOrNull { it.startMs }
        val windows = oldest?.let { ceil((nowMs - it).toDouble() / (WINDOW_DAYS * DAY_MS)).toInt() } ?: 0
        val fullCost = windows + (nights.size + extraForDefaults) * perNight
        val forced = E2e.intArg(ARG_HISTORY_DAYS, -1)
        val userDays = settings.sources.backfillDays
        val within = (available / SCENARIO_MARGIN).toInt()
        val (days, capped) =
            when {
                forced >= 0 -> forced to false
                fullCost <= within || nights.isEmpty() -> userDays to false
                else -> cappedDays(nights, (within - windows) / perNight, nowMs, userDays) to true
            }
        val covered = nights.count { days == 0 || it.startMs >= nowMs - days * DAY_MS }
        return Census(
            settingsSource = file.source,
            settingsFileSha256 = file.sha256,
            sourcePackages = settings.sources.sourcePackages,
            sessions = sessions.count { it.packageName in settings.sources.sourcePackages },
            nights = nights.size,
            naps = nights.count { it.mode(settings.algo) == SessionMode.NAP },
            skippedGroups = real.skipped.size,
            oldestSessionStartMs = oldest,
            estimatedReadCalls = fullCost,
            historyDays = days,
            capped = capped,
            coveredNights = covered,
            policyDays = policyDays(nights, settings, nowMs, days),
            modelId = modelId,
        )
    }

    /** Days back to the oldest of the [count] most recent [nights] (at least one night), within [userDays] when set. */
    private fun cappedDays(
        nights: List<NightCandidate>,
        count: Int,
        nowMs: Long,
        userDays: Int,
    ): Int {
        val oldestCovered = nights[(count.coerceAtLeast(1) - 1).coerceAtMost(nights.lastIndex)]
        val days = ceil((nowMs - oldestCovered.startMs).toDouble() / DAY_MS).toInt() + 1
        return if (userDays == 0) days else minOf(userDays, days)
    }

    /** The policy scenarios cover the [POLICY_NAPS] most recent naps, at least two weeks and at most two months. */
    private fun policyDays(
        nights: List<NightCandidate>,
        settings: AppSettings,
        nowMs: Long,
        historyDays: Int,
    ): Int {
        val naps = nights.filter { it.mode(settings.algo) == SessionMode.NAP }.take(POLICY_NAPS)
        val toNaps = naps.lastOrNull()?.let { ceil((nowMs - it.startMs).toDouble() / DAY_MS).toInt() + 1 } ?: MIN_POLICY_DAYS
        val days = toNaps.coerceIn(MIN_POLICY_DAYS, MAX_POLICY_DAYS)
        return if (historyDays == 0) days else minOf(days, historyDays)
    }
}

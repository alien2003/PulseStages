package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.di.OwnPackageName
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import javax.inject.Inject

/**
 * Reads the sleep sessions of the enabled sources around a time range and turns them into nights. Reads are padded
 * by [OVERLAP_MS] on both sides so that fragments of a night that crosses the range boundary are merged correctly.
 */
class NightWindowReader
    @Inject
    constructor(
        private val repository: HealthRepository,
        private val builder: NightCandidateBuilder,
        @param:OwnPackageName private val ownPackage: String,
    ) {
        /** The enabled sleep-source packages (our own package is never a source), sorted. */
        fun enabledPackages(settings: AppSettings): Set<String> =
            settings.sources.sourcePackages.filterTo(sortedSetOf()) { it != ownPackage }

        /** Nights built from the sessions of the enabled sources whose start lies in `[fromMs, toMs)`. */
        suspend fun read(
            fromMs: Long,
            toMs: Long,
            settings: AppSettings,
        ): CandidateSet {
            val packages = enabledPackages(settings)
            if (packages.isEmpty() || fromMs >= toMs) return CandidateSet.EMPTY
            val sessions = repository.readSleepSessions(fromMs, toMs, packages)
            return builder.build(sessions, settings.sources, settings.write.overwritePolicy, settings.algo)
        }

        /** The nights owned by the window `[fromMs, toMs)` (first fragment starts inside it; see [CandidateSet.ownedBy]). */
        suspend fun nightsInWindow(
            fromMs: Long,
            toMs: Long,
            settings: AppSettings,
        ): CandidateSet = read(fromMs - OVERLAP_MS, toMs + OVERLAP_MS, settings).ownedBy(fromMs, toMs)

        /** The nights overlapping `[startMs, endMs)`. */
        suspend fun nightsAround(
            startMs: Long,
            endMs: Long,
            settings: AppSettings,
        ): CandidateSet = read(startMs - OVERLAP_MS, endMs + OVERLAP_MS, settings).overlapping(startMs, endMs)

        /** Constants. */
        companion object {
            /** Padding of every read: one day. */
            const val OVERLAP_MS: Long = 24L * 60 * 60 * 1000
        }
    }

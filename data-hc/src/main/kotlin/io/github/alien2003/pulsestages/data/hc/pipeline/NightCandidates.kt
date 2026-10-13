package io.github.alien2003.pulsestages.data.hc.pipeline

import androidx.health.connect.client.records.SleepSessionRecord
import io.github.alien2003.pulsestages.algo.model.TimeSpan
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.di.OwnPackageName
import io.github.alien2003.pulsestages.data.local.db.SourceOutcome
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.settings.SourceSettings
import java.security.MessageDigest
import javax.inject.Inject

/**
 * One output night: one or more source sessions of one package merged into a single session.
 *
 * @property packageName package that wrote the source sessions.
 * @property fragments the source sessions, sorted by start.
 * @property startMs start of the first fragment.
 * @property endMs latest end of any fragment.
 * @property startZoneOffsetSec the first fragment's start offset (or the fallback zone's offset).
 * @property endZoneOffsetSec the end offset of the fragment that ends last (or the fallback zone's offset).
 * @property forcedAwake gaps between merged fragments; the estimator marks them AWAKE.
 * @property sourceUuids sorted, distinct uuids of [fragments].
 * @property clientRecordId deterministic `"ps:" + sha256hex(sortedUuids.joinToString(","))`.
 * @property hasStages true when a fragment already had stages (only possible with RECOMPUTE_ALL).
 * @property startOffsetIsFallback the first fragment has no start offset: [startZoneOffsetSec] is the fallback zone's.
 * @property endOffsetIsFallback the last-ending fragment has no end offset: [endZoneOffsetSec] is the fallback zone's.
 */
data class NightCandidate(
    val packageName: String,
    val fragments: List<HcSleepSession>,
    val startMs: Long,
    val endMs: Long,
    val startZoneOffsetSec: Int,
    val endZoneOffsetSec: Int,
    val forcedAwake: List<TimeSpan>,
    val sourceUuids: List<String>,
    val clientRecordId: String,
    val hasStages: Boolean,
    val startOffsetIsFallback: Boolean = false,
    val endOffsetIsFallback: Boolean = false,
) {
    /** Length of the merged session. */
    val durationMs: Long get() = endMs - startMs
}

/**
 * Source sessions that do not become an output night.
 *
 * @property packageName their package.
 * @property sessions the sessions (one, or the fragments of a merged group).
 * @property outcome why ([SourceOutcome.HAS_STAGES], [SourceOutcome.NAP] or [SourceOutcome.LOWER_PRIORITY_DUPLICATE]).
 * @property reason English detail for the ledger and the log.
 * @property stagedWinnerUuids for a [SourceOutcome.LOWER_PRIORITY_DUPLICATE] of a night that a higher-priority source
 *   recorded with its own sleep stages: the uuids of those sessions (the night needs no estimate, and an output we wrote
 *   for it earlier is retired); empty otherwise.
 */
data class SkippedNight(
    val packageName: String,
    val sessions: List<HcSleepSession>,
    val outcome: SourceOutcome,
    val reason: String,
    val stagedWinnerUuids: List<String> = emptyList(),
) {
    /** Earliest start of [sessions]. */
    val startMs: Long get() = sessions.minOf { it.startMs }

    /** Latest end of [sessions]. */
    val endMs: Long get() = sessions.maxOf { it.endMs }
}

/**
 * Result of [NightCandidateBuilder.build].
 *
 * @property candidates nights to estimate, sorted by start.
 * @property skipped nights that are not estimated, sorted by start.
 * @property own our own sessions found in the input (never estimated).
 */
data class CandidateSet(
    val candidates: List<NightCandidate>,
    val skipped: List<SkippedNight> = emptyList(),
    val own: List<HcSleepSession> = emptyList(),
) {
    /** Number of nights ([candidates] + [skipped]). */
    val nightCount: Int get() = candidates.size + skipped.size

    /** True when there is nothing to record. */
    val isEmpty: Boolean get() = candidates.isEmpty() && skipped.isEmpty() && own.isEmpty()

    /** Uuids of every source session in this set ([candidates] and [skipped]; not [own]). */
    val sourceUuids: Set<String>
        get() = candidates.flatMapTo(HashSet()) { it.sourceUuids } + skipped.flatMap { group -> group.sessions.map { it.uuid } }

    /**
     * The nights owned by the window `[fromMs, toMs)`: those whose first fragment starts in it (a night spanning a
     * window boundary belongs to the window of its first fragment).
     */
    fun ownedBy(
        fromMs: Long,
        toMs: Long,
    ): CandidateSet {
        val inWindow = { start: Long -> start in fromMs until toMs }
        return CandidateSet(
            candidates = candidates.filter { inWindow(it.startMs) },
            skipped = skipped.filter { inWindow(it.startMs) },
            own = own.filter { inWindow(it.startMs) },
        )
    }

    /**
     * The nights containing at least one of [uuids], plus the lower-priority duplicates of a staged session among
     * [uuids] (a session with its own stages that arrives later decides about the night it duplicates).
     */
    fun containingAny(uuids: Set<String>): CandidateSet =
        CandidateSet(
            candidates = candidates.filter { c -> c.sourceUuids.any { it in uuids } },
            skipped = skipped.filter { s -> s.sessions.any { it.uuid in uuids } || s.stagedWinnerUuids.any { it in uuids } },
            own = own.filter { it.uuid in uuids },
        )

    /** The nights overlapping `[startMs, endMs)`. */
    fun overlapping(
        startMs: Long,
        endMs: Long,
    ): CandidateSet =
        CandidateSet(
            candidates = candidates.filter { it.startMs < endMs && it.endMs > startMs },
            skipped = skipped.filter { it.startMs < endMs && it.endMs > startMs },
            own = own.filter { it.startMs < endMs && it.endMs > startMs },
        )

    /** Union of both sets (a night present in both is kept once). */
    operator fun plus(other: CandidateSet): CandidateSet =
        CandidateSet(
            candidates = (candidates + other.candidates).distinctBy { it.clientRecordId }.sortedWith(CANDIDATE_ORDER),
            skipped =
                (skipped + other.skipped)
                    .distinctBy { night -> night.sessions.map { it.uuid }.sorted() }
                    .sortedBy { it.startMs },
            own = (own + other.own).distinctBy { it.uuid }.sortedBy { it.startMs },
        )

    /** Constants. */
    companion object {
        /** The empty set. */
        val EMPTY: CandidateSet = CandidateSet(emptyList())

        internal val CANDIDATE_ORDER: Comparator<NightCandidate> =
            compareBy<NightCandidate>({ it.startMs }, { it.packageName }, { it.clientRecordId })
    }
}

/** Deterministic keys of output nights. */
object NightKeys {
    /** Prefix of every clientRecordId we write. */
    const val CLIENT_RECORD_ID_PREFIX: String = "ps:"

    /** `"ps:" + sha256hex(sortedUuids.joinToString(","))` (uuids are de-duplicated and sorted first). */
    fun clientRecordId(sourceUuids: Collection<String>): String {
        val joined = sourceUuids.distinct().sorted().joinToString(",")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(CLIENT_RECORD_ID_PREFIX.length + digest.size * 2).append(CLIENT_RECORD_ID_PREFIX)
        digest.forEach { byte ->
            val value = byte.toInt() and BYTE_MASK
            hex.append(HEX_DIGITS[value ushr NIBBLE_BITS]).append(HEX_DIGITS[value and NIBBLE_MASK])
        }
        return hex.toString()
    }

    private const val HEX_DIGITS = "0123456789abcdef"
    private const val BYTE_MASK = 0xff
    private const val NIBBLE_MASK = 0x0f
    private const val NIBBLE_BITS = 4
}

/**
 * Turns source sessions into output nights (pure, deterministic). Steps:
 * 1. our own sessions are set aside ([CandidateSet.own], outcome OWN) and sessions of packages that are not enabled
 *    in [SourceSettings.sourcePackages] are ignored (allowlist);
 * 2. sessions whose stages contain any type other than UNKNOWN (0) / SLEEPING (2) are skipped (HAS_STAGES) unless
 *    the overwrite policy is RECOMPUTE_ALL;
 * 3. per package, sessions sorted by start are merged while the gap to the merged end is `< mergeGapMin` (overlaps
 *    included); gaps become forced-AWAKE spans; the zone offsets are the first fragment's start offset and the
 *    last-ending fragment's end offset (fallback: [zoneOffsets]);
 * 4. merged nights shorter than `minSessionMin` are naps (NAP);
 * 5. nights of different packages that overlap by more than 50 % of the shorter one are duplicates: the night whose
 *    package comes first in `sourcePriority` (unlisted packages last, then by name) is kept, the others are
 *    LOWER_PRIORITY_DUPLICATE. Sessions skipped in step 2 take part as well (merged and nap-filtered per package like
 *    the others): a night that a higher-priority source recorded with its own stages makes the overlapping nights of
 *    lower-priority sources duplicates too, although it is not estimated itself (it stays HAS_STAGES). Exception: a
 *    night lying mostly inside a session of another package but covering at most half of it is a piece of that sleep,
 *    not a second record of it: it is a duplicate of that session whatever the priority, and blocks nothing.
 */
class NightCandidateBuilder
    @Inject
    constructor(
        @param:OwnPackageName private val ownPackage: String,
        private val zoneOffsets: ZoneOffsetProvider,
    ) {
        /** Builds the nights of [sessions] with [sources] and [overwritePolicy]. */
        fun build(
            sessions: List<HcSleepSession>,
            sources: SourceSettings,
            overwritePolicy: OverwritePolicy,
        ): CandidateSet {
            val distinct = sessions.filter { it.endMs > it.startMs }.distinctBy { it.uuid }
            val own = distinct.filter { it.packageName == ownPackage }.sortedBy { it.startMs }
            val enabled = sources.sourcePackages.toSet() - ownPackage
            val allowed = distinct.filter { it.packageName in enabled }

            val (staged, usable) = allowed.partition { overwritePolicy != OverwritePolicy.RECOMPUTE_ALL && it.hasRealStages() }
            val withStages =
                staged.map {
                    SkippedNight(
                        it.packageName,
                        listOf(it),
                        SourceOutcome.HAS_STAGES,
                        "session already has sleep stages",
                    )
                }

            val mergeGapMs = sources.mergeGapMin * MINUTE_MS
            val merged =
                usable
                    .groupBy { it.packageName }
                    .flatMap { (packageName, list) -> merge(packageName, list.sortedWith(SESSION_ORDER), mergeGapMs) }

            val minDurationMs = sources.minSessionMin * MINUTE_MS
            val (nights, naps) = merged.partition { it.durationMs >= minDurationMs }
            val napNights =
                naps.map { nap ->
                    SkippedNight(
                        packageName = nap.packageName,
                        sessions = nap.fragments,
                        outcome = SourceOutcome.NAP,
                        reason = "nap: ${nap.durationMs / MINUTE_MS} min < minSessionMin ${sources.minSessionMin} min",
                    )
                }

            // Sessions with stages are not estimated, but they still record the night for their source.
            val stagedNights =
                staged
                    .groupBy { it.packageName }
                    .flatMap { (packageName, list) -> merge(packageName, list.sortedWith(SESSION_ORDER), mergeGapMs) }
                    .filter { it.durationMs >= minDurationMs }

            val (kept, duplicates) = removeDuplicates(nights, stagedNights, sources.sourcePriority)
            return CandidateSet(
                candidates = kept.sortedWith(CandidateSet.CANDIDATE_ORDER),
                skipped = (withStages + napNights + duplicates).sortedWith(compareBy({ it.startMs }, { it.packageName })),
                own = own,
            )
        }

        /**
         * Keeps, among nights of different packages overlapping by more than half, the one of the higher-priority
         * package. [stagedNights] (sessions with their own stages, not estimated) only block: a [nights] entry they
         * outrank becomes a duplicate, and they are never returned.
         *
         * A piece of a longer session never wins over it: a night that lies mostly inside a session of another package
         * but covers at most half of that session ([isPieceOf], e.g. a 40-min fragment a watch recorded during an 8-h
         * night another source recorded completely) is a duplicate of that session whatever the priority, and a
         * staged piece blocks nothing. Without this, a short higher-priority fragment (estimated since
         * `minSessionMin` may be as low as 10 min) would replace the whole night.
         */
        private fun removeDuplicates(
            nights: List<NightCandidate>,
            stagedNights: List<NightCandidate>,
            priority: List<String>,
        ): Pair<List<NightCandidate>, List<SkippedNight>> {
            val stagedIds = stagedNights.mapTo(HashSet()) { it.clientRecordId }
            val ordered = (nights + stagedNights).sortedWith(priorityOrder(priority))
            val containers = ordered.associate { night -> night.clientRecordId to ordered.filter { isPieceOf(night, it) } }
            val (pieces, whole) = ordered.partition { containers.getValue(it.clientRecordId).isNotEmpty() }
            val kept = mutableListOf<NightCandidate>()
            val duplicates = mutableListOf<SkippedNight>()
            whole.forEach { night ->
                val winner = kept.firstOrNull { it.packageName != night.packageName && overlapsMostly(it, night) }
                if (winner == null) {
                    kept += night
                } else if (night.clientRecordId !in stagedIds) {
                    duplicates += duplicateOf(night, winner, winnerHasStages = winner.clientRecordId in stagedIds)
                }
                // A staged night that a higher-priority night outranks stays HAS_STAGES and blocks nothing.
            }
            val keptIds = kept.mapTo(HashSet()) { it.clientRecordId }
            pieces.filter { it.clientRecordId !in stagedIds }.forEach { piece ->
                val candidates = containers.getValue(piece.clientRecordId)
                val container = candidates.firstOrNull { it.clientRecordId in keptIds } ?: candidates.first()
                duplicates += pieceOf(piece, container, containerHasStages = container.clientRecordId in stagedIds)
            }
            return kept.filter { it.clientRecordId !in stagedIds } to duplicates
        }

        private fun duplicateOf(
            night: NightCandidate,
            winner: NightCandidate,
            winnerHasStages: Boolean,
        ): SkippedNight =
            SkippedNight(
                packageName = night.packageName,
                sessions = night.fragments,
                outcome = SourceOutcome.LOWER_PRIORITY_DUPLICATE,
                reason =
                    if (winnerHasStages) {
                        "overlaps a session with sleep stages of higher-priority source ${winner.packageName} " +
                            "(${winner.sourceUuids.joinToString(",")})"
                    } else {
                        "overlaps the night of higher-priority source ${winner.packageName} (${winner.clientRecordId})"
                    },
                stagedWinnerUuids = if (winnerHasStages) winner.sourceUuids else emptyList(),
            )

        private fun pieceOf(
            piece: NightCandidate,
            container: NightCandidate,
            containerHasStages: Boolean,
        ): SkippedNight =
            SkippedNight(
                packageName = piece.packageName,
                sessions = piece.fragments,
                outcome = SourceOutcome.LOWER_PRIORITY_DUPLICATE,
                reason =
                    if (containerHasStages) {
                        "lies inside a longer session with sleep stages of ${container.packageName} " +
                            "(${container.sourceUuids.joinToString(",")}) and covers at most half of it"
                    } else {
                        "lies inside the longer night of ${container.packageName} (${container.clientRecordId}) " +
                            "and covers at most half of it"
                    },
                stagedWinnerUuids = if (containerHasStages) container.sourceUuids else emptyList(),
            )

        private fun merge(
            packageName: String,
            sorted: List<HcSleepSession>,
            mergeGapMs: Long,
        ): List<NightCandidate> {
            val groups = mutableListOf<MutableList<HcSleepSession>>()
            var groupEnd = Long.MIN_VALUE
            sorted.forEach { session ->
                val current = groups.lastOrNull()
                if (current != null && session.startMs - groupEnd < mergeGapMs) {
                    current += session
                    groupEnd = maxOf(groupEnd, session.endMs)
                } else {
                    groups += mutableListOf(session)
                    groupEnd = session.endMs
                }
            }
            return groups.map { toCandidate(packageName, it) }
        }

        private fun toCandidate(
            packageName: String,
            fragments: List<HcSleepSession>,
        ): NightCandidate {
            val first = fragments.first()
            val lastEnding = fragments.maxWith(compareBy<HcSleepSession>({ it.endMs }, { it.startMs }))
            val gaps = gapsBetween(fragments.map { TimeSpan(it.startMs, it.endMs) })
            val uuids = fragments.map { it.uuid }.distinct().sorted()
            return NightCandidate(
                packageName = packageName,
                fragments = fragments,
                startMs = first.startMs,
                endMs = lastEnding.endMs,
                startZoneOffsetSec = first.startZoneOffsetSec ?: zoneOffsets.offsetSecondsAt(first.startMs),
                endZoneOffsetSec = lastEnding.endZoneOffsetSec ?: zoneOffsets.offsetSecondsAt(lastEnding.endMs),
                forcedAwake = gaps,
                sourceUuids = uuids,
                clientRecordId = NightKeys.clientRecordId(uuids),
                hasStages = fragments.any { it.hasRealStages() },
                startOffsetIsFallback = first.startZoneOffsetSec == null,
                endOffsetIsFallback = lastEnding.endZoneOffsetSec == null,
            )
        }

        private fun priorityOrder(priority: List<String>): Comparator<NightCandidate> {
            val rank = { packageName: String -> priority.indexOf(packageName).let { if (it < 0) Int.MAX_VALUE else it } }
            return compareBy<NightCandidate>({ rank(it.packageName) }, { it.packageName }, { it.startMs })
        }

        private fun overlapsMostly(
            a: NightCandidate,
            b: NightCandidate,
        ): Boolean {
            val overlap = minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)
            return overlap > 0 && overlap * 2 > minOf(a.durationMs, b.durationMs)
        }

        /**
         * Whether [piece] lies mostly inside [other] of another package but covers at most half of it: more than half of
         * [piece] overlaps [other], and the overlap is at most half of [other]. Then [piece] is shorter than [other].
         */
        private fun isPieceOf(
            piece: NightCandidate,
            other: NightCandidate,
        ): Boolean {
            if (piece.packageName == other.packageName) return false
            val overlap = minOf(piece.endMs, other.endMs) - maxOf(piece.startMs, other.startMs)
            return overlap > 0 && overlap * 2 > piece.durationMs && overlap * 2 <= other.durationMs
        }

        private companion object {
            const val MINUTE_MS = 60_000L

            val SESSION_ORDER: Comparator<HcSleepSession> = compareBy({ it.startMs }, { it.endMs }, { it.uuid })

            /** Stage types that do not count as "real" stages: UNKNOWN and the generic SLEEPING. */
            val NON_STAGE_TYPES: Set<Int> = setOf(SleepSessionRecord.STAGE_TYPE_UNKNOWN, SleepSessionRecord.STAGE_TYPE_SLEEPING)

            fun HcSleepSession.hasRealStages(): Boolean = stages.any { it.type !in NON_STAGE_TYPES }
        }
    }

/**
 * The uncovered time between [spans] sorted by start: every gap between the latest end so far and the next start
 * (overlapping spans leave no gap). These are the forced-AWAKE spans of a night merged from fragments.
 */
internal fun gapsBetween(spans: List<TimeSpan>): List<TimeSpan> {
    val gaps = mutableListOf<TimeSpan>()
    var coveredUntil = spans.firstOrNull()?.endMs ?: return gaps
    spans.drop(1).forEach { span ->
        if (span.startMs > coveredUntil) gaps += TimeSpan(coveredUntil, span.startMs)
        coveredUntil = maxOf(coveredUntil, span.endMs)
    }
    return gaps
}

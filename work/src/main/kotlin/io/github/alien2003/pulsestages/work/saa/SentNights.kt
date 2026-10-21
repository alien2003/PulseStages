package io.github.alien2003.pulsestages.work.saa

import io.github.alien2003.pulsestages.data.local.db.EstimatedNightDao
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import javax.inject.Inject

/**
 * The session start and end of a night of the last request to Sleep as Android when the request was sent.
 *
 * @property startMs the session start.
 * @property endMs the session end.
 */
data class SentSpan(
    val startMs: Long,
    val endMs: Long,
)

/**
 * A night of the last request to Sleep as Android that PulseStages has since written with another start or end.
 *
 * @property night the WRITTEN night PulseStages has now: the night of the request rewritten in place, or the night that
 *   took it over (a merge, or a night built from a session the source recreated).
 * @property sentStartMs the session start of the night when the request was sent; when several nights of the request
 *   are now [night], of the one Sleep as Android most likely holds for it ([night] itself, else the one that overlaps it
 *   most, else the longest).
 * @property sentEndMs its session end then.
 * @property replaced the other nights whose notices this one replaces (any status): the nights of the request it took
 *   over, those linked to their sources (such as the nights merged on the way), those linked to the sources of [night]
 *   (such as the nights it superseded) and the earlier nights that took over the span sent (such as a night built from
 *   a session the source then recreated again).
 * @property otherSources [night] has other source sessions than the night of the request (a merge, a split or a
 *   recreated session).
 * @property sentSpans the spans of every night of the request that is now [night], by start: one, or several when nights
 *   of the request were merged.
 * @property silent an earlier notice of one of the [replaced] nights already showed these times: the notice replaces it
 *   without alerting again.
 * @property neverOverwrite "Existing sessions" is "Never overwrite" (set by the caller, not by [SentNights.check]):
 *   PulseStages never rewrites [night], so a recompute cannot repair it and the notice says to sync in Sleep as
 *   Android.
 */
data class ChangedNight(
    val night: EstimatedNightEntity,
    val sentStartMs: Long,
    val sentEndMs: Long,
    val replaced: Set<String>,
    val otherSources: Boolean,
    val sentSpans: List<SentSpan> = listOf(SentSpan(sentStartMs, sentEndMs)),
    val silent: Boolean = false,
    val neverOverwrite: Boolean = false,
) {
    /** The version of [night] as stored in the noticed versions ([SentNights.versionOf]). */
    val key: String get() = SentNights.versionOf(night)

    /**
     * The start of the time in which Sleep as Android may hold records that keep it from importing [night]: the earliest
     * of its session and the [sentSpans]. Sleep as Android skips a session that overlaps a record it holds, so every
     * record from here to [repairEndMs] must go: the versions of the request's nights, and the sessions of the source
     * that [night] now covers, which PulseStages never wrote on their own.
     */
    val repairStartMs: Long get() = (sentSpans.map { it.startMs } + sentStartMs + night.sessionStartMs).min()

    /** The end of that time: the latest of the session end of [night] and the [sentSpans]. */
    val repairEndMs: Long get() = (sentSpans.map { it.endMs } + sentEndMs + night.sessionEndMs).max()
}

/**
 * What a check of an owed request finds in the ledger about the nights of the last request ([SentNights.check]).
 *
 * @property changes the nights PulseStages has since written with another start or end, in a version that no notice
 *   shows now, by start.
 * @property withdrawn the notified versions whose nights are back at the span the request was sent with, sorted: their
 *   notices no longer apply (never a version of a night that changed).
 */
data class SentNightsCheck(
    val changes: List<ChangedNight>,
    val withdrawn: List<String>,
) {
    /** The tags (clientRecordIds) of the notices of [withdrawn]. */
    val withdrawnTags: List<String> get() = withdrawn.map { SentNights.idOf(it) }.distinct()

    /** Constants. */
    companion object {
        /** Nothing found. */
        val NONE: SentNightsCheck = SentNightsCheck(emptyList(), emptyList())
    }
}

/**
 * The nights of a request to Sleep as Android as the ledger has them: the WRITTEN nights of its sessions when it is sent
 * ([keyOf]), and at every later check of an owed request which of them PulseStages has since written with another start
 * or end, and which notified nights are back at the span sent ([check]). Sleep as Android imports a night once and
 * never updates it, so such a night may be out of date there. Only the ledger is read (a few queries per night of the
 * request), never Health Connect: it works without background access. Under "Never overwrite" no night is rewritten in
 * place or merged, so only a session that the source deleted and wrote again under another id can give a change.
 */
class SentNights
    @Inject
    constructor(
        private val nights: EstimatedNightDao,
    ) {
        /**
         * The WRITTEN nights (with a Health Connect record) linked to [sessionUuids], as a key (their [versionOf], sorted,
         * joined by `;`); null when there is none.
         */
        suspend fun keyOf(sessionUuids: Collection<String>): String? {
            if (sessionUuids.isEmpty()) return null
            return nights
                .findBySourceUuids(sessionUuids)
                .filter { it.isWritten() }
                .map(::versionOf)
                .sorted()
                .joinToString(SEPARATOR)
                .ifEmpty { null }
        }

        /**
         * The nights of [sentNightsKey] that PulseStages now has with another session start or end, except for the
         * versions in [noticedKey], and the versions of [noticedKey] whose nights are back at their sent span. Each night
         * of the key is looked up by its id: a WRITTEN night is itself, a SUPERSEDED or SOURCE_DELETED one is followed
         * to the WRITTEN night that took it over ([successorOf]), and any other status is skipped (the user removed the
         * night, or there is no record a recompute could repair). Nights of the key now in one night give one change.
         * Malformed entries are skipped.
         */
        suspend fun check(
            sentNightsKey: String?,
            noticedKey: String?,
        ): SentNightsCheck {
            val noticed = entriesOf(noticedKey).mapNotNull(::parse)
            val noticedKeys = noticed.mapTo(mutableSetOf()) { it.key }
            val states = entriesOf(sentNightsKey).mapNotNull(::parse).mapNotNull { stateOf(it) }
            val changes =
                states
                    .filter { it.changed }
                    .groupBy { versionOf(it.current) }
                    .filterKeys { it !in noticedKeys }
                    .values
                    .map { oneChange(it, noticed) }
                    .sortedBy { it.night.sessionStartMs }
            val changedIds = states.filter { it.changed }.mapTo(mutableSetOf()) { it.current.clientRecordId }
            val withdrawn =
                states
                    .filterNot { it.changed }
                    .flatMap { state -> noticed.filter { it.id in state.related || it.id == state.current.clientRecordId } }
                    .filter { it.id !in changedIds }
                    .mapTo(sortedSetOf()) { it.key }
                    .toList()
            return SentNightsCheck(changes, withdrawn)
        }

        /** The night of the request [sent] as the ledger has it now, or null. */
        private suspend fun stateOf(sent: Version): SentState? {
            val row = nights.findByClientRecordId(sent.id) ?: return null
            val linked = if (row.status in FOLLOWED) nights.findBySourceUuids(row.sourceUuids) else emptyList()
            val current =
                when (row.status) {
                    NightStatus.WRITTEN -> row.takeIf { it.isWritten() }
                    in FOLLOWED -> successorOf(linked, sent.startMs, sent.endMs)
                    else -> null
                }
            return current?.let {
                SentState(sent, it, relatedOf(sent, row, linked, it), otherSources = it.sourceUuids != row.sourceUuids)
            }
        }

        /**
         * The other nights whose notices are about the night [sent] ([row], with the nights [linked] to its sources) that
         * is now [current]: those linked to the sources of either, and the SUPERSEDED or SOURCE_DELETED nights that overlap
         * the span sent by more than half (an earlier night that took it over, such as one built from a session the source
         * then recreated again).
         */
        private suspend fun relatedOf(
            sent: Version,
            row: EstimatedNightEntity,
            linked: List<EstimatedNightEntity>,
            current: EstimatedNightEntity,
        ): Set<String> {
            val earlier =
                nights
                    .findOverlapping(sent.startMs, sent.endMs, FOLLOWED.toList())
                    .filter { overlapsMostly(it, sent.startMs, sent.endMs) }
            return (nights.findBySourceUuids(current.sourceUuids) + linked + earlier + row).mapTo(sortedSetOf()) { it.clientRecordId } -
                current.clientRecordId
        }

        /**
         * The WRITTEN night that took over a night sent with [startMs]–[endMs]: of the nights sharing its sources
         * ([linked]), the one that overlaps that span most (also through several merges); without one, the WRITTEN night
         * that overlaps it most, by more than half of the shorter of the two (as a night of another app must to replace
         * one): the source recreated its session under another id, a merged night gave way to the night of its other
         * sources after the session of the request was deleted, or a night of another app replaced it. Null without
         * either.
         */
        private suspend fun successorOf(
            linked: List<EstimatedNightEntity>,
            startMs: Long,
            endMs: Long,
        ): EstimatedNightEntity? {
            val candidates =
                linked.filter { it.isWritten() }.ifEmpty {
                    nights
                        .findOverlapping(startMs, endMs, listOf(NightStatus.WRITTEN))
                        .filter { it.isWritten() && overlapsMostly(it, startMs, endMs) }
                }
            return candidates.maxByOrNull { overlapOf(it, startMs, endMs) }
        }

        /** One entry of a key: a night and its session span. */
        private data class Version(
            val id: String,
            val startMs: Long,
            val endMs: Long,
        ) {
            val key: String get() = "$id:$startMs:$endMs"
        }

        /**
         * A night of the request: the entry [sent], the WRITTEN night it is now ([current]), the other nights whose
         * notices are about it ([related]), and whether [current] has other sources than the night sent.
         */
        private data class SentState(
            val sent: Version,
            val current: EstimatedNightEntity,
            val related: Set<String>,
            val otherSources: Boolean,
        ) {
            val changed: Boolean get() = current.sessionStartMs != sent.startMs || current.sessionEndMs != sent.endMs
        }

        /** Keys and helpers. */
        companion object {
            /** The separator of the nights in a key. */
            private const val SEPARATOR = ";"

            /** One night of a key, `clientRecordId:sessionStartMs:sessionEndMs` (ids are `ps:<hex>`, so greedy). */
            private val NIGHT_KEY = Regex("(.+):(-?\\d+):(-?\\d+)")

            /** Statuses of a night of the request that another night may have taken over. */
            private val FOLLOWED = setOf(NightStatus.SUPERSEDED, NightStatus.SOURCE_DELETED)

            /**
             * The noticed versions after a check that found [found] has notified its changes: the version each
             * notice now shows. Those of [noticedKey] stay except the withdrawn ones and those of the nights a change
             * now shows over (its own night, whose notice it replaces, and its [ChangedNight.replaced] nights, whose
             * notices it cancels); the versions of the changes are added. Sorted and joined like [keyOf]; null when
             * there are none. So a night that goes back to a version notified earlier, after another notice replaced
             * that one, is notified again: the notice shown has other times. Whether it alerts was decided by [check].
             */
            fun noticedAfter(
                noticedKey: String?,
                found: SentNightsCheck,
            ): String? {
                val shownOver = found.changes.flatMapTo(mutableSetOf()) { it.replaced + it.night.clientRecordId }
                return (entriesOf(noticedKey) - found.withdrawn.toSet())
                    .filterNot { idOf(it) in shownOver }
                    .plus(found.changes.map { it.key })
                    .toSortedSet()
                    .joinToString(SEPARATOR)
                    .ifEmpty { null }
            }

            /** The version of [night] in a key: `clientRecordId:sessionStartMs:sessionEndMs`. */
            fun versionOf(night: EstimatedNightEntity): String = "${night.clientRecordId}:${night.sessionStartMs}:${night.sessionEndMs}"

            /** The clientRecordId of the [version] of a night ([versionOf]). */
            fun idOf(version: String): String = NIGHT_KEY.matchEntire(version)?.groupValues?.get(1) ?: version

            /**
             * One change of the nights of the request that are now one night ([states], one per entry): the sent span of
             * the night Sleep as Android most likely holds for it (the entry of that night itself, else the one that
             * overlaps it most, else the longest; their earliest start and latest end would be the span of a merge),
             * every sent span, and the replaced nights of all; silent when a [noticed] version of a replaced night has
             * the same times.
             */
            private fun oneChange(
                states: List<SentState>,
                noticed: List<Version>,
            ): ChangedNight {
                val night = states.first().current
                val main =
                    states.maxWith(
                        compareBy<SentState>(
                            { it.sent.id == night.clientRecordId },
                            { overlapOf(night, it.sent.startMs, it.sent.endMs) },
                            { it.sent.endMs - it.sent.startMs },
                        ),
                    )
                val replaced = states.flatMapTo(sortedSetOf()) { it.related }
                return ChangedNight(
                    night = night,
                    sentStartMs = main.sent.startMs,
                    sentEndMs = main.sent.endMs,
                    replaced = replaced,
                    otherSources = states.any { it.otherSources },
                    sentSpans = states.map { SentSpan(it.sent.startMs, it.sent.endMs) }.distinct().sortedBy { it.startMs },
                    silent =
                        noticed.any {
                            it.id in replaced && it.startMs == night.sessionStartMs && it.endMs == night.sessionEndMs
                        },
                )
            }

            /** How long the session of [night] overlaps [startMs]–[endMs] (negative when they are apart). */
            private fun overlapOf(
                night: EstimatedNightEntity,
                startMs: Long,
                endMs: Long,
            ): Long = minOf(night.sessionEndMs, endMs) - maxOf(night.sessionStartMs, startMs)

            /** True when the session of [night] and [startMs]–[endMs] overlap by more than half of the shorter of the two. */
            private fun overlapsMostly(
                night: EstimatedNightEntity,
                startMs: Long,
                endMs: Long,
            ): Boolean {
                val overlap = overlapOf(night, startMs, endMs)
                return overlap > 0 && overlap * 2 > minOf(night.sessionEndMs - night.sessionStartMs, endMs - startMs)
            }

            private fun parse(entry: String): Version? =
                NIGHT_KEY.matchEntire(entry)?.destructured?.let { (id, startMs, endMs) ->
                    Version(id, startMs.toLongOrNull() ?: return null, endMs.toLongOrNull() ?: return null)
                }

            private fun entriesOf(key: String?): List<String> = key?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

            private fun EstimatedNightEntity.isWritten(): Boolean = status == NightStatus.WRITTEN && hcRecordId != null
        }
    }

package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.SourceOutcome
import io.github.alien2003.pulsestages.data.local.db.SourceSessionEntity
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import java.time.Clock
import javax.inject.Inject

/**
 * One version of a source session as read from Health Connect. OHealth updates a session in place (same uuid) with a
 * later end, and also writes it again with unchanged times; both change [lastModifiedMs].
 *
 * @property uuid Health Connect record id of the session.
 * @property packageName package that wrote it.
 * @property startMs session start, epoch ms.
 * @property endMs session end, epoch ms.
 * @property lastModifiedMs metadata.lastModifiedTime, epoch ms.
 */
data class SourceVersion(
    val uuid: String,
    val packageName: String,
    val startMs: Long,
    val endMs: Long,
    val lastModifiedMs: Long,
) {
    /** `uuid:start:end:lastModified`: equal keys are the same version of the same session. */
    val key: String get() = "$uuid:$startMs:$endMs:$lastModifiedMs"

    /** Constructors. */
    companion object {
        /** The version of [session]. */
        fun of(session: HcSleepSession): SourceVersion =
            SourceVersion(session.uuid, session.packageName, session.startMs, session.endMs, session.lastModifiedMs)
    }
}

/** Why the source sessions are not settled ([Settlement.Unsettled]); see [SourceSettlement] for the rules. */
enum class UnsettledReason {
    /** PulseStages has not processed the session as it is now: no ledger row, or a row with other times. */
    NOT_PROCESSED,

    /**
     * A source session of a recent ledger night is gone from Health Connect and PulseStages has not applied the deletion
     * yet: our record of that night may still be there.
     */
    DELETION_PENDING,

    /**
     * The session belongs to two written nights: superseding the older output failed or was interrupted (the next sync
     * repairs it, [NightPipeline.supersedeLeftovers]).
     */
    LEFTOVER_OUTPUT,

    /** The written night of the session does not cover it: its session grew and could not be estimated again. */
    OUTPUT_BEHIND,

    /** The night of a recent session has no output yet and may still get one ([EstimatedNightEntity.awaitsEstimate]). */
    AWAITING_ESTIMATE,

    /**
     * The source has not written heart rate from long enough after the session's end yet (or, without heart rate of its
     * own in the session, the session changed too recently): the session may still be extended.
     */
    WAITING_FOR_DATA,
}

/** The verdict of [SourceSettlement.check]. */
sealed interface Settlement {
    /** The in-scope sessions read (empty when nothing was read). */
    val versions: List<SourceVersion>

    /**
     * Every in-scope session is processed and every recent one has data from long enough after its end.
     *
     * @property latestHeartRateMs per package with recent sessions, the time of its latest heart-rate sample (packages
     *   without any are left out).
     */
    data class Settled(
        override val versions: List<SourceVersion>,
        val latestHeartRateMs: Map<String, Long>,
    ) : Settlement

    /**
     * The first finding that keeps the sessions from being settled.
     *
     * @property reason what was found.
     * @property version the session it concerns; null for [UnsettledReason.DELETION_PENDING] (the session is gone).
     * @property nightClientRecordId the ledger night it concerns, if any.
     * @property thresholdMs [UnsettledReason.WAITING_FOR_DATA]: the time the source's heart rate must reach, or, without
     *   heart rate of its own in the session ([ownHeartRate] false), the time of the clock from which it counts as final.
     * @property latestHeartRateMs [UnsettledReason.WAITING_FOR_DATA]: the latest heart-rate sample of the package, if any.
     * @property ownHeartRate [UnsettledReason.WAITING_FOR_DATA]: the package wrote heart rate in the session.
     */
    data class Unsettled(
        override val versions: List<SourceVersion>,
        val reason: UnsettledReason,
        val version: SourceVersion?,
        val nightClientRecordId: String?,
        val thresholdMs: Long? = null,
        val latestHeartRateMs: Long? = null,
        val ownHeartRate: Boolean = true,
    ) : Settlement

    /**
     * The app is not in the foreground and READ_HEALTH_DATA_IN_BACKGROUND is not granted: nothing was read (the background
     * rule refuses before the call, so no quota is used).
     *
     * @property detail English detail of the refusal.
     */
    data class Unreadable(
        val detail: String,
    ) : Settlement {
        override val versions: List<SourceVersion> get() = emptyList()
    }

    /**
     * Any other Health Connect error (after the repository's retries).
     *
     * @property error the error.
     */
    data class Failed(
        val error: HealthDataException,
    ) : Settlement {
        override val versions: List<SourceVersion> get() = emptyList()
    }
}

/** The [SourceVersion.key]s of the [Settlement.versions], sorted and joined by `;` (empty when nothing was read). */
fun Settlement.keyOf(): String = versions.map { it.key }.sorted().joinToString(";")

/**
 * Whether the source sessions that another Health Connect reader imports are final and PulseStages has brought its
 * records of them up to date ("settled"), for the sync request to Sleep as Android: it imports a session it lacks once
 * and never updates it, while OHealth publishes a provisional session (cut at about 07:29 while the user still sleeps)
 * and extends the same session later. [check] only reads, Health Connect first and the ledger second, and holds no
 * [LedgerLock]: a run that changes both in between shows up as a mismatch, which only defers the request.
 *
 * The sessions of the enabled sources, minus the ignored packages, that start in the reader's window are read once.
 * Zero-length sessions (never part of a night) and sessions ending more than [CLOCK_TOLERANCE_MS] in the future are
 * ignored. A session is in scope when it ended or was last changed in the last [SETTLE_TIMEOUT_MS], and recent when it
 * ended then. The rules, in this order, the first finding being returned:
 * 1. Every ledger night of these packages that ended in the last [SETTLE_TIMEOUT_MS] and that a deletion would change
 *    (written, not estimated or failed) still has all its source sessions in Health Connect; otherwise our record of it
 *    may still be there ([UnsettledReason.DELETION_PENDING]).
 * 2. Per in-scope session, from the ledger only: PulseStages processed it as it is now ([UnsettledReason.NOT_PROCESSED]);
 *    it belongs to one written night only ([UnsettledReason.LEFTOVER_OUTPUT]); its written night covers it
 *    ([UnsettledReason.OUTPUT_BEHIND]); a recent one's night does not await an estimate
 *    ([UnsettledReason.AWAITING_ESTIMATE]). A session that no automatic run would update counts as processed: one that
 *    the NEVER policy kept from being written or whose written night NEVER keeps, and one whose night the user deleted.
 * 3. Per recent session, the heart rate of its own package, whatever `hrSources` says (another app's heart rate tells
 *    nothing about the source's next upload): a sample at or after the session's end + `mergeGapMin` +
 *    [DATA_MARGIN_EXTRA_MS], and at least [CLOCK_TOLERANCE_MS] after the session was last changed (samples stamped after
 *    the change can only come from a later upload). When the package wrote no heart rate in the session: the session
 *    unchanged for [QUIET_MS] and that margin past its end ([UnsettledReason.WAITING_FOR_DATA]).
 *
 * Cost: one sleep-session read, plus one heart-rate read per package with recent sessions once rules 1 and 2 hold.
 */
class SourceSettlement
    @Inject
    constructor(
        private val repository: HealthRepository,
        private val ledger: NightLedger,
        private val windowReader: NightWindowReader,
        private val clock: Clock,
    ) {
        private val guard = HealthAccessGuard(repository)

        /**
         * Whether the sessions of the enabled sources of [settings] except [ignoredPackages] (a reader never imports its
         * own sessions) that start in the last [windowMs] (the reader's import window) are settled. Health Connect errors
         * are returned as [Settlement.Unreadable] (the background rule) or [Settlement.Failed]; cancellation propagates.
         */
        suspend fun check(
            settings: AppSettings,
            ignoredPackages: Set<String>,
            windowMs: Long,
        ): Settlement {
            val packages = windowReader.enabledPackages(settings) - ignoredPackages
            return try {
                if (packages.isEmpty()) Settlement.Settled(emptyList(), emptyMap()) else evaluate(settings, packages, windowMs)
            } catch (e: HealthDataException.PermissionDenied) {
                refused(e)
            } catch (e: HealthDataException) {
                Settlement.Failed(e)
            }
        }

        /** A refusal: the background rule ([Settlement.Unreadable]), or a permission that is missing ([Settlement.Failed]). */
        private suspend fun refused(error: HealthDataException.PermissionDenied): Settlement =
            if (guard.reasonForDenial() == BlockedReason.BACKGROUND_PERMISSION_MISSING) {
                Settlement.Unreadable(error.message.orEmpty())
            } else {
                Settlement.Failed(error)
            }

        /** Reads the sessions of [packages] and applies the rules (see the class documentation). */
        private suspend fun evaluate(
            settings: AppSettings,
            packages: Set<String>,
            windowMs: Long,
        ): Settlement {
            val now = clock.millis()
            val read = repository.readSleepSessions(now - windowMs, now, packages)
            val inScope =
                read.filter {
                    it.endMs > it.startMs && it.endMs <= now + CLOCK_TOLERANCE_MS &&
                        maxOf(it.endMs, it.lastModifiedMs) > now - SETTLE_TIMEOUT_MS
                }
            val uuids = inScope.map { it.uuid }
            val rows = ledger.sources.findByUuids(uuids).associateBy { it.uuid }
            val scope = Scope(now, settings, inScope, rows, ledger.nights.findBySourceUuids(uuids))
            return deletionPending(read, packages, scope) ?: unprocessed(scope) ?: dataAfterEnd(scope)
        }

        /**
         * Rule 1: a ledger night of [packages] that ends in the last [SETTLE_TIMEOUT_MS] and that a deletion would change
         * ([NightRun.REPLACEABLE]), with a source session that is not among all the sessions [read].
         */
        private suspend fun deletionPending(
            read: List<HcSleepSession>,
            packages: Set<String>,
            scope: Scope,
        ): Settlement? {
            val present = read.mapTo(HashSet()) { it.uuid }
            return ledger.nights
                .findOverlapping(scope.now - SETTLE_TIMEOUT_MS, Long.MAX_VALUE, NightRun.REPLACEABLE.toList())
                .firstOrNull { night -> night.sourcePackage in packages && night.sourceUuids.any { it !in present } }
                ?.let { scope.unsettled(UnsettledReason.DELETION_PENDING, session = null, nightClientRecordId = it.clientRecordId) }
        }

        /** Rule 2: the first in-scope session with a finding in the ledger. */
        private suspend fun unprocessed(scope: Scope): Settlement? =
            scope.inScope.firstNotNullOfOrNull { session ->
                val row = scope.rows[session.uuid]
                val nightId = row?.nightClientRecordId
                // The night the row points to is among the owners unless the row is not among its sources.
                val night = nightId?.let { id -> scope.owners.find { it.clientRecordId == id } ?: ledger.nights.findByClientRecordId(id) }
                ledgerFinding(session, row, night, scope)?.let { scope.unsettled(it, session, nightId) }
            }

        /** What the ledger says against [session], with its [row] and the [night] that row points to; null when nothing. */
        private suspend fun ledgerFinding(
            session: HcSleepSession,
            row: SourceSessionEntity?,
            night: EstimatedNightEntity?,
            scope: Scope,
        ): UnsettledReason? {
            val policy = scope.settings.write.overwritePolicy
            return when {
                !processed(session, row, night, policy) -> UnsettledReason.NOT_PROCESSED
                scope.writtenOwners(session) > 1 -> UnsettledReason.LEFTOVER_OUTPUT
                policy != OverwritePolicy.NEVER && night.writtenWithout(session) -> UnsettledReason.OUTPUT_BEHIND
                scope.isRecent(session) && night?.awaitsEstimate == true -> UnsettledReason.AWAITING_ESTIMATE
                else -> null
            }
        }

        /**
         * Whether PulseStages processed [session] as it is now: its [row] has the session's times. No automatic run changes
         * anything for a session without a row that a written night overlaps under NEVER (NEVER does not write a night over
         * an output), nor for a changed session whose [night] the user deleted (never written again automatically) or
         * whose written night NEVER keeps: those count as processed. A row marked deleted does not: the session is back.
         */
        private suspend fun processed(
            session: HcSleepSession,
            row: SourceSessionEntity?,
            night: EstimatedNightEntity?,
            policy: OverwritePolicy,
        ): Boolean =
            when {
                row == null -> {
                    policy == OverwritePolicy.NEVER &&
                        ledger.nights.findOverlapping(session.startMs, session.endMs, listOf(NightStatus.WRITTEN)).isNotEmpty()
                }

                row.outcome == SourceOutcome.DELETED -> {
                    false
                }

                row.startMs == session.startMs && row.endMs == session.endMs -> {
                    true
                }

                night?.status == NightStatus.DELETED -> {
                    true
                }

                else -> {
                    policy == OverwritePolicy.NEVER && night?.status == NightStatus.WRITTEN
                }
            }

        /** Rule 3, per package with recent sessions: its heart rate after their ends; [Settlement.Settled] when it suffices. */
        private suspend fun dataAfterEnd(scope: Scope): Settlement {
            val marginMs = dataMarginMs(scope.settings)
            val latest = sortedMapOf<String, Long>()
            for ((packageName, sessions) in scope.recent.groupBy { it.packageName }.toSortedMap()) {
                val heartRate = repository.readHeartRate(sessions.minOf { it.startMs }, scope.now + CLOCK_TOLERANCE_MS, setOf(packageName))
                val edge = heartRate.lastOrNull()?.timeMs
                if (edge != null) latest[packageName] = edge
                val waiting = sessions.firstNotNullOfOrNull { waitingForData(it, heartRate, edge, marginMs, scope) }
                if (waiting != null) return waiting
            }
            return Settlement.Settled(scope.versions, latest)
        }

        /**
         * [UnsettledReason.WAITING_FOR_DATA] for [session], or null when its package's [heartRate] (latest sample [edge])
         * or, without heart rate of its own in the session, the time since it last changed shows that it is final.
         */
        private fun waitingForData(
            session: HcSleepSession,
            heartRate: List<HrSample>,
            edge: Long?,
            marginMs: Long,
            scope: Scope,
        ): Settlement? {
            val own = heartRate.any { it.timeMs in session.startMs until session.endMs }
            val sinceChange = if (own) CLOCK_TOLERANCE_MS else QUIET_MS
            val threshold = maxOf(session.endMs + marginMs, session.lastModifiedMs + sinceChange)
            val settled = if (own) edge != null && edge >= threshold else scope.now >= threshold
            return if (settled) {
                null
            } else {
                scope
                    .unsettled(UnsettledReason.WAITING_FOR_DATA, session, scope.rows[session.uuid]?.nightClientRecordId)
                    .copy(thresholdMs = threshold, latestHeartRateMs = edge, ownHeartRate = own)
            }
        }

        /** A written night that does not cover [session] (null: none). */
        private fun EstimatedNightEntity?.writtenWithout(session: HcSleepSession): Boolean =
            this != null && status == NightStatus.WRITTEN && !covers(session)

        /** The night's session spans [session]. */
        private fun EstimatedNightEntity.covers(session: HcSleepSession): Boolean =
            sessionStartMs <= session.startMs && session.endMs <= sessionEndMs

        /**
         * One evaluation at [now] with [settings]: the [inScope] sessions, their `source_sessions` [rows] by uuid and the
         * ledger nights that list them ([owners]).
         */
        private class Scope(
            val now: Long,
            val settings: AppSettings,
            val inScope: List<HcSleepSession>,
            val rows: Map<String, SourceSessionEntity>,
            val owners: List<EstimatedNightEntity>,
        ) {
            /** The in-scope sessions that ended in the last [SETTLE_TIMEOUT_MS]. */
            val recent: List<HcSleepSession> = inScope.filter { isRecent(it) }

            /** The versions of the in-scope sessions. */
            val versions: List<SourceVersion> = inScope.map(SourceVersion::of)

            /** Whether [session] ended in the last [SETTLE_TIMEOUT_MS]. */
            fun isRecent(session: HcSleepSession): Boolean = session.endMs > now - SETTLE_TIMEOUT_MS

            /** The number of written nights that list [session]. */
            fun writtenOwners(session: HcSleepSession): Int =
                owners.count { it.status == NightStatus.WRITTEN && session.uuid in it.sourceUuids }

            /** A [Settlement.Unsettled] for [reason] about [session] and the night [nightClientRecordId], with [versions]. */
            fun unsettled(
                reason: UnsettledReason,
                session: HcSleepSession?,
                nightClientRecordId: String?,
            ): Settlement.Unsettled = Settlement.Unsettled(versions, reason, session?.let(SourceVersion::of), nightClientRecordId)
        }

        /** Constants. */
        companion object {
            /** One minute. */
            private const val MINUTE_MS: Long = 60_000L

            /**
             * Sessions that ended and were last changed longer ago are no longer gated. 12 h outlasts a provisional or
             * intermediate version (about 2 and 2.5 h on 18 Oct 2026), a sleep-in to 12:55 (5.5 h after a 07:29 cut) and
             * a final version published up to 2 h 56 min after its end (17 Oct 2026).
             */
            const val SETTLE_TIMEOUT_MS: Long = 12L * 60 * 60 * 1000

            /**
             * Added to `sources.mergeGapMin` for the margin after a session's end that its source's heart rate must reach
             * (60 min by default): it covers a back-to-sleep fragment that would merge with the session and the 20–27 min
             * OHealth needs to report a sleep period; the slack observed is 3–11 min, so do not lower it.
             */
            const val DATA_MARGIN_EXTRA_MS: Long = 30L * 60 * 1000

            /**
             * The band's clock and the phone's may differ by this much: sessions ending and heart rate stamped after now +
             * 5 min are ignored, and heart rate counts only from 5 min after the session was last changed.
             */
            const val CLOCK_TOLERANCE_MS: Long = 5L * 60 * 1000

            /**
             * How long a session in which its package wrote no heart rate must be unchanged to count as final. No evidence
             * yet; the same order as the heart-rate margin.
             */
            const val QUIET_MS: Long = 60L * 60 * 1000

            /** The margin after a session's end that its source's heart rate must reach with [settings] ([DATA_MARGIN_EXTRA_MS]). */
            fun dataMarginMs(settings: AppSettings): Long = settings.sources.mergeGapMin * MINUTE_MS + DATA_MARGIN_EXTRA_MS
        }
    }

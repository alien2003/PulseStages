package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.params.NapOutputPolicy
import io.github.alien2003.pulsestages.data.hc.EstimatedSessionWrite
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.WriteResult
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.SourceOutcome
import io.github.alien2003.pulsestages.data.local.db.SourceSessionEntity
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/** What [NightPipeline] and its per-call [NightRun]s work with. */
internal class PipelineDeps(
    val repository: HealthRepository,
    val estimator: NightEstimator,
    val ledger: NightLedger,
    val titles: DefaultTitleProvider,
    val logger: AppLogger,
    val clock: Clock,
    val estimationDispatcher: CoroutineDispatcher,
) {
    /** Classifies permission errors raised in the middle of a run. */
    val guard: HealthAccessGuard = HealthAccessGuard(repository)

    /** Reads the estimation input of a night. */
    val inputReader: NightInputReader = NightInputReader(repository, ledger.baselines)
}

/** The result of one unit of work of a [NightRun] (a night, or a repair). */
internal sealed interface Step {
    /** True when no further night is processed after this step. */
    val stopsRun: Boolean

    /** Finished with [result]. */
    data class Done(
        val result: NightResult,
    ) : Step {
        override val stopsRun: Boolean get() = false
    }

    /** Failed; logged (and recorded for a night), the run continues. */
    data object Failed : Step {
        override val stopsRun: Boolean get() = false
    }

    /** Cancelled between nights. */
    data object Cancelled : Step {
        override val stopsRun: Boolean get() = true
    }

    /** The run must stop: quota hit, or blocked for [reason]. */
    data class Stop(
        val quotaHit: Boolean,
        val reason: BlockedReason?,
    ) : Step {
        override val stopsRun: Boolean get() = true
    }
}

/** What happened to one night (or repair) that finished normally. */
internal enum class NightResult {
    WRITTEN,
    UP_TO_DATE,
    STALE_LEFT,
    NEVER_SKIPPED,
    DELETED_SKIPPED,
    NOT_ESTIMATED,
    NOT_ESTIMATED_KEPT,

    /** A repair or retirement of outputs (counted through `superseded` only). */
    REPAIRED,
}

/** Uuids among [uuids] whose source row belongs to another night (not [ownNight]) that currently has an output. */
internal suspend fun NightLedger.protectedUuids(
    uuids: List<String>,
    ownNight: String?,
): Set<String> =
    sources
        .findByUuids(uuids)
        .filter { row ->
            val night = row.nightClientRecordId
            night != null && night != ownNight && nights.findByClientRecordId(night)?.status == NightStatus.WRITTEN
        }.mapTo(mutableSetOf()) { it.uuid }

/** The `source_sessions` row of this session. */
internal fun HcSleepSession.toSourceRow(
    outcome: SourceOutcome,
    reason: String?,
    night: String?,
    now: Long,
): SourceSessionEntity =
    SourceSessionEntity(
        uuid = uuid,
        packageName = packageName,
        startMs = startMs,
        endMs = endMs,
        outcome = outcome,
        reason = reason,
        nightClientRecordId = night,
        lastSeenAt = now,
    )

/**
 * What one [NightRun] works with.
 *
 * @property settings the settings of the run.
 * @property trigger why the run was started.
 * @property granted the granted Health Connect permissions.
 * @property paramsHash the params hash of [settings] with [granted].
 * @property requestedAtMs when the user asked for the run; nights deleted after it are not recreated (null = no limit).
 */
internal class RunSpec(
    val settings: AppSettings,
    val trigger: PipelineTrigger,
    val granted: Set<String>,
    val paramsHash: String,
    val requestedAtMs: Long? = null,
)

/**
 * The state of one [NightPipeline.process] call (or of one ledger repair): decides, estimates and writes night by
 * night. Not thread-safe; always used under [LedgerLock].
 */
@Suppress("TooManyFunctions") // One private function per decision / write step.
internal class NightRun(
    deps: PipelineDeps,
    spec: RunSpec,
    private val candidates: List<NightCandidate>,
) {
    private val settings = spec.settings
    private val trigger = spec.trigger
    private val granted = spec.granted
    private val paramsHash = spec.paramsHash
    private val requestedAtMs = spec.requestedAtMs
    private val repository = deps.repository
    private val estimator = deps.estimator
    private val ledger = deps.ledger
    private val titles = deps.titles
    private val logger = deps.logger
    private val clock = deps.clock
    private val estimationDispatcher = deps.estimationDispatcher
    private val guard = deps.guard
    private val inputReader = deps.inputReader

    /** Outputs superseded since the last [takeSuperseded]. */
    private var superseded: Int = 0

    /** Our sessions around [candidates] by clientRecordId, read once per run on the first write. */
    private var storedOwn: MutableMap<String, HcSleepSession>? = null

    private val policy: OverwritePolicy get() = settings.write.overwritePolicy

    /** Returns the outputs superseded since the last call and resets the counter. */
    fun takeSuperseded(): Int = superseded.also { superseded = 0 }

    /** Processes one night with per-night isolation. */
    suspend fun step(candidate: NightCandidate): Step =
        guarded(candidate.clientRecordId, onFailure = { recordFailure(candidate, it) }) { processNight(candidate) }

    /**
     * Retires the outputs that sessions with their own sleep stages made unnecessary, so Health Connect does not hold
     * two staged sessions for one night (never under NEVER, which does not touch outputs):
     * - source sessions that got their own stages after we estimated them (HAS_STAGES in [skipped], modified after our
     *   write);
     * - nights that are now lower-priority duplicates of a session with stages of a higher-priority source
     *   ([SkippedNight.stagedWinnerUuids]: that session arrived, or its source was enabled or moved up, after we wrote
     *   the night).
     *
     * Such a WRITTEN night loses its record and becomes SUPERSEDED. Null when there is nothing to do.
     */
    suspend fun retireStagedOutputs(skipped: List<SkippedNight>): Step? {
        val retiring = policy != OverwritePolicy.NEVER
        val staged = skipped.filter { retiring && it.outcome == SourceOutcome.HAS_STAGES }.flatMap { it.sessions }
        val duplicates =
            skipped
                .filter { retiring && it.outcome == SourceOutcome.LOWER_PRIORITY_DUPLICATE && it.stagedWinnerUuids.isNotEmpty() }
                .flatMap { it.sessions }
        if (staged.isEmpty() && duplicates.isEmpty()) return null
        return guarded(nightKey = null, onFailure = { logger.e(TAG, "could not retire outputs of sessions with stages", throwable = it) }) {
            val modifiedAt = staged.associate { it.uuid to it.lastModifiedMs }
            val outdated =
                ledger.nights.findBySourceUuids(modifiedAt.keys).filter { night ->
                    night.status == NightStatus.WRITTEN && night.sourceUuids.any { (modifiedAt[it] ?: Long.MIN_VALUE) > night.updatedAt }
                }
            retire(outdated.map { it.clientRecordId }, STAGED_REASON)
            val outranked =
                ledger.nights
                    .findBySourceUuids(duplicates.map { it.uuid })
                    .filter { it.status == NightStatus.WRITTEN }
            retire(outranked.map { it.clientRecordId }, OUTRANKED_BY_STAGED_REASON)
            NightResult.REPAIRED
        }
    }

    /** Deletes the records of the WRITTEN nights [ids] and marks them SUPERSEDED for [reason]. */
    private suspend fun retire(
        ids: List<String>,
        reason: String,
    ) {
        if (ids.isEmpty()) return
        deleteOutputs(ids)
        ledger.nights.updateStatus(ids, NightStatus.SUPERSEDED, reason, clearOutput = true, now = clock.millis())
        ids.forEach { logger.i(TAG, "$reason; our session was removed", nightKey = it) }
        superseded += ids.size
    }

    /**
     * Supersedes WRITTEN nights whose source session now belongs to another WRITTEN night: the newer night was
     * written, but superseding the older one failed or was interrupted (a transient delete error, a ledger error, the
     * worker stopped in between). Repairs the "one output per night" invariant on every sync.
     */
    suspend fun supersedeLeftovers(): Step =
        guarded(nightKey = null, onFailure = { logger.e(TAG, "could not repair leftover outputs", throwable = it) }) {
            val written = ledger.nights.findOverlapping(Long.MIN_VALUE, Long.MAX_VALUE, listOf(NightStatus.WRITTEN))
            val owners =
                ledger.sources
                    .findByUuids(written.flatMap { it.sourceUuids }.distinct())
                    .associate { it.uuid to it.nightClientRecordId }
            val current = written.mapTo(HashSet()) { it.clientRecordId }
            written.forEach { night ->
                val owner =
                    night.sourceUuids.firstNotNullOfOrNull { uuid ->
                        owners[uuid]?.takeIf {
                            it != night.clientRecordId &&
                                it in current
                        }
                    }
                if (owner != null) {
                    logger.w(TAG, "a newer output ($owner) owns sources of this night; superseding it now", nightKey = night.clientRecordId)
                    supersede(listOf(night), owner)
                    current -= night.clientRecordId
                }
            }
            NightResult.REPAIRED
        }

    /** Runs [block] with per-unit isolation: stop errors stop the run, anything else is a [Step.Failed]. */
    @Suppress("TooGenericExceptionCaught") // Per-night isolation: any failure of one night is recorded, not thrown.
    private suspend fun guarded(
        nightKey: String?,
        onFailure: suspend (Exception) -> Unit,
        block: suspend () -> NightResult,
    ): Step =
        try {
            Step.Done(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: HealthDataException.QuotaExceeded) {
            logger.w(TAG, "Health Connect quota exceeded; stopping this run", nightKey = nightKey, throwable = e)
            Step.Stop(quotaHit = true, reason = null)
        } catch (e: HealthDataException.PermissionDenied) {
            val reason = guard.reasonForDenial()
            logger.w(TAG, "Health Connect refused access ($reason); stopping this run", nightKey = nightKey, throwable = e)
            Step.Stop(quotaHit = false, reason = reason)
        } catch (e: HealthDataException.Unavailable) {
            logger.w(TAG, "Health Connect unavailable; stopping this run", nightKey = nightKey, throwable = e)
            Step.Stop(quotaHit = false, reason = BlockedReason.HEALTH_CONNECT_UNAVAILABLE)
        } catch (e: Exception) {
            onFailure(e)
            Step.Failed
        }

    private suspend fun processNight(raw: NightCandidate): NightResult {
        val existing = ledger.nights.findByClientRecordId(raw.clientRecordId)
        val candidate = raw.withLedgerOffsets(existing)
        val linked = ledger.nights.findBySourceUuids(candidate.sourceUuids).filter { it.clientRecordId != candidate.clientRecordId }
        val related = relatedNights(candidate, linked)
        val hasOutput = existing != null && existing.status == NightStatus.WRITTEN
        val blockedByNever = policy == OverwritePolicy.NEVER && !hasOutput && related.any { it.status == NightStatus.WRITTEN }
        if (blockedByNever) {
            logger.i(TAG, "NEVER policy: an overlapping output exists; not writing this night", nightKey = candidate.clientRecordId)
        }
        val skip = if (blockedByNever) NightResult.NEVER_SKIPPED else skipReason(candidate, existing, linked)
        if (skip == null) return estimateAndWrite(candidate, existing, related)
        if (hasOutput && policy != OverwritePolicy.NEVER) {
            // An up-to-date or stale output: make sure nothing it replaces is left behind.
            supersede(related, candidate.clientRecordId)
            refreshSourceRows(candidate)
        }
        return skip
    }

    /**
     * The candidate with the zone offsets the ledger stored for this night where the source session has none: the
     * device's current zone is only a guess, and a later zone change must neither count as a source change nor
     * rewrite the historical offset.
     */
    private fun NightCandidate.withLedgerOffsets(existing: EstimatedNightEntity?): NightCandidate =
        if (existing == null || (!startOffsetIsFallback && !endOffsetIsFallback)) {
            this
        } else {
            copy(
                startZoneOffsetSec = if (startOffsetIsFallback) existing.startZoneOffsetSec else startZoneOffsetSec,
                endZoneOffsetSec = if (endOffsetIsFallback) existing.endZoneOffsetSec else endZoneOffsetSec,
            )
        }

    /**
     * Estimates [candidate] in its mode ([NightCandidate.mode]) and writes it; a nap under `napMode.outputPolicy` SKIP
     * is not estimated and nothing is read for it ([skipNap]).
     */
    private suspend fun estimateAndWrite(
        candidate: NightCandidate,
        existing: EstimatedNightEntity?,
        related: List<EstimatedNightEntity>,
    ): NightResult {
        val napMode = settings.algo.napMode
        if (candidate.mode(settings.algo) == SessionMode.NAP && napMode.outputPolicy == NapOutputPolicy.SKIP) {
            return skipNap(candidate, existing, related)
        }
        val input = buildInput(candidate)
        return when (val outcome = withContext(estimationDispatcher) { estimator.estimate(input, settings.algo) }) {
            is EstimationOutcome.Estimated -> write(candidate, existing, related, outcome.estimate)
            is EstimationOutcome.NotEstimated -> notEstimated(candidate, existing, related, outcome)
        }
    }

    /**
     * Why [candidate] is not (re)estimated, or null when it must be estimated. A night the user deleted ("delete all
     * my sessions") is not written again by automatic syncs, also when a later fragment gave it another
     * clientRecordId ([linked] nights share a source uuid); a trigger that recreates deleted nights does not recreate
     * one that was deleted after the run was requested ([requestedAtMs]).
     */
    private suspend fun skipReason(
        candidate: NightCandidate,
        existing: EstimatedNightEntity?,
        linked: List<EstimatedNightEntity>,
    ): NightResult? {
        if (existing != null && existing.status == NightStatus.WRITTEN) return writtenSkipReason(candidate, existing)
        val deleted = (listOfNotNull(existing) + linked).filter { it.status == NightStatus.DELETED }
        val deletedAfterRequest = requestedAtMs?.let { requested -> deleted.any { it.updatedAt > requested } } ?: false
        if (trigger.recreatesDeleted && deletedAfterRequest) {
            logger.i(TAG, "deleted by the user after this run was requested; not written again", nightKey = candidate.clientRecordId)
        }
        val recreate = trigger.recreatesDeleted && !deletedAfterRequest
        return if (deleted.isEmpty() || recreate) null else NightResult.DELETED_SKIPPED
    }

    private suspend fun writtenSkipReason(
        candidate: NightCandidate,
        existing: EstimatedNightEntity,
    ): NightResult? =
        when {
            policy == OverwritePolicy.NEVER -> {
                NightResult.NEVER_SKIPPED
            }

            sourcesChanged(candidate, existing) -> {
                null
            }

            policy == OverwritePolicy.RECOMPUTE_ALL || trigger.forcesRecompute -> {
                null
            }

            existing.algoVersion == ALGO_VERSION && existing.paramsHash == paramsHash -> {
                NightResult.UP_TO_DATE
            }

            else -> {
                logger.d(TAG, "stale night left as is (overwritePolicy SKIP_SAME_VERSION)", nightKey = candidate.clientRecordId)
                NightResult.STALE_LEFT
            }
        }

    /** True when the session boundaries / offsets or a fragment's times differ from what the ledger knows. */
    private suspend fun sourcesChanged(
        candidate: NightCandidate,
        existing: EstimatedNightEntity,
    ): Boolean =
        existing.sessionStartMs != candidate.startMs ||
            existing.sessionEndMs != candidate.endMs ||
            existing.startZoneOffsetSec != candidate.startZoneOffsetSec ||
            existing.endZoneOffsetSec != candidate.endZoneOffsetSec ||
            fragmentsChanged(candidate)

    private suspend fun fragmentsChanged(candidate: NightCandidate): Boolean {
        val rows = ledger.sources.findByUuids(candidate.sourceUuids).associateBy { it.uuid }
        return candidate.fragments.any { fragment ->
            val row = rows[fragment.uuid]
            row == null || row.startMs != fragment.startMs || row.endMs != fragment.endMs
        }
    }

    /**
     * Other nights that this night replaces: [linked] nights (another clientRecordId sharing a source uuid) with an
     * output or NOT_ESTIMATED / FAILED, and WRITTEN nights overlapping it (same package, or another package by more
     * than half of the shorter night).
     */
    private suspend fun relatedNights(
        candidate: NightCandidate,
        linked: List<EstimatedNightEntity>,
    ): List<EstimatedNightEntity> {
        val byUuid = linked.filter { it.status in REPLACEABLE }
        val overlapping =
            ledger.nights
                .findOverlapping(candidate.startMs, candidate.endMs, listOf(NightStatus.WRITTEN))
                .filter { it.clientRecordId != candidate.clientRecordId }
                .filter { it.sourcePackage == candidate.packageName || overlapsMostly(it.sessionStartMs, it.sessionEndMs, candidate) }
        return (byUuid + overlapping).distinctBy { it.clientRecordId }
    }

    private suspend fun buildInput(candidate: NightCandidate): NightInput = inputReader.read(NightSpan.of(candidate), settings, granted)

    private suspend fun write(
        candidate: NightCandidate,
        existing: EstimatedNightEntity?,
        related: List<EstimatedNightEntity>,
        estimate: NightEstimate,
    ): NightResult {
        val awakeAsLight = settings.write.awakeAsLight
        val sleepWakeOnly = estimate.mode == SessionMode.NAP && settings.algo.napMode.outputPolicy == NapOutputPolicy.SLEEP_WAKE
        val stages =
            if (sleepWakeOnly) {
                StageMapping.toSleepWakeHcStages(estimate.stages, awakeAsLight)
            } else {
                StageMapping.toHcStages(estimate.stages, awakeAsLight)
            }
        val problems =
            StageMapping.validate(
                stages,
                candidate.startMs,
                candidate.endMs,
                settings.algo.preprocess.epochSec * SECOND_MS,
            )
        if (problems.isNotEmpty()) throw InvalidStagesException(problems)

        val recomputeCount = nextRecomputeCount(candidate, existing)
        val version = ALGO_VERSION * VERSION_FACTOR + recomputeCount
        val result = repository.writeEstimatedSession(sessionWrite(candidate, estimate, stages, version))

        val now = clock.millis()
        ledger.nights.recordNight(
            writtenNight(candidate, existing, estimate, Written(stages, sleepWakeOnly, recomputeCount, result)),
            candidate.fragments.map { it.toSourceRow(SourceOutcome.ESTIMATED, null, candidate.clientRecordId, now) },
        )
        val coverage = SessionText.round3(estimate.coverage)
        val what = if (estimate.mode == SessionMode.NAP) "nap" else "night"
        val output = if (sleepWakeOnly) "${stages.size} sleep/wake stages" else "${stages.size} stages"
        logger.i(
            TAG,
            "wrote ${estimate.estimator} $what v$version ($output, coverage $coverage)",
            nightKey = candidate.clientRecordId,
        )
        // Under NEVER only nights without an output can be related here (see processNight).
        supersede(related, candidate.clientRecordId)
        deleteUnknownOwnRecords(candidate)
        return NightResult.WRITTEN
    }

    /**
     * Deletes our records in Health Connect that describe the night just written but that the ledger does not know as
     * an output (written before a reinstall / "clear data", or left behind by a failed delete): another clientRecordId
     * whose notes list one of the night's source uuids, or that overlaps the night by more than half of the shorter
     * one. Only our own records are ever deleted (by clientRecordId).
     */
    private suspend fun deleteUnknownOwnRecords(candidate: NightCandidate) {
        val stored = storedOwn ?: return
        val unknown =
            stored.values
                .filter { it.clientRecordId != candidate.clientRecordId && describes(it, candidate) }
                .mapNotNull { it.clientRecordId }
                .filter { ledger.nights.findByClientRecordId(it)?.status != NightStatus.WRITTEN }
        if (unknown.isEmpty()) return
        deleteOutputs(unknown)
        unknown.forEach {
            logger.i(TAG, "deleted our earlier record $it of this night (unknown to the ledger)", nightKey = candidate.clientRecordId)
        }
        superseded += unknown.size
    }

    private fun describes(
        own: HcSleepSession,
        candidate: NightCandidate,
    ): Boolean =
        SessionText.sourceUuidsOf(own.notes).any { it in candidate.sourceUuids } ||
            overlapsMostly(own.startMs, own.endMs, candidate)

    /**
     * recomputeCount of the next write: the ledger's count + 1 for a rewrite (0 for a first write), and never so
     * low that the version would not exceed the one already stored in Health Connect (e.g. after a reinstall).
     */
    private suspend fun nextRecomputeCount(
        candidate: NightCandidate,
        existing: EstimatedNightEntity?,
    ): Long {
        val fromLedger =
            when {
                existing == null -> 0L
                existing.clientRecordVersion > 0 -> existing.recomputeCount + 1L
                else -> existing.recomputeCount.toLong()
            }
        val stored = storedOwnSessions()[candidate.clientRecordId]
        val fromStored = stored?.let { it.clientRecordVersion + 1 - ALGO_VERSION * VERSION_FACTOR } ?: 0L
        return maxOf(fromLedger, fromStored, 0L)
    }

    /** Our records around the run's nights currently in Health Connect, by clientRecordId (one read per run). */
    private suspend fun storedOwnSessions(): MutableMap<String, HcSleepSession> {
        val cached = storedOwn
        if (cached != null) return cached
        val fromMs = candidates.minOf { it.startMs } - STORED_LOOKUP_MS
        val toMs = candidates.maxOf { it.startMs } + STORED_LOOKUP_MS
        val read =
            repository
                .readOwnSessions(fromMs, toMs)
                .filter { it.clientRecordId != null }
                .associateByTo(HashMap()) { it.clientRecordId.orEmpty() }
        storedOwn = read
        return read
    }

    private fun sessionWrite(
        candidate: NightCandidate,
        estimate: NightEstimate,
        stages: List<HcStage>,
        version: Long,
    ): EstimatedSessionWrite =
        EstimatedSessionWrite(
            clientRecordId = candidate.clientRecordId,
            clientRecordVersion = version,
            startMs = candidate.startMs,
            endMs = candidate.endMs,
            startZoneOffsetSec = candidate.startZoneOffsetSec,
            endZoneOffsetSec = candidate.endZoneOffsetSec,
            stages = stages,
            title =
                SessionText.title(
                    template = settings.write.titleTemplate,
                    defaultTemplate = titles.defaultTitleTemplate(),
                    estimator = estimate.estimator,
                    algoVersion = estimate.algoVersion,
                    confidence = estimate.confidence,
                ),
            notes =
                SessionText.notes(
                    estimator = estimate.estimator,
                    mode = estimate.mode,
                    algoVersion = estimate.algoVersion,
                    paramsHash = paramsHash,
                    coverage = estimate.coverage,
                    confidence = estimate.confidence,
                    sourceUuids = candidate.sourceUuids,
                    hrSamples = estimate.hrSampleCount,
                    calibrated = estimate.flags.calibrated,
                ),
        )

    private fun writtenNight(
        candidate: NightCandidate,
        existing: EstimatedNightEntity?,
        estimate: NightEstimate,
        written: Written,
    ): EstimatedNightEntity {
        val now = clock.millis()
        // The estimated stages (after awakeAsLight): for a sleep/wake nap the ledger keeps them, not the two written types.
        val estimated = StageMapping.toHcStages(estimate.stages, settings.write.awakeAsLight)
        val minutes = StageMinutes.of(estimated)
        return EstimatedNightEntity(
            clientRecordId = candidate.clientRecordId,
            sourceUuids = candidate.sourceUuids,
            sourcePackage = candidate.packageName,
            sessionStartMs = candidate.startMs,
            sessionEndMs = candidate.endMs,
            startZoneOffsetSec = candidate.startZoneOffsetSec,
            endZoneOffsetSec = candidate.endZoneOffsetSec,
            hcRecordId = written.result.recordId,
            algoVersion = estimate.algoVersion,
            paramsHash = paramsHash,
            estimator = estimate.estimator,
            modelId = if (estimate.estimator == EstimatorType.HYBRID) estimator.modelId else null,
            coverage = estimate.coverage,
            confidence = estimate.confidence,
            recomputeCount = written.recomputeCount.toInt(),
            clientRecordVersion = written.result.storedVersion,
            status = NightStatus.WRITTEN,
            statusReason = null,
            awakeMin = minutes.awake,
            lightMin = minutes.light,
            deepMin = minutes.deep,
            remMin = minutes.rem,
            unknownMin = minutes.unknown,
            elevatedHr = estimate.flags.elevatedHr,
            lowConfidence = estimate.flags.lowConfidence,
            calibrated = estimate.flags.calibrated,
            rulesFallback = estimate.flags.rulesFallback,
            warnings = estimate.warnings.map { it.code }.distinct(),
            nadirBpm = estimate.nadirBpm,
            hrSamples = estimate.hrSampleCount,
            stages = StageMapping.toLedgerStages(estimated),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            mode = estimate.mode,
            sleepWakeOnly = written.sleepWakeOnly,
        )
    }

    /**
     * A nap under `napMode.outputPolicy` SKIP: nothing is estimated or written, and nothing is read for it. An output
     * written for it earlier (another policy, or before it was a nap) is deleted, and so is every output it would
     * replace if it were written (`related`); the night is recorded NOT_ESTIMATED with the reason `NAP_SKIPPED: …`.
     */
    private suspend fun skipNap(
        candidate: NightCandidate,
        existing: EstimatedNightEntity?,
        related: List<EstimatedNightEntity>,
    ): NightResult {
        val minutes = candidate.durationMs / MINUTE_MS
        val reason =
            "${NotEstimatedReason.NAP_SKIPPED}: nap of $minutes min (shorter than napMode.maxSessionMin = " +
                "${settings.algo.napMode.maxSessionMin} min) and naps are not written (napMode.outputPolicy = SKIP)"
        if (existing?.status == NightStatus.WRITTEN) {
            deleteOutputs(listOf(candidate.clientRecordId))
            logger.i(
                TAG,
                "nap output removed: naps are no longer written (napMode.outputPolicy = SKIP)",
                nightKey = candidate.clientRecordId,
            )
        }
        val now = clock.millis()
        val protected = ledger.protectedUuids(candidate.sourceUuids, candidate.clientRecordId)
        ledger.nights.recordNight(
            nightWithoutOutput(candidate, existing, NightStatus.NOT_ESTIMATED, reason, existing?.coverage ?: 0.0),
            candidate.fragments
                .filter { it.uuid !in protected }
                .map { it.toSourceRow(SourceOutcome.NOT_ESTIMATED, reason, candidate.clientRecordId, now) },
        )
        supersede(related, candidate.clientRecordId)
        logger.i(TAG, "not estimated: $reason", nightKey = candidate.clientRecordId)
        return NightResult.NOT_ESTIMATED
    }

    private suspend fun notEstimated(
        candidate: NightCandidate,
        existing: EstimatedNightEntity?,
        related: List<EstimatedNightEntity>,
        outcome: EstimationOutcome.NotEstimated,
    ): NightResult {
        val reason = "${outcome.reason}: ${outcome.detail}"
        if (existing?.status == NightStatus.WRITTEN) {
            logger.w(TAG, "recompute not possible ($reason); keeping the previous output", nightKey = candidate.clientRecordId)
            refreshSourceRows(candidate)
            return NightResult.NOT_ESTIMATED_KEPT
        }
        val now = clock.millis()
        val protected = ledger.protectedUuids(candidate.sourceUuids, candidate.clientRecordId)
        ledger.nights.recordNight(
            nightWithoutOutput(candidate, existing, NightStatus.NOT_ESTIMATED, reason, outcome.coverage),
            candidate.fragments
                .filter { it.uuid !in protected }
                .map { it.toSourceRow(SourceOutcome.NOT_ESTIMATED, reason, candidate.clientRecordId, now) },
        )
        supersede(related.filter { it.status != NightStatus.WRITTEN }, candidate.clientRecordId)
        logger.i(TAG, "not estimated: $reason", nightKey = candidate.clientRecordId)
        return NightResult.NOT_ESTIMATED
    }

    @Suppress("TooGenericExceptionCaught") // Recording a failure must never fail the run.
    private suspend fun recordFailure(
        candidate: NightCandidate,
        error: Exception,
    ) {
        logger.e(TAG, "processing failed: ${error.javaClass.simpleName}: ${error.message}", candidate.clientRecordId, error)
        try {
            val existing = ledger.nights.findByClientRecordId(candidate.clientRecordId)
            // A night whose output exists stays WRITTEN; a failed supersede after the write is repaired by
            // supersedeLeftovers on the next sync.
            if (existing?.status == NightStatus.WRITTEN) return
            val now = clock.millis()
            val reason = "${error.javaClass.simpleName}: ${error.message}".take(MAX_REASON_LENGTH)
            val protected = ledger.protectedUuids(candidate.sourceUuids, candidate.clientRecordId)
            ledger.nights.recordNight(
                nightWithoutOutput(candidate, existing, NightStatus.FAILED, reason, existing?.coverage ?: 0.0),
                candidate.fragments
                    .filter { it.uuid !in protected }
                    .map { it.toSourceRow(SourceOutcome.FAILED, reason, candidate.clientRecordId, now) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "could not record the failure", candidate.clientRecordId, e)
        }
    }

    /** A NOT_ESTIMATED / FAILED row (no output) that keeps the counters of an earlier row. */
    private fun nightWithoutOutput(
        candidate: NightCandidate,
        existing: EstimatedNightEntity?,
        status: NightStatus,
        reason: String,
        coverage: Double,
    ): EstimatedNightEntity {
        val now = clock.millis()
        return EstimatedNightEntity(
            clientRecordId = candidate.clientRecordId,
            sourceUuids = candidate.sourceUuids,
            sourcePackage = candidate.packageName,
            sessionStartMs = candidate.startMs,
            sessionEndMs = candidate.endMs,
            startZoneOffsetSec = candidate.startZoneOffsetSec,
            endZoneOffsetSec = candidate.endZoneOffsetSec,
            hcRecordId = null,
            algoVersion = ALGO_VERSION,
            paramsHash = paramsHash,
            estimator = candidate.mode(settings.algo).let { settings.algo.forMode(it).estimator },
            coverage = coverage,
            recomputeCount = existing?.recomputeCount ?: 0,
            clientRecordVersion = existing?.clientRecordVersion ?: 0,
            status = status,
            statusReason = reason,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            mode = candidate.mode(settings.algo),
        )
    }

    /** Deletes the outputs of [related] (by clientRecordId) and marks them SUPERSEDED by [newClientRecordId]. */
    private suspend fun supersede(
        related: List<EstimatedNightEntity>,
        newClientRecordId: String,
    ) {
        if (related.isEmpty()) return
        val withOutput = related.filter { it.status == NightStatus.WRITTEN }.map { it.clientRecordId }
        if (withOutput.isNotEmpty()) deleteOutputs(withOutput)
        ledger.nights.markSuperseded(related.map { it.clientRecordId }, newClientRecordId, clock.millis())
        related.forEach { logger.i(TAG, "superseded by $newClientRecordId", nightKey = it.clientRecordId) }
        superseded += related.size
    }

    /** Deletes our records with [clientRecordIds] and forgets them in this run's cache of stored records. */
    private suspend fun deleteOutputs(clientRecordIds: List<String>) {
        repository.deleteOwnSessions(clientRecordIds)
        storedOwn?.let { cache -> clientRecordIds.forEach { cache.remove(it) } }
    }

    /** Re-links the fragments of a night whose output is kept (ESTIMATED, lastSeenAt = now). */
    private suspend fun refreshSourceRows(candidate: NightCandidate) {
        val now = clock.millis()
        ledger.sources.upsertAll(candidate.fragments.map { it.toSourceRow(SourceOutcome.ESTIMATED, null, candidate.clientRecordId, now) })
    }

    /** What [write] wrote ([sleepWakeOnly]: a nap in the SLEEP_WAKE output). */
    private class Written(
        val stages: List<HcStage>,
        val sleepWakeOnly: Boolean,
        val recomputeCount: Long,
        val result: WriteResult,
    )

    /** Constants. */
    companion object {
        private const val TAG = "NightPipeline"
        private const val SECOND_MS = 1_000L
        private const val MINUTE_MS = 60_000L
        private const val VERSION_FACTOR = 1_000L
        private const val MAX_REASON_LENGTH = 500
        private const val STAGED_REASON = "the source session now has its own sleep stages"
        private const val OUTRANKED_BY_STAGED_REASON = "a higher-priority source recorded this night with its own sleep stages"

        /** Margin around the nights' starts in which our existing records are looked up. */
        private const val STORED_LOOKUP_MS = 24L * 60 * 60 * 1000

        /** Statuses of nights that a new night may replace. */
        val REPLACEABLE: Set<NightStatus> = setOf(NightStatus.WRITTEN, NightStatus.NOT_ESTIMATED, NightStatus.FAILED)

        /** True when `[startMs, endMs)` and [candidate] overlap by more than half of the shorter of the two. */
        fun overlapsMostly(
            startMs: Long,
            endMs: Long,
            candidate: NightCandidate,
        ): Boolean {
            val overlap = minOf(endMs, candidate.endMs) - maxOf(startMs, candidate.startMs)
            val shorter = minOf(endMs - startMs, candidate.durationMs)
            return overlap > 0 && overlap * 2 > shorter
        }
    }
}

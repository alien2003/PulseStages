package io.github.alien2003.pulsestages.data.hc.usecase

import androidx.health.connect.client.records.SleepSessionRecord
import io.github.alien2003.pulsestages.algo.metrics.ConfusionMatrix
import io.github.alien2003.pulsestages.algo.metrics.IntervalScoring
import io.github.alien2003.pulsestages.algo.metrics.NightScore
import io.github.alien2003.pulsestages.algo.metrics.StageMinutesError
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.di.EstimationDispatcher
import io.github.alien2003.pulsestages.data.hc.di.OwnPackageName
import io.github.alien2003.pulsestages.data.hc.pipeline.HealthAccessGuard
import io.github.alien2003.pulsestages.data.hc.pipeline.NightEstimator
import io.github.alien2003.pulsestages.data.hc.pipeline.NightInputReader
import io.github.alien2003.pulsestages.data.hc.pipeline.NightSpan
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import io.github.alien2003.pulsestages.algo.personal.ReferenceNight as CalibrationNight

/**
 * A night with real sleep stages written by another app (another tracker, Sleep as Android, ...), with the input our
 * estimator needs for the same session.
 *
 * @property session the reference session as stored in Health Connect.
 * @property truth its stages mapped to [SleepStage] ([ReferenceStages]), sorted, clipped to the session; SLEEPING and
 *   UNKNOWN stages are left out (not scored).
 * @property input heart rate and steps over the session ± `contextMarginMin` (+ optional channels when enabled and
 *   granted) and the baseline of the nights before it.
 * @property heartRatePackages the origins the heart rate was read from (the enabled sources), or null when it came from
 *   any origin because the enabled sources had none for this session.
 */
data class ReferenceNight(
    val session: HcSleepSession,
    val truth: List<StageInterval>,
    val input: NightInput,
    val heartRatePackages: Set<String>?,
) {
    /** The night in core-algo's form for [io.github.alien2003.pulsestages.algo.personal.PersonalCalibrator]. */
    fun toCalibrationNight(): CalibrationNight = CalibrationNight(input, truth)
}

/**
 * Result of [ReferenceComparison.findReferenceNights].
 *
 * @property nights reference nights with heart rate, oldest first.
 * @property withoutHeartRate reference sessions skipped because no heart rate was found for them.
 * @property withoutSleepStages sessions of other apps that have stages but are no reference ([ReferenceStages.isReference]):
 *   awake / asleep only (no LIGHT, DEEP or REM, e.g. a tracker that only writes SLEEPING and AWAKE bouts), or real
 *   stages covering too little of the session. Scoring them would only score their AWAKE minutes.
 * @property fromMs start of the searched range.
 * @property toMs end of the searched range.
 */
data class ReferenceSearch(
    val nights: List<ReferenceNight>,
    val withoutHeartRate: List<HcSleepSession>,
    val withoutSleepStages: List<HcSleepSession>,
    val fromMs: Long,
    val toMs: Long,
)

/**
 * How our estimator did on one reference night.
 *
 * @property night the reference night.
 * @property estimator the estimator that produced the stages, or null when the night was not estimated.
 * @property score chunk-level comparison with the reference, or null when the night was not estimated.
 * @property notEstimatedReason English reason when the night was not estimated (or the estimator failed).
 * @property mode whether the reference session was estimated as a night or as a nap (`napMode.maxSessionMin` of the
 *   parameters it was compared with).
 */
data class ReferenceNightScore(
    val night: ReferenceNight,
    val estimator: EstimatorType?,
    val score: NightScore?,
    val notEstimatedReason: String?,
    val mode: SessionMode = SessionMode.NIGHT,
) {
    /** Cohen's kappa of this night, or null when not estimated / undefined. */
    val kappa: Double? get() = score?.confusion?.kappa?.takeIf { it.isFinite() }

    /** Accuracy of this night, or null when not estimated / undefined. */
    val accuracy: Double? get() = score?.confusion?.accuracy?.takeIf { it.isFinite() }

    /** Absolute DEEP / REM / AWAKE minute errors, or null when not estimated. */
    val minutesError: StageMinutesError? get() = score?.minutesError
}

/**
 * Per-night and pooled agreement of our estimator with the reference nights (the "Compare" screen). Short reference
 * sessions are estimated as naps; [forMode] gives the same numbers for the nights or the naps alone.
 *
 * @property nights one score per reference night, in input order.
 */
data class ReferenceReport(
    val nights: List<ReferenceNightScore>,
) {
    /** The modes of the compared sessions, in [SessionMode] order. */
    val modes: List<SessionMode> get() = SessionMode.entries.filter { mode -> nights.any { it.mode == mode } }

    /** Whether the sessions are of both modes (the metrics are then also shown per mode). */
    val mixesModes: Boolean get() = modes.size > 1

    /** The report of the sessions of [mode] only. */
    fun forMode(mode: SessionMode): ReferenceReport = ReferenceReport(nights.filter { it.mode == mode })

    /** The confusion matrices of every scored night summed (rows: reference, columns: estimate). */
    val pooled: ConfusionMatrix = nights.mapNotNull { it.score?.confusion }.fold(ConfusionMatrix.EMPTY) { sum, m -> sum + m }

    /** Number of nights that were estimated and scored. */
    val scoredNights: Int get() = nights.count { it.score != null }

    /** Pooled Cohen's kappa, or null when undefined. */
    val kappa: Double? get() = pooled.kappa.takeIf { it.isFinite() }

    /** Pooled accuracy, or null without scored chunks. */
    val accuracy: Double? get() = pooled.accuracy.takeIf { it.isFinite() }

    /** Pooled macro-F1, or null without scored chunks. */
    val macroF1: Double? get() = pooled.macroF1.takeIf { it.isFinite() }

    /** Mean absolute minute errors over the scored nights, or null when none was scored. */
    val meanMinutesError: StageMinutesError?
        get() {
            val errors = nights.mapNotNull { it.minutesError }
            if (errors.isEmpty()) return null
            return StageMinutesError(
                deep = errors.sumOf { it.deep } / errors.size,
                rem = errors.sumOf { it.rem } / errors.size,
                awake = errors.sumOf { it.awake } / errors.size,
            )
        }
}

/**
 * Mapping of Health Connect stage types of reference sessions to scored stages: AWAKE, AWAKE_IN_BED and OUT_OF_BED →
 * AWAKE, LIGHT → LIGHT, DEEP → DEEP, REM → REM; SLEEPING and UNKNOWN are not scored (null). A session is a reference
 * night only with real sleep stages ([isReference]).
 */
object ReferenceStages {
    /** Real stages (AWAKE / LIGHT / DEEP / REM) must cover at least this share of a reference session. */
    const val MIN_STAGED_SHARE: Double = 0.5

    /**
     * Whether [session] can serve as a reference night: its scored stages ([truthOf]) contain at least one sleep stage
     * (LIGHT, DEEP or REM; AWAKE alone says nothing about the sleep stages) and cover at least [MIN_STAGED_SHARE] of
     * the session (the rest being SLEEPING / UNKNOWN, which is not scored).
     */
    fun isReference(session: HcSleepSession): Boolean {
        val truth = truthOf(session)
        val sessionMs = session.endMs - session.startMs
        if (sessionMs <= 0 || truth.none { it.stage != SleepStage.AWAKE }) return false
        return coveredMs(truth) >= MIN_STAGED_SHARE * sessionMs
    }

    /** Time covered by [sorted] intervals (overlaps counted once). */
    private fun coveredMs(sorted: List<StageInterval>): Long {
        var covered = 0L
        var end = Long.MIN_VALUE
        sorted.forEach { interval ->
            val start = maxOf(interval.startMs, end)
            if (interval.endMs > start) covered += interval.endMs - start
            end = maxOf(end, interval.endMs)
        }
        return covered
    }

    /** The scored stage of [type], or null when it is not a real stage. */
    fun stageOf(type: Int): SleepStage? =
        when (type) {
            SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> {
                SleepStage.AWAKE
            }

            SleepSessionRecord.STAGE_TYPE_LIGHT -> {
                SleepStage.LIGHT
            }

            SleepSessionRecord.STAGE_TYPE_DEEP -> {
                SleepStage.DEEP
            }

            SleepSessionRecord.STAGE_TYPE_REM -> {
                SleepStage.REM
            }

            else -> {
                null
            }
        }

    /** The scored stages of [session], sorted and clipped to the session; empty when it has no real stage. */
    fun truthOf(session: HcSleepSession): List<StageInterval> =
        session.stages
            .mapNotNull { stage ->
                stageOf(stage.type)?.let { StageInterval(maxOf(stage.startMs, session.startMs), minOf(stage.endMs, session.endMs), it) }
            }.filter { it.endMs > it.startMs }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))
}

/**
 * Validation against reference nights from Health Connect: [findReferenceNights] collects the sessions of every app
 * except ours that have real sleep stages ([ReferenceStages.isReference]: LIGHT / DEEP / REM, covering most of the
 * session; awake / asleep-only sessions are reported apart) and that are not shorter than the source setting
 * `minSessionMin` (sessions between it and `napMode.maxSessionMin` are compared as naps, [ReferenceReport.forMode]),
 * each with heart rate (+ steps) over the session ± the context margin: from
 * the enabled sources first, from any origin when those have none. [compare] runs our estimator on them and scores the
 * result with core-algo's [IntervalScoring] (30-s chunks: kappa, per-stage precision / recall / F1, accuracy, confusion
 * matrix, minute errors), per night and pooled. Never writes anything; never throws except for cancellation.
 */
class ReferenceComparison
    @Inject
    constructor(
        private val repository: HealthRepository,
        private val settingsRepository: SettingsRepository,
        private val inputReader: NightInputReader,
        private val estimator: NightEstimator,
        @param:OwnPackageName private val ownPackage: String,
        @param:EstimationDispatcher private val estimationDispatcher: CoroutineDispatcher,
        private val logger: AppLogger,
    ) {
        private val guard = HealthAccessGuard(repository)

        /** Reference nights whose session starts in `[fromMs, toMs)`; reads in the foreground. */
        suspend fun findReferenceNights(
            fromMs: Long,
            toMs: Long,
        ): UseCaseResult<ReferenceSearch> =
            runUseCase(guard, logger, TAG) {
                val settings = settingsRepository.settings.first()
                val access = guard.check(inBackground = false)
                val blocked = access.blockedReason
                if (blocked != null) return@runUseCase UseCaseResult.Blocked(blocked, access.detail)
                val minDurationMs = settings.sources.minSessionMin * MINUTE_MS
                val staged =
                    repository
                        .readSleepSessions(fromMs, toMs, packages = null)
                        .filter { it.packageName != ownPackage && it.endMs - it.startMs >= minDurationMs }
                        .filter { ReferenceStages.truthOf(it).isNotEmpty() }
                val (sessions, withoutSleepStages) = staged.partition { ReferenceStages.isReference(it) }
                val nights = mutableListOf<ReferenceNight>()
                val withoutHeartRate = mutableListOf<HcSleepSession>()
                sessions.forEach { session ->
                    currentCoroutineContext().ensureActive()
                    val night = referenceNight(session, settings, access.granted)
                    if (night == null) withoutHeartRate += session else nights += night
                }
                logger.i(
                    TAG,
                    "found ${nights.size} reference night(s), ${withoutHeartRate.size} without heart rate, " +
                        "${withoutSleepStages.size} without real sleep stages",
                )
                UseCaseResult.Success(ReferenceSearch(nights, withoutHeartRate, withoutSleepStages, fromMs, toMs))
            }

        /** Estimates every night of [nights] with [params] and scores it against its reference stages. */
        suspend fun compare(
            nights: List<ReferenceNight>,
            params: AlgoParams,
        ): ReferenceReport =
            ReferenceReport(
                nights.map { night ->
                    currentCoroutineContext().ensureActive()
                    score(night, params)
                },
            )

        /** [compare] with the current settings. */
        suspend fun compareWithCurrentSettings(nights: List<ReferenceNight>): ReferenceReport =
            compare(nights, settingsRepository.settings.first().algo)

        @Suppress("TooGenericExceptionCaught") // One failing night is reported in its score, the others are still scored.
        private suspend fun score(
            night: ReferenceNight,
            params: AlgoParams,
        ): ReferenceNightScore {
            val mode = params.modeOf(night.input.sessionEndMs - night.input.sessionStartMs)
            return try {
                when (val outcome = withContext(estimationDispatcher) { estimator.estimate(night.input, params) }) {
                    is EstimationOutcome.Estimated -> {
                        ReferenceNightScore(
                            night,
                            outcome.estimate.estimator,
                            IntervalScoring.score(night.truth, outcome.estimate.stages),
                            null,
                            outcome.estimate.mode,
                        )
                    }

                    is EstimationOutcome.NotEstimated -> {
                        ReferenceNightScore(night, null, null, "${outcome.reason}: ${outcome.detail}", mode)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "estimating reference session ${night.session.uuid} failed", throwable = e)
                ReferenceNightScore(night, null, null, "${e.javaClass.simpleName}: ${e.message}", mode)
            }
        }

        /** The reference night of [session], or null when no heart rate exists for it. */
        private suspend fun referenceNight(
            session: HcSleepSession,
            settings: AppSettings,
            granted: Set<String>,
        ): ReferenceNight? {
            val span = NightSpan(session.startMs, session.endMs, session.packageName)
            val enabled = settings.sources.sourcePackages.filterTo(sortedSetOf()) { it != ownPackage }
            val preferred = if (enabled.isEmpty()) null else enabled
            val first = inputReader.readFrom(span, settings, granted, preferred)
            val (input, origins) =
                if (preferred != null && !first.hasHeartRateInSession()) {
                    inputReader.readFrom(span, settings, granted, packages = null) to null
                } else {
                    first to preferred
                }
            return if (input.hasHeartRateInSession()) ReferenceNight(session, ReferenceStages.truthOf(session), input, origins) else null
        }

        private fun NightInput.hasHeartRateInSession(): Boolean = heartRate.any { it.timeMs in sessionStartMs until sessionEndMs }

        private companion object {
            const val TAG = "ReferenceComparison"
            const val MINUTE_MS = 60_000L
        }
    }

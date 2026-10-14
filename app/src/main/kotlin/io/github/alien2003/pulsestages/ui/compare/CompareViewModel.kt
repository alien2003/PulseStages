package io.github.alien2003.pulsestages.ui.compare

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.metrics.ConfusionMatrix
import io.github.alien2003.pulsestages.algo.metrics.StageMinutesError
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SessionMode
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.data.hc.usecase.CalibrationOutcome
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceNight
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceNightScore
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceReport
import io.github.alien2003.pulsestages.data.hc.usecase.SpecialAccess
import io.github.alien2003.pulsestages.data.hc.usecase.UseCaseResult
import io.github.alien2003.pulsestages.data.local.db.PersonalModelEntity
import io.github.alien2003.pulsestages.data.local.db.PersonalModelKind
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.platform.AppInfoResolver
import io.github.alien2003.pulsestages.source.ReferenceSource
import io.github.alien2003.pulsestages.source.ScreenSources
import io.github.alien2003.pulsestages.ui.common.UiMessage
import io.github.alien2003.pulsestages.ui.common.UiText
import io.github.alien2003.pulsestages.ui.common.WorkResultText
import io.github.alien2003.pulsestages.ui.common.formatDecimal
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/** What the Compare tab is doing. */
enum class ComparePhase {
    /** Searching Health Connect for reference nights. */
    SEARCHING,

    /** Estimating the reference nights. */
    SCORING,

    /** Done (possibly without reference nights). */
    READY,

    /** The search failed. */
    ERROR,
}

/** A longer action of the Compare tab. */
enum class CompareBusy {
    /** Calibrating the personal model. */
    CALIBRATING,

    /** Going back to the global models. */
    RESETTING,

    /** Estimating the reference nights again (after a calibration or reset). */
    RESCORING,
}

/**
 * Accuracy of one stage, pooled over the reference nights (30-s chunks).
 *
 * @property stage the stage.
 * @property recall share of the reference's chunks of this stage that the estimate got right ("accuracy per stage").
 * @property precision share of the estimate's chunks of this stage that the reference confirms.
 * @property f1 harmonic mean of [precision] and [recall].
 * @property support reference chunks of this stage.
 */
@Immutable
data class StageMetrics(
    val stage: SleepStage,
    val recall: Double,
    val precision: Double,
    val f1: Double,
    val support: Long,
)

/**
 * One row of the confusion matrix: the reference stage [truth] and how the estimate classified its chunks.
 *
 * @property truth the reference stage.
 * @property counts chunks per estimated stage, in [SleepStage.SCORED] order.
 * @property shares [counts] as a share of the row total (0 when the row is empty).
 * @property total chunks of the row.
 */
@Immutable
data class ConfusionRow(
    val truth: SleepStage,
    val counts: List<Long>,
    val shares: List<Double>,
    val total: Long,
)

/**
 * Pooled validation metrics.
 *
 * @property kappa Cohen's kappa, or null when undefined.
 * @property accuracy overall share of agreeing chunks, or null.
 * @property macroF1 mean F1 over the stages present, or null.
 * @property scoredNights nights estimated and scored.
 * @property stages per-stage accuracy and F1.
 * @property matrix the confusion matrix, rows = reference, columns = estimate.
 * @property meanMinutesError mean absolute DEEP / REM / AWAKE minute errors per night, or null.
 * @property byMode the pooled kappa and accuracy of the nights and of the naps apart, when the reference sessions are of
 *   both modes (short reference sessions are estimated as naps); empty otherwise.
 */
@Immutable
data class CompareMetrics(
    val kappa: Double?,
    val accuracy: Double?,
    val macroF1: Double?,
    val scoredNights: Int,
    val stages: List<StageMetrics>,
    val matrix: List<ConfusionRow>,
    val meanMinutesError: StageMinutesError?,
    val byMode: List<ModeMetrics> = emptyList(),
) {
    /** Mapping. */
    companion object {
        /** The metrics of [report]. */
        fun of(report: ReferenceReport): CompareMetrics =
            CompareMetrics(
                kappa = report.kappa,
                accuracy = report.accuracy,
                macroF1 = report.macroF1,
                scoredNights = report.scoredNights,
                stages = stagesOf(report.pooled),
                matrix = rowsOf(report.pooled),
                meanMinutesError = report.meanMinutesError,
                byMode =
                    if (report.mixesModes) {
                        report.modes.map { mode ->
                            val part = report.forMode(mode)
                            ModeMetrics(mode, part.kappa, part.accuracy, part.scoredNights)
                        }
                    } else {
                        emptyList()
                    },
            )

        /** Per-stage scores of [matrix] in [SleepStage.SCORED] order. */
        fun stagesOf(matrix: ConfusionMatrix): List<StageMetrics> =
            SleepStage.SCORED.map { stage ->
                val score = matrix.stage(stage)
                StageMetrics(stage, score.recall, score.precision, score.f1, score.support)
            }

        /** Rows of [matrix] with counts and row shares. */
        fun rowsOf(matrix: ConfusionMatrix): List<ConfusionRow> =
            SleepStage.SCORED.map { truth ->
                val counts = SleepStage.SCORED.map { predicted -> matrix[truth, predicted] }
                val total = counts.sum()
                ConfusionRow(truth, counts, counts.map { if (total == 0L) 0.0 else it.toDouble() / total }, total)
            }
    }
}

/**
 * Pooled metrics of the reference sessions of one mode.
 *
 * @property mode nights or naps.
 * @property kappa Cohen's kappa, or null when undefined.
 * @property accuracy share of agreeing chunks, or null.
 * @property scoredNights sessions of [mode] estimated and scored.
 */
@Immutable
data class ModeMetrics(
    val mode: SessionMode,
    val kappa: Double?,
    val accuracy: Double?,
    val scoredNights: Int,
)

/**
 * One reference night in the list.
 *
 * @property uuid Health Connect id of the reference session.
 * @property packageName the app that wrote it.
 * @property label the app's label, or null when not visible.
 * @property startMs session start.
 * @property endMs session end.
 * @property zoneOffsetSec zone offset at the start (0 when the writer gave none).
 * @property kappa Cohen's kappa of the estimate against the reference, or null (not estimated / undefined).
 * @property accuracy share of agreeing chunks, or null.
 * @property estimator the estimator that produced the stages, or null when the night was not estimated.
 * @property mode whether the reference session was estimated as a night or as a nap.
 */
@Immutable
data class ReferenceNightItem(
    val uuid: String,
    val packageName: String,
    val label: String?,
    val startMs: Long,
    val endMs: Long,
    val zoneOffsetSec: Int,
    val kappa: Double?,
    val accuracy: Double?,
    val estimator: EstimatorType?,
    val mode: SessionMode = SessionMode.NIGHT,
) {
    /** Mapping. */
    companion object {
        /** The list item of [score]; [labels] maps package names to app labels. */
        fun of(
            score: ReferenceNightScore,
            labels: Map<String, String?>,
        ): ReferenceNightItem {
            val session = score.night.session
            return ReferenceNightItem(
                uuid = session.uuid,
                packageName = session.packageName,
                label = labels[session.packageName],
                startMs = session.startMs,
                endMs = session.endMs,
                zoneOffsetSec = session.startZoneOffsetSec ?: 0,
                kappa = score.kappa,
                accuracy = score.accuracy,
                estimator = score.estimator,
                mode = score.mode,
            )
        }
    }
}

/**
 * The personal model in use.
 *
 * @property kind calibrated or EM.
 * @property createdAt when it was made.
 * @property nightsUsed nights it was built from.
 * @property kappaBefore held-out kappa of the global models (calibrated models), or null.
 * @property kappaAfter held-out kappa with the personal model, or null.
 */
@Immutable
data class ModelInfo(
    val kind: PersonalModelKind,
    val createdAt: Long,
    val nightsUsed: Int,
    val kappaBefore: Double?,
    val kappaAfter: Double?,
) {
    /** Mapping. */
    companion object {
        /** The info of [row]. */
        fun of(row: PersonalModelEntity): ModelInfo = ModelInfo(row.kind, row.createdAt, row.nightsUsed, row.kappaBefore, row.kappaAfter)
    }
}

/**
 * How far back the Compare tab searches for reference nights.
 *
 * @property days length of the range in days, or null for the whole history.
 */
enum class CompareRange(
    val days: Int?,
) {
    /** The last 90 days (the default; the only range offered without READ_HEALTH_DATA_HISTORY). */
    RECENT(CompareViewModel.SEARCH_DAYS),

    /** The last 365 days. */
    YEAR(DAYS_PER_YEAR),

    /** The whole history (reads one heart-rate series per reference night: slower for long histories). */
    ALL(null),
}

private const val DAYS_PER_YEAR = 365

/**
 * UI state of the Compare tab.
 *
 * @property phase searching, scoring, ready or failed.
 * @property error why the search failed (phase ERROR).
 * @property range the searched range.
 * @property historyLimited READ_HEALTH_DATA_HISTORY is missing: only about 30 days were visible, and only the
 *   [CompareRange.RECENT] range is offered.
 * @property synthetic the reference nights are synthetic demo data (debug builds).
 * @property referenceCount reference nights found (being scored / scored).
 * @property metrics pooled metrics, when nights were scored.
 * @property nights per-night results, newest first.
 * @property withoutHeartRate reference sessions skipped because no heart rate was found for them.
 * @property withoutSleepStages other apps' sessions with stages that are no reference (awake / asleep only).
 * @property model the active personal model, or null for the global models.
 * @property personalizationEnabled `personal.enabled`: calibration is only possible when it is on.
 * @property busy a calibration, reset or re-estimate is running.
 * @property confirmReset the "Reset to global model" confirmation is open.
 * @property messages queued Snackbar messages.
 */
@Immutable
data class CompareUiState(
    val phase: ComparePhase = ComparePhase.SEARCHING,
    val error: UiText? = null,
    val range: CompareRange = CompareRange.RECENT,
    val historyLimited: Boolean = false,
    val synthetic: Boolean = false,
    val referenceCount: Int = 0,
    val metrics: CompareMetrics? = null,
    val nights: List<ReferenceNightItem> = emptyList(),
    val withoutHeartRate: Int = 0,
    val withoutSleepStages: Int = 0,
    val model: ModelInfo? = null,
    val personalizationEnabled: Boolean = true,
    val busy: CompareBusy? = null,
    val confirmReset: Boolean = false,
    val messages: List<UiMessage> = emptyList(),
) {
    /** Ready without any reference night (the empty state explains what a reference night is). */
    val empty: Boolean get() = phase == ComparePhase.READY && referenceCount == 0

    /** The range can be changed now (a longer range needs READ_HEALTH_DATA_HISTORY). */
    fun canSelect(range: CompareRange): Boolean = busy == null && (range == CompareRange.RECENT || !historyLimited)

    /** "Calibrate" can run now. */
    val canCalibrate: Boolean get() = phase == ComparePhase.READY && referenceCount > 0 && personalizationEnabled && busy == null
}

/**
 * The Compare tab: finds the nights other apps wrote with real stages (reference nights) in the chosen
 * [CompareRange] (the last [SEARCH_DAYS] days by default; a year or the whole history with READ_HEALTH_DATA_HISTORY),
 * scores our estimator on them (pooled kappa, per-stage accuracy and F1, confusion matrix, per-night kappa) and
 * calibrates or resets the personal model.
 *
 * The reference nights come from the current source of [ScreenSources]: when it is replaced (debug builds: demo mode
 * switched), a running action is cancelled and the tab starts over with the new source. While no screen shows the tab
 * ([onScreenStarted] / [onScreenStopped]; the switch is in Settings, so it is hidden then), the new search waits until
 * the tab is shown again, so a hidden tab does not read Health Connect or score the reference nights.
 */
@HiltViewModel
class CompareViewModel
    @Inject
    constructor(
        sources: ScreenSources,
        private val appInfo: AppInfoResolver,
        private val clock: Clock,
        private val logger: AppLogger,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(CompareUiState())

        /** The current source of [ScreenSources]; null until it is known. */
        private var source: ReferenceSource? = null
        private var references: List<ReferenceNight> = emptyList()
        private var loadJob: Job? = null
        private var actionJob: Job? = null
        private var requestedRange = CompareRange.RECENT
        private val labels = mutableMapOf<String, String?>()
        private var visibleScreens = 0
        private var refreshDeferred = false

        /** The current UI state. */
        val state: StateFlow<CompareUiState> = mutableState.asStateFlow()

        init {
            viewModelScope.launch {
                sources.references.collectLatest { next ->
                    onSource(next)
                    coroutineScope {
                        launch {
                            next
                                .activeModel()
                                .catch { failure -> logger.w(TAG, "cannot read the personal model", throwable = failure) }
                                .collect { row -> mutableState.update { it.copy(model = row?.let(ModelInfo::of)) } }
                        }
                        launch {
                            next
                                .personalizationEnabled()
                                .catch { failure -> logger.w(TAG, "cannot read the settings", throwable = failure) }
                                .collect { enabled -> mutableState.update { it.copy(personalizationEnabled = enabled) } }
                        }
                    }
                }
            }
        }

        /**
         * [next] is the source now: the tab starts over with it (queued messages and the chosen range are kept). The
         * first source is searched at once (the tab is being opened); a replaced one only when a screen shows the tab.
         */
        private fun onSource(next: ReferenceSource) {
            val replaced = source != null
            source = next
            if (replaced) {
                actionJob?.cancel()
                loadJob?.cancel()
                references = emptyList()
                mutableState.update { CompareUiState(range = it.range, synthetic = next.synthetic, messages = it.messages) }
            } else {
                mutableState.update { it.copy(synthetic = next.synthetic) }
            }
            if (!replaced || visibleScreens > 0) refresh() else refreshDeferred = true
        }

        /** A screen showing the tab became visible; a search that waited for it (replaced source) runs now. */
        fun onScreenStarted() {
            visibleScreens++
            if (refreshDeferred) refresh()
        }

        /** A screen showing the tab is no longer visible (stopped, or left the composition). */
        fun onScreenStopped() {
            visibleScreens = (visibleScreens - 1).coerceAtLeast(0)
        }

        /** Searches [range] instead (ignored while busy; a longer range needs READ_HEALTH_DATA_HISTORY). */
        fun selectRange(range: CompareRange) {
            val current = mutableState.value
            if (range == current.range || !current.canSelect(range)) return
            requestedRange = range
            refresh()
        }

        /** Searches Health Connect again and scores the reference nights. */
        fun refresh() {
            val source = source ?: return
            refreshDeferred = false
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    mutableState.update { it.copy(phase = ComparePhase.SEARCHING, error = null) }
                    val historyLimited = source.historyAccess() != SpecialAccess.GRANTED
                    // Without the history permission Health Connect returns about 30 days: a longer range finds nothing more.
                    val range = if (historyLimited) CompareRange.RECENT else requestedRange
                    mutableState.update { it.copy(range = range, historyLimited = historyLimited) }
                    val now = clock.millis()
                    val fromMs = range.days?.let { now - it * DAY_MS } ?: 0L
                    when (val result = source.findReferenceNights(fromMs, now + 1)) {
                        is UseCaseResult.Success -> {
                            val search = result.value
                            references = search.nights
                            mutableState.update {
                                it.copy(
                                    phase = if (search.nights.isEmpty()) ComparePhase.READY else ComparePhase.SCORING,
                                    referenceCount = search.nights.size,
                                    withoutHeartRate = search.withoutHeartRate.size,
                                    withoutSleepStages = search.withoutSleepStages.size,
                                    metrics = null,
                                    nights = emptyList(),
                                )
                            }
                            if (search.nights.isNotEmpty()) score(source)
                        }

                        else -> {
                            references = emptyList()
                            mutableState.update { it.copy(phase = ComparePhase.ERROR, error = searchErrorText(result)) }
                        }
                    }
                }
        }

        /** "Calibrate": fits a personal model on the reference nights and shows kappa before / after. */
        fun calibrate() {
            val source = source
            if (source == null || !mutableState.value.canCalibrate) return
            val nights = references
            mutableState.update { it.copy(busy = CompareBusy.CALIBRATING) }
            actionJob =
                viewModelScope.launch {
                    val outcome = source.calibrate(nights)
                    mutableState.update { it.copy(busy = null, messages = it.messages + UiMessage(calibrationText(outcome))) }
                    if (outcome is CalibrationOutcome.Calibrated) rescore(source)
                }
        }

        /** Opens the "Reset to global model" confirmation. */
        fun requestReset() {
            mutableState.update { it.copy(confirmReset = true) }
        }

        /** Closes the confirmation. */
        fun dismissReset() {
            mutableState.update { it.copy(confirmReset = false) }
        }

        /** Confirmed: deactivates the personal model. */
        fun confirmReset() {
            val source = source ?: return
            mutableState.update { it.copy(confirmReset = false, busy = CompareBusy.RESETTING) }
            actionJob =
                viewModelScope.launch {
                    source.resetToGlobal()
                    mutableState.update { it.copy(busy = null, messages = it.messages + UiMessage(UiText.of(R.string.compare_reset_done))) }
                    rescore(source)
                }
        }

        /** Removes a shown Snackbar message. */
        fun messageShown(id: Long) {
            mutableState.update { state -> state.copy(messages = state.messages.filterNot { it.id == id }) }
        }

        private suspend fun rescore(source: ReferenceSource) {
            if (references.isEmpty()) return
            mutableState.update { it.copy(busy = CompareBusy.RESCORING) }
            score(source)
            mutableState.update { it.copy(busy = null) }
        }

        private suspend fun score(source: ReferenceSource) {
            val report = source.compare(references)
            resolveLabels(report.nights.map { it.night.session.packageName }.distinct())
            mutableState.update {
                it.copy(
                    phase = ComparePhase.READY,
                    metrics = CompareMetrics.of(report),
                    nights =
                        report.nights
                            .map { score -> ReferenceNightItem.of(score, labels) }
                            .sortedByDescending { item -> item.startMs },
                )
            }
        }

        private suspend fun resolveLabels(packages: List<String>) {
            packages.filterNot { it in labels }.forEach { labels[it] = appInfo.resolve(it).label }
        }

        /** Constants. */
        companion object {
            /** Length of the default searched range ([CompareRange.RECENT]) in days. */
            const val SEARCH_DAYS: Int = 90

            private const val DAY_MS = 24L * 60 * 60 * 1000
            private const val TAG = "CompareViewModel"
        }
    }

/** The error text of a failed reference search. */
internal fun searchErrorText(result: UseCaseResult<*>): UiText =
    when (result) {
        is UseCaseResult.Blocked -> WorkResultText.blocked(result.reason)
        is UseCaseResult.Failed -> UiText.of(if (result.retryable) R.string.error_hc_transient else R.string.compare_error_generic)
        is UseCaseResult.Success, is UseCaseResult.NotEstimated, UseCaseResult.NotFound -> UiText.of(R.string.compare_error_generic)
    }

/** The Snackbar text of a calibration [outcome]. */
internal fun calibrationText(outcome: CalibrationOutcome): UiText =
    when (outcome) {
        is CalibrationOutcome.Calibrated -> {
            val result = outcome.result
            val before = result.kappaBefore
            val after = result.kappaAfter
            val trained = UiText.plural(R.plurals.compare_calibrated_nights, result.trainingNights)
            if (before != null && after != null) {
                UiText.of(
                    R.string.compare_calibrated,
                    trained,
                    formatDecimal(before, KAPPA_DIGITS),
                    formatDecimal(after, KAPPA_DIGITS),
                    UiText.plural(R.plurals.compare_held_out, result.heldOutNights),
                )
            } else {
                UiText.of(R.string.compare_calibrated_unmeasured, trained)
            }
        }

        is CalibrationOutcome.NotCalibrated -> {
            UiText.of(R.string.compare_not_calibrated)
        }

        CalibrationOutcome.PersonalizationDisabled -> {
            UiText.of(R.string.compare_personalization_off)
        }
    }

/** Fraction digits of kappa values. */
internal const val KAPPA_DIGITS = 2

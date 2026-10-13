package io.github.alien2003.pulsestages.ui.nights

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.algo.model.EpochResult
import io.github.alien2003.pulsestages.algo.model.EstimationOutcome
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NightEstimate
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.model.StageProbabilities
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.hc.pipeline.DeleteNightResult
import io.github.alien2003.pulsestages.data.hc.usecase.CsvExport
import io.github.alien2003.pulsestages.data.hc.usecase.NightInspection
import io.github.alien2003.pulsestages.data.hc.usecase.UseCaseResult
import io.github.alien2003.pulsestages.data.hc.usecase.andThen
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.platform.DocumentAccessException
import io.github.alien2003.pulsestages.platform.DocumentStore
import io.github.alien2003.pulsestages.source.NightsSource
import io.github.alien2003.pulsestages.ui.common.JobUi
import io.github.alien2003.pulsestages.ui.common.JobWatch
import io.github.alien2003.pulsestages.ui.common.RunSubject
import io.github.alien2003.pulsestages.ui.common.UiMessage
import io.github.alien2003.pulsestages.ui.common.UiText
import io.github.alien2003.pulsestages.ui.common.WorkResultText
import io.github.alien2003.pulsestages.ui.home.healthErrorText
import io.github.alien2003.pulsestages.work.schedule.WorkStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Loading state of the ledger row of the detail. */
enum class DetailLoad {
    /** Being read. */
    LOADING,

    /** No night with this clientRecordId. */
    NOT_FOUND,

    /** The ledger could not be read. */
    ERROR,

    /** [NightDetailUiState.night] is set. */
    LOADED,
}

/** The re-estimate of the night with the current settings (per-epoch posteriors, features, heart rate). */
@Immutable
sealed interface InspectionState {
    /** Health Connect is being read and the night estimated. */
    data object Loading : InspectionState

    /**
     * The re-estimate.
     *
     * @property inspection the inspection (input, outcome, current params hash).
     * @property estimate its estimate.
     */
    data class Ready(
        val inspection: NightInspection,
        val estimate: NightEstimate,
    ) : InspectionState {
        /** Epoch length of the estimate. */
        val epochMs: Long get() = estimate.params.preprocess.epochSec * MILLIS_PER_SECOND

        /** Start times of the epochs, for the time-to-epoch lookup. */
        val epochStarts: List<Long> = estimate.perEpoch.map { it.startMs }
    }

    /**
     * The night is not estimable with the current settings.
     *
     * @property inspection the inspection (it still has the input).
     * @property reason why.
     */
    data class NotEstimated(
        val inspection: NightInspection?,
        val reason: NotEstimatedReason,
    ) : InspectionState

    /**
     * Nothing could be read: Health Connect is unavailable or a permission is missing.
     *
     * @property reason why.
     */
    data class Blocked(
        val reason: BlockedReason,
    ) : InspectionState

    /**
     * Reading or estimating failed.
     *
     * @property retryable a later retry is expected to work (rate limit, transient error).
     */
    data class Failed(
        val retryable: Boolean,
    ) : InspectionState
}

/**
 * A named per-epoch feature value.
 *
 * @property name the feature name as the estimator reports it (also the CSV column).
 * @property value its value, or null when not finite.
 */
@Immutable
data class FeatureValue(
    val name: String,
    val value: Double?,
)

/**
 * The epoch the user tapped or dragged to.
 *
 * @property index epoch index.
 * @property startMs epoch start.
 * @property endMs epoch end.
 * @property estimatedStage the stage of the re-estimate.
 * @property writtenStage the stage written to Health Connect at that time, or null when nothing was written there.
 * @property posterior the four stage probabilities of the re-estimate.
 * @property hasData the epoch has heart-rate data.
 * @property hrRaw resampled heart rate, or null.
 * @property hrSmooth smoothed heart rate, or null.
 * @property features the per-epoch features.
 */
@Immutable
data class EpochSelection(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val estimatedStage: SleepStage,
    val writtenStage: SleepStage?,
    val posterior: StageProbabilities,
    val hasData: Boolean,
    val hrRaw: Double?,
    val hrSmooth: Double?,
    val features: List<FeatureValue>,
) {
    /** Mapping. */
    companion object {
        /** The selection of epoch [index] of [estimate]; [written] are the stages written to Health Connect. */
        fun of(
            estimate: NightEstimate,
            index: Int,
            epochMs: Long,
            written: List<StageInterval>,
        ): EpochSelection {
            val epoch: EpochResult = estimate.perEpoch[index]
            return EpochSelection(
                index = index,
                startMs = epoch.startMs,
                endMs = epoch.startMs + epochMs,
                estimatedStage = epoch.stage,
                writtenStage = stageAt(written, epoch.startMs + epochMs / 2),
                posterior = epoch.posterior,
                hasData = epoch.hasData,
                hrRaw = epoch.hrRaw,
                hrSmooth = epoch.hrSmooth,
                features =
                    estimate.featureNames.mapIndexed { i, name ->
                        FeatureValue(name, epoch.features.getOrNull(i)?.takeIf { it.isFinite() })
                    },
            )
        }
    }
}

/** "Compare with RULES". */
@Immutable
sealed interface ComparisonState {
    /** Not requested. */
    data object None : ComparisonState

    /** Running. */
    data object Running : ComparisonState

    /**
     * Done.
     *
     * @property primaryEstimator the estimator of the re-estimate.
     * @property primary stages of the re-estimate.
     * @property rules stages of the RULES estimator on the same input.
     * @property kappa Cohen's kappa of RULES against the re-estimate, or null when undefined.
     * @property accuracy share of 30-s chunks with the same stage, or null.
     */
    data class Ready(
        val primaryEstimator: EstimatorType,
        val primary: List<StageInterval>,
        val rules: List<StageInterval>,
        val kappa: Double?,
        val accuracy: Double?,
    ) : ComparisonState

    /**
     * Failed.
     *
     * @property message why.
     */
    data class Failed(
        val message: UiText,
    ) : ComparisonState
}

/**
 * UI state of the night detail.
 *
 * @property clientRecordId the night shown, or null before [NightDetailViewModel.show] was called.
 * @property load whether the ledger row is loaded.
 * @property night the ledger row (stages exactly as written), when loaded.
 * @property stale the night was written with other settings or another algorithm version.
 * @property synthetic the night is synthetic demo data (debug builds).
 * @property overwriteNever the overwrite policy is NEVER: a recompute does not rewrite a night that has a session.
 * @property inspection the re-estimate for the charts and the epoch details.
 * @property selection the selected epoch, or null.
 * @property comparison "Compare with RULES".
 * @property recompute the recompute job of this night.
 * @property pendingExport a CSV waiting for the user to pick its destination (the screen opens the file picker).
 * @property exportPickerOpen the file picker for [pendingExport] is open (not opened again after a rotation).
 * @property exporting the CSV is being written.
 * @property confirmDelete the "Delete my session" confirmation is open.
 * @property deleting the session is being deleted.
 * @property messages queued Snackbar messages.
 */
@Immutable
data class NightDetailUiState(
    val clientRecordId: String? = null,
    val load: DetailLoad = DetailLoad.LOADING,
    val night: EstimatedNightEntity? = null,
    val stale: Boolean = false,
    val synthetic: Boolean = false,
    val overwriteNever: Boolean = false,
    val inspection: InspectionState = InspectionState.Loading,
    val selection: EpochSelection? = null,
    val comparison: ComparisonState = ComparisonState.None,
    val recompute: JobUi = JobUi(),
    val pendingExport: CsvExport? = null,
    val exportPickerOpen: Boolean = false,
    val exporting: Boolean = false,
    val confirmDelete: Boolean = false,
    val deleting: Boolean = false,
    val messages: List<UiMessage> = emptyList(),
) {
    /** The re-estimate, when ready. */
    val ready: InspectionState.Ready? get() = inspection as? InspectionState.Ready

    /** "Recompute" would rewrite nothing: the night has a session and the overwrite policy is NEVER. */
    val recomputeBlockedByPolicy: Boolean get() = overwriteNever && night?.status == NightStatus.WRITTEN
}

/**
 * The detail of one night: the ledger row (what was written), its re-estimate with the current settings (read from
 * Health Connect, never written) for the heart-rate curve and the per-epoch posteriors, and the actions Recompute
 * (WorkManager), Compare with RULES, Export CSV (Storage Access Framework) and Delete my session.
 *
 * One instance serves the whole Nights tab (it lives in the Nights destination, see `NIGHT_DETAIL_VIEW_MODEL_KEY`):
 * the right pane of wide layouts and the detail destination of compact ones show the same night with the same state
 * when the window changes size, and [show] switches it to another night (the previous night's work is cancelled).
 * Re-estimates triggered by a changed ledger row wait while no pane shows the night ([onPaneStarted] /
 * [onPaneStopped]), so a hidden tab does not read Health Connect. The night of a pending CSV export is kept in the
 * [SavedStateHandle]: when the process died while the file picker was open, the CSV is produced again for the picked
 * document instead of leaving it empty.
 */
@Suppress("TooManyFunctions") // One function per user action of the screen.
@HiltViewModel
class NightDetailViewModel
    @Inject
    constructor(
        private val source: NightsSource,
        private val documents: DocumentStore,
        private val logger: AppLogger,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(NightDetailUiState(synthetic = source.synthetic))
        private var recomputeWatch = JobWatch()
        private var inspectedVersion: Long? = null
        private var inspectJob: Job? = null
        private var nightJob: Job? = null
        private var visiblePanes = 0
        private var inspectionDeferred = false

        /** The current UI state. */
        val state: StateFlow<NightDetailUiState> = mutableState.asStateFlow()

        init {
            viewModelScope.launch {
                source
                    .overwritePolicy()
                    .catch { failure -> logger.w(TAG, "cannot read the overwrite policy", throwable = failure) }
                    .collect { policy -> mutableState.update { it.copy(overwriteNever = policy == OverwritePolicy.NEVER) } }
            }
        }

        /**
         * Shows the night [clientRecordId]: reads its ledger row and re-estimates it. Showing the night already shown
         * changes nothing; another night replaces it (selection, comparison and confirmation are reset, a CSV export
         * in progress and queued messages are kept).
         */
        fun show(clientRecordId: String) {
            if (mutableState.value.clientRecordId == clientRecordId) return
            nightJob?.cancel()
            inspectJob?.cancel()
            inspectedVersion = null
            inspectionDeferred = false
            recomputeWatch = JobWatch()
            mutableState.update { current ->
                NightDetailUiState(
                    clientRecordId = clientRecordId,
                    synthetic = source.synthetic,
                    overwriteNever = current.overwriteNever,
                    pendingExport = current.pendingExport,
                    exportPickerOpen = current.exportPickerOpen,
                    exporting = current.exporting,
                    messages = current.messages,
                )
            }
            nightJob =
                viewModelScope.launch {
                    launch {
                        combine(source.night(clientRecordId), staleReference(source, logger)) { night, hash -> night to hash }
                            .catch { failure ->
                                logger.e(TAG, "cannot read night $clientRecordId", nightKey = clientRecordId, throwable = failure)
                                mutableState.update { it.copy(load = DetailLoad.ERROR) }
                            }.collect { (night, hash) -> onNight(night, hash) }
                    }
                    launch { source.recomputeStatus(clientRecordId).collect { status -> onRecomputeStatus(status) } }
                }
        }

        /** A pane showing the night became visible; a re-estimate that waited for it runs now. */
        fun onPaneStarted() {
            visiblePanes++
            if (inspectionDeferred) {
                inspectionDeferred = false
                inspect()
            }
        }

        /** A pane showing the night is no longer visible (stopped, or left the composition). */
        fun onPaneStopped() {
            visiblePanes = (visiblePanes - 1).coerceAtLeast(0)
        }

        /** Reads Health Connect and re-estimates the night again (after an error). */
        fun retryInspection() {
            inspect()
        }

        /** Selects the epoch at [timeMs] (tap or drag on a chart); outside the epochs the selection is kept. */
        fun selectAt(timeMs: Long) {
            val ready = mutableState.value.ready ?: return
            val index = epochIndexAt(ready.epochStarts, ready.epochMs, timeMs) ?: return
            select(ready, index)
        }

        /**
         * Moves the selection by [delta] epochs (the previous / next buttons); selects the first epoch when none is
         * selected (the "Show epoch details" button).
         */
        fun moveSelection(delta: Int) {
            val ready = mutableState.value.ready ?: return
            if (ready.estimate.perEpoch.isEmpty()) return
            val current = mutableState.value.selection?.index
            val index = if (current == null) 0 else (current + delta).coerceIn(0, ready.estimate.perEpoch.lastIndex)
            select(ready, index)
        }

        /** Clears the epoch selection. */
        fun clearSelection() {
            mutableState.update { it.copy(selection = null) }
        }

        /** "Compare with RULES" on the re-estimate's input. */
        fun compareWithRules() {
            val ready = mutableState.value.ready ?: return
            mutableState.update { it.copy(comparison = ComparisonState.Running) }
            viewModelScope.launch {
                val comparison =
                    when (val result = source.compareWithRules(ready.inspection)) {
                        is UseCaseResult.Success -> {
                            val value = result.value
                            ComparisonState.Ready(
                                primaryEstimator = value.primary.estimator,
                                primary = value.primary.stages,
                                rules = value.other.stages,
                                kappa = value.agreement.kappa,
                                accuracy = value.agreement.accuracy,
                            )
                        }

                        else -> {
                            ComparisonState.Failed(resultText(result))
                        }
                    }
                // The user may have switched to another night in the meantime.
                mutableState.update { if (it.ready === ready) it.copy(comparison = comparison) else it }
            }
        }

        /** "Export CSV": prepares the CSV; the screen then asks the user where to save it. */
        fun exportCsv() {
            val current = mutableState.value
            val ready = current.ready ?: return
            val clientRecordId = current.clientRecordId ?: return
            when (val result = source.exportCsv(ready.inspection)) {
                is UseCaseResult.Success -> {
                    savedState[KEY_EXPORT_NIGHT] = clientRecordId
                    mutableState.update { it.copy(pendingExport = result.value, exportPickerOpen = false) }
                }

                else -> {
                    message(resultText(result))
                }
            }
        }

        /**
         * The screen is about to open the file picker for the pending CSV: true exactly once per pending CSV (several
         * screens may observe it, only one opens the picker; after a rotation it is not opened again).
         */
        fun claimExportPicker(): Boolean {
            var claimed = false
            mutableState.update { current ->
                claimed = current.pendingExport != null && !current.exportPickerOpen
                if (claimed) current.copy(exportPickerOpen = true) else current
            }
            return claimed
        }

        /** The file picker could not be opened (no app handles it). */
        fun onExportPickerUnavailable() {
            savedState.remove<String>(KEY_EXPORT_NIGHT)
            mutableState.update {
                it.copy(
                    pendingExport = null,
                    exportPickerOpen = false,
                    messages = it.messages + UiMessage(UiText.of(R.string.error_no_app_for_action)),
                )
            }
        }

        /**
         * The user picked [uri] for the CSV (null: the picker was cancelled). A picked document is never left empty
         * without a message: when the prepared CSV is gone (the process was restarted while the picker was open), the
         * night is exported again; when that fails, the failure is reported.
         */
        fun onExportDestination(uri: String?) {
            val prepared = mutableState.value.pendingExport
            val nightId = savedState.get<String>(KEY_EXPORT_NIGHT) ?: mutableState.value.clientRecordId
            savedState.remove<String>(KEY_EXPORT_NIGHT)
            mutableState.update { it.copy(pendingExport = null, exportPickerOpen = false, exporting = uri != null) }
            if (uri == null) return
            viewModelScope.launch {
                val export = prepared?.let { UseCaseResult.Success(it) } ?: exportAgain(nightId)
                val text =
                    when (export) {
                        is UseCaseResult.Success -> {
                            write(uri, export.value, nightId)
                        }

                        else -> {
                            logger.w(TAG, "CSV export failed: the night could not be exported again ($export)", nightKey = nightId)
                            UiText.of(R.string.night_export_failed)
                        }
                    }
                mutableState.update { it.copy(exporting = false, messages = it.messages + UiMessage(text)) }
            }
        }

        /** "Recompute": recomputes the night in the background and reports the result. */
        fun recompute() {
            val clientRecordId = mutableState.value.clientRecordId ?: return
            recomputeWatch.request()
            message(UiText.of(R.string.night_recompute_started))
            viewModelScope.launch { source.recomputeNight(clientRecordId) }
        }

        /** Opens the "Delete my session" confirmation. */
        fun requestDelete() {
            mutableState.update { it.copy(confirmDelete = true) }
        }

        /** Closes the confirmation. */
        fun dismissDelete() {
            mutableState.update { it.copy(confirmDelete = false) }
        }

        /** Confirmed: deletes our session of this night from Health Connect. */
        fun confirmDelete() {
            val clientRecordId = mutableState.value.clientRecordId ?: return
            mutableState.update { it.copy(confirmDelete = false, deleting = true) }
            viewModelScope.launch {
                val text =
                    try {
                        when (source.deleteNight(clientRecordId)) {
                            DeleteNightResult.DELETED -> UiText.of(R.string.night_delete_done)
                            DeleteNightResult.NO_OUTPUT -> UiText.of(R.string.night_delete_no_output)
                            DeleteNightResult.NOT_FOUND -> UiText.of(R.string.night_not_found_title)
                        }
                    } catch (e: HealthDataException) {
                        logger.w(TAG, "deleting the session failed: ${e.message}", nightKey = clientRecordId, throwable = e)
                        healthErrorText(e)
                    }
                mutableState.update { it.copy(deleting = false, messages = it.messages + UiMessage(text)) }
            }
        }

        /** Removes a shown Snackbar message. */
        fun messageShown(id: Long) {
            mutableState.update { state -> state.copy(messages = state.messages.filterNot { it.id == id }) }
        }

        private fun onNight(
            night: EstimatedNightEntity?,
            hash: String?,
        ) {
            if (night == null) {
                mutableState.update { it.copy(load = DetailLoad.NOT_FOUND, night = null) }
                return
            }
            mutableState.update {
                it.copy(load = DetailLoad.LOADED, night = night, stale = hash != null && night.isStale(ALGO_VERSION, hash))
            }
            // Re-estimate when the night is first shown and whenever its row changed (recomputed, deleted, ...); a
            // change while no pane shows the night waits until one does.
            if (inspectedVersion != night.updatedAt) {
                val first = inspectedVersion == null
                inspectedVersion = night.updatedAt
                if (first || visiblePanes > 0) inspect() else inspectionDeferred = true
            }
        }

        private fun onRecomputeStatus(status: WorkStatus) {
            val finished = recomputeWatch.onStatus(status)
            val text = finished?.let { WorkResultText.of(RunSubject.RECOMPUTE_NIGHT, it) }
            mutableState.update { current ->
                val updated = current.copy(recompute = JobUi.of(status))
                if (text == null) updated else updated.copy(messages = updated.messages + UiMessage(text))
            }
        }

        private fun inspect() {
            val clientRecordId = mutableState.value.clientRecordId ?: return
            inspectJob?.cancel()
            mutableState.update { it.copy(inspection = InspectionState.Loading, selection = null, comparison = ComparisonState.None) }
            inspectJob =
                viewModelScope.launch {
                    val inspection = inspectionState(source.inspect(clientRecordId))
                    mutableState.update { it.copy(inspection = inspection) }
                }
        }

        private suspend fun exportAgain(nightId: String?): UseCaseResult<CsvExport> {
            if (nightId == null) return UseCaseResult.NotFound
            logger.i(TAG, "the prepared CSV is gone (the app was restarted); exporting the night again", nightKey = nightId)
            return source.inspect(nightId).andThen { source.exportCsv(it) }
        }

        private suspend fun write(
            uri: String,
            export: CsvExport,
            nightId: String?,
        ): UiText =
            try {
                documents.writeText(uri, export.content)
                UiText.of(R.string.night_export_done, export.fileName)
            } catch (e: DocumentAccessException) {
                logger.w(TAG, "CSV export failed: ${e.message}", nightKey = nightId, throwable = e)
                UiText.of(R.string.night_export_failed)
            }

        private fun select(
            ready: InspectionState.Ready,
            index: Int,
        ) {
            val written =
                mutableState.value.night
                    ?.stages
                    .orEmpty()
            mutableState.update { it.copy(selection = EpochSelection.of(ready.estimate, index, ready.epochMs, written)) }
        }

        private fun message(text: UiText) {
            mutableState.update { it.copy(messages = it.messages + UiMessage(text)) }
        }

        private companion object {
            const val TAG = "NightDetailViewModel"

            /** The night whose CSV waits for the file picker's result (survives process death). */
            const val KEY_EXPORT_NIGHT = "night_detail.export_night"
        }
    }

/**
 * ViewModel key of the one [NightDetailViewModel] of the Nights tab, in the ViewModelStore of the Nights destination
 * (the wide pane and the detail destination share it).
 */
const val NIGHT_DETAIL_VIEW_MODEL_KEY: String = "night-detail"

/** The inspection state of a use-case [result]. */
internal fun inspectionState(result: UseCaseResult<NightInspection>): InspectionState =
    when (result) {
        is UseCaseResult.Success -> {
            val estimate = result.value.estimate
            val outcome = result.value.outcome
            when {
                estimate != null -> {
                    InspectionState.Ready(result.value, estimate)
                }

                outcome is EstimationOutcome.NotEstimated -> {
                    InspectionState.NotEstimated(result.value, outcome.reason)
                }

                else -> {
                    InspectionState.Failed(retryable = false)
                }
            }
        }

        is UseCaseResult.NotEstimated -> {
            InspectionState.NotEstimated(null, result.outcome.reason)
        }

        is UseCaseResult.Blocked -> {
            InspectionState.Blocked(result.reason)
        }

        is UseCaseResult.Failed -> {
            InspectionState.Failed(result.retryable)
        }

        UseCaseResult.NotFound -> {
            InspectionState.Failed(retryable = false)
        }
    }

/** Snackbar / inline text of a use-case result that is not a success. */
internal fun resultText(result: UseCaseResult<*>): UiText =
    when (result) {
        is UseCaseResult.Success -> UiText.of(R.string.result_done)
        is UseCaseResult.NotEstimated -> UiText.of(result.outcome.reason.labelRes())
        is UseCaseResult.Blocked -> WorkResultText.blocked(result.reason)
        is UseCaseResult.Failed -> UiText.of(if (result.retryable) R.string.error_hc_transient else R.string.result_failed)
        UseCaseResult.NotFound -> UiText.of(R.string.night_not_found_title)
    }

private const val MILLIS_PER_SECOND = 1_000L

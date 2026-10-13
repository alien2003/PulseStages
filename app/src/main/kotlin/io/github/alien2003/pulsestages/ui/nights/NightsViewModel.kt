package io.github.alien2003.pulsestages.ui.nights

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.NightSummary
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.source.NightsSource
import io.github.alien2003.pulsestages.source.ScreenSources
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * One night of the list.
 *
 * @property summary the ledger row.
 * @property stale the night was written with another algorithm version or params hash than the current settings
 *   (offered for recomputation, never recomputed silently).
 */
@Immutable
data class NightListItem(
    val summary: NightSummary,
    val stale: Boolean,
) {
    /** The night's clientRecordId (list key and detail argument). */
    val clientRecordId: String get() = summary.clientRecordId

    /** The night has written stages to show (shares, confidence, coverage). */
    val estimated: Boolean get() = summary.status == NightStatus.WRITTEN

    /** The night has no output because of a problem (its status and reason are shown). */
    val problem: Boolean get() = summary.status == NightStatus.NOT_ESTIMATED || summary.status == NightStatus.FAILED

    /** Mapping. */
    companion object {
        /** List items of [summaries]; stale is judged against [currentParamsHash] (never stale while it is unknown). */
        fun of(
            summaries: List<NightSummary>,
            currentParamsHash: String?,
        ): List<NightListItem> =
            summaries.map { summary ->
                NightListItem(summary, stale = currentParamsHash != null && summary.isStale(ALGO_VERSION, currentParamsHash))
            }
    }
}

/** UI state of the Nights list. */
@Immutable
sealed interface NightsUiState {
    /** The ledger is being read. */
    data object Loading : NightsUiState

    /** The ledger could not be read. */
    data object Error : NightsUiState

    /**
     * The nights, newest first (empty when nothing was processed yet).
     *
     * @property nights the list items.
     * @property synthetic the nights are synthetic demo data (debug builds).
     */
    data class Loaded(
        val nights: List<NightListItem>,
        val synthetic: Boolean = false,
    ) : NightsUiState
}

/**
 * Observes the nights for the Nights list, with the stale marker judged against the current settings, and keeps the
 * selected night (in the [SavedStateHandle], so it survives rotation, folding and process death).
 *
 * The nights come from the current source of [ScreenSources]: when it is replaced (debug builds: demo mode switched),
 * the list shows the loading state until the new source's nights are read (when the tab only resumes with the same
 * data, it keeps showing its nights meanwhile). A selected night that the new list does not have is not shown: a wide
 * window then shows the newest night, and switching back selects it again.
 *
 * The selection follows the window size: in a wide window the selected night is shown in the right pane; when the
 * window becomes compact while it is shown there, it is opened as its own destination ([takeNightToOpen]); when the
 * detail destination becomes wide, it returns to the list with that night selected ([onDetailWidened]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NightsViewModel
    @Inject
    constructor(
        sources: ScreenSources,
        logger: AppLogger,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        /** `synthetic` of the list shown last, or null before one was shown. */
        private var shownSynthetic: Boolean? = null

        /** The current UI state. */
        val state: StateFlow<NightsUiState> =
            sources.nights
                .flatMapLatest { source ->
                    val replaced = shownSynthetic.let { it != null && it != source.synthetic }
                    nightsState(source, logger, loadingFirst = replaced)
                }.onEach { state -> if (state is NightsUiState.Loaded) shownSynthetic = state.synthetic }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NightsUiState.Loading)

        /** The night the user selected last, or null (a wide window then shows the newest night). */
        val selectedId: StateFlow<String?> = savedState.getStateFlow(KEY_SELECTED, null)

        /**
         * The user selected [clientRecordId]: in the right pane of a wide window ([inPane] true; it is opened as its
         * own destination when the window becomes compact) or as the detail destination of a compact window.
         */
        fun select(
            clientRecordId: String,
            inPane: Boolean,
        ) {
            savedState[KEY_SELECTED] = clientRecordId
            savedState[KEY_OPEN_WHEN_COMPACT] = inPane
        }

        /** The detail destination of [clientRecordId] became wide: the list shows it in its right pane. */
        fun onDetailWidened(clientRecordId: String) {
            select(clientRecordId, inPane = true)
        }

        /**
         * The window became compact: the night that was shown in the right pane, to be opened as its own destination
         * (at most once), or null when the user was not looking at a selected night.
         */
        fun takeNightToOpen(): String? {
            val open = savedState.get<Boolean>(KEY_OPEN_WHEN_COMPACT) == true
            savedState[KEY_OPEN_WHEN_COMPACT] = false
            return selectedId.value.takeIf { open }
        }

        private companion object {
            const val KEY_SELECTED = "nights.selected"
            const val KEY_OPEN_WHEN_COMPACT = "nights.open_when_compact"
        }
    }

/**
 * The list of [source], an error when its nights cannot be read; [loadingFirst]: the loading state until they are
 * (the source replaced the one of the list shown).
 */
private fun nightsState(
    source: NightsSource,
    logger: AppLogger,
    loadingFirst: Boolean,
): Flow<NightsUiState> {
    val loaded =
        combine(source.nights(), staleReference(source, logger)) { nights, hash ->
            NightsUiState.Loaded(NightListItem.of(nights, hash), source.synthetic) as NightsUiState
        }
    return (if (loadingFirst) loaded.onStart { emit(NightsUiState.Loading) } else loaded)
        .catch { failure ->
            logger.e(NIGHTS_TAG, "cannot read the nights", throwable = failure)
            emit(NightsUiState.Error)
        }
}

private const val NIGHTS_TAG = "NightsViewModel"

/**
 * The current params hash for the stale markers: null until it is known; a failure only loses the markers (logged),
 * never the list.
 */
internal fun staleReference(
    source: NightsSource,
    logger: AppLogger,
): Flow<String?> =
    source
        .currentParamsHash()
        .map<String, String?> { it }
        .onStart { emit(null) }
        .catch { failure -> logger.w("NightsSource", "stale markers unavailable", throwable = failure) }

/** Keep upstream flows for this long after the last collector stops (survives configuration changes). */
internal const val STOP_TIMEOUT_MS: Long = 5_000

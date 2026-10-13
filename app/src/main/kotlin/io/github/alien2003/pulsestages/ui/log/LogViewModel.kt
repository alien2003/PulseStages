package io.github.alien2003.pulsestages.ui.log

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.local.db.LogDao
import io.github.alien2003.pulsestages.data.local.db.LogEntryEntity
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.platform.DocumentAccessException
import io.github.alien2003.pulsestages.platform.ShareableFileWriter
import io.github.alien2003.pulsestages.ui.common.UiMessage
import io.github.alien2003.pulsestages.ui.common.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

/**
 * UI state of the Log screen.
 *
 * @property loading the entries are being read.
 * @property failed the log could not be read.
 * @property levels the levels shown (filter chips).
 * @property entries the newest entries with one of [levels], newest first (at most [LogViewModel.DISPLAY_LIMIT]).
 * @property sharing a share file is being written.
 * @property shareUri content uri of a written share file waiting for the share sheet, or null.
 * @property messages queued Snackbar messages.
 */
@Immutable
data class LogUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val levels: Set<LogLevel> = LogLevel.entries.toSet(),
    val entries: List<LogEntryEntity> = emptyList(),
    val sharing: Boolean = false,
    val shareUri: String? = null,
    val messages: List<UiMessage> = emptyList(),
)

/** The internal log: level filter and sharing of the filtered log as a text file. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LogViewModel
    @Inject
    constructor(
        private val logDao: LogDao,
        private val files: ShareableFileWriter,
        private val logger: AppLogger,
        private val clock: Clock,
        private val zone: ZoneId,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(LogUiState())
        private val levels = MutableStateFlow(LogLevel.entries.toSet())

        /** The current UI state. */
        val state: StateFlow<LogUiState> = mutableState.asStateFlow()

        init {
            viewModelScope.launch {
                levels
                    .flatMapLatest { selected ->
                        when {
                            selected.isEmpty() -> flowOf(emptyList())
                            selected.size == LogLevel.entries.size -> logDao.observeRecent(DISPLAY_LIMIT)
                            else -> logDao.observeRecentWithLevels(selected.sortedBy { it.ordinal }, DISPLAY_LIMIT)
                        }.map { selected to it }
                    }.catch { failure ->
                        logger.e(TAG, "cannot read the log", throwable = failure)
                        mutableState.update { it.copy(loading = false, failed = true) }
                    }.collect { (selected, entries) ->
                        mutableState.update { it.copy(loading = false, failed = false, levels = selected, entries = entries) }
                    }
            }
        }

        /** Shows or hides the entries of [level]. */
        fun toggleLevel(level: LogLevel) {
            levels.update { if (level in it) it - level else it + level }
        }

        /** Writes every stored entry of the selected levels to a text file and asks the UI to open the share sheet. */
        fun share() {
            if (mutableState.value.sharing) return
            val selected = levels.value
            viewModelScope.launch {
                mutableState.update { it.copy(sharing = true) }
                val entries = logDao.getAll().filter { it.level in selected }
                if (entries.isEmpty()) {
                    mutableState.update {
                        it.copy(
                            sharing = false,
                            messages = it.messages + UiMessage(UiText.of(R.string.log_share_empty)),
                        )
                    }
                    return@launch
                }
                val text = LogText.document(entries, selected, clock.millis(), zone)
                try {
                    val uri = files.write(SHARE_FILE_NAME, text)
                    mutableState.update { it.copy(sharing = false, shareUri = uri) }
                } catch (e: DocumentAccessException) {
                    logger.w(TAG, "cannot write the log file to share", throwable = e)
                    mutableState.update {
                        it.copy(
                            sharing = false,
                            messages = it.messages + UiMessage(UiText.of(R.string.log_share_failed)),
                        )
                    }
                }
            }
        }

        /** The share sheet was opened ([started]) or could not be opened. */
        fun shareHandled(started: Boolean) {
            mutableState.update {
                it.copy(
                    shareUri = null,
                    messages = if (started) it.messages else it.messages + UiMessage(UiText.of(R.string.error_no_app_for_action)),
                )
            }
        }

        /** Removes a shown Snackbar message. */
        fun messageShown(id: Long) {
            mutableState.update { state -> state.copy(messages = state.messages.filterNot { it.id == id }) }
        }

        /** Constants. */
        companion object {
            /** Entries shown on screen (the share file contains every stored entry of the selected levels). */
            const val DISPLAY_LIMIT: Int = 2_000

            /** Name of the shared file. */
            const val SHARE_FILE_NAME: String = "pulsestages-log.txt"

            private const val TAG = "LogScreen"
        }
    }

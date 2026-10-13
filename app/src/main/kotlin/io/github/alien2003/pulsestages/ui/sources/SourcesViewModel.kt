package io.github.alien2003.pulsestages.ui.sources

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.di.OwnPackageName
import io.github.alien2003.pulsestages.data.hc.pipeline.SourceEntry
import io.github.alien2003.pulsestages.data.hc.pipeline.SourceScanner
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.data.local.settings.SettingsUpdateResult
import io.github.alien2003.pulsestages.data.local.settings.SettingsValidator
import io.github.alien2003.pulsestages.platform.AppInfoResolver
import io.github.alien2003.pulsestages.ui.common.UiMessage
import io.github.alien2003.pulsestages.ui.common.UiText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One row of the Sources screen.
 *
 * @property packageName the data origin.
 * @property label app label from PackageManager, or null when the app is not visible.
 * @property icon app icon, or null.
 * @property sessionCount sleep sessions it wrote in the scanned range, or null when Health Connect could not be
 *   scanned (the row comes from the stored settings only).
 * @property lastSessionEndMs end of its latest session in the range, or null.
 * @property enabled it is an enabled sleep source.
 * @property manual it was added by package name.
 * @property isOwn it is PulseStages itself (never a source).
 */
@Immutable
data class SourceItem(
    val packageName: String,
    val label: String?,
    val icon: ImageBitmap?,
    val sessionCount: Int?,
    val lastSessionEndMs: Long?,
    val enabled: Boolean,
    val manual: Boolean,
    val isOwn: Boolean,
) {
    /** A manually added package without known sessions: it can be removed from the list. */
    val removable: Boolean get() = manual && !isOwn && (sessionCount ?: 0) == 0
}

/** Why the sources could not be scanned. */
enum class SourcesError {
    /** Health Connect is not available. */
    UNAVAILABLE,

    /** Reading sleep sessions is not permitted. */
    PERMISSION,

    /** Another Health Connect error (quota, transient, ...). */
    FAILED,
}

/**
 * UI state of the Sources screen.
 *
 * @property loading the scan is running.
 * @property error why the scan failed, or null. The configured sources are still shown (from the stored settings,
 *   without session counts) and stay editable.
 * @property items PulseStages' own package last, every other source in priority order (highest first).
 * @property fullHistory the whole history was scanned (READ_HEALTH_DATA_HISTORY granted).
 * @property scannedDays days scanned when [fullHistory] is false.
 * @property hrSources where heart rate and steps are read from.
 * @property manualInput the text of the "add package" field.
 * @property manualError why the typed package cannot be added, or null.
 * @property messages queued Snackbar messages.
 */
@Immutable
data class SourcesUiState(
    val loading: Boolean = true,
    val error: SourcesError? = null,
    val items: List<SourceItem> = emptyList(),
    val fullHistory: Boolean = false,
    val scannedDays: Long = SourceScanner.RECENT_DAYS,
    val hrSources: HrSourcesMode = HrSourcesMode.SAME_AS_SLEEP,
    val manualInput: String = "",
    val manualError: UiText? = null,
    val messages: List<UiMessage> = emptyList(),
) {
    /** The rows that can be enabled and reordered (everything except PulseStages itself). */
    val movable: List<SourceItem> get() = items.filterNot { it.isOwn }

    /** The rows come from a successful scan (they have session counts). */
    val scanned: Boolean get() = error == null
}

/**
 * Sleep sources: scans Health Connect with [SourceScanner], lets the user enable sources, reorder their priority, add
 * packages by name and choose where heart rate and steps come from. The order shown is the order stored in
 * `sourcePriority`; every change stores the three source lists from the rows shown. When the scan fails (Health
 * Connect unavailable, permission missing, quota, ...), the configured sources are listed from the settings, so they
 * can still be edited, and the error is shown above them.
 */
@HiltViewModel
class SourcesViewModel
    @Inject
    constructor(
        private val scanner: SourceScanner,
        private val settingsRepository: SettingsRepository,
        private val appInfo: AppInfoResolver,
        private val logger: AppLogger,
        @param:OwnPackageName private val ownPackage: String,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(SourcesUiState())

        /** The current UI state. */
        val state: StateFlow<SourcesUiState> = mutableState.asStateFlow()

        init {
            viewModelScope.launch {
                settingsRepository.settings
                    .map { it.sources.hrSources }
                    .distinctUntilChanged()
                    .catch { failure -> logger.e(TAG, "cannot read the settings", throwable = failure) }
                    .collect { mode -> mutableState.update { it.copy(hrSources = mode) } }
            }
            refresh()
        }

        /** Scans Health Connect again. */
        fun refresh() {
            viewModelScope.launch {
                // A previous error stays shown (with the configured sources) until this scan succeeds.
                mutableState.update { it.copy(loading = true) }
                try {
                    val result = scanner.scan()
                    val items = withAppInfo(result.sources, scanned = true)
                    mutableState.update {
                        it.copy(
                            loading = false,
                            error = null,
                            items = items.filterNot { item -> item.isOwn } + items.filter { item -> item.isOwn },
                            fullHistory = result.fullHistory,
                        )
                    }
                } catch (e: HealthDataException) {
                    logger.w(TAG, "source scan failed; showing the configured sources", throwable = e)
                    val error =
                        when (e) {
                            is HealthDataException.Unavailable -> SourcesError.UNAVAILABLE
                            is HealthDataException.PermissionDenied -> SourcesError.PERMISSION
                            else -> SourcesError.FAILED
                        }
                    val configured = withAppInfo(scanner.configured(), scanned = false)
                    mutableState.update { it.copy(loading = false, error = error, items = configured, fullHistory = false) }
                }
            }
        }

        /** Enables or disables [packageName] as a sleep source. */
        fun setEnabled(
            packageName: String,
            enabled: Boolean,
        ) {
            val items = mutableState.value.items.map { if (it.packageName == packageName && !it.isOwn) it.copy(enabled = enabled) else it }
            val message =
                UiMessage(UiText.of(R.string.sources_none_enabled)).takeIf { items.none { item -> item.enabled && !item.isOwn } }
            persist(items, message)
        }

        /** Moves [packageName] one place up in the priority order. */
        fun moveUp(packageName: String) = move(packageName, -1)

        /** Moves [packageName] one place down in the priority order. */
        fun moveDown(packageName: String) = move(packageName, +1)

        /** Updates the text of the "add package" field. */
        fun onManualInput(text: String) {
            mutableState.update { it.copy(manualInput = text, manualError = null) }
        }

        /** Validates the typed package name and adds it as an enabled, manually added source. */
        fun addManual() {
            val current = mutableState.value
            val name = current.manualInput.trim()
            val error =
                when {
                    name.isEmpty() -> R.string.sources_manual_error_blank
                    !SettingsValidator.packageNameRegex.matches(name) -> R.string.sources_manual_error_invalid
                    name == ownPackage -> R.string.sources_manual_error_own
                    current.items.any { it.packageName == name } -> R.string.sources_manual_error_duplicate
                    else -> null
                }
            if (error != null) {
                mutableState.update { it.copy(manualError = UiText.of(error)) }
                return
            }
            viewModelScope.launch {
                val info = appInfo.resolve(name)
                val state = mutableState.value
                val sessions = if (state.scanned) 0 else null
                val added = SourceItem(name, info.label, info.icon, sessions, null, enabled = true, manual = true, isOwn = false)
                mutableState.update { it.copy(manualInput = "", manualError = null) }
                persist(state.movable + added + state.items.filter { it.isOwn }, UiMessage(UiText.of(R.string.sources_added, name)))
            }
        }

        /** Removes a manually added package that has no sessions (or whose sessions are unknown: no scan). */
        fun removeManual(packageName: String) {
            val items = mutableState.value.items
            val remaining = items.filterNot { it.packageName == packageName && it.removable }
            if (remaining.size != items.size) persist(remaining, UiMessage(UiText.of(R.string.sources_removed, packageName)))
        }

        /** Reads heart rate and steps from any app ([any]) or only from the sleep session's app. */
        fun setHrSourcesAny(any: Boolean) {
            val mode = if (any) HrSourcesMode.ANY else HrSourcesMode.SAME_AS_SLEEP
            mutableState.update { it.copy(hrSources = mode) }
            viewModelScope.launch {
                val result = settingsRepository.update { s -> s.copy(sources = s.sources.copy(hrSources = mode)) }
                if (result is SettingsUpdateResult.Rejected) addMessage(UiText.of(R.string.sources_save_failed))
            }
        }

        /** Removes a shown Snackbar message. */
        fun messageShown(id: Long) {
            mutableState.update { state -> state.copy(messages = state.messages.filterNot { it.id == id }) }
        }

        private fun move(
            packageName: String,
            delta: Int,
        ) {
            val state = mutableState.value
            val movable = state.movable.toMutableList()
            val index = movable.indexOfFirst { it.packageName == packageName }
            val target = index + delta
            if (index < 0 || target !in movable.indices) return
            movable.add(target, movable.removeAt(index))
            persist(movable + state.items.filter { it.isOwn }, null)
        }

        /** Shows [items] and stores the source lists they describe. */
        private fun persist(
            items: List<SourceItem>,
            message: UiMessage?,
        ) {
            mutableState.update { it.copy(items = items, messages = it.messages + listOfNotNull(message)) }
            val movable = items.filterNot { it.isOwn }
            viewModelScope.launch {
                val result =
                    settingsRepository.update { s ->
                        s.copy(
                            sources =
                                s.sources.copy(
                                    sourcePriority = movable.map { it.packageName },
                                    sourcePackages = movable.filter { it.enabled }.map { it.packageName },
                                    manualPackages = movable.filter { it.manual }.map { it.packageName },
                                ),
                        )
                    }
                if (result is SettingsUpdateResult.Rejected) {
                    logger.w(TAG, "source settings rejected: ${result.errors.joinToString("; ") { it.message }}")
                    addMessage(UiText.of(R.string.sources_save_failed))
                    refresh()
                }
            }
        }

        private fun addMessage(text: UiText) {
            mutableState.update { it.copy(messages = it.messages + UiMessage(text)) }
        }

        /** The rows of [entries]; without a successful scan ([scanned] false) their session counts are unknown. */
        private suspend fun withAppInfo(
            entries: List<SourceEntry>,
            scanned: Boolean,
        ): List<SourceItem> =
            coroutineScope {
                entries
                    .map { entry -> async { entry to appInfo.resolve(entry.packageName) } }
                    .awaitAll()
                    .map { (entry, info) ->
                        SourceItem(
                            packageName = entry.packageName,
                            label = info.label,
                            icon = info.icon,
                            sessionCount = entry.sessionCount.takeIf { scanned },
                            lastSessionEndMs = entry.lastSessionEndMs,
                            enabled = entry.enabled,
                            manual = entry.manual,
                            isOwn = entry.isOwn,
                        )
                    }
            }

        private companion object {
            const val TAG = "Sources"
        }
    }

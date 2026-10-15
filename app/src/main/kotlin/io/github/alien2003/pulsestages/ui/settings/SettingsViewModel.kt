package io.github.alien2003.pulsestages.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.params.EmissionTable
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsError
import io.github.alien2003.pulsestages.data.local.settings.SettingsImportResult
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.data.local.settings.SettingsUpdateResult
import io.github.alien2003.pulsestages.platform.DocumentAccessException
import io.github.alien2003.pulsestages.platform.DocumentStore
import io.github.alien2003.pulsestages.source.StaleNights
import io.github.alien2003.pulsestages.source.StaleNightsSource
import io.github.alien2003.pulsestages.ui.common.UiMessage
import io.github.alien2003.pulsestages.ui.common.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state of the Settings screen.
 *
 * @property loading the stored settings have not been read yet.
 * @property loadFailed the settings store could not be read (defaults are shown).
 * @property settings the current settings (stored, or just applied).
 * @property drafts text typed into number / text fields, by path (kept while it is being edited or is invalid).
 * @property problems why the last edit of a path was not stored, by edited path.
 * @property confirmReset the "Reset to defaults" confirmation is open.
 * @property importErrors problems of a rejected import (dialog), or null.
 * @property busy an export or import is running.
 * @property revision incremented whenever the settings are replaced as a whole (reset, import, emission reset), so that
 *   text fields drop what was typed into them.
 * @property messages queued Snackbar messages.
 */
@Immutable
data class SettingsUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val drafts: Map<String, String> = emptyMap(),
    val problems: Map<String, List<FieldProblem>> = emptyMap(),
    val confirmReset: Boolean = false,
    val importErrors: List<SettingsError>? = null,
    val busy: Boolean = false,
    val revision: Int = 0,
    val messages: List<UiMessage> = emptyList(),
) {
    /** Text shown in the field of [spec]: the draft, or the stored value. */
    fun textOf(spec: SettingSpec): String =
        drafts[spec.path] ?: when (spec) {
            is IntSpec -> spec.read(settings).toString()
            is DoubleSpec -> formatSettingNumber(spec.read(settings))
            is OptionalDoubleSpec -> spec.read(settings)?.let(::formatSettingNumber).orEmpty()
            is OptionalIntSpec -> spec.read(settings)?.toString().orEmpty()
            is TextSpec -> spec.read(settings).orEmpty()
            else -> ""
        }
}

/** An edit of one setting from the Settings screen. */
sealed interface SettingsEdit {
    /** JSON path of the edited setting. */
    val path: String

    /**
     * Text typed into a number or text field.
     *
     * @property text the field's text.
     */
    data class Text(
        override val path: String,
        val text: String,
    ) : SettingsEdit

    /**
     * A Switch was toggled.
     *
     * @property value the new state.
     */
    data class Switch(
        override val path: String,
        val value: Boolean,
    ) : SettingsEdit

    /**
     * An option was chosen.
     *
     * @property key the option's key.
     */
    data class Choice(
        override val path: String,
        val key: String,
    ) : SettingsEdit

    /**
     * A whole-number slider was released.
     *
     * @property value the new value.
     */
    data class IntValue(
        override val path: String,
        val value: Int,
    ) : SettingsEdit

    /**
     * A decimal slider was released.
     *
     * @property value the new value.
     */
    data class DoubleValue(
        override val path: String,
        val value: Double,
    ) : SettingsEdit

    /**
     * A range slider was released.
     *
     * @property min the new lower bound.
     * @property max the new upper bound.
     */
    data class Range(
        override val path: String,
        val min: Double,
        val max: Double,
    ) : SettingsEdit

    /**
     * A time was picked.
     *
     * @property minutes minutes after midnight.
     */
    data class Time(
        override val path: String,
        val minutes: Int,
    ) : SettingsEdit
}

/**
 * Edits, validates, resets, exports and imports [AppSettings]. Every edit is validated with [SettingsChecks] before it
 * is stored; an invalid value stays in its field with the error below it and is never stored. When a stored change
 * makes estimated nights stale (it changed the params hash: algorithm parameters, the estimator, the output options
 * awake-as-light and Sleep as Android compatibility), a
 * Snackbar says how many and offers to recompute them; nothing is recomputed without that tap.
 */
@Suppress("TooManyFunctions") // One function per user action of the screen.
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val repository: SettingsRepository,
        private val documents: DocumentStore,
        private val logger: AppLogger,
        private val staleNights: StaleNightsSource,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(SettingsUiState())
        private var lastParamsHash: String? = null
        private var storedSinceHashChange = false
        private var stalePromptId: Long? = null

        /** The current UI state. */
        val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

        init {
            viewModelScope.launch {
                repository.settings
                    .catch { failure ->
                        logger.e(TAG, "cannot read the settings", throwable = failure)
                        mutableState.update { it.copy(loading = false, loadFailed = true) }
                    }.collect { settings -> mutableState.update { it.copy(loading = false, loadFailed = false, settings = settings) } }
            }
            viewModelScope.launch {
                staleNights
                    .staleNights()
                    .catch { failure -> logger.w(TAG, "cannot count the stale nights", throwable = failure) }
                    .collect { onStaleNights(it) }
            }
        }

        /** The action of the Snackbar message [id] was tapped. */
        fun onMessageAction(id: Long) {
            if (id != stalePromptId) return
            stalePromptId = null
            staleNights.recomputeStale()
            logger.i(TAG, "recompute of the stale nights requested from the settings")
            mutableState.update { it.withMessage(R.string.home_recompute_stale_started) }
        }

        /** Applies an edit of one setting. */
        fun onEdit(edit: SettingsEdit) {
            val spec = SettingsCatalog.spec(edit.path) ?: return
            if (edit is SettingsEdit.Text) {
                onText(spec, edit.text)
            } else {
                transformFor(edit, spec)?.let { apply(spec, it) }
            }
        }

        /** Opens the "Reset to defaults" confirmation. */
        fun requestReset() {
            mutableState.update { it.copy(confirmReset = true) }
        }

        /** Closes the confirmation without resetting. */
        fun dismissReset() {
            mutableState.update { it.copy(confirmReset = false) }
        }

        /** Resets every setting to its default. */
        fun confirmReset() {
            mutableState.update { it.copy(confirmReset = false) }
            storedSinceHashChange = true
            viewModelScope.launch {
                repository.reset()
                logger.i(TAG, "settings reset to defaults")
                mutableState.update {
                    it
                        .copy(settings = AppSettings(), drafts = emptyMap(), problems = emptyMap(), revision = it.revision + 1)
                        .withMessage(R.string.settings_reset_done)
                }
            }
        }

        /** Replaces the HMM emission table with the physiology table of the specification. */
        fun resetEmissions() {
            val paths = emissionPaths()
            storedSinceHashChange = true
            viewModelScope.launch {
                when (val result = repository.update { s -> s.hmm { it.copy(emissions = EmissionTable.PHYSIOLOGY) } }) {
                    is SettingsUpdateResult.Applied -> {
                        mutableState.update {
                            it
                                .copy(
                                    settings = result.settings,
                                    drafts = it.drafts - paths,
                                    problems = it.problems - paths,
                                    revision = it.revision + 1,
                                ).withMessage(R.string.settings_emissions_reset_done)
                        }
                    }

                    is SettingsUpdateResult.Rejected -> {
                        mutableState.update { it.copy(importErrors = result.errors).withMessage(R.string.settings_emissions_reset_failed) }
                    }
                }
            }
        }

        /** Writes the current settings as JSON to the document [uri] picked with `CreateDocument`. */
        fun exportTo(uri: String) {
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true) }
                val text =
                    try {
                        documents.writeText(uri, repository.exportJson(repository.settings.first()))
                        logger.i(TAG, "settings exported")
                        R.string.settings_export_done
                    } catch (e: DocumentAccessException) {
                        logger.w(TAG, "settings export failed", throwable = e)
                        R.string.settings_export_failed
                    }
                mutableState.update { it.copy(busy = false).withMessage(text) }
            }
        }

        /** Reads, validates and (when valid) applies the settings JSON of the document [uri] picked with `OpenDocument`. */
        fun importFrom(uri: String) {
            viewModelScope.launch {
                mutableState.update { it.copy(busy = true) }
                val json =
                    try {
                        documents.readText(uri, MAX_IMPORT_BYTES)
                    } catch (e: DocumentAccessException) {
                        logger.w(TAG, "settings import: cannot read the file", throwable = e)
                        mutableState.update { it.copy(busy = false).withMessage(R.string.settings_import_read_failed) }
                        return@launch
                    }
                applyImport(json)
            }
        }

        /** Reports that no app can create or open documents (the Storage Access Framework picker is missing). */
        fun onDocumentPickerUnavailable() {
            mutableState.update { it.withMessage(R.string.error_no_app_for_action) }
        }

        /** Closes the import error dialog. */
        fun dismissImportErrors() {
            mutableState.update { it.copy(importErrors = null) }
        }

        /** Removes a shown Snackbar message. */
        fun messageShown(id: Long) {
            mutableState.update { state -> state.copy(messages = state.messages.filterNot { it.id == id }) }
        }

        private suspend fun applyImport(json: String) {
            val errors: List<SettingsError> =
                when (val parsed = repository.importJson(json)) {
                    is SettingsImportResult.Invalid -> {
                        parsed.errors
                    }

                    is SettingsImportResult.Success -> {
                        SettingsChecks.validate(parsed.settings).ifEmpty {
                            storedSinceHashChange = true
                            when (val stored = repository.update { parsed.settings }) {
                                is SettingsUpdateResult.Applied -> {
                                    logger.i(TAG, "settings imported")
                                    mutableState.update {
                                        it
                                            .copy(
                                                settings = stored.settings,
                                                drafts = emptyMap(),
                                                problems = emptyMap(),
                                                busy = false,
                                                revision = it.revision + 1,
                                            ).withMessage(R.string.settings_import_done)
                                    }
                                    return
                                }

                                is SettingsUpdateResult.Rejected -> {
                                    stored.errors
                                }
                            }
                        }
                    }
                }
            logger.w(TAG, "settings import rejected: ${errors.joinToString("; ") { it.message }}")
            mutableState.update { it.copy(busy = false, importErrors = errors).withMessage(R.string.settings_import_rejected) }
        }

        private fun onText(
            spec: SettingSpec,
            text: String,
        ) {
            mutableState.update { it.copy(drafts = it.drafts + (spec.path to text)) }
            when (spec) {
                is IntSpec -> {
                    val value = text.trim().toIntOrNull()
                    if (value ==
                        null
                    ) {
                        setProblems(spec, listOf(FieldProblem.NotANumber(integer = true)))
                    } else {
                        apply(spec) { spec.write(it, value) }
                    }
                }

                is DoubleSpec -> {
                    val value = parseSettingNumber(text)
                    if (value ==
                        null
                    ) {
                        setProblems(spec, listOf(FieldProblem.NotANumber(integer = false)))
                    } else {
                        apply(spec) { spec.write(it, value) }
                    }
                }

                is OptionalDoubleSpec -> {
                    onOptionalNumber(spec, text)
                }

                is OptionalIntSpec -> {
                    onOptionalInt(spec, text)
                }

                is TextSpec -> {
                    apply(spec) { spec.write(it, text.takeIf { value -> value.isNotBlank() }) }
                }

                is SwitchSpec, is ChoiceSpec, is TimeSpec, is RangeSpec -> {
                    return
                }
            }
        }

        /** An optional number: empty text stores null (the night's value), anything else must be a number. */
        private fun onOptionalNumber(
            spec: OptionalDoubleSpec,
            text: String,
        ) {
            val value = parseSettingNumber(text)
            when {
                text.isBlank() -> apply(spec) { spec.write(it, null) }
                value == null -> setProblems(spec, listOf(FieldProblem.NotANumber(integer = false)))
                else -> apply(spec) { spec.write(it, value) }
            }
        }

        /** An optional whole number: empty text stores null (the night's value), anything else must be a whole number. */
        private fun onOptionalInt(
            spec: OptionalIntSpec,
            text: String,
        ) {
            val value = text.trim().toIntOrNull()
            when {
                text.isBlank() -> apply(spec) { spec.write(it, null) }
                value == null -> setProblems(spec, listOf(FieldProblem.NotANumber(integer = true)))
                else -> apply(spec) { spec.write(it, value) }
            }
        }

        /**
         * Validates the result of [transform] on the current settings; stores it when it is valid (the store applies
         * [transform] again atomically to what is stored), otherwise records the problems under [spec].
         */
        private fun apply(
            spec: SettingSpec,
            transform: (AppSettings) -> AppSettings,
        ) {
            val candidate = transform(mutableState.value.settings)
            val errors = SettingsChecks.validate(candidate)
            if (errors.isNotEmpty()) {
                setProblems(spec, errors.map { FieldProblem.Invalid(it) })
                return
            }
            // Problems of other non-text controls described rejected attempts; those controls show the stored value again.
            mutableState.update { state ->
                state.copy(
                    settings = candidate,
                    problems = state.problems.filterKeys { it != spec.path && it in state.drafts },
                )
            }
            // Set before storing: the new params hash may arrive before update() returns.
            storedSinceHashChange = true
            viewModelScope.launch {
                val result = repository.update(transform)
                if (result is SettingsUpdateResult.Rejected) {
                    val stored = repository.settings.first()
                    mutableState.update { it.copy(settings = stored) }
                    setProblems(spec, result.errors.map { FieldProblem.Invalid(it) })
                }
            }
        }

        /**
         * A new params hash after a change stored here: offers to recompute the nights it made stale (replacing an
         * earlier offer); when no night is stale (any more, e.g. the change was undone), the offer is withdrawn.
         */
        private fun onStaleNights(stale: StaleNights) {
            val previous = lastParamsHash
            lastParamsHash = stale.paramsHash
            val hashChanged = previous != null && previous != stale.paramsHash
            if (stale.count == 0 || (hashChanged && storedSinceHashChange)) withdrawStalePrompt()
            if (!hashChanged || !storedSinceHashChange) return
            storedSinceHashChange = false
            if (stale.count == 0) return
            val prompt =
                UiMessage(
                    text = UiText.plural(R.plurals.settings_stale_nights, stale.count),
                    action = UiText.of(R.string.settings_stale_recompute),
                )
            stalePromptId = prompt.id
            mutableState.update { it.copy(messages = it.messages + prompt) }
        }

        private fun withdrawStalePrompt() {
            val id = stalePromptId ?: return
            stalePromptId = null
            mutableState.update { state -> state.copy(messages = state.messages.filterNot { it.id == id }) }
        }

        private fun setProblems(
            spec: SettingSpec,
            problems: List<FieldProblem>,
        ) {
            mutableState.update { it.copy(problems = it.problems + (spec.path to problems)) }
        }

        /** The settings change described by a non-text [edit] of [spec], or null when they do not match. */
        private fun transformFor(
            edit: SettingsEdit,
            spec: SettingSpec,
        ): ((AppSettings) -> AppSettings)? =
            when {
                edit is SettingsEdit.Switch && spec is SwitchSpec -> { s -> spec.write(s, edit.value) }
                edit is SettingsEdit.Choice && spec is ChoiceSpec -> { s -> spec.write(s, edit.key) }
                edit is SettingsEdit.IntValue && spec is IntSpec -> { s -> spec.write(s, edit.value) }
                edit is SettingsEdit.DoubleValue && spec is DoubleSpec -> { s -> spec.write(s, edit.value) }
                edit is SettingsEdit.Range && spec is RangeSpec -> { s -> spec.write(s, edit.min, edit.max) }
                edit is SettingsEdit.Time && spec is TimeSpec -> { s -> spec.write(s, edit.minutes) }
                else -> null
            }

        private fun SettingsUiState.withMessage(text: Int): SettingsUiState = copy(messages = messages + UiMessage(UiText.of(text)))

        private fun emissionPaths(): Set<String> =
            SettingsCatalog.specsByPath.keys
                .filter { it.startsWith(EMISSIONS_PREFIX) }
                .toSet()

        /** Constants. */
        companion object {
            /** Largest settings file accepted for import (a complete export is about 10 KB). */
            const val MAX_IMPORT_BYTES: Int = 1024 * 1024

            /** Suggested file name of an export. */
            const val EXPORT_FILE_NAME: String = "pulsestages-settings.json"

            private const val TAG = "Settings"
            private const val EMISSIONS_PREFIX = "algo.hmm.emissions."
        }
    }

package io.github.alien2003.pulsestages.data.local.settings

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Outcome of [SettingsRepository.update]. */
sealed interface SettingsUpdateResult {
    /**
     * The new settings were valid and are stored.
     *
     * @property settings what was stored (after [repair][SettingsValidator.repair]).
     */
    data class Applied(
        val settings: AppSettings,
    ) : SettingsUpdateResult

    /**
     * The new settings were invalid; nothing was stored.
     *
     * @property errors every problem found.
     */
    data class Rejected(
        val errors: List<SettingsError>,
    ) : SettingsUpdateResult
}

/**
 * The user's settings. Every write is validated by [SettingsValidator]: invalid settings are never stored.
 * Export/import use the same JSON format as storage ([SettingsJson]; the file leaves out values equal to their default).
 */
interface SettingsRepository {
    /** Current settings; emits on every change. Emits defaults when nothing was stored yet. */
    val settings: Flow<AppSettings>

    /**
     * Atomically applies [transform] to the current settings, validates and stores the result. Returns
     * [SettingsUpdateResult.Rejected] (and stores nothing) when the result is invalid.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings): SettingsUpdateResult

    /** Restores every setting to its default ("Reset to defaults"). */
    suspend fun reset()

    /** Pretty JSON of [settings] including every default value. */
    fun exportJson(settings: AppSettings): String = SettingsJson.encode(settings)

    /** Parses and validates [json] without storing anything (see [SettingsJson.decode]). */
    fun importJson(json: String): SettingsImportResult = SettingsJson.decode(json)

    /** [Imports][importJson] [json] and, when it is valid, replaces the stored settings with the result. */
    suspend fun importAndApply(json: String): SettingsImportResult {
        val result = importJson(json)
        if (result is SettingsImportResult.Success) update { result.settings }
        return result
    }
}

/** [SettingsRepository] over the typed settings DataStore. */
class DataStoreSettingsRepository
    @Inject
    constructor(
        private val dataStore: DataStore<AppSettings>,
    ) : SettingsRepository {
        override val settings: Flow<AppSettings> = dataStore.data

        override suspend fun update(transform: (AppSettings) -> AppSettings): SettingsUpdateResult {
            var result: SettingsUpdateResult? = null
            dataStore.updateData { current ->
                val validation = SettingsValidator.validate(transform(current))
                if (validation.isValid) {
                    result = SettingsUpdateResult.Applied(validation.settings)
                    validation.settings
                } else {
                    result = SettingsUpdateResult.Rejected(validation.errors)
                    current
                }
            }
            return checkNotNull(result) { "DataStore.updateData completed without running the transform" }
        }

        override suspend fun reset() {
            dataStore.updateData { AppSettings() }
        }
    }

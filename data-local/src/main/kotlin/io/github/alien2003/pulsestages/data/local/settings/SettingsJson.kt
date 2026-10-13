package io.github.alien2003.pulsestages.data.local.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * JSON format of [AppSettings], shared by the DataStore file, export and import: unknown keys are ignored and missing
 * keys take their defaults (old and new files and exports keep loading). Values of the wrong type and unknown enum
 * names are errors, never silently replaced.
 *
 * The DataStore file stores only the values that differ from the defaults ([storage]), so the defaults that come from
 * the open-dataset training (`TrainedDefaults`: estimator, hybrid decoder settings, ...) follow app updates for every
 * value the user never changed; a value the user changed (to anything but the default of the build that stored it)
 * stays as chosen. An export always contains every value ([export]), so it is complete and self-describing.
 */
object SettingsJson {
    /** Compact configuration used for the DataStore file: values equal to their default are not written. */
    val storage: Json =
        Json {
            encodeDefaults = false
            ignoreUnknownKeys = true
        }

    /** Pretty-printed configuration used for export: every value, defaults included. */
    val export: Json =
        Json(from = storage) {
            encodeDefaults = true
            prettyPrint = true
        }

    /** Pretty JSON of [settings] including every default value. */
    fun encode(settings: AppSettings): String = export.encodeToString(AppSettings.serializer(), settings)

    /**
     * Parses and validates [json]. Missing keys take their defaults, unknown keys are ignored, the source lists are
     * [repaired][SettingsValidator.repair]; any parse or validation problem yields [SettingsImportResult.Invalid]
     * with every error found.
     */
    fun decode(json: String): SettingsImportResult {
        val parsed =
            try {
                storage.decodeFromString(AppSettings.serializer(), json)
            } catch (e: IllegalArgumentException) {
                // kotlinx.serialization.SerializationException is an IllegalArgumentException.
                return SettingsImportResult.Invalid(
                    listOf(SettingsError("$", SettingsErrorCode.MALFORMED_JSON, "", e.message.orEmpty())),
                )
            }
        val validation = SettingsValidator.validate(parsed)
        return if (validation.isValid) {
            SettingsImportResult.Success(validation.settings)
        } else {
            SettingsImportResult.Invalid(validation.errors)
        }
    }
}

/** Outcome of importing settings JSON. */
sealed interface SettingsImportResult {
    /**
     * The JSON is valid. Nothing is stored yet.
     *
     * @property settings the complete result (missing keys filled with defaults, source lists repaired).
     */
    data class Success(
        val settings: AppSettings,
    ) : SettingsImportResult

    /**
     * The JSON was rejected.
     *
     * @property errors every problem found.
     */
    data class Invalid(
        val errors: List<SettingsError>,
    ) : SettingsImportResult
}

/**
 * DataStore [Serializer] storing [AppSettings] as one JSON document. Unreadable content raises
 * [CorruptionException] so that the store's corruption handler can replace it with defaults.
 */
object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings {
        val text = input.readBytes().decodeToString()
        return try {
            SettingsJson.storage.decodeFromString(AppSettings.serializer(), text)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Settings file is not valid settings JSON", e)
        }
    }

    override suspend fun writeTo(
        t: AppSettings,
        output: OutputStream,
    ) {
        output.write(SettingsJson.storage.encodeToString(AppSettings.serializer(), t).encodeToByteArray())
    }
}

/** Creates the typed settings DataStore. */
object AppSettingsDataStoreFactory {
    /** File name inside the app's `datastore/` directory. */
    const val FILE_NAME: String = "app_settings.json"

    /**
     * Creates a DataStore over the file returned by [produceFile] (one instance per file and process). A corrupted
     * file is replaced by defaults after [onCorruption] has been told about it; [onCorruption] must not throw.
     */
    fun create(
        scope: CoroutineScope,
        onCorruption: (CorruptionException) -> Unit,
        produceFile: () -> File,
    ): DataStore<AppSettings> =
        DataStoreFactory.create(
            serializer = AppSettingsSerializer,
            corruptionHandler =
                ReplaceFileCorruptionHandler { exception ->
                    onCorruption(exception)
                    AppSettings()
                },
            scope = scope,
            produceFile = produceFile,
        )
}

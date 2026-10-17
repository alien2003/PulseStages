package io.github.alien2003.pulsestages.data.local.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.intOrNull
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
 *
 * A renamed key keeps loading through a read migration on its part of the document ([ScheduleSettingsSerializer]), so
 * every decode (the DataStore file, import) reads old files and exports; what is written uses only the new key.
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

/**
 * The JSON of [ScheduleSettings] with the read migration of `periodicHours` (up to v0.3.0) to `periodicMinutes`: a
 * whole number of hours (also a quoted one, which the old decoder accepted) becomes that many minutes; when both keys
 * are present `periodicMinutes` wins. Any other legacy value is moved under the new key unchanged, so that it is
 * rejected as before: a value that is no whole number fails to decode (MALFORMED_JSON on import, defaults on a
 * corrupted DataStore file), and an hour count whose minutes would overflow an Int stays a number the validator
 * rejects. Writing is unchanged: files and exports contain only `periodicMinutes`. The DataStore file keeps the legacy
 * key until the next settings write, so this migration is permanent.
 */
object ScheduleSettingsSerializer : JsonTransformingSerializer<ScheduleSettings>(ScheduleSettings.serializer()) {
    private const val LEGACY_KEY = "periodicHours"
    private const val KEY = "periodicMinutes"
    private const val MINUTES_PER_HOUR = 60

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val schedule = element as? JsonObject
        val legacy = schedule?.get(LEGACY_KEY) ?: return element
        val rest = schedule - LEGACY_KEY
        return JsonObject(if (KEY in rest) rest else rest + (KEY to (minutesOf(legacy) ?: legacy)))
    }

    /** The minutes of a legacy hour count, or null when [legacy] is no whole number of hours or its minutes overflow. */
    private fun minutesOf(legacy: JsonElement): JsonPrimitive? {
        val hours = (legacy as? JsonPrimitive)?.intOrNull ?: return null
        val convertible = hours in Int.MIN_VALUE / MINUTES_PER_HOUR..Int.MAX_VALUE / MINUTES_PER_HOUR
        return if (convertible) JsonPrimitive(hours * MINUTES_PER_HOUR) else null
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

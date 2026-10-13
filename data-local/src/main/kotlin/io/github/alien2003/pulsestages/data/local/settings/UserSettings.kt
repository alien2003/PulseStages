package io.github.alien2003.pulsestages.data.local.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** User preferences persisted with DataStore. Placeholder fields for the skeleton. */
@Serializable
data class UserSettings(
    val autoProcessEnabled: Boolean = true,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** JSON-backed DataStore serializer for [UserSettings]. */
object UserSettingsSerializer : Serializer<UserSettings> {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    override val defaultValue: UserSettings = UserSettings()

    override suspend fun readFrom(input: InputStream): UserSettings =
        try {
            json.decodeFromString(UserSettings.serializer(), input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Unable to read user settings", e)
        }

    override suspend fun writeTo(
        t: UserSettings,
        output: OutputStream,
    ) {
        output.write(json.encodeToString(UserSettings.serializer(), t).encodeToByteArray())
    }
}

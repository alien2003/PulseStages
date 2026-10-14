package io.github.alien2003.pulsestages.e2e.support

import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.AppSettingsDataStoreFactory
import io.github.alien2003.pulsestages.data.local.settings.SettingsImportResult
import io.github.alien2003.pulsestages.data.local.settings.SettingsJson
import io.github.alien2003.pulsestages.e2e.E2e
import io.github.alien2003.pulsestages.e2e.safety.sha256
import java.io.File

/**
 * The user's real settings, copied READ-ONLY from the app's settings DataStore file
 * (`files/datastore/app_settings.json`): the bytes are read once and decoded with the app's own JSON format and
 * validator; no DataStore is opened on the file (the app's own instance may be active in this process) and nothing is
 * ever written to it.
 *
 * @property settings the decoded settings (the defaults when there is no file).
 * @property source where they came from.
 * @property sha256 SHA-256 of the file's bytes, or null without a file.
 * @property problem why the file could not be used, if it could not.
 */
data class AppSettingsFile(
    val settings: AppSettings,
    val source: String,
    val sha256: String?,
    val problem: String?,
) {
    /** Reading. */
    companion object {
        /** The app's settings file (`Context.dataStoreFile(FILE_NAME)`). */
        val file: File get() = File(E2e.app.filesDir, "datastore/${AppSettingsDataStoreFactory.FILE_NAME}")

        /** Reads and decodes the file. */
        fun read(): AppSettingsFile {
            val target = file
            if (!target.isFile) return AppSettingsFile(AppSettings(), "defaults (the app has no settings file)", null, null)
            val bytes = target.readBytes()
            return when (val result = SettingsJson.decode(bytes.decodeToString())) {
                is SettingsImportResult.Success -> {
                    AppSettingsFile(result.settings, "the app's settings file ${target.path}", sha256(bytes), null)
                }

                is SettingsImportResult.Invalid -> {
                    AppSettingsFile(AppSettings(), "defaults (the app's settings file is invalid)", sha256(bytes), result.errors.toString())
                }
            }
        }

        /** SHA-256 of the file now (null without a file), to show that the test left it alone. */
        fun currentSha256(): String? = file.takeIf { it.isFile }?.readBytes()?.let(::sha256)
    }
}

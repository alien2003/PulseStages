package io.github.alien2003.pulsestages.e2e

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The environment of the on-device end-to-end suite (tools/device-test/README.md).
 *
 * The test APK is an instrumentation of the INSTALLED app: it runs in the app's process with the app's uid, so every
 * Health Connect call is made as PulseStages, with PulseStages' permissions. Everything here therefore uses the app's
 * context ([app]), never the context of the test package (which has no permissions and no storage in this process).
 */
object E2e {
    /** Application id of the app under test. The suite refuses to run against anything else. */
    const val APP_PACKAGE: String = "io.github.alien2003.pulsestages"

    /**
     * Namespace of the clientRecordIds the suite may write and delete in the real Health Connect
     * ([io.github.alien2003.pulsestages.e2e.safety.SafetyGuardRepository]). The app itself writes `ps:` + sha256.
     */
    const val TEST_NAMESPACE: String = "ps-e2e:"

    /** Namespace of the app's own records (never written or deleted by the suite). */
    const val APP_NAMESPACE: String = "ps:"

    /** Logcat tag of the suite (the app logs as "PulseStages"). */
    const val TAG: String = "PulseStagesE2E"

    /** Directory below the app's external files directory that the runner pulls with adb. */
    const val DIR_NAME: String = "e2e"

    /** Instrumentation argument: id of this run of tools/device-test/run-device-tests.sh. */
    const val ARG_RUN_ID: String = "e2eRunId"

    /** Status code of the progress lines sent to `am instrument -r` (AndroidJUnitRunner itself uses 1, 0 and negatives). */
    private const val PROGRESS_STATUS_CODE = 2

    /** The instrumentation. */
    val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** The app's context (the target of the instrumentation); fails when the target is not PulseStages. */
    val app: Context
        get() {
            val context = instrumentation.targetContext
            check(context.packageName == APP_PACKAGE) { "the instrumentation targets ${context.packageName}, not $APP_PACKAGE" }
            return context
        }

    /** Arguments passed with `am instrument -e`. */
    val args: Bundle get() = InstrumentationRegistry.getArguments()

    /** The id of this run ("manual" when started by hand). */
    val runId: String get() = args.getString(ARG_RUN_ID)?.takeIf { it.isNotBlank() } ?: "manual"

    /** An integer instrumentation argument, or [default]. */
    fun intArg(
        name: String,
        default: Int,
    ): Int = args.getString(name)?.trim()?.toIntOrNull() ?: default

    /** A boolean instrumentation argument ("true" / "false"), or [default]. */
    fun booleanArg(
        name: String,
        default: Boolean,
    ): Boolean = args.getString(name)?.trim()?.toBooleanStrictOrNull() ?: default

    /**
     * `/sdcard/Android/data/io.github.alien2003.pulsestages/files/e2e`: the app's own external files directory, which
     * the runner can pull with adb (no run-as needed). Nothing else of the app is written by the suite.
     */
    val outDir: File
        get() {
            val base = checkNotNull(app.getExternalFilesDir(null)) { "the app's external files directory is not available" }
            return File(base, DIR_NAME).also { it.mkdirs() }
        }

    /** [relative] inside [outDir] (parent directories are created). */
    fun file(relative: String): File = File(outDir, relative).also { it.parentFile?.mkdirs() }

    /** JSON of every report. */
    val json: Json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    /** Writes [value] as JSON to [relative]. */
    inline fun <reified T> writeJson(
        relative: String,
        value: T,
    ) {
        file(relative).writeText(json.encodeToString(value))
    }

    /** Reads [relative] as [T], or null when it does not exist. */
    inline fun <reified T> readJson(relative: String): T? {
        val target = File(outDir, relative)
        return if (target.isFile) json.decodeFromString<T>(target.readText()) else null
    }

    /** Writes [text] to [relative]. */
    fun writeText(
        relative: String,
        text: String,
    ) {
        file(relative).writeText(text)
    }

    /** Logs [message] and streams it to the runner (`am instrument -r` prints it as `INSTRUMENTATION_STATUS: e2e=…`). */
    fun progress(message: String) {
        Log.i(TAG, message)
        instrumentation.sendStatus(PROGRESS_STATUS_CODE, Bundle().apply { putString("e2e", message) })
    }
}

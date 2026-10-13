package io.github.alien2003.pulsestages.data.local.log

import android.util.Log
import io.github.alien2003.pulsestages.data.local.settings.LogLevel

/**
 * The app's internal log. Lines below the configured level (`DebugSettings.logLevel`) are dropped; the rest are
 * stored for the in-app log screen and mirrored to logcat (`adb logcat -s PulseStages:V`). Logging never throws
 * and never blocks on I/O.
 */
interface AppLogger {
    /** Logs [message] at [level] under [tag]; [nightKey] links the line to a night, [throwable] adds a stack trace. */
    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        nightKey: String? = null,
        throwable: Throwable? = null,
    )

    /** Logs at [LogLevel.DEBUG]. */
    fun d(
        tag: String,
        message: String,
        nightKey: String? = null,
        throwable: Throwable? = null,
    ) = log(LogLevel.DEBUG, tag, message, nightKey, throwable)

    /** Logs at [LogLevel.INFO]. */
    fun i(
        tag: String,
        message: String,
        nightKey: String? = null,
        throwable: Throwable? = null,
    ) = log(LogLevel.INFO, tag, message, nightKey, throwable)

    /** Logs at [LogLevel.WARN]. */
    fun w(
        tag: String,
        message: String,
        nightKey: String? = null,
        throwable: Throwable? = null,
    ) = log(LogLevel.WARN, tag, message, nightKey, throwable)

    /** Logs at [LogLevel.ERROR]. */
    fun e(
        tag: String,
        message: String,
        nightKey: String? = null,
        throwable: Throwable? = null,
    ) = log(LogLevel.ERROR, tag, message, nightKey, throwable)

    /** Suspends until every line logged before this call has been stored (or storing has stopped for good). */
    suspend fun flush()
}

/** Destination of the logcat mirror; abstracted so tests can capture it. */
fun interface LogcatSink {
    /** Writes one already formatted line. Must not throw. */
    fun write(
        level: LogLevel,
        text: String,
    )
}

/** Writes to `android.util.Log` with tag [TAG]. */
object AndroidLogcatSink : LogcatSink {
    /** The logcat tag of every line of the app. */
    const val TAG: String = "PulseStages"

    override fun write(
        level: LogLevel,
        text: String,
    ) {
        val priority =
            when (level) {
                LogLevel.DEBUG -> Log.DEBUG
                LogLevel.INFO -> Log.INFO
                LogLevel.WARN -> Log.WARN
                LogLevel.ERROR -> Log.ERROR
            }
        Log.println(priority, TAG, text)
    }
}

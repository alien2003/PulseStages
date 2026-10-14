package io.github.alien2003.pulsestages.e2e.support

import android.util.Log
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.e2e.E2e

/**
 * One line logged by the components the suite builds itself.
 *
 * @property level severity.
 * @property tag component.
 * @property message text.
 * @property nightKey night the line refers to.
 */
data class LoggedLine(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val nightKey: String?,
)

/**
 * The [AppLogger] of the components the suite builds (the real Health Connect repository, the isolated pipeline):
 * lines stay in memory and are mirrored to logcat under [E2e.TAG]. It never writes to the app's log table, so the
 * suite leaves the app's Room database alone.
 */
class E2eLogger : AppLogger {
    private val recorded = ArrayDeque<LoggedLine>()

    /** The lines logged so far (the most recent [MAX_LINES]). */
    val lines: List<LoggedLine> get() = synchronized(recorded) { recorded.toList() }

    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        nightKey: String?,
        throwable: Throwable?,
    ) {
        synchronized(recorded) {
            recorded.addLast(LoggedLine(level, tag, message, nightKey))
            if (recorded.size > MAX_LINES) recorded.removeFirst()
        }
        if (level >= LogLevel.WARN) {
            Log.w(E2e.TAG, "[$tag] $message${nightKey?.let { " ($it)" }.orEmpty()}", throwable)
        }
    }

    override suspend fun flush() = Unit

    /** Lines at [level] or above. */
    fun atLeast(level: LogLevel): List<LoggedLine> = lines.filter { it.level >= level }

    private companion object {
        const val MAX_LINES = 20_000
    }
}

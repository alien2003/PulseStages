package io.github.alien2003.pulsestages.data.hc.fake

import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.data.local.settings.SettingsUpdateResult
import io.github.alien2003.pulsestages.data.local.settings.SettingsValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A UTC [Clock] whose time is set by the test. */
class TestClock(
    /** Current time, epoch ms. */
    @Volatile var nowMs: Long,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(nowMs)

    /** Moves the clock forward by [ms]. */
    fun advanceBy(ms: Long) {
        nowMs += ms
    }
}

/**
 * One line logged through [RecordingLogger].
 *
 * @property level severity.
 * @property tag component.
 * @property message text.
 * @property nightKey night the line refers to.
 * @property throwable attached error.
 */
data class LoggedLine(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val nightKey: String?,
    val throwable: Throwable?,
)

/** [AppLogger] that keeps every line in memory. */
class RecordingLogger : AppLogger {
    private val recorded = mutableListOf<LoggedLine>()

    /** Every logged line, in order. */
    val lines: List<LoggedLine> get() = synchronized(recorded) { recorded.toList() }

    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        nightKey: String?,
        throwable: Throwable?,
    ) {
        synchronized(recorded) { recorded += LoggedLine(level, tag, message, nightKey, throwable) }
    }

    override suspend fun flush() = Unit

    /** Lines at [level] or above. */
    fun atLeast(level: LogLevel): List<LoggedLine> = lines.filter { it.level >= level }
}

/** In-memory [SettingsRepository] that validates like the real one. */
class FakeSettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    override val settings: Flow<AppSettings> = state

    /** The current settings. */
    val current: AppSettings get() = state.value

    override suspend fun update(transform: (AppSettings) -> AppSettings): SettingsUpdateResult {
        var result: SettingsUpdateResult? = null
        state.update { current ->
            val validation = SettingsValidator.validate(transform(current))
            if (validation.isValid) {
                result = SettingsUpdateResult.Applied(validation.settings)
                validation.settings
            } else {
                result = SettingsUpdateResult.Rejected(validation.errors)
                current
            }
        }
        return checkNotNull(result)
    }

    override suspend fun reset() {
        state.value = AppSettings()
    }

    /** Applies [transform] and fails the test when the result is invalid. */
    suspend fun set(transform: (AppSettings) -> AppSettings): AppSettings {
        val result = update(transform)
        check(result is SettingsUpdateResult.Applied) { "invalid settings: $result" }
        return result.settings
    }
}

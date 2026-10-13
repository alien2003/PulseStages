package io.github.alien2003.pulsestages.data.local.log

import io.github.alien2003.pulsestages.data.local.db.LogDao
import io.github.alien2003.pulsestages.data.local.db.LogEntryEntity
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.time.Clock

/**
 * [AppLogger] storing lines in Room. [log] filters by [minLevel], mirrors to [logcat] synchronously and queues the
 * line; a single writer coroutine in [scope] stores queued lines in order, in batches, and trims the table to the
 * newest [maxRows] rows after every batch. Storage errors are reported to logcat only and never reach the caller.
 *
 * @param minLevel current minimum level (normally `DebugSettings.logLevel`).
 * @param scope application-lifetime scope that owns the writer; when it is cancelled, lines go to logcat only.
 */
class RoomAppLogger(
    private val logDao: LogDao,
    private val minLevel: StateFlow<LogLevel>,
    scope: CoroutineScope,
    private val clock: Clock,
    private val logcat: LogcatSink = AndroidLogcatSink,
    private val maxRows: Int = LogEntryEntity.MAX_ROWS,
) : AppLogger {
    private sealed interface Command {
        class Write(
            val entry: LogEntryEntity,
        ) : Command

        class Flush(
            val done: CompletableDeferred<Unit>,
        ) : Command
    }

    private val queue = Channel<Command>(Channel.UNLIMITED)
    private val writer: Job = scope.launch { drain() }

    init {
        writer.invokeOnCompletion { queue.close() }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Logging must never throw into the caller.
    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        nightKey: String?,
        throwable: Throwable?,
    ) {
        try {
            if (level < minLevel.value) return
            val text = compose(message, throwable)
            mirror(level, tag, text, nightKey)
            queue.trySend(
                Command.Write(LogEntryEntity(timestampMs = clock.millis(), level = level, tag = tag, message = text, nightKey = nightKey)),
            )
        } catch (e: Exception) {
            // Nothing sensible is left to do: logcat (if it was the culprit) and the queue are both unavailable.
        }
    }

    override suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        if (queue.trySend(Command.Flush(done)).isFailure) return
        select {
            done.onAwait {}
            writer.onJoin {}
        }
    }

    private suspend fun drain() {
        val batch = ArrayList<LogEntryEntity>(MAX_BATCH)
        val flushes = ArrayList<CompletableDeferred<Unit>>()
        for (first in queue) {
            var next: Command? = first
            while (next != null) {
                when (next) {
                    is Command.Write -> batch += next.entry
                    is Command.Flush -> flushes += next.done
                }
                next = if (batch.size < MAX_BATCH) queue.tryReceive().getOrNull() else null
            }
            persist(batch)
            batch.clear()
            flushes.forEach { it.complete(Unit) }
            flushes.clear()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any storage failure (disk full, closed database, ...) is reported, not thrown.
    private suspend fun persist(batch: List<LogEntryEntity>) {
        if (batch.isEmpty()) return
        try {
            logDao.insertAll(batch)
            logDao.trimToNewest(maxRows)
        } catch (e: Exception) {
            // Rethrows only if the writer itself is cancelled. Room reports a closed database with a
            // CancellationException of its own scope; that is a storage failure, not our cancellation.
            currentCoroutineContext().ensureActive()
            mirror(LogLevel.ERROR, TAG, compose("Could not store ${batch.size} log entries", e), nightKey = null)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // The logcat mirror is best effort.
    private fun mirror(
        level: LogLevel,
        tag: String,
        text: String,
        nightKey: String?,
    ) {
        try {
            logcat.write(level, if (nightKey == null) "[$tag] $text" else "[$tag] [$nightKey] $text")
        } catch (e: Exception) {
            // Ignored: the line is still stored.
        }
    }

    private fun compose(
        message: String,
        throwable: Throwable?,
    ): String {
        val full = if (throwable == null) message else message + "\n" + throwable.stackTraceToString()
        return if (full.length <= MAX_MESSAGE_LENGTH) full else full.take(MAX_MESSAGE_LENGTH - ELLIPSIS.length) + ELLIPSIS
    }

    private companion object {
        const val TAG = "AppLogger"
        const val MAX_BATCH = 256
        const val MAX_MESSAGE_LENGTH = 8000
        const val ELLIPSIS = "…"
    }
}

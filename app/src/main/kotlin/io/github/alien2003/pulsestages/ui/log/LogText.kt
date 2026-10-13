package io.github.alien2003.pulsestages.ui.log

import io.github.alien2003.pulsestages.data.local.db.LogEntryEntity
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Plain-text form of the internal log for sharing (English, machine-friendly, one entry per line). */
object LogText {
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /** One line: `2026-10-13T05:17:00.123+03:00 INFO [Tag] [nightKey] message` (multi-line messages are indented). */
    fun line(
        entry: LogEntryEntity,
        zone: ZoneId,
    ): String {
        val time = Instant.ofEpochMilli(entry.timestampMs).atZone(zone).format(TIMESTAMP)
        val night = entry.nightKey?.let { " [$it]" }.orEmpty()
        val message = entry.message.replace("\n", "\n    ")
        return "$time ${entry.level.name} [${entry.tag}]$night $message"
    }

    /**
     * The whole export: a header with the export time, the levels included and the number of entries, then [entries]
     * oldest first.
     */
    fun document(
        entries: List<LogEntryEntity>,
        levels: Set<LogLevel>,
        exportedAtMs: Long,
        zone: ZoneId,
    ): String =
        buildString {
            appendLine("PulseStages log")
            appendLine("exported: ${Instant.ofEpochMilli(exportedAtMs).atZone(zone).format(TIMESTAMP)}")
            appendLine("levels: ${LogLevel.entries.filter { it in levels }.joinToString(",")}")
            appendLine("entries: ${entries.size}")
            appendLine()
            entries.sortedBy { it.id }.forEach { appendLine(line(it, zone)) }
        }
}

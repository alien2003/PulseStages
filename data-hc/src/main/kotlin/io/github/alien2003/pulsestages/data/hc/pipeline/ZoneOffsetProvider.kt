package io.github.alien2003.pulsestages.data.hc.pipeline

import java.time.Instant
import java.time.ZoneId

/** Zone offset to use when a source session has none (Health Connect writes need explicit offsets). */
fun interface ZoneOffsetProvider {
    /** Offset in seconds at [epochMs]. */
    fun offsetSecondsAt(epochMs: Long): Int
}

/** Offsets of the device's current default zone (`ZoneId.systemDefault().rules.getOffset(instant)`). */
object SystemZoneOffsetProvider : ZoneOffsetProvider {
    override fun offsetSecondsAt(epochMs: Long): Int =
        ZoneId
            .systemDefault()
            .rules
            .getOffset(Instant.ofEpochMilli(epochMs))
            .totalSeconds
}

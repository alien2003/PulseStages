package io.github.alien2003.pulsestages.data.local.testutil

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A [Clock] whose time is set by the test. */
class MutableClock(
    var nowMs: Long,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
}

package io.github.alien2003.pulsestages.demo

import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticNightGenerator
import io.github.alien2003.pulsestages.algo.synthetic.SyntheticOptions
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The demo naps (debug builds only): afternoon sessions short enough to be estimated as naps. */
internal object DemoNaps {
    private const val YESTERDAY = 1
    private const val SIX_DAYS_AGO = 6
    private const val LONG_NAP_MINUTES = 75
    private const val SHORT_NAP_MINUTES = 45
    private const val NAP_SEED = 20_261_200L
    private const val NAP_HOUR = 15

    /** The naps: days ago to their length in minutes. */
    val LENGTHS: Map<Int, Int> = mapOf(YESTERDAY to LONG_NAP_MINUTES, SIX_DAYS_AGO to SHORT_NAP_MINUTES)

    /** Start of the afternoon nap [daysAgo] days before [today], at 15:00 in [zone]. */
    fun start(
        daysAgo: Int,
        today: LocalDate,
        zone: ZoneId,
    ): Long =
        today
            .minusDays(daysAgo.toLong())
            .atTime(LocalTime.of(NAP_HOUR, 0))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    /** The estimation input of the demo nap [daysAgo] starting at [startMs] ([LENGTHS] gives its length). */
    fun input(
        daysAgo: Int,
        startMs: Long,
    ): NightInput =
        SyntheticNightGenerator(NAP_SEED + daysAgo)
            .generate(SyntheticOptions(sessionStartMs = startMs, durationMin = LENGTHS.getValue(daysAgo)))
            .input
}

package io.github.alien2003.pulsestages.cli

import io.github.alien2003.pulsestages.algo.model.TimeSpan
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Nap proxies (ALGORITHM.md section 13.11): the first L minutes of a labelled night as a nap session, cut exactly like
 * tools/train's `nap_proxy` (`pulsetrain/naps.py`): the session `[start, start + L)`, heart rate (and SpO2, HRV,
 * respiratory rate) of `[start − 30 min, end]`, step records ending in `(start − 30 min, end]`, the forced-awake spans
 * clipped to the session, the label intervals lying fully inside it and nothing after the end (a real nap is followed
 * by wakefulness, which a night cannot supply). The proxy keeps the night's subject, variant and baseline nadir; its id
 * is `<night id>-nap<L>` (L without a trailing ".0"). A night shorter than L gives no proxy.
 */
internal object NapProxies {
    private const val MINUTE_MS = 60_000.0
    private const val CONTEXT_MS = 30 * 60_000L

    /** The proxy of the first [minutes] of [night], or null when the night's session is shorter. */
    fun cut(
        night: NightRecord,
        minutes: Double,
    ): NightRecord? {
        val input = night.input
        val start = input.sessionStartMs
        val end = start + (minutes * MINUTE_MS).roundToLong()
        if (end > input.sessionEndMs || end <= start) return null
        val context = start - CONTEXT_MS
        val inWindow = { time: Long -> time in context..end }
        return NightRecord(
            id = "${night.id}-nap${label(minutes)}",
            subject = night.subject,
            variant = night.variant,
            input =
                input.copy(
                    sessionEndMs = end,
                    heartRate = input.heartRate.filter { inWindow(it.timeMs) },
                    steps = input.steps.filter { it.endMs > context && it.endMs <= end },
                    spo2 = input.spo2.filter { inWindow(it.timeMs) },
                    respiratoryRate = input.respiratoryRate.filter { inWindow(it.timeMs) },
                    hrv = input.hrv.filter { inWindow(it.timeMs) },
                    forcedAwake =
                        input.forcedAwake.mapNotNull { span ->
                            val from = maxOf(span.startMs, start)
                            val to = minOf(span.endMs, end)
                            if (to > from) TimeSpan(from, to) else null
                        },
                ),
            labels = night.labels.filter { it.startMs >= start && it.endMs <= end },
        )
    }

    /** [nights] followed by the proxies of every night for every length of [minutes], in that order. */
    fun withProxies(
        nights: List<NightRecord>,
        minutes: List<Double>,
    ): List<NightRecord> = nights + minutes.flatMap { length -> nights.mapNotNull { cut(it, length) } }

    /** The lengths of a `--nap-proxies` option: comma-separated positive minutes, e.g. `30,60,90,120`. */
    fun parseLengths(text: String): List<Double> {
        val lengths =
            text
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.toDoubleOrNull() }
        val values = lengths.filterNotNull().filter { it.isFinite() && it > 0.0 }
        if (values.isEmpty() || values.size != lengths.size) {
            throw CliException("--nap-proxies must be a comma-separated list of positive minutes, was '$text'", ExitCodes.USAGE)
        }
        if (values.toSet().size != values.size) throw CliException("--nap-proxies lists a length twice: '$text'", ExitCodes.USAGE)
        return values
    }

    /** [minutes] as in tools/train's ids (`f"{L:g}"`): 30.0 → "30", 90.5 → "90.5". */
    private fun label(minutes: Double): String =
        if (minutes == Math.rint(minutes)) String.format(Locale.ROOT, "%d", minutes.toLong()) else minutes.toString()
}

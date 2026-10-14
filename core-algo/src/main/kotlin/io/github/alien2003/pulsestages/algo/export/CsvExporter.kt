package io.github.alien2003.pulsestages.algo.export

import io.github.alien2003.pulsestages.algo.model.NightEstimate

/**
 * Per-epoch CSV export of a night (the "Export CSV" button and `algo-cli estimate --csv`).
 *
 * Columns: `epoch,start_ms,has_data,hr_raw,hr_smooth,<feature names…>,p_awake,p_light,p_deep,p_rem,stage,mode`; one
 * row per session epoch, `\n` line ends, `has_data` as 1/0, numbers with a '.' decimal separator independent of the
 * locale (shortest round-trip form), empty fields for missing (null/NaN) values; `mode` is the session's processing
 * mode (NIGHT or NAP, [NightEstimate.mode]), the same on every row.
 */
object CsvExporter {
    private val LEADING = listOf("epoch", "start_ms", "has_data", "hr_raw", "hr_smooth")
    private val TRAILING = listOf("p_awake", "p_light", "p_deep", "p_rem", "stage", "mode")

    /** The header line (without line end) for [featureNames]. */
    fun header(featureNames: List<String>): String = (LEADING + featureNames + TRAILING).joinToString(",")

    /** The whole CSV document of [estimate], header included. */
    fun export(estimate: NightEstimate): String =
        buildString {
            append(header(estimate.featureNames)).append('\n')
            for (epoch in estimate.perEpoch) {
                val fields =
                    listOf(
                        epoch.index.toString(),
                        epoch.startMs.toString(),
                        if (epoch.hasData) "1" else "0",
                        number(epoch.hrRaw),
                        number(epoch.hrSmooth),
                    ) +
                        epoch.features.map { number(it) } +
                        epoch.posterior.toList().map { number(it) } +
                        epoch.stage.name +
                        estimate.mode.name
                append(fields.joinToString(",")).append('\n')
            }
        }

    /** Locale-independent shortest representation; empty for null, NaN and infinities. */
    internal fun number(value: Double?): String = if (value == null || !value.isFinite()) "" else value.toString()
}

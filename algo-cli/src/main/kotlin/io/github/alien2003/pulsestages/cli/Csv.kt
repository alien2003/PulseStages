package io.github.alien2003.pulsestages.cli

/** CSV formatting shared by the commands (same conventions as CsvExporter: '.' decimals, empty for NaN). */
internal object Csv {
    /** Locale-independent shortest representation; empty for NaN and infinities. */
    fun number(value: Double): String = if (value.isFinite()) value.toString() else ""

    private const val SPECIAL = ",\"\n\r"

    /** [text] quoted per RFC 4180 when it contains a comma, quote or line break. */
    fun field(text: String): String = if (text.any { it in SPECIAL }) "\"" + text.replace("\"", "\"\"") + "\"" else text
}

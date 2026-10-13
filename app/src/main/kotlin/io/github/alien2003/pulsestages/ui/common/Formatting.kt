package io.github.alien2003.pulsestages.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.alien2003.pulsestages.R
import java.text.NumberFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Milliseconds per minute. */
const val MILLIS_PER_MINUTE: Long = 60_000L

private const val MINUTES_PER_HOUR = 60
private const val PERCENT = 100

/** Local date-time of [epochMs] at the zone offset recorded with the session ([offsetSec]). */
fun localDateTime(
    epochMs: Long,
    offsetSec: Int,
): OffsetDateTime = Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.ofTotalSeconds(offsetSec))

/** Local date-time of [epochMs] in [zone] (the device zone by default). */
fun localDateTime(
    epochMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): OffsetDateTime = Instant.ofEpochMilli(epochMs).atZone(zone).toOffsetDateTime()

/** Medium date, e.g. "13 Oct 2026", in the default locale. */
fun OffsetDateTime.formatDate(): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** Short time, e.g. "23:41", in the default locale. */
fun OffsetDateTime.formatTime(): String = format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** Medium date and short time in the default locale. */
fun OffsetDateTime.formatDateTime(): String = format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

/** "7 h 05 min" for [minutes]. */
@Composable
fun formatDuration(minutes: Long): String {
    val safe = minutes.coerceAtLeast(0)
    return stringResource(R.string.format_duration_hours_minutes, safe / MINUTES_PER_HOUR, safe % MINUTES_PER_HOUR)
}

/** Duration between two instants in whole minutes (rounded). */
fun durationMinutes(
    startMs: Long,
    endMs: Long,
): Long = ((endMs - startMs).coerceAtLeast(0) / MILLIS_PER_MINUTE.toDouble()).roundToLong()

/** A share 0..1 as a whole percentage, e.g. "23 %" (spacing follows the locale string). */
@Composable
fun formatPercent(share: Double): String = stringResource(R.string.format_percent, percentOf(share))

/** A share 0..1 as a whole percentage number. */
fun percentOf(share: Double): Int = if (share.isFinite()) (share * PERCENT).roundToInt() else 0

/** [value] with exactly [digits] fraction digits in the default locale (e.g. "0,42" in Russian). */
fun formatDecimal(
    value: Double,
    digits: Int,
): String =
    NumberFormat.getNumberInstance(Locale.getDefault()).run {
        minimumFractionDigits = digits
        maximumFractionDigits = digits
        format(value)
    }

package io.github.alien2003.pulsestages.ui.nights

import androidx.annotation.StringRes
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.data.local.db.NightStatus

/**
 * Localized explanation of a night without output, chosen by its [status]; null for a WRITTEN night. The ledger's
 * `statusReason` is an English diagnostic for the log: only the [NotEstimatedReason] code at its start is used.
 */
@StringRes
fun statusReasonRes(
    status: NightStatus,
    statusReason: String?,
): Int? =
    when (status) {
        NightStatus.WRITTEN -> null
        NightStatus.NOT_ESTIMATED -> notEstimatedReasonOf(statusReason)?.labelRes() ?: R.string.night_reason_not_estimated
        NightStatus.FAILED -> R.string.night_reason_failed
        NightStatus.SUPERSEDED -> R.string.night_reason_superseded
        NightStatus.DELETED -> R.string.night_reason_deleted
        NightStatus.SOURCE_DELETED -> R.string.night_reason_source_deleted
    }

/** The [NotEstimatedReason] code a NOT_ESTIMATED night's `statusReason` starts with ("SESSION_TOO_SHORT: ..."), or null. */
internal fun notEstimatedReasonOf(statusReason: String?): NotEstimatedReason? {
    val code = statusReason?.substringBefore(':')?.trim() ?: return null
    return NotEstimatedReason.entries.firstOrNull { it.name == code }
}

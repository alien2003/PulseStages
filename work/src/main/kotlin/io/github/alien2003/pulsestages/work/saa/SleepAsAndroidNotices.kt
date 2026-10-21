package io.github.alien2003.pulsestages.work.saa

/**
 * Shows that [ChangedNight.night] changed after Sleep as Android was asked to sync (it keeps the version it imported):
 * the notification "Check a night in Sleep as Android". Never throws for a missing permission: the result says why
 * nothing was shown.
 */
interface SleepAsAndroidNotices {
    /** Shows the notice of [change], replacing the notices of the nights it replaced. */
    fun show(change: ChangedNight): NoticeResult

    /** Removes the notice of the night [clientRecordId], if one is shown (its night is back at the span sent). */
    fun withdraw(clientRecordId: String)
}

/**
 * What [SleepAsAndroidNotices.show] did.
 *
 * @property logText English text for the log.
 */
enum class NoticeResult(
    val logText: String,
) {
    /** The notification was posted. */
    SHOWN("notification shown"),

    /** Notifications are off for PulseStages, or POST_NOTIFICATIONS is not granted. */
    NOT_ALLOWED("no notification: notifications are off for PulseStages"),

    /** The user turned the channel of the notice off. */
    CHANNEL_OFF("no notification: its channel is turned off"),
}

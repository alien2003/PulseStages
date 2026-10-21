package io.github.alien2003.pulsestages.work.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * The contract of a notification that opens a night's detail in the app: `:work` builds the intent for the app's
 * launcher activity, and the app's MainActivity reads it with [nightToOpen] (`:work` cannot reference it).
 *
 * The intent has the launcher's identity (action MAIN, category LAUNCHER, the activity, no data or package) and carries
 * the night as an extra. A task keeps the intent that started it as its base intent: a notification that reaches the
 * root MainActivity of an existing task through CLEAR_TOP does not replace it (Task.setIntent keeps the identity of a
 * root activity without relinquishTaskIdentity, Android 9–16). The launcher's identity therefore matters when the
 * notification's tap starts the task: the launcher icon then finds that task the same and brings it to the front,
 * whatever screen is on top, instead of stacking a second MainActivity on it. Android 14+ ignore the package in that
 * comparison (Task.isSameIntentFilter), so a task that the progress notification started (its intent names the
 * package) is found too; on Android 9–13 such a task still gets a second MainActivity from the launcher icon when
 * another screen is on top.
 */
object OpenNight {
    /** The extra with the night's clientRecordId. */
    const val EXTRA_CLIENT_RECORD_ID: String = "io.github.alien2003.pulsestages.extra.CLIENT_RECORD_ID"

    /** Brings the app's task to the front instead of stacking a second activity on it (as the rationale screen does). */
    const val FLAGS: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    /** The request code of the progress notifications' launcher intent, which no night's pending intent may use. */
    private const val PROGRESS_REQUEST_CODE = 0

    /**
     * The pending intent that opens the night [clientRecordId] in the app's launcher activity, or null without one. The
     * intents of all nights are equal as filters, so the request code ([requestCodeOf]) keeps them apart: an update of
     * one never rewrites the night of another.
     */
    fun pendingIntent(
        context: Context,
        clientRecordId: String,
    ): PendingIntent? {
        val component = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return null
        val intent =
            Intent
                .makeMainActivity(component)
                .putExtra(EXTRA_CLIENT_RECORD_ID, clientRecordId)
                .addFlags(FLAGS)
        return PendingIntent.getActivity(
            context,
            requestCodeOf(clientRecordId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * The request code of the pending intent of the night [clientRecordId]: its hash, never the progress notifications'
     * [PROGRESS_REQUEST_CODE]. Two nights whose ids hash alike (about one pair in four billion) share one pending intent,
     * and the older notice then opens the newer night.
     */
    fun requestCodeOf(clientRecordId: String): Int = clientRecordId.hashCode().takeIf { it != PROGRESS_REQUEST_CODE } ?: 1

    /**
     * The night that an intent with [clientRecordId] (its [EXTRA_CLIENT_RECORD_ID]) and [flags] asks to open, or null:
     * only an intent with an id opens one, and not again when the activity is [restored] (a rotation, a process restore)
     * or relaunched from Recents ([Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY]), whose intent may be the task's base
     * intent, a notice's.
     */
    fun nightToOpen(
        clientRecordId: String?,
        flags: Int,
        restored: Boolean,
    ): String? {
        val fromHistory = flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        return clientRecordId?.takeIf { !restored && !fromHistory && it.isNotBlank() }
    }
}

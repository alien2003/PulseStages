package io.github.alien2003.pulsestages.work.saa

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Sleep as Android's public sync request (its Intent API, "Broadcasts": "Request Backup Sync"). Observed with Sleep as
 * Android 20261006, the receiver reads no extras, starts its backup and other enabled syncs and, about 10 s later and
 * only when its Health Connect integration is on, a Health Connect sync of the last 30 days: it imports new sessions
 * and attaches heart rate and SpO2 to the records it had before that sync. That Health Connect part is not documented
 * and may change in any version.
 */
object SleepAsAndroid {
    /** Sleep as Android's package; the Intent API requires it on every intent. */
    const val PACKAGE: String = "com.urbandroid.sleep"

    /** The broadcast action of the sync request. */
    const val ACTION_REQUEST_SYNC: String = "com.urbandroid.sleep.REQUEST_SYNC"

    /**
     * The sync request: the action restricted to [PACKAGE] (an explicit broadcast for the manifest-receiver limits of
     * Android 8+). No extras, data, component or flags: it carries nothing about the user, and a stopped Sleep as
     * Android (never opened, or force-stopped) is deliberately not woken.
     */
    fun requestSyncIntent(): Intent = Intent(ACTION_REQUEST_SYNC).setPackage(PACKAGE)
}

/** Sends Sleep as Android's sync request ([SleepAsAndroid.requestSyncIntent]). */
fun interface SyncRequestSender {
    /** Sends the request; false when Sleep as Android (with its receiver) is not installed: nothing was sent. */
    fun send(): Boolean
}

/**
 * [SyncRequestSender] over a broadcast. Whether the receiver exists is looked up first (that needs package visibility:
 * the app manifest queries [SleepAsAndroid.PACKAGE]); the delivery itself does not. Android reports nothing back, so
 * "sent" does not mean that Sleep as Android received it (a stopped app receives no broadcasts).
 */
class BroadcastSyncRequestSender
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : SyncRequestSender {
        override fun send(): Boolean {
            val intent = SleepAsAndroid.requestSyncIntent()
            if (receiversOf(intent).isEmpty()) return false
            context.sendBroadcast(intent)
            return true
        }

        private fun receiversOf(intent: Intent): List<ResolveInfo> {
            val packageManager = context.packageManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") // The flags overload exists from API 33 only.
                packageManager.queryBroadcastReceivers(intent, 0)
            }
        }
    }

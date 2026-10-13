package io.github.alien2003.pulsestages.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.health.connect.HealthConnectManager
import android.os.Build
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient

/** Intents that open Health Connect or the Play Store page of the Health Connect app (see research/hc-docs.md §1.6, §1.8). */
object HealthConnectIntents {
    /** Package of the Health Connect app on Android 9–13 (`DEFAULT_PROVIDER_PACKAGE_NAME` is not public). */
    const val PROVIDER_PACKAGE: String = "com.google.android.apps.healthdata"

    private const val PLAY_STORE_PACKAGE = "com.android.vending"
    private const val ONBOARDING_URI = "market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding"
    private const val WEB_URI = "https://play.google.com/store/apps/details?id=$PROVIDER_PACKAGE"

    /** Health Connect's own settings (its home screen). */
    fun settings(): Intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)

    /**
     * The permission screen of this app in Health Connect on Android 14+ (where the user can also re-enable
     * permissions after cancelling the request twice); Health Connect settings on older versions.
     */
    fun managePermissions(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS).putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
        } else {
            settings()
        }

    /** The Play Store onboarding page that installs or updates Health Connect (Android 9–13). */
    fun installOrUpdate(context: Context): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setPackage(PLAY_STORE_PACKAGE)
            data = ONBOARDING_URI.toUri()
            putExtra("overlay", true)
            putExtra("callerId", context.packageName)
        }

    /** The Play Store web page of Health Connect (fallback when the Play Store app is missing). */
    fun installOrUpdateInBrowser(): Intent = Intent(Intent.ACTION_VIEW, WEB_URI.toUri())
}

/**
 * Starts the first of [intents] that some app can handle. Returns false when none can be started (no matching
 * activity, or the activity refused the caller).
 */
fun Context.startFirstAvailable(vararg intents: Intent): Boolean =
    intents.any { intent ->
        try {
            startActivity(intent)
            true
        } catch (ignored: ActivityNotFoundException) {
            false
        } catch (ignored: SecurityException) {
            false
        }
    }

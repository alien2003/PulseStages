package io.github.alien2003.pulsestages

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.alien2003.pulsestages.platform.HealthConnectIntents
import io.github.alien2003.pulsestages.platform.startFirstAvailable
import io.github.alien2003.pulsestages.ui.rationale.PermissionsRationaleScreen
import io.github.alien2003.pulsestages.ui.theme.PulseStagesTheme

/**
 * The privacy policy Health Connect shows from its permission screen (`ACTION_SHOW_PERMISSIONS_RATIONALE` up to
 * Android 13, the `VIEW_PERMISSION_USAGE` activity-alias on Android 14+), also reachable from the Home screen. It
 * explains what is read and written, that nothing leaves the device and how to delete the app's data. Static
 * content only: it does not touch Health Connect itself.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PulseStagesTheme {
                PermissionsRationaleScreen(
                    onClose = { finish() },
                    onOpenHealthConnect = { openHealthConnect() },
                    onOpenApp = { openApp() },
                )
            }
        }
    }

    private fun openHealthConnect() {
        startFirstAvailable(HealthConnectIntents.managePermissions(this), HealthConnectIntents.settings())
    }

    /**
     * "Open PulseStages": opened from inside the app, this screen only closes; opened from Health Connect, it brings
     * the app's task to the front ([OPEN_APP_FLAGS]) instead of stacking a second MainActivity on it.
     */
    private fun openApp() {
        if (!intent.getBooleanExtra(EXTRA_FROM_APP, false)) {
            startFirstAvailable(Intent(this, MainActivity::class.java).addFlags(OPEN_APP_FLAGS))
        }
        finish()
    }

    /** Constants. */
    companion object {
        /** Set by PulseStages when it opens this screen itself (Home → "How PulseStages uses your data"). */
        const val EXTRA_FROM_APP: String = "io.github.alien2003.pulsestages.extra.FROM_APP"

        /**
         * Flags of the intent that opens MainActivity from this screen: in the existing task (found by affinity), the
         * activities above MainActivity are cleared and the existing MainActivity receives the intent instead of a
         * new instance being created; without a task, a new one is started.
         */
        const val OPEN_APP_FLAGS: Int =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
}

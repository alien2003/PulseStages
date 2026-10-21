package io.github.alien2003.pulsestages

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import io.github.alien2003.pulsestages.ui.PulseStagesNavHost
import io.github.alien2003.pulsestages.ui.theme.PulseStagesTheme
import io.github.alien2003.pulsestages.work.notify.OpenNight

/**
 * The single activity of the app: edge-to-edge Compose UI with the bottom-navigation shell. A notification of a night
 * ([OpenNight]) opens that night's detail, at launch or when the app is already open; a rotation, a process restore or a
 * relaunch from Recents does not open it again. Its intent has the launcher's identity, so when the notification starts
 * the task, the launcher icon brings that task back whatever screen is on top (an existing task keeps the intent that
 * started it). It is singleTop (see the manifest): while it is on top, an intent with another identity (on Android
 * 9–13 the progress notification's, which names the package) reaches it instead of stacking a second one.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** The night a notification asked to open, until the navigation has opened it. */
    private var openNight by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openNight =
            OpenNight.nightToOpen(
                intent.getStringExtra(OpenNight.EXTRA_CLIENT_RECORD_ID),
                intent.flags,
                restored = savedInstanceState != null,
            )
        addOnNewIntentListener { newIntent ->
            openNight =
                OpenNight.nightToOpen(
                    newIntent.getStringExtra(OpenNight.EXTRA_CLIENT_RECORD_ID),
                    newIntent.flags,
                    restored = false,
                ) ?: openNight
        }
        setContent {
            PulseStagesTheme {
                PulseStagesNavHost(openNight = openNight, onNightOpened = { openNight = null })
            }
        }
    }
}

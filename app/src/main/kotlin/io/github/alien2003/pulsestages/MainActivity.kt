package io.github.alien2003.pulsestages

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import io.github.alien2003.pulsestages.ui.PulseStagesNavHost
import io.github.alien2003.pulsestages.ui.theme.PulseStagesTheme

/** The single activity of the app: edge-to-edge Compose UI with the bottom-navigation shell. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PulseStagesTheme {
                PulseStagesNavHost()
            }
        }
    }
}

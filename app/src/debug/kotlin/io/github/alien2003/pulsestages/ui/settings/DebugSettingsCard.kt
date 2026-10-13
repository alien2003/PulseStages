package io.github.alien2003.pulsestages.ui.settings

import androidx.compose.runtime.Composable
import io.github.alien2003.pulsestages.demo.DemoModeSettingsCard

/**
 * Debug builds: the "Debug build" card of the Settings tab, with the demo-mode switch (see docs/SCREENSHOTS.md). The
 * release build has none (null there).
 */
internal val debugSettingsCard: (@Composable () -> Unit)? = { DemoModeSettingsCard() }

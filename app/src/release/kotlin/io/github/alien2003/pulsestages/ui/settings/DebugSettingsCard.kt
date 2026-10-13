package io.github.alien2003.pulsestages.ui.settings

import androidx.compose.runtime.Composable

/**
 * Release builds have no debug tools (the debug build's demo-mode switch), so the Settings tab shows no card for them
 * and none of their code or text is part of the release APK.
 */
internal val debugSettingsCard: (@Composable () -> Unit)? = null

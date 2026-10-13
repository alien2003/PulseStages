package io.github.alien2003.pulsestages.ui.common

import androidx.compose.runtime.Composable

/**
 * Release builds have no demo mode (the screens' `synthetic` flag is always false), so the banner of the debug build
 * is empty here and its text is not part of the release APK.
 */
@Composable
fun SyntheticBanner() = Unit

package io.github.alien2003.pulsestages.ui.common

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass

/**
 * True when the window is at least of the medium width class (600 dp): the inner screen of a foldable, a tablet or a
 * phone in landscape. Wide layouts show list and detail side by side and the settings in two columns; compact
 * windows (the outer screen of a foldable) use a single column.
 *
 * The medium breakpoint is used instead of expanded (840 dp) because the inner screen of book-style foldables is
 * about 700–800 dp wide at the default display size, i.e. in the medium class.
 */
@Composable
fun isWideLayout(): Boolean =
    currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

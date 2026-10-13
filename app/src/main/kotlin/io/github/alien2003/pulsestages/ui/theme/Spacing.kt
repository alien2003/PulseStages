package io.github.alien2003.pulsestages.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale of the app (Material 3 uses a 4 dp grid). Screens take every padding and gap from here.
 *
 * @property extraSmall gaps inside a component (icon to text).
 * @property small gaps between related elements.
 * @property medium screen gutters and gaps between components.
 * @property large gaps between sections.
 * @property extraLarge outer padding on wide layouts.
 */
@Immutable
data class Spacing(
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val extraLarge: Dp = 32.dp,
)

/** Provides the [Spacing] of the current theme. */
val LocalSpacing = staticCompositionLocalOf { Spacing() }

/** The [Spacing] of the current theme. */
val MaterialTheme.spacing: Spacing
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current

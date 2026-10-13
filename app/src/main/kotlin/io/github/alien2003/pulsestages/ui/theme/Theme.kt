package io.github.alien2003.pulsestages.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * The fallback light scheme: every colour role from the PulseStages tonal palettes ([Color.kt]), with the tones of
 * Material 3's light colour tokens, so no role falls back to Material's baseline (purple) colours.
 */
internal val LightColors: ColorScheme =
    lightColorScheme(
        primary = NightBlue40,
        onPrimary = NightBlue100,
        primaryContainer = NightBlue90,
        onPrimaryContainer = NightBlue10,
        inversePrimary = NightBlue80,
        secondary = Coral40,
        onSecondary = Coral100,
        secondaryContainer = Coral90,
        onSecondaryContainer = Coral10,
        tertiary = Lavender40,
        onTertiary = Lavender100,
        tertiaryContainer = Lavender90,
        onTertiaryContainer = Lavender10,
        background = Neutral98,
        onBackground = Neutral10,
        surface = Neutral98,
        onSurface = Neutral10,
        surfaceVariant = NeutralVariant90,
        onSurfaceVariant = NeutralVariant30,
        surfaceTint = NightBlue40,
        inverseSurface = Neutral20,
        inverseOnSurface = Neutral95,
        error = Red40,
        onError = Red100,
        errorContainer = Red90,
        onErrorContainer = Red10,
        outline = NeutralVariant50,
        outlineVariant = NeutralVariant80,
        scrim = Neutral0,
        surfaceBright = Neutral98,
        surfaceContainer = Neutral94,
        surfaceContainerHigh = Neutral92,
        surfaceContainerHighest = Neutral90,
        surfaceContainerLow = Neutral96,
        surfaceContainerLowest = Neutral100,
        surfaceDim = Neutral87,
        primaryFixed = NightBlue90,
        primaryFixedDim = NightBlue80,
        onPrimaryFixed = NightBlue10,
        onPrimaryFixedVariant = NightBlue30,
        secondaryFixed = Coral90,
        secondaryFixedDim = Coral80,
        onSecondaryFixed = Coral10,
        onSecondaryFixedVariant = Coral30,
        tertiaryFixed = Lavender90,
        tertiaryFixedDim = Lavender80,
        onTertiaryFixed = Lavender10,
        onTertiaryFixedVariant = Lavender30,
    )

/** The fallback dark scheme, like [LightColors] with the tones of Material 3's dark colour tokens. */
internal val DarkColors: ColorScheme =
    darkColorScheme(
        primary = NightBlue80,
        onPrimary = NightBlue20,
        primaryContainer = NightBlue30,
        onPrimaryContainer = NightBlue90,
        inversePrimary = NightBlue40,
        secondary = Coral80,
        onSecondary = Coral20,
        secondaryContainer = Coral30,
        onSecondaryContainer = Coral90,
        tertiary = Lavender80,
        onTertiary = Lavender20,
        tertiaryContainer = Lavender30,
        onTertiaryContainer = Lavender90,
        background = Neutral6,
        onBackground = Neutral90,
        surface = Neutral6,
        onSurface = Neutral90,
        surfaceVariant = NeutralVariant30,
        onSurfaceVariant = NeutralVariant80,
        surfaceTint = NightBlue80,
        inverseSurface = Neutral90,
        inverseOnSurface = Neutral20,
        error = Red80,
        onError = Red20,
        errorContainer = Red30,
        onErrorContainer = Red90,
        outline = NeutralVariant60,
        outlineVariant = NeutralVariant30,
        scrim = Neutral0,
        surfaceBright = Neutral24,
        surfaceContainer = Neutral12,
        surfaceContainerHigh = Neutral17,
        surfaceContainerHighest = Neutral22,
        surfaceContainerLow = Neutral10,
        surfaceContainerLowest = Neutral4,
        surfaceDim = Neutral6,
        primaryFixed = NightBlue90,
        primaryFixedDim = NightBlue80,
        onPrimaryFixed = NightBlue10,
        onPrimaryFixedVariant = NightBlue30,
        secondaryFixed = Coral90,
        secondaryFixedDim = Coral80,
        onSecondaryFixed = Coral10,
        onSecondaryFixedVariant = Coral30,
        tertiaryFixed = Lavender90,
        tertiaryFixedDim = Lavender80,
        onTertiaryFixed = Lavender10,
        onTertiaryFixedVariant = Lavender30,
    )

/**
 * App theme: Material You dynamic colour from the wallpaper on Android 12+ (API 31), the PulseStages palette otherwise;
 * light or dark following the system; Material 3 default typography and the app's [Spacing] scale.
 */
@Composable
fun PulseStagesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                DarkColors
            }

            else -> {
                LightColors
            }
        }
    CompositionLocalProvider(LocalSpacing provides Spacing()) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

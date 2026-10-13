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
import androidx.compose.ui.platform.LocalContext

private val LightColors: ColorScheme =
    lightColorScheme(
        primary = NightBlue40,
        onPrimary = Neutral99,
        primaryContainer = NightBlue90,
        onPrimaryContainer = NightBlue10,
        secondary = Coral40,
        onSecondary = Neutral99,
        secondaryContainer = Coral90,
        onSecondaryContainer = Coral20,
        tertiary = Lavender40,
        background = Neutral99,
        onBackground = Neutral10,
        surface = Neutral99,
        onSurface = Neutral10,
    )

private val DarkColors: ColorScheme =
    darkColorScheme(
        primary = NightBlue80,
        onPrimary = NightBlue20,
        primaryContainer = NightBlue40,
        onPrimaryContainer = NightBlue90,
        secondary = Coral80,
        onSecondary = Coral20,
        secondaryContainer = Coral40,
        onSecondaryContainer = Coral90,
        tertiary = Lavender80,
        background = Neutral10,
        onBackground = Neutral90,
        surface = Neutral10,
        onSurface = Neutral90,
    )

/** App theme: Material You dynamic colour on Android 12+ (API 31), the PulseStages palette otherwise. */
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
    MaterialTheme(colorScheme = colorScheme, content = content)
}

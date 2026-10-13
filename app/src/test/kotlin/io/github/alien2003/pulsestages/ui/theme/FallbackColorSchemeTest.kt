package io.github.alien2003.pulsestages.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** The fallback schemes (Android 9–11, dynamic colour off) set every role from the app's palettes. */
class FallbackColorSchemeTest {
    /** Every role whose Material baseline value is a purple / pink / purple-grey tone (all but error, black, white). */
    private val roles: Map<String, (ColorScheme) -> Color> =
        mapOf(
            "primary" to { it.primary },
            "onPrimary" to { it.onPrimary },
            "primaryContainer" to { it.primaryContainer },
            "onPrimaryContainer" to { it.onPrimaryContainer },
            "inversePrimary" to { it.inversePrimary },
            "secondary" to { it.secondary },
            "onSecondary" to { it.onSecondary },
            "secondaryContainer" to { it.secondaryContainer },
            "onSecondaryContainer" to { it.onSecondaryContainer },
            "tertiary" to { it.tertiary },
            "onTertiary" to { it.onTertiary },
            "tertiaryContainer" to { it.tertiaryContainer },
            "onTertiaryContainer" to { it.onTertiaryContainer },
            "background" to { it.background },
            "onBackground" to { it.onBackground },
            "surface" to { it.surface },
            "onSurface" to { it.onSurface },
            "surfaceVariant" to { it.surfaceVariant },
            "onSurfaceVariant" to { it.onSurfaceVariant },
            "surfaceTint" to { it.surfaceTint },
            "inverseSurface" to { it.inverseSurface },
            "inverseOnSurface" to { it.inverseOnSurface },
            "outline" to { it.outline },
            "outlineVariant" to { it.outlineVariant },
            "surfaceBright" to { it.surfaceBright },
            "surfaceContainer" to { it.surfaceContainer },
            "surfaceContainerHigh" to { it.surfaceContainerHigh },
            "surfaceContainerHighest" to { it.surfaceContainerHighest },
            "surfaceContainerLow" to { it.surfaceContainerLow },
            "surfaceDim" to { it.surfaceDim },
            "primaryFixed" to { it.primaryFixed },
            "secondaryFixed" to { it.secondaryFixed },
            "tertiaryFixed" to { it.tertiaryFixed },
        )

    @Test
    fun `no role of the light fallback is Material's baseline colour`() {
        val baseline = lightColorScheme()
        roles.forEach { (name, role) ->
            if (role(baseline) != Color.White) assertNotEquals(role(baseline), role(LightColors), "light $name")
        }
    }

    @Test
    fun `no role of the dark fallback is Material's baseline colour`() {
        val baseline = darkColorScheme()
        roles.forEach { (name, role) ->
            if (role(baseline) != Color.White) assertNotEquals(role(baseline), role(DarkColors), "dark $name")
        }
    }
}

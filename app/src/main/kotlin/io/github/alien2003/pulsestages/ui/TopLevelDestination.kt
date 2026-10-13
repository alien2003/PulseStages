package io.github.alien2003.pulsestages.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.alien2003.pulsestages.R

/** The four tabs of the bottom navigation bar. */
enum class TopLevelDestination(
    /** Route (or graph) the tab opens. */
    val route: Route,
    /** Tab label. */
    @param:StringRes val label: Int,
    /** Tab icon. */
    @param:DrawableRes val icon: Int,
) {
    /** Home. */
    HOME(Route.Home, R.string.tab_home, R.drawable.ic_home),

    /** Nights. */
    NIGHTS(Route.NightsGraph, R.string.tab_nights, R.drawable.ic_nights),

    /** Compare. */
    COMPARE(Route.Compare, R.string.tab_compare, R.drawable.ic_compare),

    /** Settings. */
    SETTINGS(Route.SettingsGraph, R.string.tab_settings, R.drawable.ic_settings),
}

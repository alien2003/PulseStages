package io.github.alien2003.pulsestages.ui

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations (navigation-compose serializes them with kotlinx-serialization). */
@Serializable
sealed interface Route {
    /** Home tab: Health Connect status and permissions. */
    @Serializable
    data object Home : Route

    /** Graph of the Nights tab (list and detail). */
    @Serializable
    data object NightsGraph : Route

    /** List of estimated nights. */
    @Serializable
    data object Nights : Route

    /**
     * Detail of one night.
     *
     * @property clientRecordId the night's deterministic Health Connect clientRecordId.
     */
    @Serializable
    data class NightDetail(
        val clientRecordId: String,
    ) : Route

    /** Compare tab: nights with real stages from other apps. */
    @Serializable
    data object Compare : Route

    /** Graph of the Settings tab (settings, sources, log). */
    @Serializable
    data object SettingsGraph : Route

    /** All settings. */
    @Serializable
    data object Settings : Route

    /** Sleep sources and their priority. */
    @Serializable
    data object Sources : Route

    /** Internal log. */
    @Serializable
    data object Log : Route
}

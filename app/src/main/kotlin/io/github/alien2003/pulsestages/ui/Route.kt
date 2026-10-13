package io.github.alien2003.pulsestages.ui

import kotlinx.serialization.Serializable

/** Type-safe navigation destinations (navigation-compose serializes them with kotlinx-serialization). */
@Serializable
sealed interface Route {
    @Serializable
    data object Home : Route
}

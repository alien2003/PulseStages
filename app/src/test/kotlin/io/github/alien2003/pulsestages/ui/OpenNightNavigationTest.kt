package io.github.alien2003.pulsestages.ui

import android.app.Application
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * How the night of a notification is opened ([openNight]) on Robolectric (JUnit 4 via the Vintage engine), over a
 * NavHostController with the graph of PulseStagesNavHost (its routes and nesting, without the screens): from any tab,
 * and from the detail of another night, it ends on the Nights tab with the list below one detail of that night, so the
 * detail finds the list's back stack entry whose ViewModels it shares; the other tabs keep their own back stacks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OpenNightNavigationTest {
    private val navController =
        NavHostController(RuntimeEnvironment.getApplication()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            graph =
                createGraph(startDestination = Route.Home) {
                    composable<Route.Home> {}
                    navigation<Route.NightsGraph>(startDestination = Route.Nights) {
                        composable<Route.Nights> {}
                        composable<Route.NightDetail> {}
                    }
                    composable<Route.Compare> {}
                    navigation<Route.SettingsGraph>(startDestination = Route.Settings) {
                        composable<Route.Settings> {}
                        composable<Route.Sources> {}
                        composable<Route.Log> {}
                    }
                }
        }

    /** The back stack below the root graph: route names, a night's detail with its night. */
    private fun backStack(): List<String> =
        navController.currentBackStack.value
            .filter { it.destination != navController.graph }
            .map { entry ->
                if (entry.destination.hasRoute<Route.NightDetail>()) {
                    "NightDetail(${entry.toRoute<Route.NightDetail>().clientRecordId})"
                } else {
                    entry.destination.route
                        .orEmpty()
                        .substringAfterLast('.')
                }
            }

    /** Fails unless the Nights tab shows the detail of [clientRecordId] above the list. */
    private fun assertShowsNight(clientRecordId: String) {
        assertEquals(listOf("Home", "NightsGraph", "Nights", "NightDetail($clientRecordId)"), backStack())
        assertNotNull("the detail finds the list's entry", navController.getBackStackEntry<Route.Nights>())
    }

    @Test
    fun fromHomeTheNightOpensAboveTheListOfTheNightsTab() {
        navController.openNight("ps:0a")

        assertShowsNight("ps:0a")
        navController.navigateUp()
        assertEquals(listOf("Home", "NightsGraph", "Nights"), backStack())
    }

    @Test
    fun fromTheLogInSettingsTheNightOpensAndTheSettingsTabKeepsItsLog() {
        navController.navigateToTab(TopLevelDestination.SETTINGS)
        navController.navigate(Route.Log)

        navController.openNight("ps:0a")

        assertShowsNight("ps:0a")
        navController.navigateToTab(TopLevelDestination.SETTINGS)
        assertEquals(listOf("Home", "SettingsGraph", "Settings", "Log"), backStack())
    }

    @Test
    fun aNightOpensInPlaceOfTheDetailOfAnotherNight() {
        navController.openNight("ps:0b")

        navController.openNight("ps:0a")
        assertShowsNight("ps:0a")

        // The Nights tab, left on the detail of a night, restores it before the next night replaces it.
        navController.navigateToTab(TopLevelDestination.COMPARE)
        navController.openNight("ps:0c")
        assertShowsNight("ps:0c")
    }

    @Test
    fun theSameNightTwiceKeepsOneDetail() {
        navController.openNight("ps:0a")

        navController.openNight("ps:0a")

        assertShowsNight("ps:0a")
    }
}

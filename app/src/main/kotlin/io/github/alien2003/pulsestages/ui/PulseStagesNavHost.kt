package io.github.alien2003.pulsestages.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.alien2003.pulsestages.R
import io.github.alien2003.pulsestages.ui.compare.CompareRoute
import io.github.alien2003.pulsestages.ui.home.HomeRoute
import io.github.alien2003.pulsestages.ui.log.LogRoute
import io.github.alien2003.pulsestages.ui.nights.NIGHT_DETAIL_VIEW_MODEL_KEY
import io.github.alien2003.pulsestages.ui.nights.NightDetailRoute
import io.github.alien2003.pulsestages.ui.nights.NightsRoute
import io.github.alien2003.pulsestages.ui.nights.NightsViewModel
import io.github.alien2003.pulsestages.ui.settings.SettingsRoute
import io.github.alien2003.pulsestages.ui.sources.SourcesRoute

/**
 * The app shell: a Material 3 NavigationBar with the four tabs and the navigation graph. Every tab keeps its own back
 * stack (state is saved and restored when switching tabs); Sources and Log belong to the Settings tab, the night
 * detail to the Nights tab.
 */
@Composable
fun PulseStagesNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { tab ->
                    val label = stringResource(tab.label)
                    NavigationBarItem(
                        selected = destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true,
                        onClick = { navController.navigateToTab(tab) },
                        icon = { Icon(painter = painterResource(tab.icon), contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Route.Home,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            composable<Route.Home> {
                HomeRoute()
            }
            navigation<Route.NightsGraph>(startDestination = Route.Nights) {
                composable<Route.Nights> {
                    NightsRoute(onOpenNight = { id -> navController.navigate(Route.NightDetail(id)) })
                }
                composable<Route.NightDetail> { entry ->
                    val clientRecordId = entry.toRoute<Route.NightDetail>().clientRecordId
                    // The list and the detail share the Nights destination's ViewModels (selection, night detail),
                    // so the detail keeps its state when the window changes between compact and wide.
                    val nightsEntry = remember(entry) { navController.getBackStackEntry<Route.Nights>() }
                    val nightsViewModel: NightsViewModel = hiltViewModel(nightsEntry)
                    NightDetailRoute(
                        clientRecordId = clientRecordId,
                        viewModel = hiltViewModel(nightsEntry, key = NIGHT_DETAIL_VIEW_MODEL_KEY),
                        onBack = { navController.navigateUp() },
                        onWideLayout = {
                            nightsViewModel.onDetailWidened(clientRecordId)
                            navController.popBackStack<Route.Nights>(inclusive = false)
                        },
                    )
                }
            }
            composable<Route.Compare> {
                CompareRoute()
            }
            navigation<Route.SettingsGraph>(startDestination = Route.Settings) {
                composable<Route.Settings> {
                    SettingsRoute(
                        onOpenSources = { navController.navigate(Route.Sources) },
                        onOpenLog = { navController.navigate(Route.Log) },
                    )
                }
                composable<Route.Sources> {
                    SourcesRoute(onBack = { navController.navigateUp() })
                }
                composable<Route.Log> {
                    LogRoute(onBack = { navController.navigateUp() })
                }
            }
        }
    }
}

/** Switches to [tab], keeping one copy of each tab on the back stack and restoring its saved state. */
private fun NavHostController.navigateToTab(tab: TopLevelDestination) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

package com.beakon.companion.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.beakon.companion.ui.screens.HistoryScreen
import com.beakon.companion.ui.screens.MapScreen
import com.beakon.companion.ui.screens.SetupScreen
import com.beakon.companion.ui.screens.StatusScreen

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("status", "Status", Icons.Filled.LocationOn),
    Destination("map", "Map", Icons.Filled.Place),
    Destination("history", "History", Icons.AutoMirrored.Filled.List),
)

@Composable
fun BeakonApp(viewModel: AppViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val loggedIn = viewModel.loggedIn

    // Logout must not strand the user on Status (where the bottom bar is gone).
    // Navigating here also clears the signed-in back stack.
    LaunchedEffect(loggedIn, currentRoute) {
        if (NavigationDecision.shouldNavigateToSetup(loggedIn, currentRoute)) {
            navController.navigate("setup") {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (loggedIn) {
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (loggedIn) "status" else "setup",
            modifier = Modifier.padding(padding),
        ) {
            composable("setup") {
                SetupScreen(
                    viewModel = viewModel,
                    onLoggedIn = {
                        navController.navigate("status") {
                            popUpTo("setup") { inclusive = true }
                        }
                    },
                )
            }
            composable("status") {
                StatusScreen(
                    viewModel = viewModel,
                )
            }
            composable("map") { MapScreen(viewModel) }
            composable("history") { HistoryScreen(viewModel) }
        }
    }
}

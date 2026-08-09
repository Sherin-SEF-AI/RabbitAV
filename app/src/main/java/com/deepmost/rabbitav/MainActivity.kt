package com.deepmost.rabbitav

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.deepmost.rabbitav.app.ui.RabbitAvTheme
import com.deepmost.rabbitav.feature.calibration.CalibrationScreen
import com.deepmost.rabbitav.feature.debug.DebugScreen
import com.deepmost.rabbitav.feature.drive.DriveScreen
import com.deepmost.rabbitav.feature.drive.DriveViewModel
import com.deepmost.rabbitav.feature.map.MapScreen
import com.deepmost.rabbitav.feature.onboarding.OnboardingScreen
import com.deepmost.rabbitav.feature.settings.OemGuidanceScreen
import com.deepmost.rabbitav.feature.settings.PrivacyScreen
import com.deepmost.rabbitav.feature.settings.SettingsScreen
import com.deepmost.rabbitav.feature.trips.TripDetailScreen
import com.deepmost.rabbitav.feature.trips.TripsScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen on while the HUD is visible — this is a windshield app.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            RabbitAvTheme {
                RabbitAvApp()
            }
        }
    }
}

private data class NavItem(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun RabbitAvApp() {
    val navController = rememberNavController()
    val items = listOf(
        NavItem("drive", R.string.nav_drive, Icons.Filled.Navigation),
        NavItem("map", R.string.nav_map, Icons.Filled.Map),
        NavItem("trips", R.string.nav_trips, Icons.Filled.Route),
        NavItem("settings", R.string.nav_settings, Icons.Filled.Settings),
        NavItem("debug", R.string.nav_debug, Icons.Filled.BugReport),
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            // The nav bar hides on full-screen flows (onboarding, calibration).
            if (currentRoute != null && currentRoute in items.map { it.route }) {
                NavigationBar {
                    for (item in items) {
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "gate",
            modifier = Modifier.padding(padding),
        ) {
            composable("gate") {
                OnboardingScreen(
                    onFinished = {
                        navController.navigate("drive") {
                            popUpTo("gate") { inclusive = true }
                        }
                    }
                )
            }
            composable("drive") {
                val vm: DriveViewModel = hiltViewModel()
                DriveScreen(
                    viewModel = vm,
                    onNavigateToCalibration = { navController.navigate("calibration") },
                )
            }
            composable("calibration") {
                CalibrationScreen(onDone = { navController.popBackStack() })
            }
            composable("map") { MapScreen() }
            composable("trips") {
                TripsScreen(onTripClick = { id -> navController.navigate("trip/$id") })
            }
            composable("trip/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L
                TripDetailScreen(tripId = id, onBack = { navController.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(
                    onOpenOemGuide = { navController.navigate("oem") },
                    onOpenPrivacy = { navController.navigate("privacy") },
                )
            }
            composable("oem") { OemGuidanceScreen(onBack = { navController.popBackStack() }) }
            composable("privacy") { PrivacyScreen(onBack = { navController.popBackStack() }) }
            composable("debug") { DebugScreen() }
        }
    }
}

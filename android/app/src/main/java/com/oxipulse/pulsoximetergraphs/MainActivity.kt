package com.oxipulse.pulsoximetergraphs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.oxipulse.pulsoximetergraphs.data.ble.BlePermissions
import com.oxipulse.pulsoximetergraphs.data.settings.ThemeMode
import com.oxipulse.pulsoximetergraphs.ui.graphs.GraphScreen
import com.oxipulse.pulsoximetergraphs.ui.settings.SettingsScreen
import com.oxipulse.pulsoximetergraphs.ui.theme.PulsoximeterGraphsTheme

private const val ROUTE_GRAPH = "graph"
private const val ROUTE_SETTINGS = "settings"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appContainer = (application as PulsoxApplication).container

        setContent {
            val themeMode by appContainer.themePreferenceRepository.themeMode.collectAsState()
            val isDarkTheme = themeMode == ThemeMode.DARK

            PulsoximeterGraphsTheme(darkTheme = isDarkTheme) {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) { /* Results are re-checked lazily by BlePermissions before each BLE action. */ }

                // minSdk is already 31 (API S), so the modern runtime Bluetooth permissions
                // always apply here — no API-level guard needed.
                LaunchedEffect(Unit) {
                    if (!BlePermissions.hasAllPermissions(this@MainActivity)) {
                        permissionLauncher.launch(BlePermissions.REQUIRED)
                    }
                }

                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = ROUTE_GRAPH) {
                    composable(ROUTE_GRAPH) {
                        GraphScreen(
                            appContainer = appContainer,
                            onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                            isDarkTheme = isDarkTheme,
                            onToggleTheme = { appContainer.themePreferenceRepository.toggle() },
                        )
                    }
                    composable(ROUTE_SETTINGS) {
                        SettingsScreen(
                            appContainer = appContainer,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}

package com.openautolink.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.openautolink.app.ui.diagnostics.DiagnosticsScreen
import com.openautolink.app.ui.carplay.CarPlayPinScreen
import com.openautolink.app.ui.projection.ProjectionScreen
import com.openautolink.app.ui.projection.ProjectionViewModel
import com.openautolink.app.ui.settings.SettingsScreen
import com.openautolink.app.ui.settings.SettingsViewModel
import com.openautolink.app.ui.settings.ViewportEditorScreen

object AppDestinations {
    const val PROJECTION = "projection"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val VIEWPORT_EDITOR = "viewport_editor"
    const val CARPLAY_PIN = "carplay_pin/{pin}"
}

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    // Share ViewModels across screens
    val settingsViewModel: SettingsViewModel = viewModel()
    val projectionViewModel: ProjectionViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = AppDestinations.PROJECTION
    ) {
        composable(AppDestinations.PROJECTION) {
            ProjectionScreen(
                viewModel = projectionViewModel,
                onNavigateToSettings = {
                    navController.navigate(AppDestinations.SETTINGS)
                }
            )
        }
        composable(AppDestinations.SETTINGS) {
            val projectionUiState by projectionViewModel.uiState.collectAsStateWithLifecycle()
            val pairedPhones by projectionViewModel.pairedPhones.collectAsStateWithLifecycle()

            // Forward paired phones from session to settings ViewModel
            LaunchedEffect(pairedPhones) {
                settingsViewModel.onPairedPhonesReceived(pairedPhones)
            }

            SettingsScreen(
                viewModel = settingsViewModel,
                sessionState = projectionUiState.sessionState,
                onSaveAndConnect = { projectionViewModel.connect() },
                onBack = { navController.popBackStack() },
                onNavigateToDiagnostics = {
                    navController.navigate(AppDestinations.DIAGNOSTICS)
                },
                onNavigateToViewportEditor = {
                    navController.navigate(AppDestinations.VIEWPORT_EDITOR)
                },
            )
        }
        composable(AppDestinations.DIAGNOSTICS) {
            DiagnosticsScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(AppDestinations.VIEWPORT_EDITOR) {
            val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            ViewportEditorScreen(
                initialWidth = uiState.customViewportWidth,
                initialHeight = uiState.customViewportHeight,
                aspectRatioLocked = uiState.viewportAspectRatioLocked,
                onDone = { width, height, ratioLocked ->
                    settingsViewModel.updateCustomViewport(width, height)
                    settingsViewModel.updateViewportAspectRatioLocked(ratioLocked)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(AppDestinations.CARPLAY_PIN) { backStackEntry ->
            val pin = backStackEntry.arguments?.getString("pin") ?: ""
            CarPlayPinScreen(pin = pin)
        }
    }
}

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
import com.openautolink.app.ui.projection.ProjectionScreen
import com.openautolink.app.ui.projection.ProjectionViewModel
import com.openautolink.app.ui.settings.SettingsScreen
import com.openautolink.app.ui.settings.SettingsViewModel
import com.openautolink.app.ui.settings.SafeAreaEditorScreen

object AppDestinations {
    const val PROJECTION = "projection"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val SAFE_AREA_EDITOR = "safe_area_editor"
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
                onSaveAndConnect = {
                    // No longer used — Save button shows dialog with instructions.
                    // Settings are saved automatically by DataStore.
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
                onNavigateToDiagnostics = {
                    navController.navigate(AppDestinations.DIAGNOSTICS)
                },
                onNavigateToSafeAreaEditor = {
                    navController.navigate(AppDestinations.SAFE_AREA_EDITOR)
                },
            )
        }
        composable(AppDestinations.DIAGNOSTICS) {
            DiagnosticsScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(AppDestinations.SAFE_AREA_EDITOR) {
            val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            SafeAreaEditorScreen(
                initialTop = uiState.safeAreaTop,
                initialBottom = uiState.safeAreaBottom,
                initialLeft = uiState.safeAreaLeft,
                initialRight = uiState.safeAreaRight,
                displayMode = uiState.displayMode,
                onDone = { top, bottom, left, right ->
                    settingsViewModel.updateSafeAreaInsets(top, bottom, left, right)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}

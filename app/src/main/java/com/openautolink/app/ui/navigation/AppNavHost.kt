package com.openautolink.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.openautolink.app.ui.diagnostics.DiagnosticsScreen
import com.openautolink.app.ui.projection.ProjectionScreen
import com.openautolink.app.ui.projection.ProjectionViewModel
import com.openautolink.app.ui.projection.ProjectionViewport
import com.openautolink.app.ui.settings.SettingsScreen
import com.openautolink.app.ui.settings.SettingsViewModel
import com.openautolink.app.ui.settings.SafeAreaEditorScreen
import com.openautolink.app.ui.settings.CarPlaySafeEditor
import com.openautolink.app.ui.settings.applySettingsAndRestart
import kotlinx.coroutines.launch

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
    val settingsScope = rememberCoroutineScope()

    NavHost(
        navController = navController,
        startDestination = AppDestinations.PROJECTION
    ) {
        composable(AppDestinations.PROJECTION) {
            val projectionUiState by projectionViewModel.uiState.collectAsStateWithLifecycle()
            val carPlayState by projectionViewModel.carPlayState.collectAsStateWithLifecycle()
            val reconnectProjection = {
                if (carPlayState.selected) projectionViewModel.reconnectCarPlay()
                else settingsViewModel.saveAndReconnect()
            }

            ProjectionScreen(
                viewModel = projectionViewModel,
                onNavigateToSettings = {
                    // Legacy — no longer used, settings are an overlay now
                },
                onReconnect = reconnectProjection,
                settingsOverlay = { onBack, onShowDiagnostics, onCarPlaySafeEditor ->
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        sessionState = projectionUiState.sessionState,
                        activeProtocolStatus = if (carPlayState.selected) when {
                            carPlayState.evidence.videoRendered && carPlayState.evidence.runtimeAuthenticated -> "CarPlay · video active"
                            carPlayState.busy -> "CarPlay · connecting"
                            else -> "CarPlay · stopped"
                        } else null,
                        onSaveAndConnect = {
                            settingsScope.launch {
                                applySettingsAndRestart(
                                    settingsViewModel::commitCarPlaySettings,
                                    { projectionViewModel.reconnectAfterSettingsSave(settingsViewModel::saveAndReconnect) },
                                    onBack,
                                )
                            }
                        },
                        onBack = onBack,
                        onNavigateToDiagnostics = onShowDiagnostics,
                        onNavigateToCarPlaySafeAreaEditor = onCarPlaySafeEditor,
                        onNavigateToSafeAreaEditor = {
                            navController.navigate(AppDestinations.SAFE_AREA_EDITOR)
                        },
                    )
                },
                diagnosticsOverlay = { onBack ->
                    DiagnosticsScreen(onBack = onBack)
                },
                carPlaySafeEditorOverlay = { onBack ->
                    CarPlaySafeEditor(settingsViewModel, onBack)
                },
            )
        }
        // Ordinary settings destination has no projection Surface to borrow. Its owning
        // Activity/NavHost rectangle uses the identical viewport policy, outside SettingsScreen.
        composable(AppDestinations.SETTINGS) {
            val uiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            var showCarPlaySafeEditor by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onBack = { navController.popBackStack() },
                    onSaveAndConnect = {
                        settingsScope.launch {
                            applySettingsAndRestart(
                                settingsViewModel::commitCarPlaySettings,
                                { projectionViewModel.reconnectAfterSettingsSave(settingsViewModel::saveAndReconnect) },
                                { navController.popBackStack() },
                            )
                        }
                    },
                    onNavigateToDiagnostics = { navController.navigate(AppDestinations.DIAGNOSTICS) },
                    onNavigateToSafeAreaEditor = { navController.navigate(AppDestinations.SAFE_AREA_EDITOR) },
                    onNavigateToCarPlaySafeAreaEditor = { showCarPlaySafeEditor = true },
                )
                if (showCarPlaySafeEditor) ProjectionViewport(uiState.displayMode) {
                    CarPlaySafeEditor(settingsViewModel) { showCarPlaySafeEditor = false }
                }
            }
        }
        composable(AppDestinations.DIAGNOSTICS) {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
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

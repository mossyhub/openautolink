package com.openautolink.app.ui.navigation

import androidx.compose.runtime.Composable
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

/** The exact callback shared by overlay and destination; persistence precedes every runtime action. */
@Composable
internal fun rememberProductionSettingsSaveAction(
    settingsViewModel: SettingsViewModel,
    projectionViewModel: ProjectionViewModel,
    commitClusterLayout: (suspend () -> Boolean)?,
    onBack: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        val receipt = com.openautolink.app.ui.settings.SettingsSaveTransaction.next()
        receipt.event("click", "received", "adapter=${commitClusterLayout != null}")
        // Navigation completion must return to Android Main even when persistence resumes on IO.
        scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate + receipt) {
            applySettingsAndRestart(
                {
                    // A missing adapter has never accepted a cluster edit: its entire pane is
                    // unavailable. Do not invent a successful layout acknowledgement or require
                    // the optional store for an ordinary-settings-only transaction. Once installed,
                    // the owner retains the adapter and every Save awaits its real commit.
                    settingsViewModel.commitSettings(commitClusterLayout)
                },
                {
                    receipt.event("reconnect_dispatch", "requested", "source=save kind=${if (projectionViewModel.carPlayState.value.selected) "CarPlay" else "AndroidAuto"}")
                    projectionViewModel.reconnectAfterSettingsSave(settingsViewModel::saveAndReconnect)
                },
                { onBack(); receipt.event("close", "completed") },
            )
        }
    }
}

/** Exact callback used by the rendered projection Reconnect menu, separate from committed Save. */
@Composable
internal fun rememberProductionProjectionReconnectAction(
    settingsViewModel: SettingsViewModel,
    projectionViewModel: ProjectionViewModel,
): () -> Unit = {
    com.openautolink.app.ui.settings.SettingsSaveTransaction.next().event("reconnect_dispatch", "requested", "source=menu commitsDraft=false")
    if (projectionViewModel.carPlayState.value.selected) projectionViewModel.reconnectCarPlay()
    else projectionViewModel.reconnectAndroidAutoWithLiveHost(settingsViewModel::saveAndReconnect)
}

/** Production route: initialize the real binding only after the retained projection VM exists.
 * The host supplies preview admission explicitly; there is no disconnected/no-op default. */
@Composable
fun AppNavHost(
    clusterPreviewFactory: (android.content.Context, () -> Boolean) -> com.openautolink.app.cluster.ClusterCalibrationPreview,
    navController: NavHostController = rememberNavController(),
    secondaryOutputStatuses: kotlinx.coroutines.flow.StateFlow<Map<com.openautolink.app.secondary.SecondaryOutputId,
        com.openautolink.app.secondary.SecondaryOutputStatus>>? = null,
    announcementStore: com.openautolink.app.announcements.ReleaseAnnouncementStore? = null,
    announcementVersionCode: Int = com.openautolink.app.BuildConfig.VERSION_CODE,
) {
    val projectionViewModel: ProjectionViewModel = viewModel()
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // ProjectionViewModel initializes the actual process SessionManager before this lookup.
    val manager = remember(projectionViewModel) {
        requireNotNull(com.openautolink.app.session.SessionManager.instanceOrNull())
    }
    val preferences = remember(context) { com.openautolink.app.data.AppPreferences.getInstance(context) }
    val preview = remember(context, projectionViewModel, manager) {
        clusterPreviewFactory(context) {
            manager.sessionState.value == com.openautolink.app.session.SessionState.IDLE &&
                !projectionViewModel.carPlayState.value.selected && !projectionViewModel.carPlayState.value.busy
        }
    }
    var initializationError by remember { mutableStateOf<String?>(null) }
    val bindings = com.openautolink.app.ui.settings.rememberProductionClusterSettingsBinding(
        context, preferences, manager.sessionState, manager.clusterVideoStatus,
        projectionViewModel.carPlayState, preview,
        onInitializationError = { initializationError = it },
    )
    val secondarySource = com.openautolink.app.ui.settings.rememberProductionSecondaryDisplaysSource(context, secondaryOutputStatuses ?: manager.secondaryOutputStatuses)
    // Optional cluster loading must never replace the real projection/navigation tree.
    AppNavHost(clusterBindings = bindings, navController = navController,
        clusterInitializationError = initializationError, secondarySource = secondarySource,
        announcementStore = announcementStore, announcementVersionCode = announcementVersionCode)
}

@Composable
fun AppNavHost(
    clusterBindings: com.openautolink.app.ui.settings.ClusterSettingsBindings?,
    navController: NavHostController = rememberNavController(),
    clusterInitializationError: String? = null,
    secondarySource: com.openautolink.app.ui.settings.SecondaryDisplaysSource? = null,
    announcementStore: com.openautolink.app.announcements.ReleaseAnnouncementStore? = null,
    announcementVersionCode: Int = com.openautolink.app.BuildConfig.VERSION_CODE,
) {
    // Share ViewModels across screens
    val settingsViewModel: SettingsViewModel = viewModel()
    val projectionViewModel: ProjectionViewModel = viewModel()
    val audioBindings = com.openautolink.app.ui.settings.rememberProductionAudioBindings(projectionViewModel::reconnectCarPlay)
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val mediaActivity = androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity
    val mediaDiagnosticsViewModel = remember(mediaActivity) {
        androidx.lifecycle.ViewModelProvider(mediaActivity)[com.openautolink.app.ui.diagnostics.MediaDiagnosticsViewModel::class.java]
    }
    val microphoneBinding = remember(projectionViewModel, appContext) {
        com.openautolink.app.ui.diagnostics.MicrophoneDiagnosticBinding(
            appContext, projectionViewModel::microphoneDiagnosticEligible,
            appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE),
            projectionViewModel::parkedMicrophoneDiagnosticEligible,
            projectionViewModel::stopProjectionForMicrophoneDiagnostic,
            projectionViewModel::returnFromMicrophoneDiagnostic,
            { projectionViewModel.diagnosticProjectionStatus },
        )
    }
    androidx.compose.runtime.DisposableEffect(microphoneBinding) {
        onDispose { microphoneBinding.close() }
    }

    NavHost(
        navController = navController,
        startDestination = AppDestinations.PROJECTION
    ) {
        composable(AppDestinations.PROJECTION) {
            val projectionUiState by projectionViewModel.uiState.collectAsStateWithLifecycle()
            val carPlayState by projectionViewModel.carPlayState.collectAsStateWithLifecycle()
            val reconnectProjection = rememberProductionProjectionReconnectAction(settingsViewModel, projectionViewModel)

            var announcementSettings by remember { mutableStateOf(false) }
            ProjectionScreen(
                viewModel = projectionViewModel,
                announcementStore = announcementStore,
                announcementVersionCode = announcementVersionCode,
                onAnnouncementOpenSettings = { announcementSettings = true },
                onNavigateToSettings = {
                    // Legacy — no longer used, settings are an overlay now
                },
                onReconnect = reconnectProjection,
                settingsOverlay = { onBack, onShowDiagnostics, onCarPlaySafeEditor ->
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        onDispose { announcementSettings = false }
                    }
                    SettingsScreen(
                        initialSecondaryDisplays = announcementSettings,
                        clusterBindings = clusterBindings,
                        clusterInitializationError = clusterInitializationError,
                        secondarySource = secondarySource,
                        viewModel = settingsViewModel,
                        audioBindings = audioBindings,
                        sessionState = projectionUiState.sessionState,
                        activeProtocolStatus = if (carPlayState.selected) when {
                            carPlayState.evidence.videoRendered && carPlayState.evidence.runtimeAuthenticated -> "CarPlay · video active"
                            carPlayState.busy -> "CarPlay · connecting"
                            else -> "CarPlay · stopped"
                        } else null,
                        onSaveAndConnect = rememberProductionSettingsSaveAction(settingsViewModel, projectionViewModel, clusterBindings?.commitLayoutDraft, onBack),
                        onBack = onBack,
                        onNavigateToDiagnostics = onShowDiagnostics,
                        onNavigateToCarPlaySafeAreaEditor = onCarPlaySafeEditor,
                        onNavigateToSafeAreaEditor = {
                            navController.navigate(AppDestinations.SAFE_AREA_EDITOR)
                        },
                    )
                },
                diagnosticsOverlay = { onBack ->
                    DiagnosticsScreen(onBack = onBack, microphoneController = microphoneBinding.controller, mediaViewModel = mediaDiagnosticsViewModel)
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
                    clusterBindings = clusterBindings,
                    clusterInitializationError = clusterInitializationError,
                        secondarySource = secondarySource,
                    viewModel = settingsViewModel,
                    audioBindings = audioBindings,
                    onBack = { navController.popBackStack() },
                    onSaveAndConnect = rememberProductionSettingsSaveAction(settingsViewModel, projectionViewModel, clusterBindings?.commitLayoutDraft) {
                        navController.popBackStack()
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
            DiagnosticsScreen(onBack = { navController.popBackStack() }, microphoneController = microphoneBinding.controller, mediaViewModel = mediaDiagnosticsViewModel)
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

package com.openautolink.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Content only: caller MUST host this alongside projection in its already mode-padded parent.
 * Drag/clear/manual edits remain local in SafeAreaEditorScreen. Cancel/back discard them;
 * Done merges only safe edges into the ViewModel draft. Only global Save persists/reconnects.
 */
@Composable
fun CarPlaySafeEditor(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.carPlaySettings.collectAsStateWithLifecycle()
    // Full-size hit target blocks settings/global Save behind the editor; child handles and
    // buttons still own their gestures. No separate Window and no Surface replacement.
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = {}) }) {
        SafeAreaEditorScreen(settings.safeTop, settings.safeBottom, settings.safeLeft, settings.safeRight,
            // Parent excluded chrome once. Even the editor toolbar must not add bars a second time.
            displayMode = "fullscreen_immersive", coordinateWidth = 10000, coordinateHeight = 10000,
            maximumInset = 4999, title = "CarPlay safe area · 100 units = 1%", doneLabel = "Use calibration",
            onDone = { top, bottom, left, right ->
                viewModel.updateCarPlayDraft { it.copy(safeTop = top, safeBottom = bottom, safeLeft = left, safeRight = right) }
                onBack()
            }, onBack = onBack)
    }
    BackHandler(onBack = onBack)
}

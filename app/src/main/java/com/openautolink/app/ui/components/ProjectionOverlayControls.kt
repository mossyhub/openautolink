package com.openautolink.app.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.openautolink.app.R
import com.openautolink.app.ui.projection.LogUploadState
import com.openautolink.app.ui.projection.ProjectionUiState

internal data class OverlayControl(
    val label: String,
    val icon: ImageVector,
    val action: () -> Unit,
    val status: String? = null,
    val tint: Color = Color.Unspecified,
    val enabled: Boolean = true,
    val dismissOnClick: Boolean = true,
)

private const val MENU_DRAG_HINT = "Hold and drag me where you want me"
private const val MENU_DRAG_HINT_DISMISSED = "menu_drag_hint_dismissed"

/** Shared AA/CarPlay menu. Only the logo floats; Settings is always available. */
@Composable
fun ProjectionOverlayControls(
    uiState: ProjectionUiState,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onReconnect: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onSwitchPhone: (() -> Unit)? = null,
    carHotspotSwitching: Boolean = false,
    onFileLogging: (() -> Unit)? = null,
    onUpload: (() -> Unit)? = null,
    onSimulateIgnition: (() -> Unit)? = null,
    enableDriverVideoRestore: Boolean = false,
) = com.openautolink.app.ui.theme.OalUiScale {
    val colors = MaterialTheme.colorScheme
    val driverVideoRestore = if(enableDriverVideoRestore) rememberProductionDriverVideoRestore() else null
    // Old show-floating-button preferences do not hide actions in this menu.
    // Callback/capability availability and the actual diagnostic enable flags do.
    val controls = buildList {
        add(OverlayControl("Settings", Icons.Default.Settings, onSettings))
        if (onSwitchPhone != null) {
            add(OverlayControl("Switch Phone", Icons.Default.PhoneAndroid, onSwitchPhone,
                if (carHotspotSwitching) "Switching phone" else null,
                if (carHotspotSwitching) colors.tertiary else Color.Unspecified))
        }
        add(OverlayControl("Stats for nerds", Icons.Default.Info, onStats,
            if (uiState.showStats) "Visible" else null,
            if (uiState.showStats) colors.primary else Color.Unspecified))
        if (onReconnect != null) {
            add(OverlayControl("Reconnect", Icons.Default.Refresh, onReconnect))
        }
        driverVideoRestore?.let { restore ->
            add(OverlayControl(restore.label, Icons.Default.Restore, restore.action ?: {},
                restore.message, enabled=restore.action!=null && !restore.pending, dismissOnClick=false))
        }
        if (onFileLogging != null && uiState.fileLoggingEnabled) {
            add(OverlayControl("File Logging", Icons.Default.FiberManualRecord, onFileLogging,
                if (uiState.fileLoggingActive) "Recording" else "Stopped",
                if (uiState.fileLoggingActive) colors.error else Color.Unspecified))
        }
        if (onUpload != null && uiState.uploadEnabled) {
            add(OverlayControl("Upload Logs",
                when (uiState.uploadState) {
                    LogUploadState.SUCCESS -> Icons.Default.CloudDone
                    LogUploadState.ERROR -> Icons.Default.CloudOff
                    else -> Icons.Default.CloudUpload
                }, onUpload,
                uiState.uploadStatusMessage ?: when (uiState.uploadState) {
                    LogUploadState.UPLOADING -> "Uploading"
                    LogUploadState.SUCCESS -> "Uploaded"
                    LogUploadState.ERROR -> "Upload failed"
                    LogUploadState.IDLE -> null
                },
                when (uiState.uploadState) {
                    LogUploadState.UPLOADING -> colors.tertiary
                    LogUploadState.SUCCESS -> colors.secondary
                    LogUploadState.ERROR -> colors.error
                    LogUploadState.IDLE -> Color.Unspecified
                },
                enabled = uiState.uploadState != LogUploadState.UPLOADING,
                dismissOnClick = false))
        }
        if (onSimulateIgnition != null && uiState.simulateIgnitionButton) {
            add(OverlayControl("Simulate ignition cycle", Icons.Default.PowerSettingsNew, onSimulateIgnition))
        }
    }
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("overlay_positions", Context.MODE_PRIVATE) }
    // Teaching is not a placement: launcher/menu resets must not teach again.
    val onboarding = remember(context) { context.getSharedPreferences("overlay_onboarding", Context.MODE_PRIVATE) }
    var hintDismissed by remember(onboarding) {
        mutableStateOf(onboarding.getBoolean(MENU_DRAG_HINT_DISMISSED, false))
    }
    DisposableEffect(onboarding) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == null || key == MENU_DRAG_HINT_DISMISSED) {
                hintDismissed = preferences.getBoolean(MENU_DRAG_HINT_DISMISSED, false)
            }
        }
        onboarding.registerOnSharedPreferenceChangeListener(listener)
        onDispose { onboarding.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val acknowledgeHint: () -> Unit = {
        // Only real completed movement or an explicit acknowledgement writes this.
        if (!hintDismissed) {
            hintDismissed = true
            onboarding.edit().putBoolean(MENU_DRAG_HINT_DISMISSED, true).apply()
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    val physicalDensity = LocalView.current.resources.displayMetrics.density
    OverlaySafeArea(modifier) {
        val origin = LocalOverlaySafeAreaOrigin.current
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            val buttonSize = with(LocalDensity.current) { 56.dp.toPx() }
            val geometry = OverlayButtonGeometry(
                parentWidth = width, parentHeight = height,
                anchorX = (width - buttonSize).coerceAtLeast(0f) / 2f,
                anchorY = (height - buttonSize).coerceAtLeast(0f) / 2f,
                buttonWidth = buttonSize, buttonHeight = buttonSize,
                density = physicalDensity, originX = origin.x, originY = origin.y,
            )
            DraggableOverlayButton(
                painter = painterResource(R.drawable.ic_oal_glyph),
                contentDescription = "OAL controls",
                onClick = { menuOpen = true },
                // A new control gets its own default, not an old Settings position.
                positionKey = "overlay_menu", geometry = geometry,
                modifier = Modifier.testTag("settingsButton"),
                onDragCompleted = acknowledgeHint,
                decoration = { topLeft ->
                    if (!hintDismissed && !menuOpen) {
                        OverlayDragHint(geometry, topLeft, acknowledgeHint)
                    }
                },
            )
        }
    }
    if (menuOpen) {
        ProjectionOverlayRecoveryDialog(
            actions = controls,
            onAcknowledgeHint = if (hintDismissed) null else acknowledgeHint,
            onDismiss = { menuOpen = false },
            onRestore = {
                OverlayButtonPositionStore.resetAll(prefs)
                com.openautolink.app.diagnostics.DiagnosticLog.i("overlay", "Overlay positions restored from OAL menu")
            },
        )
    }
}

/** Bounded sibling, never a modal coach mark. Tiny windows keep teaching in the menu. */
@Composable
private fun OverlayDragHint(
    geometry: OverlayButtonGeometry,
    logoTopLeft: OverlayButtonPoint,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.toPx() }
    val minWidth = with(density) { 160.dp.toPx() }
    val minHeight = with(density) { 96.dp.toPx() }
    val maxWidth = with(density) { 320.dp.toPx() }
    val maxHeight = with(density) { 160.dp.toPx() }
    val width = geometry.parentWidth
    val height = geometry.parentHeight
    val left = logoTopLeft.x
    val top = logoTopLeft.y
    val right = left + geometry.buttonWidth
    val bottom = top + geometry.buttonHeight
    // Prefer below/above, then a side. Every candidate excludes the logo + gap.
    val area = listOf(
        Rect(0f, bottom + gap, width, height),
        Rect(0f, 0f, width, top - gap),
        Rect(right + gap, 0f, width, height),
        Rect(0f, 0f, left - gap, height),
    ).firstOrNull { it.width >= minWidth && it.height >= minHeight } ?: return
    val bubbleWidth = minOf(area.width, maxWidth)
    val bubbleHeight = minOf(area.height, maxHeight)
    val x = ((left + right - bubbleWidth) / 2f).coerceIn(area.left, area.right - bubbleWidth)
    val y = ((top + bottom - bubbleHeight) / 2f).coerceIn(area.top, area.bottom - bubbleHeight)
    Surface(
        modifier = Modifier.absoluteOffset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .width(with(density) { bubbleWidth.toDp() })
            .heightIn(max = with(density) { bubbleHeight.toDp() })
            .testTag("overlayDragHint"),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text(MENU_DRAG_HINT, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("overlayDragHintDismiss")) { Text("Got it") }
        }
    }
}

/** All dialog chrome scrolls too, so actions remain reachable in short windows. */
@Composable
internal fun ProjectionOverlayRecoveryDialog(
    actions: List<OverlayControl>,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified,
    onAcknowledgeHint: (() -> Unit)? = null,
) = com.openautolink.app.ui.theme.OalUiScale {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        com.openautolink.app.ui.theme.OalUiScale {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxWidth().testTag("overlayRecoveryScroll")
                    .verticalScroll(rememberScrollState()).padding(24.dp)) {
                    Text("OAL controls", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Text("Tap an action. Long-press and drag the OAL logo to move it.")
                    if (onAcknowledgeHint != null) {
                        Text(MENU_DRAG_HINT)
                        TextButton(onClick = onAcknowledgeHint,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("overlayMenuHintDismiss")) {
                            Text("Got it")
                        }
                    }
                    actions.forEach { control ->
                        val tint = if (control.tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else control.tint
                        TextButton(
                            onClick = {
                                if (control.dismissOnClick) onDismiss()
                                control.action()
                            },
                            enabled = control.enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("overlayRecoveryAction:${control.label}"),
                            colors = ButtonDefaults.textButtonColors(contentColor = tint, disabledContentColor = tint),
                        ) {
                            Icon(control.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                Text(control.label, color = tint)
                                control.status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = tint) }
                            }
                        }
                    }
                    TextButton(
                        onClick = { onRestore(); onDismiss() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("overlayRecoveryAction:Restore button positions"),
                    ) { Text("Restore button positions") }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("overlayRecoveryAction:Close"),
                    ) { Text("Close") }
                }
            }
        }
    }
}

package com.openautolink.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.openautolink.app.ui.projection.ProjectionUiState
import com.openautolink.app.ui.projection.LogUploadState

/** Shared AA/CarPlay appearance, preferences and persisted drag keys.
 * Null actions omit backend-unsupported controls. Settings is unconditional.
 */
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
) {
        BoxWithConstraints(
            modifier = modifier

                .padding(end = 16.dp, bottom = 16.dp),
        ) {
            // `maxWidth.value` returns DP, but the drag offset inside
            // DraggableOverlayButton is in PIXELS. Passing the DP value
            // was clamping the drag to ~screen_width/density pixels —
            // i.e. an "invisible wall" roughly 1/3 of the way across the
            // screen on common AAOS panel densities. Convert to pixels.
            val density = LocalDensity.current
            val maxBoundsX = with(density) { this@BoxWithConstraints.maxWidth.toPx() }
            val maxBoundsY = with(density) { this@BoxWithConstraints.maxHeight.toPx() }

            Column {
                // Settings button — draggable
                DraggableOverlayButton(
                    icon = Icons.Default.Settings,
                    contentDescription = "Settings",
                    onClick = onSettings,
                    positionKey = "overlay_settings",
                    modifier = Modifier.testTag("settingsButton"),
                    maxBoundsX = maxBoundsX,
                    maxBoundsY = maxBoundsY,
                )

                // Switch Phone button — Car Hotspot mode only. Tapping opens a
                // centered chooser overlay; the underlying AA session keeps
                // streaming until the user explicitly picks a different phone.
                if (onSwitchPhone != null && uiState.overlayPhoneSwitchButton) {
                    Spacer(modifier = Modifier.height(8.dp))

                    DraggableOverlayButton(
                        icon = Icons.Default.PhoneAndroid,
                        contentDescription = "Switch Phone",
                        onClick = { onSwitchPhone?.invoke() },
                        positionKey = "overlay_switch_phone",
                        containerColor = if (carHotspotSwitching) {
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                        },
                        tint = if (carHotspotSwitching) {
                            MaterialTheme.colorScheme.onTertiary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.testTag("switchPhoneButton"),
                        maxBoundsX = maxBoundsX,
                        maxBoundsY = maxBoundsY,
                    )
                }

                // Stats button — user-configurable, unlike Settings which always stays visible.
                if (uiState.overlayStatsButton) {
                    Spacer(modifier = Modifier.height(8.dp))

                    DraggableOverlayButton(
                        icon = Icons.Default.Info,
                        contentDescription = "Stats for nerds",
                        onClick = onStats,
                        positionKey = "overlay_stats",
                        containerColor = if (uiState.showStats) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                        },
                        tint = if (uiState.showStats) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.testTag("statsButton"),
                        maxBoundsX = maxBoundsX,
                        maxBoundsY = maxBoundsY,
                    )
                }

                // Reconnect button — invokes the exact same callback as Settings'
                // Save & Reconnect action, so preference reload and session behavior
                // cannot drift between the two entry points.
                if (onReconnect != null && uiState.overlayReconnectButton) {
                    Spacer(modifier = Modifier.height(8.dp))

                    DraggableOverlayButton(
                        icon = Icons.Default.Refresh,
                        contentDescription = "Reconnect",
                        onClick = { onReconnect?.invoke() },
                        positionKey = "overlay_reconnect",
                        modifier = Modifier.testTag("reconnectButton"),
                        maxBoundsX = maxBoundsX,
                        maxBoundsY = maxBoundsY,
                    )
                }

                // File logging button — only shown when enabled in Settings → Diagnostics
                if (onFileLogging != null && uiState.fileLoggingEnabled) {
                Spacer(modifier = Modifier.height(8.dp))

                DraggableOverlayButton(
                    icon = Icons.Default.FiberManualRecord,
                    contentDescription = "File Logging",
                    onClick = { onFileLogging?.invoke() },
                    positionKey = "overlay_file_log",
                    containerColor = if (uiState.fileLoggingActive) {
                        Color.Red.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                    },
                    tint = if (uiState.fileLoggingActive) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.testTag("fileLogButton"),
                    maxBoundsX = maxBoundsX,
                    maxBoundsY = maxBoundsY,
                )
                } // end fileLoggingEnabled

                // Upload Logs button — maintainer-only, shown when the feature
                // is enabled in Settings. Color states: amber (uploading),
                // green (success), red (error), default surface (idle). Appears
                // whenever uploadEnabled so the user can fire it right after a
                // drive while the just-written logs are still in the 6h window.
                if (onUpload != null && uiState.uploadEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))

                    val uploadContainer = when (uiState.uploadState) {
                        LogUploadState.UPLOADING -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                        LogUploadState.SUCCESS -> Color(0xFF2E7D32).copy(alpha = 0.85f)
                        LogUploadState.ERROR -> Color.Red.copy(alpha = 0.8f)
                        LogUploadState.IDLE -> MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                    }
                    val uploadTint = if (uiState.uploadState == LogUploadState.IDLE) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color.White
                    }
                    val uploadIcon = when (uiState.uploadState) {
                        LogUploadState.SUCCESS -> Icons.Default.CloudDone
                        LogUploadState.ERROR -> Icons.Default.CloudOff
                        else -> Icons.Default.CloudUpload
                    }
                    DraggableOverlayButton(
                        icon = uploadIcon,
                        contentDescription = "Upload Logs",
                        onClick = { onUpload?.invoke() },
                        positionKey = "overlay_upload_logs",
                        containerColor = uploadContainer,
                        tint = uploadTint,
                        modifier = Modifier.testTag("uploadLogsButton"),
                        maxBoundsX = maxBoundsX,
                        maxBoundsY = maxBoundsY,
                    )
                }

                // Simulate ignition cycle — maintainer tool, off by default.
                // Runs the shutdown -> Bluetooth loss -> republish -> reconnect
                // sequence without turning the car off, so the path where the
                // reconnect failures actually happen can be tested in the
                // driveway instead of once per drive.
                if (onSimulateIgnition != null && uiState.simulateIgnitionButton) {
                    Spacer(modifier = Modifier.height(8.dp))

                    DraggableOverlayButton(
                        icon = Icons.Default.PowerSettingsNew,
                        contentDescription = "Simulate ignition cycle",
                        onClick = { onSimulateIgnition?.invoke() },
                        positionKey = "overlay_simulate_ignition",
                        modifier = Modifier.testTag("simulateIgnitionButton"),
                        maxBoundsX = maxBoundsX,
                        maxBoundsY = maxBoundsY,
                    )
                }
            }
        }

}

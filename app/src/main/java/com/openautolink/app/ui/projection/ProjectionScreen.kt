package com.openautolink.app.ui.projection

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openautolink.app.R
import com.openautolink.app.audio.AudioStats
import com.openautolink.app.session.SessionState
import com.openautolink.app.ui.components.DraggableOverlayButton
import com.openautolink.app.video.VideoStats

@Composable
fun ProjectionScreen(
    viewModel: ProjectionViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showPhoneSwitcher by viewModel.showPhoneSwitcher.collectAsStateWithLifecycle()
    val pairedPhones by viewModel.pairedPhones.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.connect()
        onDispose {
            // Don't disconnect on dispose — session survives config changes
        }
    }

    // Compute explicit padding from system bar AND display cutout dimensions.
    // Use getInsetsIgnoringVisibility() because AAOS CarSystemUI may not update
    // inset values when bars are requested to hide via WindowInsetsController.
    // Display cutout insets represent physically curved/missing screen areas.
    val view = LocalView.current
    val rootInsets = view.rootWindowInsets
    val sysBarInsets = rootInsets?.getInsetsIgnoringVisibility(
        android.view.WindowInsets.Type.systemBars()
    )
    val cutoutInsets = rootInsets?.getInsetsIgnoringVisibility(
        android.view.WindowInsets.Type.displayCutout()
    )
    val barTop = sysBarInsets?.top ?: 0
    val barBottom = sysBarInsets?.bottom ?: 0
    val barLeft = sysBarInsets?.left ?: 0
    val barRight = sysBarInsets?.right ?: 0
    val cutTop = cutoutInsets?.top ?: 0
    val cutBottom = cutoutInsets?.bottom ?: 0
    val cutLeft = cutoutInsets?.left ?: 0
    val cutRight = cutoutInsets?.right ?: 0

    val density = LocalDensity.current
    val displayModePadding = with(density) {
        when (uiState.displayMode) {
            // System bars visible — bars cover cutouts, pad for bars only
            "system_ui_visible" -> androidx.compose.foundation.layout.PaddingValues(
                start = maxOf(barLeft, cutLeft).toDp(),
                end = maxOf(barRight, cutRight).toDp(),
                top = maxOf(barTop, cutTop).toDp(),
                bottom = maxOf(barBottom, cutBottom).toDp()
            )
            // Status bar hidden — video extends into status bar area but must
            // still avoid display cutout at top and nav bar areas
            "status_bar_hidden" -> androidx.compose.foundation.layout.PaddingValues(
                start = maxOf(barLeft, cutLeft).toDp(),
                end = maxOf(barRight, cutRight).toDp(),
                top = cutTop.toDp(),
                bottom = maxOf(barBottom, cutBottom).toDp()
            )
            // Nav bar hidden — video extends into nav bar area but must
            // still avoid display cutout on sides and status bar
            "nav_bar_hidden" -> androidx.compose.foundation.layout.PaddingValues(
                start = cutLeft.toDp(),
                end = cutRight.toDp(),
                top = maxOf(barTop, cutTop).toDp(),
                bottom = cutBottom.toDp()
            )
            // Fullscreen — video fills entire screen edge-to-edge.
            // AA stable_insets (sent to bridge) keep buttons away from curves.
            "fullscreen_immersive" -> androidx.compose.foundation.layout.PaddingValues()
            else -> androidx.compose.foundation.layout.PaddingValues()
        }
    }

    // Track phone switch button position for popup anchoring
    var phoneSwitchBtnRootPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var phoneSwitchBtnSize by remember { mutableStateOf(IntSize.Zero) }
    var boxRootPos by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(displayModePadding)
            .onGloballyPositioned { boxRootPos = it.positionInParent() }
            .testTag("projectionScreen")
    ) {
        val surfaceModifier = Modifier
            .fillMaxSize()
            .testTag("projectionSurface")

        // SurfaceView for video rendering — intercepts touch for forwarding to bridge
        @SuppressLint("ClickableViewAccessibility")
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            viewModel.onSurfaceAvailable(
                                holder.surface,
                                holder.surfaceFrame.width(),
                                holder.surfaceFrame.height()
                            )
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            viewModel.onSurfaceAvailable(holder.surface, width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.onSurfaceDestroyed()
                        }
                    })

                    setOnTouchListener { v, event ->
                        viewModel.onTouchEvent(event, v.width, v.height)
                        true
                    }
                }
            },
            modifier = surfaceModifier
        )

        // Connection status HUD — centered overlay, visible when not streaming
        if (uiState.sessionState != SessionState.STREAMING) {
            ConnectionHud(
                uiState = uiState,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Floating overlay buttons — bottom-right, above nav bar. Draggable.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
        ) {
            // Settings button — draggable
            DraggableOverlayButton(
                icon = Icons.Default.Settings,
                contentDescription = "Settings",
                onClick = { onNavigateToSettings() },
                positionKey = "overlay_settings",
                modifier = Modifier.testTag("settingsButton"),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Stats button — draggable
            DraggableOverlayButton(
                icon = Icons.Default.Info,
                contentDescription = "Stats for nerds",
                onClick = { viewModel.toggleStats() },
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
            )

            // Phone switch button — draggable, togglable in settings
            if (uiState.overlayPhoneSwitchButton) {
                Spacer(modifier = Modifier.height(8.dp))

                DraggableOverlayButton(
                    icon = Icons.Default.PhoneAndroid,
                    contentDescription = "Switch phone",
                    onClick = { viewModel.togglePhoneSwitcher() },
                    positionKey = "overlay_phone_switch",
                    containerColor = if (showPhoneSwitcher) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                    },
                    tint = if (showPhoneSwitcher) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.testTag("phoneSwitchButton"),
                    onGlobalPosition = { coords ->
                        phoneSwitchBtnRootPos = coords.positionInParent()
                        // Walk up to get root-relative position
                        var c: LayoutCoordinates? = coords
                        var pos = androidx.compose.ui.geometry.Offset.Zero
                        while (c != null && c.isAttached) {
                            pos += c.positionInParent()
                            c = c.parentLayoutCoordinates
                        }
                        phoneSwitchBtnRootPos = pos
                        phoneSwitchBtnSize = coords.size
                    },
                )
            }
        }

        // Phone switcher popup — anchored above the phone switch button
        if (showPhoneSwitcher) {
            val popupWidthDp = 280.dp
            val popupGapDp = 8.dp
            // Button position relative to the Box: subtract Box's root position
            val btnInBoxX = phoneSwitchBtnRootPos.x - boxRootPos.x
            val btnInBoxY = phoneSwitchBtnRootPos.y - boxRootPos.y
            PhoneSwitcherPopup(
                phones = pairedPhones,
                currentPhone = uiState.phoneName,
                onSwitchPhone = { mac -> viewModel.switchPhone(mac) },
                onDismiss = { viewModel.togglePhoneSwitcher() },
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        // Try to right-align popup with button's right edge
                        var x = (btnInBoxX + phoneSwitchBtnSize.width - placeable.width).toInt()
                        // Position popup's bottom edge at button's top edge minus gap
                        val gapPx = popupGapDp.toPx()
                        var y = (btnInBoxY - placeable.height - gapPx).toInt()
                        // Clamp to stay within parent bounds
                        val maxX = constraints.maxWidth - placeable.width
                        val maxY = constraints.maxHeight - placeable.height
                        x = x.coerceIn(0, maxX.coerceAtLeast(0))
                        y = y.coerceIn(0, maxY.coerceAtLeast(0))
                        placeable.place(x, y)
                    }
                }
            )
        }

        // Stats overlay panel — bottom-left
        if (uiState.showStats) {
            VideoStatsOverlay(
                stats = uiState.videoStats,
                audioStats = uiState.audioStats,
                sessionState = uiState.sessionState,
                bridgeName = uiState.bridgeName,
                bridgeVersion = uiState.bridgeVersion,
                bridgeUptimeSeconds = uiState.bridgeUptimeSeconds,
                phoneName = uiState.phoneName,
                phoneBatteryLevel = uiState.phoneBatteryLevel,
                phoneSignalStrength = uiState.phoneSignalStrength,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }

    }
}

@Composable
private fun VideoStatsOverlay(
    stats: VideoStats,
    audioStats: AudioStats,
    sessionState: SessionState,
    bridgeName: String?,
    bridgeVersion: Int?,
    bridgeUptimeSeconds: Long,
    phoneName: String?,
    phoneBatteryLevel: Int?,
    phoneSignalStrength: Int?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(14.dp)
            .testTag("videoStatsOverlay")
    ) {
        Column {
            StatLine("Stats for nerds", "", header = true)
            Spacer(modifier = Modifier.height(6.dp))

            StatLine("Session", sessionState.name)

            // Bridge info
            if (bridgeName != null) {
                val versionStr = bridgeVersion?.let { " v$it" } ?: ""
                StatLine("Bridge", "$bridgeName$versionStr")
            }
            if (bridgeUptimeSeconds > 0) {
                StatLine("Uptime", formatUptime(bridgeUptimeSeconds))
            }

            // Phone info
            if (phoneName != null) {
                val batteryStr = phoneBatteryLevel?.let { " ($it%)" } ?: ""
                val signalStr = phoneSignalStrength?.let { " ▮".repeat(it) + "▯".repeat((4 - it).coerceAtLeast(0)) } ?: ""
                StatLine("Phone", "$phoneName$batteryStr$signalStr")
            }

            if (stats.codec != "none") {
                Spacer(modifier = Modifier.height(4.dp))
                StatLine("Codec", "${stats.codec}${if (stats.isHardware) " HW" else " SW"}")
                if (stats.decoderName.isNotBlank()) {
                    StatLine("Decoder", stats.decoderName)
                }
                if (stats.width > 0) {
                    StatLine("Resolution", "${stats.width}x${stats.height}")
                }
                StatLine("FPS", "${"%.1f".format(stats.fps)} fps",
                    valueColor = when {
                        stats.fps >= 50 -> Color.Green
                        stats.fps >= 25 -> Color(0xFFFFFF00)
                        else -> Color.Red
                    })
                StatLine("Frames", "Rx:${stats.framesReceived}  Dec:${stats.framesDecoded}")
                if (stats.bitrateKbps > 0) {
                    val bitrateStr = if (stats.bitrateKbps >= 1000) {
                        "${"%.1f".format(stats.bitrateKbps / 1000)} Mbps"
                    } else {
                        "${stats.bitrateKbps.toInt()} kbps"
                    }
                    StatLine("Bitrate", bitrateStr,
                        valueColor = when {
                            stats.bitrateKbps >= 4000 -> Color.Green
                            stats.bitrateKbps >= 2000 -> Color(0xFFFFFF00)
                            else -> Color.Red
                        })
                }
                if (stats.framesDropped > 0) {
                    StatLine("Dropped", stats.framesDropped.toString(),
                        valueColor = Color(0xFFFFAB00))
                }
                if (stats.codecResets > 0) {
                    StatLine("Resets", stats.codecResets.toString(),
                        valueColor = Color(0xFFFF6E40))
                }
            } else {
                StatLine("Video", "not active")
            }

            // Audio stats section
            Spacer(modifier = Modifier.height(6.dp))
            if (audioStats.activePurposes.isNotEmpty()) {
                StatLine("Audio", audioStats.activePurposes.joinToString(", ") { it.name.lowercase() })
                if (audioStats.sampleRate > 0) {
                    StatLine("Rate", "${audioStats.sampleRate} Hz")
                }
                val totalFrames = audioStats.framesWritten.values.sum()
                if (totalFrames > 0) {
                    StatLine("Frames", totalFrames.toString())
                }
                audioStats.underruns.forEach { (purpose, count) ->
                    if (count > 0) {
                        StatLine("${purpose.name.lowercase()} xrun", count.toString(),
                            valueColor = Color(0xFFFFAB00))
                    }
                }
            } else {
                StatLine("Audio", "not active")
            }
        }
    }
}

@Composable
private fun StatLine(
    label: String,
    value: String,
    header: Boolean = false,
    valueColor: Color = Color.White,
) {
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            text = label,
            color = if (header) Color(0xFF64FFDA) else Color(0xFFB0BEC5),
            fontSize = if (header) 16.sp else 13.sp,
            fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            lineHeight = if (header) 20.sp else 16.sp,
            modifier = Modifier.width(if (header) 200.dp else 100.dp),
        )
        if (value.isNotEmpty()) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = value,
                color = valueColor,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
            )
        }
    }
}

private fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "${h}h ${m}m ${s}s" else if (m > 0) "${m}m ${s}s" else "${s}s"
}

@Composable
private fun PhoneSwitcherPopup(
    phones: List<com.openautolink.app.transport.ControlMessage.PairedPhone>,
    currentPhone: String?,
    onSwitchPhone: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(280.dp)
            .testTag("phoneSwitcherPopup"),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Switch Phone",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (phones.isEmpty()) {
                Text(
                    text = "Loading paired phones...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                phones.forEach { phone ->
                    val isCurrent = phone.connected ||
                            (currentPhone != null && phone.name == currentPhone)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                if (!isCurrent) onSwitchPhone(phone.mac)
                            }
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("switchPhone_${phone.mac}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = phone.name.ifBlank { "Unknown" },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            )
                            Text(
                                text = phone.mac,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                        if (isCurrent) {
                            Text(
                                text = "Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionHud(
    uiState: ProjectionUiState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC000000))
            .padding(32.dp)
            .testTag("connectionHud"),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.ic_logo),
                contentDescription = "OpenAutoLink",
                modifier = Modifier.height(120.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "OpenAutoLink",
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (uiState.sessionState) {
                SessionState.IDLE -> {
                    Text(
                        text = "Disconnected",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFFB0B0B0)
                    )
                }
                SessionState.CONNECTING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Connecting to bridge...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = uiState.bridgeHost,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF808080)
                    )
                }
                SessionState.BRIDGE_CONNECTED -> {
                    Text(
                        text = "Waiting for phone...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    uiState.bridgeName?.let { name ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Bridge: $name",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF808080)
                        )
                    }
                }
                SessionState.PHONE_CONNECTED -> {
                    Text(
                        text = "Phone connected",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    uiState.phoneName?.let { name ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF808080)
                        )
                    }
                }
                SessionState.STREAMING -> {
                    // HUD is hidden during streaming
                }
                SessionState.ERROR -> {
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Transparent overlay showing dashed lines at safe area and content inset boundaries.
 * Teal = stable (safe area), Red = content (hard cutoff).
 */
@Composable
private fun SafeAreaOverlay(
    top: Int,
    bottom: Int,
    left: Int,
    right: Int,
    contentTop: Int,
    contentBottom: Int,
    contentLeft: Int,
    contentRight: Int,
    videoWidth: Int,
    videoHeight: Int,
    modifier: Modifier = Modifier,
) {
    val hasStable = top > 0 || bottom > 0 || left > 0 || right > 0
    val hasContent = contentTop > 0 || contentBottom > 0 || contentLeft > 0 || contentRight > 0
    if (!hasStable && !hasContent) return

    val stableColor = Color(0xFF64FFDA)
    val contentColor = Color(0xFFFF6E40)
    val dashEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
        floatArrayOf(12f, 8f), 0f
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        fun drawInsetLines(t: Int, b: Int, l: Int, r: Int, color: Color) {
            if (t > 0) {
                val y = (t.toFloat() / videoHeight) * h
                drawLine(color, androidx.compose.ui.geometry.Offset(0f, y),
                    androidx.compose.ui.geometry.Offset(w, y), 3f, pathEffect = dashEffect)
            }
            if (b > 0) {
                val y = h - (b.toFloat() / videoHeight) * h
                drawLine(color, androidx.compose.ui.geometry.Offset(0f, y),
                    androidx.compose.ui.geometry.Offset(w, y), 3f, pathEffect = dashEffect)
            }
            if (l > 0) {
                val x = (l.toFloat() / videoWidth) * w
                drawLine(color, androidx.compose.ui.geometry.Offset(x, 0f),
                    androidx.compose.ui.geometry.Offset(x, h), 3f, pathEffect = dashEffect)
            }
            if (r > 0) {
                val x = w - (r.toFloat() / videoWidth) * w
                drawLine(color, androidx.compose.ui.geometry.Offset(x, 0f),
                    androidx.compose.ui.geometry.Offset(x, h), 3f, pathEffect = dashEffect)
            }
        }

        drawInsetLines(top, bottom, left, right, stableColor)
        drawInsetLines(contentTop, contentBottom, contentLeft, contentRight, contentColor)
    }
}

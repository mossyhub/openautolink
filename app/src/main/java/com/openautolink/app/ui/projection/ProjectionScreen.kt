package com.openautolink.app.ui.projection

import com.openautolink.app.ui.components.OalAlertDialog

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openautolink.app.R
import com.openautolink.app.carplay.CarPlayProjectionLayer
import com.openautolink.app.audio.AudioStats
import com.openautolink.app.session.SessionState
import com.openautolink.app.ui.components.ProjectionOverlayControls
import com.openautolink.app.ui.components.StatsForNerdsOverlay
import com.openautolink.app.ui.components.StatLine
import com.openautolink.app.video.VideoStats

@Composable
private fun CarPlayRecoveryPanel(
    guidance: String,
    enabled: Boolean,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(24.dp).background(Color.Black.copy(alpha = 0.85f)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(guidance, color = Color.White, textAlign = TextAlign.Center)
        Row {
            androidx.compose.material3.TextButton(onClick = onSettings) { Text("Connection settings") }
            androidx.compose.material3.TextButton(onClick = onRetry, enabled = enabled,
                modifier = Modifier.testTag("carPlayRetry")) { Text("Retry CarPlay") }
        }
    }
}

/** Same compact, passive treatment as AA's keyframe wait; never owns projected input. */
@Composable
private fun CarPlayVideoWaitIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("carPlayVideoWaitIndicator"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        Text("Waiting for video\u2026", style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}

/** Persisted next-session intent, not a spinner or a claim that the phone renegotiated. */
@Composable
private fun SettingsSaveNotice(message: String, modifier: Modifier = Modifier) {
    Text(message, modifier.padding(16.dp).clip(RoundedCornerShape(12.dp))
        .background(Color(0xE6000000)).padding(horizontal = 20.dp, vertical = 12.dp)
        .testTag("settingsSaveNotice"), color = Color.White, style = MaterialTheme.typography.bodyMedium)
}

/** Confirmation only: the ViewModel owns routing, permissions and exact-generation retirement. */
@Composable
private fun UsbHotPlugSwitchDialog(
    prompt: com.openautolink.app.carplay.UsbHotPlugPrompt,
    onSwitch: () -> Unit,
    onStay: () -> Unit,
) {
    OalAlertDialog(
        modifier = Modifier.testTag("usbHotPlugPrompt"),
        onDismissRequest = onStay,
        title = { Text("USB phone connected") },
        text = { Text("Switch to ${prompt.protocolLabel} for this connection? Your normal connection settings will not change.") },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onSwitch,
                modifier = Modifier.testTag("usbHotPlugSwitch")) { Text("Switch") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onStay,
                modifier = Modifier.testTag("usbHotPlugStay")) { Text("Stay here") }
        },
    )
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ProjectionScreen(
    viewModel: ProjectionViewModel = viewModel(),
    onNavigateToSettings: () -> Unit = {},
    onReconnect: () -> Unit = {},
    settingsOverlay: @Composable (onBack: () -> Unit, onShowDiagnostics: () -> Unit,
        onCarPlaySafeEditor: () -> Unit) -> Unit = { _, _, _ -> },
    diagnosticsOverlay: @Composable (onBack: () -> Unit) -> Unit = {},
    carPlaySafeEditorOverlay: @Composable (onBack: () -> Unit) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val carPlayState by viewModel.carPlayState.collectAsStateWithLifecycle()
    val usbHotPlugPrompt by viewModel.usbHotPlugPrompt.collectAsStateWithLifecycle()
    val usbSwitchFailure by viewModel.usbSwitchFailure.collectAsStateWithLifecycle()
    val carPlayEnabled by viewModel.carPlayEnabled.collectAsStateWithLifecycle()
    val carPlayStartupGuidance by viewModel.carPlayStartupGuidance.collectAsStateWithLifecycle()
    val settingsSaveMessage by viewModel.settingsSaveMessage.collectAsStateWithLifecycle()
    val carPlayPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) viewModel.connectCarPlay()
    }
    val retryCarPlay = {
        carPlayPermissionLauncher.launch(arrayOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ))
    }
    val connectionMode by viewModel.connectionMode.collectAsStateWithLifecycle()
    val directTransport by viewModel.directTransport.collectAsStateWithLifecycle()
    val isCarHotspotMode = connectionMode == com.openautolink.app.data.AppPreferences.CONNECTION_MODE_CAR_HOTSPOT
    val carHotspotPhones by viewModel.carHotspotPhones.collectAsStateWithLifecycle()
    val carHotspotSweeping by viewModel.carHotspotSweepActive.collectAsStateWithLifecycle()
    val carHotspotSweepProgress by viewModel.carHotspotSweepProgress.collectAsStateWithLifecycle()
    val knownPhones by viewModel.knownPhones.collectAsStateWithLifecycle()
    val defaultPhoneId by viewModel.defaultPhoneId.collectAsStateWithLifecycle()
    val carHotspotSwitching by viewModel.carHotspotSwitching.collectAsStateWithLifecycle()
    val carHotspotStatus by viewModel.carHotspotStatus.collectAsStateWithLifecycle()
    val carHotspotStatusDetail by viewModel.carHotspotStatusDetail.collectAsStateWithLifecycle()
    val chooserMessage by viewModel.carHotspotChooserMessage.collectAsStateWithLifecycle()
    val bluetoothPhoneHost by viewModel.bluetoothPhoneHost.collectAsStateWithLifecycle()
    val activePhoneId by viewModel.activePhoneId.collectAsStateWithLifecycle()

    // Settings overlay state
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Separate sibling overlay; never navigate away from or re-key the live Surface.
    var showCarPlaySafeEditor by remember { mutableStateOf(false) }
    // Diagnostics overlay state — rendered as overlay (not nav destination) so the
    // projection Surface stays alive underneath. Returning to projection avoids
    // a codec/Surface re-init and the black frames that come with it.
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    // USB authorization recovery is independent of the saved AA/WPP mode and
    // must also survive CarPlay's early return below. Observation is not consent.
    val transportMode by viewModel.transportMode.collectAsStateWithLifecycle()
    val usbState by com.openautolink.app.transport.usb.UsbConnectionManager
        .connectionState.collectAsStateWithLifecycle()
    val usbDevices by com.openautolink.app.transport.usb.UsbConnectionManager
        .availableDevices.collectAsStateWithLifecycle()
    val showUsbPicker = carPlayState.failure == null && usbHotPlugPrompt == null && !showCarPlaySafeEditor &&
        (usbDevices.isEmpty() || usbDevices.any { !viewModel.isUsbCarPlayRecoveryBlocked(it) }) &&
        (!carPlayState.selected || usbState == com.openautolink.app.transport.usb.UsbConnectionState.AWAITING_USER_SELECTION) &&
        UsbPickerVisibility.shouldShow(
            transportMode, showSettings, showDiagnostics,
            carPlayEnabled = carPlayEnabled, usbState = usbState, devices = usbDevices,
        )

    LaunchedEffect(showSettings) {
        viewModel.setSettingsOpen(showSettings)
    }

    DisposableEffect(Unit) {
        viewModel.startProjectionOnce()
        onDispose {
            // Don't disconnect on dispose — session survives config changes
        }
    }

    // Keep the AA density calculations unchanged; ProjectionViewport owns mode padding once.
    val density = LocalDensity.current

    // One generation-qualified confirmation for either projection owner, not a settings action.
    // Keep the current Surface and the sibling safe-area editor in their existing composition.
    if (!showSettings && !showDiagnostics && !showCarPlaySafeEditor) {
        if (usbHotPlugPrompt == null) usbSwitchFailure?.let { guidance ->
            OalAlertDialog(
                modifier = Modifier.testTag("usbSwitchFailure"),
                onDismissRequest = viewModel::dismissUsbSwitchFailure,
                title = { Text("USB switch paused") },
                text = { Text(guidance) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = viewModel::retryUsbSwitch) { Text("Retry USB") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = viewModel::dismissUsbSwitchFailure) { Text("Dismiss") }
                },
            )
        }
        usbHotPlugPrompt?.let { prompt ->
            key(prompt.generation) {
                UsbHotPlugSwitchDialog(
                    prompt = prompt,
                    onSwitch = { viewModel.acceptUsbHotPlug(prompt.generation) },
                    onStay = { viewModel.stayOnCurrentProjection(prompt.generation) },
                )
            }
        }
    }

    if (!showSettings && !showDiagnostics && !showCarPlaySafeEditor && usbHotPlugPrompt == null && usbSwitchFailure == null) carPlayState.failure?.let { failure ->
        OalAlertDialog(
            modifier = Modifier.testTag("carPlayFailure"),
            onDismissRequest = viewModel::dismissCarPlayFailure,
            title = { Text("CarPlay connection failed") },
            text = { Text(failure.userGuidance) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = viewModel::returnToAndroidAuto) {
                    Text(if (carPlayState.selected) "Return to Android Auto" else "Keep Android Auto")
                }
            },
            dismissButton = {
                Row {
                    androidx.compose.material3.TextButton(onClick = { showSettings = true; viewModel.dismissCarPlayFailure() }) {
                        Text("Connection settings")
                    }
                    androidx.compose.material3.TextButton(onClick = retryCarPlay,
                        enabled = carPlayEnabled && failure.kind != com.openautolink.app.carplay.CarPlayFailureKind.USB_STARTUP_TIMEOUT) {
                        Text("Retry CarPlay")
                    }
                }
            },
        )
    }

    if (carPlayState.selected) {
        ProjectionViewport(uiState.displayMode) {
            CarPlayProjectionLayer(
                viewModel = viewModel, state = carPlayState, enabled = carPlayEnabled, controls = uiState,
                onReconnect = onReconnect,
                showSettings = showSettings, showDiagnostics = showDiagnostics,
                onSettings = { showSettings = true },
            )
            if (!showSettings && !showDiagnostics && !showCarPlaySafeEditor) settingsSaveMessage?.let {
                SettingsSaveNotice(it, Modifier.align(Alignment.TopCenter))
            }
            if (CarPlayVideoWaitPolicy.shouldShow(
                    carPlayState, carPlayEnabled,
                    settingsSaveMessage != null || showSettings || showDiagnostics || showCarPlaySafeEditor || showUsbPicker || usbHotPlugPrompt != null || usbSwitchFailure != null,
                )) {
                CarPlayVideoWaitIndicator(Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
            }
            if (showSettings) settingsOverlay(
                { showSettings = false }, { showSettings = false; showDiagnostics = true },
                { showCarPlaySafeEditor = true })
            if (showDiagnostics) diagnosticsOverlay { showDiagnostics = false }
            // Same exact parent and constraints as CarPlayProjectionLayer, above settings chrome.
            if (showCarPlaySafeEditor) carPlaySafeEditorOverlay { showCarPlaySafeEditor = false }
        }
        if (settingsSaveMessage == null && !showSettings && !showDiagnostics && !showUsbPicker && !carPlayState.busy &&
            !carPlayState.evidence.videoRendered && carPlayState.failure == null) {
            Box(Modifier.fillMaxSize()) {
                CarPlayRecoveryPanel(
                    guidance = carPlayStartupGuidance ?: carPlayState.status,
                    enabled = carPlayEnabled,
                    onRetry = retryCarPlay,
                    onSettings = { showSettings = true },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        androidx.activity.compose.BackHandler(!showCarPlaySafeEditor && (showSettings || showDiagnostics)) {
            showSettings = false; showDiagnostics = false
        }
        if (showUsbPicker) {
            UsbDevicePickerOverlay(onOpenSettings = { showSettings = true })
        }
        return
    }

    // Effective system inset = max(systemBars, displayCutout) per edge. This
    // is what AA should treat as `content_insets` so dropdowns/dialogs avoid
    // both the AAOS chrome (in system_ui_visible mode) and the panel's
    // physical curves/cutouts (always). Push to SessionManager so any
    // start()/reconnect() picks it up as fallback when user hasn't
    // overridden the values in settings.
    //
    // We do NOT auto-push system bar / cutout insets to AA as
    // `content_insets`. In `system_ui_visible` mode the outer Box already
    // pads the SurfaceView away from the chrome, and auto-margin +
    // auto-DPI use the padded render rect — so AA naturally only fills
    // the chrome-free area. Pushing insets ALSO would double-count: AA
    // would shrink ITS UI inside the codec frame, leaving us with a
    // small island of UI inside an already-shrunk surface.
    //
    // In `fullscreen_immersive` we want AA to use the full panel; no
    // insets needed.
    //
    // Curved-corner / cutout cars where AAOS doesn't model the curve in
    // its insets API still need a way to tell AA "stay X pixels away
    // from the right edge". That's the Settings → Display Insets editor
    // (per-edge user override). Push those values verbatim — they're
    // the ONLY auto/system contribution to AA's content_insets now;
    // SessionManager merges them with any user-set safeAreaTop/Bottom/etc.
    LaunchedEffect(uiState.safeAreaTop, uiState.safeAreaBottom,
                   uiState.safeAreaLeft, uiState.safeAreaRight) {
        viewModel.setSystemInsets(0, 0, 0, 0)
    }

    ProjectionViewport(uiState.displayMode, Modifier.testTag("projectionScreen")) {
        // The Box's content area = the "render rect" — the region where the
        // AA SurfaceView actually lands after displayMode padding. Push it
        // (plus the panel's reported DPI) to SessionManager so auto-DPI can
        // mirror GM's getScaledDensity formula:
        //   density = panelDpi / (renderRectWidth / (codecW − widthMargin))
        // The same formula is used for fullscreen and system_ui_visible
        // modes; only the renderRect changes between them, which makes
        // auto-DPI track displayMode automatically.
        val panelDpi = androidx.compose.ui.platform.LocalConfiguration.current.densityDpi
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val rrW = constraints.maxWidth
            val rrH = constraints.maxHeight
            LaunchedEffect(rrW, rrH, panelDpi, uiState.displayMode) {
                viewModel.setRenderRect(rrW, rrH, panelDpi, uiState.displayMode)
            }
        }
        // ── Video surface rendering ────────────────────────────────────────
        // Two modes:
        //
        // "letterbox": SurfaceView constrained to 16:9, centered. Decoder
        //   stretches codec frame to that surface (uniform when codec is 16:9).
        //   No margin handling. Black bars visible on non-16:9 panels.
        //
        // "crop" (margin-zoom): SurfaceView is INFLATED beyond the parent
        //   so the codec's *inner content rect* (codec_w − widthMargin) ×
        //   (codec_h − heightMargin) lands at parent size. The codec's black
        //   margin pixels extend past the parent's clipToBounds and disappear.
        //   The decoder's non-uniform SCALE_TO_FIT is effectively uniform here
        //   because the SurfaceView's aspect == codec frame aspect, so square
        //   pixels survive intact. Touch coords map for free: the OnTouchListener
        //   receives MotionEvents in the inflated SurfaceView's coord space
        //   (only inside the parent's clipped region), and the existing linear
        //   TouchScaler.scalePoint(eventX, eventY, viewW, viewH, codecW, codecH)
        //   already produces the correct codec-space coordinate.
        //
        // Choose margins so inner aspect ≈ panel aspect for square pixels.
        // Example: 2914×1134 panel + 1080p codec → heightMargin ≈ 358
        // (inner 1920×722 ≈ 2.66:1 ≈ 2914:1134).
        val codecW = uiState.videoStats.width
        val codecH = uiState.videoStats.height
        val userWm = uiState.aaWidthMargin.coerceAtLeast(0)
        val userHm = uiState.aaHeightMargin.coerceAtLeast(0)
        val autoMargins = uiState.aaAutoMargins
        val isCropMode = uiState.videoScalingMode == "crop"

        @SuppressLint("ClickableViewAccessibility")
        val surfaceFactory: (android.content.Context) -> SurfaceView = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        com.openautolink.app.diagnostics.DiagnosticLog.i(
                            "video",
                            "SurfaceView.surfaceCreated: ${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()}"
                        )
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
                        com.openautolink.app.diagnostics.DiagnosticLog.i(
                            "video",
                            "SurfaceView.surfaceChanged: ${width}x${height} format=0x${Integer.toHexString(format)}"
                        )
                        viewModel.onSurfaceAvailable(holder.surface, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        com.openautolink.app.diagnostics.DiagnosticLog.i(
                            "video",
                            "SurfaceView.surfaceDestroyed"
                        )
                        viewModel.onSurfaceDestroyed()
                    }
                })
                // No touch listener here — touches are captured by an
                // overlay sitting over the parent's visible rect (see Crop
                // and Letterbox branches below) which knows the panel and
                // codec inner-rect dims explicitly. Attaching a touch
                // listener to the inflated SurfaceView produced inconsistent
                // edge-of-panel mappings depending on Android version /
                // clip behaviour.
            }
        }

        // Key on mode + display mode + margins so the SurfaceView is recreated
        // when any of those change (avoids stale dimensions and re-runs holder
        // callbacks). NOTE: we MUST always emit exactly one AndroidView at
        // the same position in this composition slot — switching between
        // call sites (e.g. if/else with two AndroidViews) destroys and
        // recreates the SurfaceView mid-decode, putting MediaCodec in a bad
        // state. Render once, vary the Modifier instead.
        key(uiState.videoScalingMode, uiState.displayMode, userWm, userHm, autoMargins) {
            if (isCropMode) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                ) {
                    val parentWPx = constraints.maxWidth
                    val parentHPx = constraints.maxHeight

                    // Default to filling the parent until codec dims arrive,
                    // so the SurfaceView is created once and just resized
                    // when the phone tells us its codec resolution.
                    val surfaceModifier = if (
                        codecW > 0 && codecH > 0 &&
                        parentWPx > 0 && parentHPx > 0
                    ) {
                        // Auto vs manual margins (mirrors C++ SDR logic):
                        //  - autoMargins=true: compute per-frame from panel
                        //    AR so the codec inner rect fills the parent
                        //    with square pixels.
                        //  - autoMargins=false: use the user-set values
                        //    literally; 0 = no margins → whole codec frame
                        //    fills the parent (decoder may stretch).
                        val (wm, hm) = if (autoMargins) {
                            com.openautolink.app.video.MarginAutoCalc.compute(
                                codecW, codecH, parentWPx, parentHPx
                            )
                        } else {
                            userWm to userHm
                        }
                        val innerW = (codecW - wm).coerceAtLeast(1)
                        val innerH = (codecH - hm).coerceAtLeast(1)
                        // Uniform scale chosen so the inner content rect at
                        // minimum covers the parent (margin pixels overflow).
                        val scale = maxOf(
                            parentWPx.toFloat() / innerW.toFloat(),
                            parentHPx.toFloat() / innerH.toFloat()
                        )
                        val viewWPx = (codecW * scale).toInt().coerceAtLeast(1)
                        val viewHPx = (codecH * scale).toInt().coerceAtLeast(1)
                        // Anchor the codec's TOP-LEFT to the parent's
                        // top-left, not centred. The phone (observed on
                        // both Pixel and Samsung Wireless AA) ignores
                        // `UiConfig.margins` and top-aligns its UI inside
                        // the codec frame when only `height_margin` is
                        // sent (i.e. the inner content lives at codec
                        // rows [0, codecH - hm], with the hm rows of
                        // margin painted at the BOTTOM). Centering the
                        // SurfaceView would crop the top of AA's UI and
                        // reveal the bottom margin band on screen.
                        // Same logic applies to width_margin → left-align.
                        val offXPx = 0
                        val offYPx = 0
                        Modifier
                            .offset { IntOffset(offXPx, offYPx) }
                            .requiredSize(
                                with(density) { viewWPx.toDp() },
                                with(density) { viewHPx.toDp() }
                            )
                            .testTag("projectionSurface")
                    } else {
                        Modifier
                            .fillMaxSize()
                            .testTag("projectionSurface")
                    }

                    AndroidView(
                        factory = surfaceFactory,
                        modifier = surfaceModifier,
                    )

                    // Touch overlay: covers the parent's visible rect (the
                    // panel area). pointerInteropFilter delivers MotionEvents
                    // with coords in this modifier's local space, which is
                    // the panel rect — exactly what we want to map to the
                    // codec inner rect (where AA's UI lives, top-left
                    // anchored).
                    val touchInnerW: Int
                    val touchInnerH: Int
                    if (codecW > 0 && codecH > 0 && parentWPx > 0 && parentHPx > 0) {
                        val (wm, hm) = if (autoMargins) {
                            com.openautolink.app.video.MarginAutoCalc.compute(
                                codecW, codecH, parentWPx, parentHPx
                            )
                        } else {
                            userWm to userHm
                        }
                        touchInnerW = (codecW - wm).coerceAtLeast(1)
                        touchInnerH = (codecH - hm).coerceAtLeast(1)
                    } else {
                        touchInnerW = 0
                        touchInnerH = 0
                    }
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInteropFilter { ev ->
                                if (!showSettings) {
                                    viewModel.onTouchEvent(
                                        ev,
                                        parentWPx, parentHPx,
                                        touchInnerW, touchInnerH,
                                        parentWPx, parentHPx,
                                    )
                                }
                                true
                            }
                            .testTag("projectionTouch")
                    )
                }
            } else {
                // Letterbox: 16:9 centered, with a touch overlay sitting
                // exactly over the same rect (so its local space ↔ codec
                // space is a uniform 1:1 ratio).
                androidx.compose.foundation.layout.BoxWithConstraints(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                ) {
                    val ltrW = constraints.maxWidth
                    val ltrH = constraints.maxHeight
                    AndroidView(
                        factory = surfaceFactory,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("projectionSurface")
                    )
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInteropFilter { ev ->
                                if (!showSettings) {
                                    // No margin in letterbox — codec frame
                                    // = full surface, so inner = codec.
                                    viewModel.onTouchEvent(
                                        ev,
                                        ltrW, ltrH,
                                        codecW, codecH,
                                        ltrW, ltrH,
                                    )
                                }
                                true
                            }
                            .testTag("projectionTouch")
                    )
                }
            }
        }

        if (!showSettings && !showDiagnostics && !showCarPlaySafeEditor) settingsSaveMessage?.let {
            SettingsSaveNotice(it, Modifier.align(Alignment.TopCenter))
        }
        // Setup/retry is secondary recovery UI, not the normal startup mechanism.
        if (settingsSaveMessage == null && carPlayEnabled && carPlayStartupGuidance != null && !showSettings && !showDiagnostics) {
            CarPlayRecoveryPanel(
                guidance = carPlayStartupGuidance!!,
                enabled = true,
                onRetry = retryCarPlay,
                onSettings = { showSettings = true },
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (settingsSaveMessage == null && uiState.sessionState != SessionState.STREAMING) {
            ConnectionHud(
                uiState = uiState,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Car Hotspot status banner — visible whenever we're in Car Hotspot
        // mode and not actively streaming. Tells the user what the app is
        // doing (searching, switching, awaiting their pick, etc.) so a
        // black screen never feels like the app is frozen.
        if (isCarHotspotMode && carHotspotStatus != ProjectionViewModel.CarHotspotStatus.STREAMING &&
            carHotspotStatus != ProjectionViewModel.CarHotspotStatus.INACTIVE
        ) {
            CarHotspotStatusBanner(
                status = carHotspotStatus,
                detail = carHotspotStatusDetail,
                onTapPicker = { viewModel.showCarHotspotChooser() },
                onTapScan = { viewModel.rescanCarHotspotPhones() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp),
            )
        }

        // Waiting-for-keyframe indicator — subtle spinner during STREAMING when
        // the decoder has no IDR yet (black video). Disappears as soon as first
        // frame is decoded. Placed bottom-center to avoid blocking the projection.
        // Also show while CONNECTED, not only STREAMING.
        //
        // The 112-second black screen measured in-vehicle happened with the
        // session healthy but no frames rendering yet, and gating on STREAMING
        // alone meant the one indicator that explains the wait stayed hidden for
        // the whole of it.
        if (settingsSaveMessage == null && (uiState.sessionState == SessionState.STREAMING ||
                uiState.sessionState == SessionState.CONNECTED) &&
            uiState.videoStats.waitingForKeyframe
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .testTag("keyframeWaitIndicator"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Column {
                        Text(
                            text = "Waiting for video\u2026",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        // Real progress, not a spinner that says nothing.
                        //
                        // We cannot ask the phone for a keyframe — the request is
                        // advisory and gearhead ignores it — so the honest readout
                        // is how long we have waited against how often this phone
                        // actually sends them, learned from the stream.
                        val waited = uiState.videoStats.waitingForKeyframeMs
                        val period = uiState.videoStats.estimatedKeyframePeriodMs
                        if (waited > 1500) {
                            val waitedS = waited / 1000
                            val detail = if (period > 0) {
                                val remain = ((period - waited) / 1000).coerceAtLeast(0)
                                "${waitedS}s \u00b7 usually every ${period / 1000}s" +
                                    if (remain > 0) " \u00b7 ~${remain}s left" else ""
                            } else {
                                "${waitedS}s \u00b7 the phone sends these on its own schedule"
                            }
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB0B0B0)
                            )
                            if (period > 0) {
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { (waited.toFloat() / period).coerceIn(0f, 1f) },
                                    modifier = Modifier.width(220.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Stats overlay panel — bottom-left
        if (uiState.showStats) {
            VideoStatsOverlay(
                stats = uiState.videoStats,
                audioStats = uiState.audioStats,
                sessionState = uiState.sessionState,
                phoneName = uiState.phoneName,
                phoneBatteryLevel = uiState.phoneBatteryLevel,
                aaPixelAspect = uiState.aaPixelAspect,
                aaDpi = uiState.aaDpi,
                effectiveDpi = uiState.effectiveDpi,
                wifiFrequencyMhz = uiState.wifiFrequencyMhz,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }

        ProjectionOverlayControls(
            uiState = uiState,
            onSettings = { showSettings = true },
            onStats = viewModel::toggleStats,
            onReconnect = onReconnect,
            modifier = Modifier.align(Alignment.BottomEnd),
            onSwitchPhone = if (PhoneSwitcherPolicy.visible(directTransport, connectionMode)) ({ viewModel.showCarHotspotChooser() }) else null,
            carHotspotSwitching = carHotspotSwitching,
            onFileLogging = viewModel::toggleFileLogging,
            onUpload = viewModel::uploadLogsNow,
            onSimulateIgnition = viewModel::simulateIgnitionCycle,
        )

        // Settings overlay — slides in from left, video keeps playing underneath
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally { -it }, // slide in from left edge
            exit = slideOutHorizontally { -it },  // slide out to left edge
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
            ) {
                settingsOverlay(
                    { showSettings = false },
                    {
                        // Open diagnostics on top of settings; closing diagnostics
                        // returns the user to the settings overlay rather than
                        // straight to projection.
                        showDiagnostics = true
                    },
                    { showCarPlaySafeEditor = true },
                )
            }
        }

        // Diagnostics overlay — same pattern as Settings. Projection keeps
        // rendering underneath so closing the overlay shows live video, not
        // a recreated Surface.
        AnimatedVisibility(
            visible = showDiagnostics,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
            ) {
                diagnosticsOverlay { showDiagnostics = false }
            }
        }

        // Phone chooser overlay — Car Hotspot mode uses the centered overlay
        // backed by PhoneDiscovery (mDNS + sweep).
        val showChooser by viewModel.showPhoneChooser.collectAsStateWithLifecycle()
        if (isCarHotspotMode) {
            // Fade-in centered modal — independent of the floating button's
            // movable position. Tapping outside the card dismisses without
            // disconnecting the active AA session.
            AnimatedVisibility(
                visible = showChooser,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                CarHotspotPhoneChooserOverlay(
                    discovered = carHotspotPhones,
                    knownPhones = knownPhones,
                    defaultPhoneId = defaultPhoneId,
                    activePhoneId = activePhoneId,
                    sweeping = carHotspotSweeping,
                    sweepProgress = carHotspotSweepProgress,
                    switching = carHotspotSwitching,
                    chooserMessage = chooserMessage,
                    bluetoothPhoneHost = bluetoothPhoneHost,
                    onSelect = { viewModel.selectCarHotspotPhone(it) },
                    onSelectKnown = { kp ->
                        // Selecting a known-but-not-currently-discovered phone:
                        // build a synthetic DiscoveredPhone and ask the VM to
                        // dial. If it can't reach, the existing reconnect
                        // backoff handles it.
                        val match = carHotspotPhones.firstOrNull { it.phoneId == kp.phoneId }
                        if (match != null) {
                            viewModel.selectCarHotspotPhone(match)
                        }
                    },
                    onSetDefault = { viewModel.setDefaultPhoneId(it) },
                    onForget = { viewModel.forgetKnownPhone(it) },
                    onRescan = { viewModel.rescanCarHotspotPhones() },
                    onDismiss = { viewModel.dismissPhoneChooser() },
                )
            }
        }

        // The USB picker is above the floating controls in this Box. Never let
        // it cover Settings/Diagnostics, and offer an explicit way back to the
        // transport settings even while no phone is plugged in.
        if (showUsbPicker) {
            UsbDevicePickerOverlay(onOpenSettings = { showSettings = true })
        }

        // Also supported while CarPlay is enabled in settings but Android Auto owns projection.
        // Do not place this inside AnimatedVisibility/settings' rail, scroll or content pane.
        if (showCarPlaySafeEditor) carPlaySafeEditorOverlay { showCarPlaySafeEditor = false }

    }
}

/**
 * Top-of-screen status banner for the Car Hotspot connect lifecycle. Renders
 * over the projection surface so the user always knows what's happening
 * when streaming hasn't yet started (or has dropped).
 *
 * Different statuses get different colors + actions:
 *   - SEARCHING / SWITCHING / CONNECTING: blue/grey, spinner, no actions.
 *   - AWAITING_USER_PICK: amber, "Pick a phone" button → opens chooser.
 *   - PHONE_NOT_FOUND: red, "Try again" button → kicks sweep.
 */
@Composable
private fun CarHotspotStatusBanner(
    status: ProjectionViewModel.CarHotspotStatus,
    detail: String?,
    onTapPicker: () -> Unit,
    onTapScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (headline, accent, isError, isAwaitingPick) = when (status) {
        ProjectionViewModel.CarHotspotStatus.SEARCHING ->
            Quad("Looking for your phone…", Color(0xFF64B5F6), false, false)
        ProjectionViewModel.CarHotspotStatus.SWITCHING ->
            Quad("Switching phones…", Color(0xFFFFB74D), false, false)
        ProjectionViewModel.CarHotspotStatus.CONNECTING ->
            Quad("Connecting…", Color(0xFF64B5F6), false, false)
        ProjectionViewModel.CarHotspotStatus.AWAITING_USER_PICK ->
            Quad("Choose a phone to start", Color(0xFFFFB74D), false, true)
        ProjectionViewModel.CarHotspotStatus.PHONE_NOT_FOUND ->
            Quad("Couldn't find your phone", Color(0xFFEF5350), true, false)
        else -> Quad("", Color.White, false, false)
    }
    if (headline.isEmpty()) return

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC1B1B1F))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!isError && !isAwaitingPick) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = accent,
                strokeWidth = 2.dp,
            )
        }
        Column {
            Text(
                text = headline,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        when {
            isAwaitingPick -> {
                Spacer(modifier = Modifier.width(4.dp))
                androidx.compose.material3.FilledTonalButton(
                    onClick = onTapPicker,
                    modifier = Modifier.height(36.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp, vertical = 0.dp,
                    ),
                ) { Text("Pick phone", fontSize = 13.sp) }
            }
            isError -> {
                Spacer(modifier = Modifier.width(4.dp))
                androidx.compose.material3.FilledTonalButton(
                    onClick = onTapScan,
                    modifier = Modifier.height(36.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp, vertical = 0.dp,
                    ),
                ) { Text("Try again", fontSize = 13.sp) }
            }
        }
    }
}

/**
 * Tiny 4-tuple helper used by [CarHotspotStatusBanner] to pack its derived
 * display state. Kotlin's stdlib only goes to Triple.
 */
private data class Quad(
    val headline: String,
    val accent: Color,
    val isError: Boolean,
    val isAwaitingPick: Boolean,
)

/**
 * Centered, modal phone chooser for Car Hotspot mode.
 *
 * Layout requirements:
 *  - Centered on the screen, NOT anchored to the floating button (which is
 *    user-draggable).
 *  - Tap outside the card to dismiss without disconnecting the active session.
 *  - Tap inside the card does not propagate to the dismissable scrim.
 *
 * Sections:
 *  - Currently active phone (highlighted, no-op when tapped).
 *  - Known phones (persistent list); each shows online (mDNS+sweep visible)
 *    or offline (greyed). Trailing "Set default" / "Forget" actions.
 *  - "Other phones nearby" — discovered phones that aren't yet known.
 *  - "Scan" button (re-fires sweep + nudges mDNS).
 *  - "Cancel" / dismiss.
 */
@Composable
private fun CarHotspotPhoneChooserOverlay(
    discovered: List<com.openautolink.app.transport.PhoneDiscovery.DiscoveredPhone>,
    knownPhones: List<com.openautolink.app.data.KnownPhone>,
    defaultPhoneId: String,
    activePhoneId: String?,
    sweeping: Boolean,
    sweepProgress: String,
    switching: Boolean,
    chooserMessage: String?,
    /** Companion IP of the Bluetooth-connected phone; null outside WPP mode. */
    bluetoothPhoneHost: String?,
    onSelect: (com.openautolink.app.transport.PhoneDiscovery.DiscoveredPhone) -> Unit,
    onSelectKnown: (com.openautolink.app.data.KnownPhone) -> Unit,
    onSetDefault: (String) -> Unit,
    onForget: (String) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Build a quick lookup of currently-discovered phones by phone_id.
    val discoveredById: Map<String, com.openautolink.app.transport.PhoneDiscovery.DiscoveredPhone> =
        remember(discovered) { discovered.mapNotNull { it.phoneId?.let { id -> id to it } }.toMap() }

    // Discovered phones that are NOT in the known-phones list — shown
    // separately as "new phones nearby".
    val knownIds: Set<String> = remember(knownPhones) { knownPhones.map { it.phoneId }.toSet() }
    val newlyDiscovered: List<com.openautolink.app.transport.PhoneDiscovery.DiscoveredPhone> =
        remember(discovered, knownIds) {
            discovered.filter { it.phoneId != null && it.phoneId !in knownIds && it.isResolved }
        }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        // Force a fixed dark color scheme inside the chooser, regardless of
        // whatever the AAOS theme is doing. The projection screen runs over a
        // black surface (or a video stream), so a light card with dark text
        // becomes unreadable when video is missing. Hardcoding the palette
        // here keeps the chooser legible in every state.
        val darkScheme = androidx.compose.material3.darkColorScheme(
            surface = Color(0xFF1B1B1F),
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFCCCCCC),
            primary = Color(0xFF4FC3F7),
            onPrimary = Color.Black,
            primaryContainer = Color(0xFF0D47A1),
            onPrimaryContainer = Color.White,
            secondaryContainer = Color(0xFF2C2C32),
            onSecondaryContainer = Color.White,
            tertiary = Color(0xFFFFB74D),
            tertiaryContainer = Color(0xFF3E2723),
            onTertiaryContainer = Color.White,
            surfaceVariant = Color(0xFF2A2A30),
            onError = Color.White,
            error = Color(0xFFEF5350),
        )
        androidx.compose.material3.MaterialTheme(colorScheme = darkScheme) {
            // Inner card — centered. `clickable(enabled = false)` blocks the
            // outer scrim's dismiss when tapping inside the card.
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(darkScheme.surface)
                    .clickable(enabled = false) {}
                    .padding(24.dp)
                    .widthIn(min = 360.dp, max = 520.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = darkScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Switch Phone",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    if (switching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = darkScheme.primary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Active session keeps streaming until you pick a different phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = darkScheme.onSurfaceVariant,
                )

                if (!chooserMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(darkScheme.error.copy(alpha = 0.18f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            chooserMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = darkScheme.error,
                        )
                    }
                }

                // ── Known phones ─────────────────────────────────────
                if (knownPhones.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        "Your phones",
                        style = MaterialTheme.typography.labelLarge,
                        color = darkScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    knownPhones.forEach { kp ->
                    val online = kp.phoneId in discoveredById
                    val isActive = activePhoneId != null && activePhoneId == kp.phoneId
                    val isDefault = kp.phoneId == defaultPhoneId
                    KnownPhoneRow(
                        phone = kp,
                        online = online,
                        // Only meaningful in WPP mode. In Car Hotspot mode every
                        // online phone is genuinely selectable, so flagging one
                        // would be misleading rather than helpful.
                        isBluetoothPhone = bluetoothPhoneHost != null &&
                            discoveredById[kp.phoneId]?.host == bluetoothPhoneHost,
                        isActive = isActive,
                        isDefault = isDefault,
                        onTap = {
                            if (online && !isActive) onSelectKnown(kp)
                        },
                        onSetDefault = { onSetDefault(kp.phoneId) },
                        onForget = { onForget(kp.phoneId) },
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // ── New phones nearby ────────────────────────────────
            if (newlyDiscovered.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "New phones nearby",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                newlyDiscovered.forEach { phone ->
                    DiscoveredPhoneRow(phone = phone, onTap = { onSelect(phone) })
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // ── Empty state ──────────────────────────────────────
            if (knownPhones.isEmpty() && newlyDiscovered.isEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (sweeping) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                        Text(
                            if (sweeping) "Searching for phones…"
                            else "No phones found yet. Make sure the companion app is running on your phone and that the phone is connected to this car's WiFi.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // ── Sweep progress (when running with results already shown) ──
            if (sweeping && (knownPhones.isNotEmpty() || newlyDiscovered.isNotEmpty())) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    sweepProgress.ifEmpty { "Scanning…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // ── Bottom actions ───────────────────────────────────
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onRescan,
                    enabled = !sweeping,
                ) {
                    Text(if (sweeping) "Scanning…" else "Scan")
                }
                Spacer(modifier = Modifier.width(8.dp))
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
            }
        }
    }
}

@Composable
private fun KnownPhoneRow(
    phone: com.openautolink.app.data.KnownPhone,
    online: Boolean,
    isActive: Boolean,
    isDefault: Boolean,
    /**
     * True for the phone connected to this car over Bluetooth.
     *
     * Only that phone can start Android Auto, so without marking it the list
     * implies every online phone is equally usable.
     */
    isBluetoothPhone: Boolean = false,
    onTap: () -> Unit,
    onSetDefault: () -> Unit,
    onForget: () -> Unit,
) {
    val containerColor = when {
        isActive -> MaterialTheme.colorScheme.primaryContainer
        online -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .clickable(enabled = online && !isActive, onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Online indicator dot.
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    if (online) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                ),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    phone.friendlyName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                )
                if (isActive) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (isDefault) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "DEFAULT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                when {
                    isBluetoothPhone -> "Bluetooth connected — Android Auto starts here"
                    online -> "Online — id ${phone.phoneId.take(8)}"
                    else -> "Not on car WiFi"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Trailing actions (compact). Always available so the user can mark
        // a phone as default or forget it without it being currently online.
        if (!isDefault) {
            androidx.compose.material3.TextButton(onClick = onSetDefault) {
                Text("Default", fontSize = 12.sp)
            }
        }
        androidx.compose.material3.TextButton(onClick = onForget) {
            Text("Forget", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DiscoveredPhoneRow(
    phone: com.openautolink.app.transport.PhoneDiscovery.DiscoveredPhone,
    onTap: () -> Unit,
) {
    val sourceLabel = when (phone.source) {
        com.openautolink.app.transport.PhoneDiscovery.Source.MDNS -> "via mDNS"
        com.openautolink.app.transport.PhoneDiscovery.Source.SWEEP -> "via sweep"
        com.openautolink.app.transport.PhoneDiscovery.Source.BOTH -> "via mDNS + sweep"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.PhoneAndroid,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                phone.friendlyName ?: phone.serviceName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                buildString {
                    phone.host?.let { append(it) }
                    if (phone.port > 0) append(":").append(phone.port)
                    append("  ").append(sourceLabel)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Connect",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun VideoStatsOverlay(
    stats: VideoStats,
    audioStats: AudioStats,
    sessionState: SessionState,
    phoneName: String?,
    phoneBatteryLevel: Int?,
    aaPixelAspect: Int,
    aaDpi: Int,
    effectiveDpi: Int,
    wifiFrequencyMhz: Int = 0,
    modifier: Modifier = Modifier
) {
    StatsForNerdsOverlay(modifier = modifier) {
            StatLine("Session", sessionState.name)

            // WiFi band
            if (wifiFrequencyMhz > 0) {
                val band = if (wifiFrequencyMhz > 4000) "5 GHz" else "2.4 GHz"
                StatLine("WiFi", "$band (${wifiFrequencyMhz} MHz)",
                    valueColor = if (wifiFrequencyMhz > 4000) Color.Green else Color(0xFFFFFF00))
            }

            // Phone info
            if (phoneName != null) {
                StatLine("Phone", phoneName)
            }
            if (phoneBatteryLevel != null) {
                StatLine("Battery", "$phoneBatteryLevel%",
                    valueColor = when {
                        phoneBatteryLevel >= 50 -> Color.Green
                        phoneBatteryLevel >= 20 -> Color(0xFFFFFF00)
                        else -> Color.Red
                    })
            }

            if (stats.codec != "none") {
                Spacer(modifier = Modifier.height(4.dp))
                if (stats.codecFormat.isNotBlank()) {
                    StatLine("Codec", stats.codecFormat)
                }
                StatLine("Decoder", "${stats.decoderName}${if (stats.isHardware) " HW" else " SW"}")
                if (stats.width > 0) {
                    StatLine("Resolution", "${stats.width}x${stats.height}")
                }
                StatLine("DPI setting", "$aaDpi")
                if (effectiveDpi > 0 && effectiveDpi != aaDpi) {
                    StatLine("Auto DPI base", "$effectiveDpi",
                        valueColor = Color(0xFF64B5F6))
                }
                if (aaPixelAspect > 0) {
                    StatLine("Pixel Aspect", "${"%.4f".format(aaPixelAspect / 10000f)} (${aaPixelAspect}e⁻⁴)")
                } else {
                    StatLine("Pixel Aspect", "auto")
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

private fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "${h}h ${m}m ${s}s" else if (m > 0) "${m}m ${s}s" else "${s}s"
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
                    // "Disconnected" on its own reads as "gave up", which is
                    // wrong: after an ignition cycle the app sits here retrying
                    // every few seconds and reconnects on its own. Show that it
                    // is still working rather than leaving the user to guess.
                    val retrying = uiState.reconnectAttempt > 0 ||
                        uiState.statusMessage == "Disconnected"
                    if (retrying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Waiting for your phone\u2026",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (uiState.reconnectAttempt > 1) {
                                "Reconnecting automatically — attempt ${uiState.reconnectAttempt}"
                            } else {
                                "Reconnecting automatically"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF9E9E9E),
                        )
                    } else {
                        Text(
                            text = uiState.statusMessage,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFB0B0B0)
                        )
                    }
                }
                SessionState.CONNECTING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                SessionState.CONNECTED -> {
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
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

/**
 * Modal picker for USB transport or explicit same-cable CarPlay authorization recovery.
 *
 * Lists attached non-hub devices. A row tap grants exact single-use recovery
 * authority; existing Switch/Stay and awaited retirement still precede startup.
 * Apple devices use CarPlay, never the Android Auto accessory switch.
 */
@Composable
private fun UsbDevicePickerOverlay(onOpenSettings: () -> Unit) {
    val devices by com.openautolink.app.transport.usb.UsbConnectionManager
        .availableDevices.collectAsStateWithLifecycle()
    val state by com.openautolink.app.transport.usb.UsbConnectionManager
        .connectionState.collectAsStateWithLifecycle()
    val status by com.openautolink.app.transport.usb.UsbConnectionManager
        .status.collectAsStateWithLifecycle()

    val showPicker = state != com.openautolink.app.transport.usb.UsbConnectionState.CONNECTED &&
            state != com.openautolink.app.transport.usb.UsbConnectionState.CONNECTING &&
            state != com.openautolink.app.transport.usb.UsbConnectionState.SWITCHING_TO_ACCESSORY &&
            state != com.openautolink.app.transport.usb.UsbConnectionState.PERMISSION_REQUESTED

    AnimatedVisibility(
        visible = showPicker,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 360.dp, max = 560.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = "Select your phone",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (devices.isEmpty())
                            "Plug your phone into the car's USB port. Detected USB devices will appear here."
                        else
                            "Dismiss Android's USB dialog first, then tap your phone to authorize a USB permission request. Apple phones use CarPlay; Android phones use Android Auto. Your normal connection settings will not change.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (devices.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Waiting for USB device...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            devices.forEach { device ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            com.openautolink.app.transport.usb.UsbConnectionManager
                                                .selectDevice(device)
                                        }
                                        .padding(vertical = 12.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PhoneAndroid,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            text = device.friendlyName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "VID:%04X PID:%04X%s".format(
                                                device.vendorId, device.productId,
                                                if (device.isAccessoryMode) "  •  Accessory mode" else "",
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FilledTonalButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("usbConnectionSettingsButton"),
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connection settings")
                    }
                }
            }
        }
    }
}



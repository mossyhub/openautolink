package com.openautolink.app.ui.settings

import com.openautolink.app.ui.components.OalAlertDialog

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

import com.openautolink.app.session.SessionState
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.transport.aasdk.GalProtocolPolicy
import com.openautolink.app.ui.components.LocalEchoTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import com.openautolink.app.ui.theme.OalUiScale

// Standard MAC form aa:bb:cc:dd:ee:ff. gearhead rejects anything else
// (WIFI_INVALID_BSSID) before it will attempt projection.
private val BSSID_PATTERN = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")

internal enum class SettingsTab(
    val title: String,
    val icon: ImageVector,
) {
    CONNECTION("Connection", Icons.Default.Router),
    DISPLAY("Display", Icons.Default.DisplaySettings),
    VIDEO("Video", Icons.Default.VideoSettings),
    AUDIO("Audio", Icons.Default.Mic),
    INPUT("Input", Icons.Default.Keyboard),
    EV("EV", Icons.Default.BatteryChargingFull),
    DIAGNOSTICS("Diagnostics", Icons.Default.BugReport),
}

private data class DisplayModeOption(
    val key: String,
    val label: String,
    val description: String,
)

private val displayModes = listOf(
    DisplayModeOption(
        "system_ui_visible",
        "System UI Visible",
        "Video avoids system bars. Recommended default."
    ),
    DisplayModeOption(
        "fullscreen_immersive",
        "Fullscreen Immersive",
        "Video fills entire screen edge-to-edge."
    ),
)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    sessionState: SessionState = SessionState.IDLE,
    onSaveAndConnect: () -> Unit = {},
    onBack: () -> Unit = {},
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToSafeAreaEditor: () -> Unit = {},
    onNavigateToCarPlaySafeAreaEditor: () -> Unit = {},
    activeProtocolStatus: String? = null,
    audioBindings: AudioSettingsBindings? = null,
) {
    OalUiScale {
        SettingsScreenContent(viewModel, sessionState, onSaveAndConnect, onBack,
            onNavigateToDiagnostics, onNavigateToSafeAreaEditor,
            onNavigateToCarPlaySafeAreaEditor, activeProtocolStatus, audioBindings)
    }
}

@Composable
private fun SettingsScreenContent(
    viewModel: SettingsViewModel,
    sessionState: SessionState,
    onSaveAndConnect: () -> Unit,
    onBack: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToSafeAreaEditor: () -> Unit,
    onNavigateToCarPlaySafeAreaEditor: () -> Unit,
    activeProtocolStatus: String?,
    audioBindings: AudioSettingsBindings?,
) {
    val uiState by viewModel.settingsState.collectAsStateWithLifecycle()
    val carPlaySaving by viewModel.carPlaySettingsSaving.collectAsStateWithLifecycle()
    val carPlayLoaded by viewModel.carPlaySettingsLoaded.collectAsStateWithLifecycle()
    val applyError by viewModel.carPlayApplyError.collectAsStateWithLifecycle()
    val audioError by viewModel.audioSaveError.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(SettingsTab.CONNECTION) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag("settingsScreen"),
        ) {
            SettingsTabRail(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                onBack = onBack,
                backEnabled = !carPlaySaving,
            )

            // Content pane — fills remaining width
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // Status and transaction guidance stay outside the scrolling tab content.
                ConnectionStatusBar(
                    sessionState = sessionState,
                    activeProtocolStatus = activeProtocolStatus,
                    onSaveAndConnect = onSaveAndConnect,
                    saving = carPlaySaving,
                    ready = carPlayLoaded,
                    showSave = selectedTab != SettingsTab.AUDIO,
                )
                if (selectedTab == SettingsTab.AUDIO && audioError.isNotEmpty()) {
                    Text(audioError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("audioSaveError"))
                }
                if (selectedTab != SettingsTab.AUDIO && applyError.isNotEmpty()) {
                    Text(applyError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("settingsApplyError"))
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (selectedTab == SettingsTab.CONNECTION) {
                    SupportOpenAutoLink()
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Tab content
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        SettingsTab.CONNECTION -> ConnectionTab(viewModel, uiState)
                        SettingsTab.DISPLAY -> DisplayTab(
                            viewModel, uiState,
                            onNavigateToSafeAreaEditor,
                            onNavigateToCarPlaySafeAreaEditor,
                        )
                        SettingsTab.VIDEO -> VideoTab(viewModel, uiState)
                        SettingsTab.AUDIO -> AudioTab(viewModel, uiState, audioBindings)
                        SettingsTab.INPUT -> InputTab(viewModel, uiState)
                        SettingsTab.EV -> EvEnergyModelTab()
                        SettingsTab.DIAGNOSTICS -> DiagnosticsSettingsTab(
                            viewModel, uiState, onNavigateToDiagnostics
                        )
                    }
                }
            }
        }
    }
}

/** Independent scrolling keeps every tab reachable without moving the back button. */
@Composable
internal fun SettingsTabRail(
    selectedTab: SettingsTab,
    onSelectTab: (SettingsTab) -> Unit,
    onBack: () -> Unit,
    backEnabled: Boolean = true,
) {
    Column(Modifier.fillMaxHeight().width(112.dp)) {
        Box(Modifier.padding(vertical = 8.dp, horizontal = 12.dp)) {
            FilledTonalIconButton(onClick = onBack, enabled = backEnabled, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(24.dp))
            }
        }
        NavigationRail(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("settingsTabRail"),
            containerColor = MaterialTheme.colorScheme.background,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            SettingsTab.entries.forEach { tab ->
                NavigationRailItem(
                    selected = selectedTab == tab,
                    onClick = { onSelectTab(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
                    label = { Text(tab.title) },
                    modifier = Modifier.padding(vertical = 4.dp).testTag("settingsTab_${tab.name}"),
                )
            }
        }
    }
}

/** Small effective widths stack labels instead of squeezing out the input. */
@Composable
internal fun SettingsLabeledControl(label: String, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (maxWidth < 400.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) { content() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                content()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionStatusBar(
    sessionState: SessionState,
    activeProtocolStatus: String?,
    onSaveAndConnect: () -> Unit,
    saving: Boolean,
    ready: Boolean,
    showSave: Boolean,
) {
    val statusColor = if (activeProtocolStatus != null) MaterialTheme.colorScheme.primary else when (sessionState) {
        SessionState.STREAMING -> Color(0xFF4CAF50) // Green
        SessionState.CONNECTED -> Color(0xFFFF9800) // Orange
        SessionState.CONNECTING -> Color(0xFFFFC107) // Amber
        SessionState.IDLE -> Color(0xFF9E9E9E) // Grey
        SessionState.ERROR -> Color(0xFFF44336) // Red
    }
    val statusText = activeProtocolStatus ?: when (sessionState) {
        SessionState.STREAMING -> "Streaming"
        SessionState.CONNECTED -> "Connected"
        SessionState.CONNECTING -> "Connecting..."
        SessionState.IDLE -> "Disconnected"
        SessionState.ERROR -> "Error"
    }

    Column(Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Audio persists independently; never commits a video/connection draft.
            if (showSave) Button(
                onClick = onSaveAndConnect,
                enabled = ready && !saving,
                modifier = Modifier.testTag("saveAndConnectButton"),
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (saving) "Saving…" else "Save changes")
            }

            // Status indicator dot + text
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(statusColor, shape = MaterialTheme.shapes.small),
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (sessionState == SessionState.CONNECTING) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        }
        if (showSave) Text(
            "Wireless: reconnects automatically. USB: unplug and reconnect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionTab(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsVersionInfo()
        SectionHeader("Normal connections")
        Text("Android Auto and CarPlay keep separate normal connections. A USB phone can temporarily take over after you confirm; saved modes stay unchanged.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        CarPlaySettingsSection(viewModel)
        SectionHeader("Android Auto · Advanced connection")
        // BT MAC override — used in the SDR's bluetooth_service.car_address.
        // Some phones won't enter wireless AA pairing without a valid car MAC.
        // Does NOT affect call audio routing.
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            Text(
                text = "Bluetooth MAC override",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Override the Bluetooth MAC reported to the phone in the AA Service Discovery " +
                    "Response. Leave blank to auto-detect. Some phones won't enter wireless AA pairing " +
                    "if auto-detect returns empty or 02:00:00:00:00:00. Read the head unit's BT MAC from " +
                    "Settings → About → Bluetooth address and paste it here (format AA:BB:CC:DD:EE:FF). " +
                    "Does NOT affect call audio routing. Save changes, then reconnect your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LocalEchoTextField(
                value = uiState.btMacOverride,
                onValueChange = { filtered -> viewModel.updateBtMacOverride(filtered) },
                filter = { it.filter { c -> c.isLetterOrDigit() || c == ':' || c == '-' }.take(17).uppercase() },
                placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("btMacOverride"),
            )
        }


        SectionHeader("Android Auto")
        Text("Android Auto connection", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                AppPreferences.DIRECT_TRANSPORT_USB to "USB",
                AppPreferences.DIRECT_TRANSPORT_WPP to "Wireless",
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = uiState.directTransport == mode,
                    onClick = { viewModel.updateDirectTransport(mode) },
                    label = { Text(label) },
                )
            }
        }
        SettingsHelp(
            text = when (uiState.directTransport) {
                AppPreferences.DIRECT_TRANSPORT_USB -> "Android Auto normally connects by USB. Allow USB access when prompted. This choice does not change CarPlay's normal connection."
                else -> "Android Auto normally connects wirelessly using the companion app and the car Wi-Fi. Pair your Android phone in the car Bluetooth settings. This choice does not change CarPlay's normal connection."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (uiState.directTransport == AppPreferences.DIRECT_TRANSPORT_WPP) {
            val wppConfigBtStatus by viewModel.wppConfigBtStatus.collectAsStateWithLifecycle()
            val appliedWppConfig by viewModel.wppConfigBtAppliedConfig.collectAsStateWithLifecycle()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Wi-Fi details sent to the phone",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SettingsHelp(
                text = "Enter the network the phone should join to reach this head unit — normally the car's own hotspot. " +
                    "Enter the SSID, password, and BSSID manually, or send SSID/BSSID from the companion app over Bluetooth.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LocalEchoTextField(
                value = uiState.hotspotSsid,
                onValueChange = { viewModel.updateHotspotSsid(it) },
                externalValue = appliedWppConfig?.ssid,
                externalValueVersion = appliedWppConfig?.version ?: 0L,
                label = { Text("Network name (SSID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LocalEchoTextField(
                value = uiState.hotspotPassword,
                onValueChange = { viewModel.updateHotspotPassword(it) },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("Leave empty only if the network is genuinely open") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LocalEchoTextField(
                value = uiState.wppChannelMhz,
                onValueChange = { viewModel.updateWppChannelMhz(it) },
                label = { Text("Hotspot frequency in MHz (optional)") },
                singleLine = true,
                supportingText = {
                    Text(
                        "e.g. 5805. Leave blank to advertise the common 5GHz channels. " +
                            "The phone compares this against its own scan and refuses to " +
                            "connect if there is no overlap — set it if the log shows " +
                            "\"WiFi channels not supported\"."
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            var wppInterfaceMenuOpen by remember { mutableStateOf(false) }
            var wppInterfaces by remember { mutableStateOf(viewModel.listCarHotspotInterfaces()) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("WPP network interface", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        uiState.wppApInterface.ifBlank { AppPreferences.DEFAULT_WPP_AP_INTERFACE },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "All WPP advertising, discovery, and traffic is restricted to this interface. " +
                            "GM EVs normally use ${AppPreferences.DEFAULT_WPP_AP_INTERFACE}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    FilledTonalButton(onClick = {
                        wppInterfaces = viewModel.listCarHotspotInterfaces()
                        wppInterfaceMenuOpen = true
                    }) { Text("Choose") }
                    androidx.compose.material3.DropdownMenu(
                        expanded = wppInterfaceMenuOpen,
                        onDismissRequest = { wppInterfaceMenuOpen = false },
                    ) {
                        if (wppInterfaces.isEmpty()) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("No active IPv4 interfaces") },
                                onClick = { wppInterfaceMenuOpen = false },
                                enabled = false,
                            )
                        } else {
                            wppInterfaces.forEach { (name, ip) ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("$name  ($ip)") },
                                    onClick = {
                                        viewModel.updateWppApInterface(name)
                                        wppInterfaceMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        if (wppConfigBtStatus.contains("Listening", ignoreCase = true)) {
                            viewModel.stopWppConfigListener()
                        } else {
                            viewModel.startWppConfigListener()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (wppConfigBtStatus.contains("Listening", ignoreCase = true)) {
                            "Stop WPP config listener"
                        } else {
                            "Start WPP config listener"
                        }
                    )
                }
            }

            LocalEchoTextField(
                value = uiState.wppBssid,
                onValueChange = { viewModel.updateWppBssid(it) },
                externalValue = appliedWppConfig?.bssid,
                externalValueVersion = appliedWppConfig?.version ?: 0L,
                label = { Text("Access point BSSID") },
                singleLine = true,
                isError = uiState.wppBssid.isNotBlank() && !BSSID_PATTERN.matches(uiState.wppBssid),
                supportingText = {
                    Text(
                        if (uiState.wppBssid.isNotBlank() && !BSSID_PATTERN.matches(uiState.wppBssid)) {
                            "Must look like aa:bb:cc:dd:ee:ff"
                        } else {
                            "The AP's MAC address. Required — the phone rejects the connection without it."
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            val wppStatusColor = when {
                wppConfigBtStatus.contains("Listening") || wppConfigBtStatus.contains("Applied") ->
                    MaterialTheme.colorScheme.primary
                wppConfigBtStatus.contains("failed", ignoreCase = true) ||
                    wppConfigBtStatus.contains("not granted", ignoreCase = true) ||
                    wppConfigBtStatus.contains("disabled", ignoreCase = true) ->
                    MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(wppStatusColor, shape = MaterialTheme.shapes.small),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "WPP BT config listener: $wppConfigBtStatus",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OverlayButtonToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

/** Stateless production control: no projection ViewModel or reconnect dependency. */
@Composable
internal fun OalInterfaceSizeSetting(percent: Int, onPercentChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        SectionHeader("OAL interface size")
        Text(
            "Sizes OpenAutoLink's own controls and text. Applies immediately, without reconnecting. " +
                "This is NOT Android Auto or CarPlay DPI and does not resize the projected video. " +
                "Your Android font-size preference is preserved.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        listOf(100 to "Normal · 100%", 125 to "Large · 125% (recommended)",
            150 to "Extra Large · 150%").forEach { (size, label) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = percent == size, role = Role.RadioButton,
                        onClick = { onPercentChange(size) })
                    .testTag("oalUiScale_$size").padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = percent == size, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("oalUiScalePreview")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Preview · OAL controls and text", style = MaterialTheme.typography.bodyLarge)
                Text("This preview uses your current interface size.", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DisplayTab(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    onNavigateToSafeAreaEditor: () -> Unit,
    onNavigateToCarPlaySafeAreaEditor: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        OalInterfaceSizeSetting(uiState.oalUiScalePercent, viewModel::updateOalUiScalePercent)
        Spacer(Modifier.height(16.dp))
        SectionHeader("Shared")
        SectionHeader("Display Mode")

        Spacer(modifier = Modifier.height(4.dp))

        SettingsHelp(
            text = "Controls how the app interacts with AAOS system bars. " +
                    "Changes apply immediately. On some AAOS head units, the system may " +
                    "prevent apps from hiding bars. To change the video encoding resolution, " +
                    "use AA Resolution on the Video tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        displayModes.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.updateDisplayMode(mode.key) }
                    .padding(vertical = 10.dp)
                    .testTag("displayMode_${mode.key}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = uiState.displayMode == mode.key,
                    onClick = { viewModel.updateDisplayMode(mode.key) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (uiState.displayMode == mode.key) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        CarPlayDisplaySettingsSection(viewModel, onNavigateToCarPlaySafeAreaEditor)

        SectionHeader("Android Auto")

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        // --- DPI ---
        SectionHeader("AA Display Density (DPI)")

        Spacer(modifier = Modifier.height(4.dp))

        // --- Layout Width Target (auto-negotiate only) ---
        if (uiState.videoAutoNegotiate) {
            SettingsHelp(
                text = "Layout Width Target — scales DPI per resolution tier so AA always " +
                        "sees the same dp width regardless of which tier the phone picks. " +
                        "Controls card/search results sizing. 0 = off (use DPI slider below).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                data class LayoutPreset(val dp: Int, val label: String)
                listOf(
                    LayoutPreset(0, "Off"),
                    LayoutPreset(960, "Compact"),
                    LayoutPreset(1280, "Normal"),
                    LayoutPreset(1920, "Wide"),
                ).forEach { preset ->
                    val isSelected = uiState.aaTargetLayoutWidthDp == preset.dp
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable {
                            viewModel.updateAaTargetLayoutWidthDp(preset.dp)
                        }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (preset.dp > 0) {
                                Text(
                                    text = "${preset.dp}dp",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.aaTargetLayoutWidthDp > 0) {
                // Show the computed DPI per tier when layout width is active
                val tierInfo = listOf(
                    "480p" to 800, "720p" to 1280, "1080p" to 1920,
                    "1440p" to 2560, "4K" to 3840
                ).joinToString(" · ") { (name, width) ->
                    val dpi = (width * 160) / uiState.aaTargetLayoutWidthDp
                    "$name→${maxOf(dpi, 80)}"
                }
                Text(
                    text = "Per-tier DPI: $tierInfo",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // DPI description — show different text depending on whether layout width target is active
        if (uiState.aaTargetLayoutWidthDp > 0 && uiState.videoAutoNegotiate) {
            SettingsHelp(
                text = "Layout Width Target is active — DPI is computed automatically per tier. " +
                        "The slider below is ignored in auto-negotiate mode when a target is set.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 12.dp)
            )
        } else {
            val recommendedDpi = when (uiState.aaResolution) {
                "480p" -> 80
                "720p" -> 107
                "1440p" -> 213
                "4k" -> 320
                else -> 160
            }

            SettingsHelp(
                text = "Controls how Android Auto sizes its UI elements. " +
                        "Lower = more content, smaller text/icons. Higher = bigger text/icons, less content.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Expandable "How DPI works" info panel
            var dpiInfoExpanded by remember { mutableStateOf(false) }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clickable { dpiInfoExpanded = !dpiInfoExpanded }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.DisplaySettings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (dpiInfoExpanded) "How DPI works ▲" else "How DPI works ▼",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (dpiInfoExpanded) {
                        Spacer(modifier = Modifier.height(8.dp))

                        // What it affects
                        val currentWidthDp = if (!uiState.videoAutoNegotiate) {
                            val (w, _) = when (uiState.aaResolution) {
                                "480p" -> 800 to 480; "720p" -> 1280 to 720
                                "1440p" -> 2560 to 1440; "4k" -> 3840 to 2160
                                else -> 1920 to 1080
                            }
                            (w * 160) / uiState.aaDpi
                        } else null

                        Text(
                            text = "DPI tells Android Auto how physically dense your display is. " +
                                    "AA converts your pixel resolution into 'dp' (density-independent pixels) " +
                                    "using the formula:\n\n" +
                                    "    screenWidthDp = pixelWidth × 160 ÷ DPI\n\n" +
                                    "This dp width determines how AA lays out its UI — card widths, " +
                                    "search results panels, navigation rail, status bar height, and text size " +
                                    "are all sized in dp.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "AA layout breakpoints (from phone-side Phenotype flags):",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "  • < 880dp → Canonical (phone-like, narrow cards)\n" +
                                    "  • 880–1240dp → Semi-widescreen (medium cards)\n" +
                                    "  • > 1240dp → Full widescreen (wide cards, CoolWalk)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (currentWidthDp != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val layoutMode = when {
                                currentWidthDp < 880 -> "Canonical"
                                currentWidthDp < 1240 -> "Semi-widescreen"
                                else -> "Full widescreen"
                            }
                            Text(
                                text = "Your current setting: ${uiState.aaResolution} at DPI ${uiState.aaDpi} " +
                                        "→ ${currentWidthDp}dp → $layoutMode",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Practical tips:\n" +
                                    "• 160 DPI at 1080p = 1920dp (full widescreen, wide search results)\n" +
                                    "• 320 DPI at 1080p = 960dp (semi-wide, narrower search results)\n" +
                                    "• Higher DPI = smaller dp width = more compact AA layout\n" +
                                    "• Text and icons also get physically smaller at higher DPI\n" +
                                    "• Match DPI to resolution to keep consistent sizing:\n" +
                                    "    480p→80  720p→107  1080p→160  1440p→213  4K→320",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!uiState.videoAutoNegotiate && uiState.aaResolution != "1080p" && uiState.aaDpi != recommendedDpi) {
                Text(
                    text = "Recommended for ${uiState.aaResolution}: $recommendedDpi DPI (tap to apply)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64B5F6),
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .clickable { viewModel.updateAaDpi(recommendedDpi) }
                )
            }
        }

        // Slider for custom DPI (80-640 range)
        var sliderDpi by remember(uiState.aaDpi) { mutableIntStateOf(uiState.aaDpi) }
        // Slider is disabled either when:
        //  - target-dp mode is active in auto-negotiate (existing behaviour), or
        //  - aaAutoDpi is on (auto-DPI overrides whatever the slider says).
        val dpiSliderEnabled =
            !(uiState.aaTargetLayoutWidthDp > 0 && uiState.videoAutoNegotiate) &&
            !uiState.aaAutoDpi

        // Auto-DPI toggle. When ON, density is computed at session start
        // from panel DPI + render rect + codec margins so AA UI lands at
        // native physical sizes regardless of display mode. When OFF, the
        // manual slider value is sent literally.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto DPI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SettingsHelp(
                    text = "Compute density from panel DPI, render rect, and codec margins. " +
                        "Tracks display mode (fullscreen vs system UI visible) automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.aaAutoDpi,
                onCheckedChange = { viewModel.updateAaAutoDpi(it) },
                modifier = Modifier.testTag("aaAutoDpi"),
            )
        }

        Text(
            text = "DPI: $sliderDpi",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (dpiSliderEnabled) Color.Unspecified
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Slider(
            value = sliderDpi.toFloat(),
            onValueChange = { sliderDpi = it.toInt() },
            onValueChangeFinished = { viewModel.updateAaDpi(sliderDpi) },
            valueRange = 80f..640f,
            steps = 0,
            enabled = dpiSliderEnabled,
            modifier = Modifier.fillMaxWidth()
        )

        // Quick presets — laid out as chips, not aligned to slider
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(96, 132, 160, 200, 240, 320, 480).forEach { preset ->
                val isSelected = uiState.aaDpi == preset
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable {
                        sliderDpi = preset
                        viewModel.updateAaDpi(preset)
                    }
                ) {
                    Text(
                        text = "$preset",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        // --- Video Scaling Mode ---
        SectionHeader("Video Scaling")

        Spacer(modifier = Modifier.height(4.dp))

        SettingsHelp(
            text = "How the video fits your screen. " +
                    "Letterbox shows the full frame with black bars on the sides. " +
                    "Crop fills the screen but cuts off top/bottom. " +
                    "Save changes, then reconnect your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        listOf(
            "letterbox" to "Letterbox (no crop, black bars on sides)",
            "crop" to "Fill screen (set Pixel Aspect for correct proportions)",
        ).forEach { (key, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.updateVideoScalingMode(key) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = uiState.videoScalingMode == key,
                    onClick = { viewModel.updateVideoScalingMode(key) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (uiState.videoScalingMode == key) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        // --- AA Video Margins ---
        SectionHeader("AA Video Margins")

        Spacer(modifier = Modifier.height(4.dp))

        SettingsHelp(
            text = "Tells the phone to letterbox its UI inside the encoded video. " +
                    "Crop mode renders only the inner (non-margin) rect onto the panel " +
                    "with uniform square-pixel scaling. Auto computes margins per-tier " +
                    "from panel aspect ratio so any AA codec resolution lands correctly. " +
                    "Disable Auto to send the literal values below " +
                    "(0/0 = no margins, decoder stretches the full codec frame).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        SettingsLabeledControl(label = "Auto-compute margins") {
            Switch(
                checked = uiState.aaAutoMargins,
                onCheckedChange = { viewModel.updateAaAutoMargins(it) },
                modifier = Modifier.testTag("aaAutoMargins"),
            )
        }

        // Width Margin
        SettingsLabeledControl(label = "Width Margin") {
            LocalEchoTextField(
                value = if (uiState.aaWidthMargin == 0) "" else uiState.aaWidthMargin.toString(),
                onValueChange = { filtered ->
                    val margin = filtered.toIntOrNull() ?: 0
                    viewModel.updateAaWidthMargin(margin.coerceIn(0, 1000))
                },
                filter = { it.filter { c -> c.isDigit() } },
                placeholder = { Text("0 (auto)") },
                singleLine = true,
                modifier = Modifier.width(120.dp).testTag("aaWidthMargin"),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("px", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Height Margin
        SettingsLabeledControl(label = "Height Margin") {
            LocalEchoTextField(
                value = if (uiState.aaHeightMargin == 0) "" else uiState.aaHeightMargin.toString(),
                onValueChange = { filtered ->
                    val margin = filtered.toIntOrNull() ?: 0
                    viewModel.updateAaHeightMargin(margin.coerceIn(0, 1000))
                },
                filter = { it.filter { c -> c.isDigit() } },
                placeholder = { Text("0 (auto)") },
                singleLine = true,
                modifier = Modifier.width(120.dp).testTag("aaHeightMargin"),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("px", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Viewing Distance / Decoder Depth (GM SDR fields) ---
        // GM puts viewing_distance + decoder_additional_depth in every
        // VideoConfiguration. The phone uses viewing_distance (mm from driver
        // eye to screen) to pick text/icon sizes that look right at the
        // actual seating distance. decoder_additional_depth tells the phone
        // how many extra reordered frames the car can buffer (GM uses 1).
        Text(
            text = "Viewing distance & decoder depth (advanced)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
        SettingsHelp(
            text = "Sent in VideoConfiguration. Viewing distance = mm from driver eye " +
                    "to display (GM uses ~700 for centre IP screens; tune per vehicle). " +
                    "Decoder depth = extra reordered frames the head unit can buffer " +
                    "(GM uses 1; raise only if you see B-frame stalls). 0 = omit field.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        SettingsLabeledControl(label = "Viewing Distance") {
            LocalEchoTextField(
                value = if (uiState.aaViewingDistanceMm == 0) "" else uiState.aaViewingDistanceMm.toString(),
                onValueChange = { filtered ->
                    val mm = filtered.toIntOrNull() ?: 0
                    viewModel.updateAaViewingDistanceMm(mm.coerceIn(0, 5000))
                },
                filter = { it.filter { c -> c.isDigit() } },
                placeholder = { Text("0 (omit)") },
                singleLine = true,
                modifier = Modifier.width(120.dp).testTag("aaViewingDistanceMm"),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("mm", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsLabeledControl(label = "Decoder Add'l Depth") {
            LocalEchoTextField(
                value = if (uiState.aaDecoderAdditionalDepth == 0) "" else uiState.aaDecoderAdditionalDepth.toString(),
                onValueChange = { filtered ->
                    val depth = filtered.toIntOrNull() ?: 0
                    viewModel.updateAaDecoderAdditionalDepth(depth.coerceIn(0, 8))
                },
                filter = { it.filter { c -> c.isDigit() } },
                placeholder = { Text("0 (omit)") },
                singleLine = true,
                modifier = Modifier.width(120.dp).testTag("aaDecoderAdditionalDepth"),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("frames", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        // --- Pixel Aspect Ratio ---
        SectionHeader("Pixel Aspect Ratio")

        Spacer(modifier = Modifier.height(4.dp))

        SettingsHelp(
            text = "Compensates for wide displays in Crop mode. Off = square pixels (no compensation). " +
                    "Auto = computed from your display's aspect ratio vs video aspect ratio. " +
                    "Manual = enter a specific value. " +
                    "Only matters in Crop mode — Letterbox constrains to 16:9 so no stretching occurs.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Pixel aspect mode: -1=auto, 0=off, >0=manual
        val pixelAspectMode = when {
            uiState.aaPixelAspect == -1 -> "auto"
            uiState.aaPixelAspect == 0 -> "off"
            else -> "manual"
        }
        val pixelAspectModes = mapOf(
            "off" to "Off (square pixels — no compensation)",
            "auto" to "Auto (compute from display size, crop mode only)",
            "manual" to "Manual (enter value)"
        )

        pixelAspectModes.forEach { (key, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        when (key) {
                            "off" -> viewModel.updateAaPixelAspect(0)
                            "auto" -> viewModel.updateAaPixelAspect(-1)
                            "manual" -> viewModel.updateAaPixelAspect(10000)
                        }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = pixelAspectMode == key,
                    onClick = {
                        when (key) {
                            "off" -> viewModel.updateAaPixelAspect(0)
                            "auto" -> viewModel.updateAaPixelAspect(-1)
                            "manual" -> viewModel.updateAaPixelAspect(10000)
                        }
                    },
                )
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
            }
        }

        // Show manual input only when manual mode selected
        if (pixelAspectMode == "manual") {
            SettingsLabeledControl(label = "Value (×10⁴)") {
                LocalEchoTextField(
                    value = uiState.aaPixelAspect.toString(),
                    onValueChange = { filtered ->
                        val pa = filtered.toIntOrNull() ?: 10000
                        viewModel.updateAaPixelAspect(pa.coerceIn(1, 30000))
                    },
                    filter = { it.filter { c -> c.isDigit() } },
                    placeholder = { Text("10000") },
                    singleLine = true,
                    modifier = Modifier.width(140.dp).testTag("aaPixelAspect"),
                )
            }
            SettingsHelp(
                text = "10000 = 1:1 (no compensation). " +
                        "Blazer EV 2914×1134 at 1080p ≈ 14454. " +
                        "Higher = wider pixel compensation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "For Android Auto video changes, save changes, then reconnect your phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // --- AA Display Insets ---
        SectionHeader("AA Display Insets")

        Spacer(modifier = Modifier.height(4.dp))

        SettingsHelp(
            text = "Tells the phone where to keep critical AA UI (dropdowns, " +
                    "dialogs, FAB buttons) so they don't land on physical " +
                    "curves, AAOS chrome, or anywhere you don't want. " +
                    "Leave at 0 to use what AAOS reports (system bars + " +
                    "display cutouts). Set values to add extra padding for " +
                    "vehicles whose curves AAOS doesn't expose (e.g. Blazer " +
                    "EV's rounded top-right corner).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onNavigateToSafeAreaEditor,
                modifier = Modifier.testTag("editSafeAreaButton"),
            ) {
                Text("Edit Display Insets")
            }

            Text(
                text = formatInsetLabel(
                    uiState.safeAreaTop, uiState.safeAreaBottom,
                    uiState.safeAreaLeft, uiState.safeAreaRight
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.safeAreaTop > 0 || uiState.safeAreaBottom > 0 ||
                    uiState.safeAreaLeft > 0 || uiState.safeAreaRight > 0)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        // --- Drive Side ---
        Column {
        SectionHeader("Android Auto Drive Side")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Select your driving side. Affects Android Auto UI layout.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        listOf("left" to "Left-Hand Drive (LHD)", "right" to "Right-Hand Drive (RHD)").forEach { (key, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.updateDriveSide(key) }
                    .padding(vertical = 10.dp)
                    .testTag("driveSide_$key"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = uiState.driveSide == key,
                    onClick = { viewModel.updateDriveSide(key) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (uiState.driveSide == key) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        } // Column (Drive Side)

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        // --- Features ---
        SectionHeader("Android Auto Features")

        Spacer(modifier = Modifier.height(8.dp))

        // GPS Forwarding
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GPS Forwarding",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Forward vehicle GPS to Android Auto for navigation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.gpsForwarding,
                onCheckedChange = { viewModel.updateGpsForwarding(it) },
                modifier = Modifier.testTag("gpsForwardingToggle"),
            )
        }

        // Always in Park
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Always in Park",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Always tell the phone the car is parked, so Android Auto " +
                        "keeps the on-screen keyboard and other features unlocked. " +
                        "Off by default, which reports the car's real gear. " +
                        "Only enable it if you are not the one driving — it disables " +
                        "Android Auto's driving safety restrictions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.alwaysInPark,
                onCheckedChange = { viewModel.updateAlwaysInPark(it) },
                modifier = Modifier.testTag("alwaysInParkToggle"),
            )
        }

        // Cluster Navigation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cluster Navigation",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                SettingsHelp(
                    text = "Enable the cluster service to show turn-by-turn directions on the instrument cluster. Disable if your car doesn't have a cluster display or Templates Host. Takes effect on next connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.clusterNavigation,
                onCheckedChange = { viewModel.updateClusterNavigation(it) },
                modifier = Modifier.testTag("clusterNavigationToggle"),
            )
        }

        // IMU Sensors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Send IMU Sensors",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Send accelerometer, gyroscope, and compass data to the bridge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.sendImuSensors,
                onCheckedChange = { viewModel.updateSendImuSensors(it) },
                modifier = Modifier.testTag("sendImuSensorsToggle"),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        // --- OAL controls menu ---
        SectionHeader("OAL controls menu")
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Tap the OAL logo during projection for Settings, phone switching, reconnect and diagnostics. " +
                "Long-press and drag the logo to move it. Its preferred position returns when screen space is available again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        // --- AA UI Customization ---
        SectionHeader("Android Auto UI")

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Sync AA Theme",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Sync Android Auto day/night theme with the car.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.syncAaTheme,
                onCheckedChange = { viewModel.updateSyncAaTheme(it) },
                modifier = Modifier.testTag("syncAaThemeToggle"),
            )
        }

        // Hide flags
        Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hide AA Clock",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Hide Android Auto's clock (AAOS has its own).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.hideAaClock,
                onCheckedChange = { viewModel.updateHideAaClock(it) },
                modifier = Modifier.testTag("hideAaClockToggle"),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hide Phone Signal",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Hide the phone signal strength indicator from AA status bar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.hidePhoneSignal,
                onCheckedChange = { viewModel.updateHidePhoneSignal(it) },
                modifier = Modifier.testTag("hidePhoneSignalToggle"),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hide Battery Level",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Hide the phone battery indicator from AA status bar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.hideBatteryLevel,
                onCheckedChange = { viewModel.updateHideBatteryLevel(it) },
                modifier = Modifier.testTag("hideBatteryLevelToggle"),
            )
        }
        } // Column (hide flags)

        Spacer(modifier = Modifier.height(16.dp))

        SectionHeader("Distance Units")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Units for navigation distances shown in the cluster display.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        val distanceUnitOptions = listOf(
            "auto" to "Auto (based on locale)",
            "metric" to "Metric (km, m)",
            "imperial" to "Imperial (mi, ft)",
        )
        distanceUnitOptions.forEach { (key, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.updateDistanceUnits(key) }
                    .padding(vertical = 6.dp)
                    .testTag("distanceUnits_$key"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = uiState.distanceUnits == key,
                    onClick = { viewModel.updateDistanceUnits(key) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun VideoTab(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        CarPlayVideoSettingsSection(viewModel)

        SectionHeader("Android Auto")
        SectionHeader("Video Negotiation")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "GAL / PDK protocol version",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        SettingsHelp(
            text = "The requested version activates cumulative phone behavior. Use 1.7 as the " +
                    "compatibility fallback; 6.0 enables modern video envelopes and the short HEVC keyframe policy.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        GalProtocolPolicy.supportedVersions.forEach { version ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.updateGalVersion(version) }
                    .padding(vertical = 6.dp)
                    .testTag("galVersion_$version"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = uiState.galVersion == version,
                    onClick = { viewModel.updateGalVersion(version) },
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = when (version) {
                        "1.7" -> "1.7 — legacy compatibility"
                        "4.3" -> "4.3 — modern display metadata"
                        "5.0" -> "5.0 — ackless audio, one codec family"
                        "5.1" -> "5.1 — media options and EV forecast"
                        else -> "6.0 — default; modern video and short HEVC keyframes"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        SettingsHelp(
            text = "Save changes, then reconnect your phone to use the selected raw HU-requested version. " +
                    "GAL 6.0 is verified over USB and wireless with H.265 at 1440p.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(20.dp))

        SettingsHelp(
            text = "When enabled, the phone picks the best codec and resolution it supports. " +
                    "Disable to manually choose a specific codec and resolution.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.updateVideoAutoNegotiate(!uiState.videoAutoNegotiate) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = uiState.videoAutoNegotiate,
                onCheckedChange = { viewModel.updateVideoAutoNegotiate(it) }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (uiState.videoAutoNegotiate) "Auto (Recommended)" else "Manual",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (!uiState.videoAutoNegotiate) {

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader("Video Codec")

        Spacer(modifier = Modifier.height(4.dp))

        SettingsHelp(
            text = "Video codec the phone uses to encode the AA stream. " +
                    "H.264 supports resolutions through 1080p. H.265 / HEVC supports " +
                    "1440p and 4K. With GAL 6.0, H.265 startup is verified over USB and wireless " +
                    "with one IDR every 120 frames.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        val isHighRes = uiState.aaResolution in listOf("1440p", "4k", "1440p_p", "4k_p")
        listOf(
            Triple("h264", "H.264", " — through 1080p"),
            Triple("h265", "H.265 / HEVC", if (isHighRes) " — selected for 1440p/4K" else " — supports 1440p/4K"),
            Triple("vp9", "VP9", ""),
        ).forEach { (key, label, suffix) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.updateVideoCodec(key) }
                    .padding(vertical = 10.dp)
                    .testTag("videoCodec_$key"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = uiState.videoCodec == key,
                    onClick = { viewModel.updateVideoCodec(key) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = label + suffix,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (uiState.videoCodec == key) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        // --- Frame Rate ---
        SectionHeader("Frame Rate")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Video frame rate the phone encodes at. Most phones support 60 FPS at 1080p.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        listOf(30 to "30 FPS", 60 to "60 FPS").forEach { (fps, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.updateVideoFps(fps) }
                    .padding(vertical = 10.dp)
                    .testTag("videoFps_$fps"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = uiState.videoFps == fps,
                    onClick = { viewModel.updateVideoFps(fps) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (uiState.videoFps == fps) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        // --- Resolution Tier ---
        SectionHeader("AA Resolution")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Resolution tier the phone encodes at. Higher = better quality, more bandwidth. " +
                    "H.265 or VP9 recommended for 1440p/4K.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        listOf(
            "480p" to "480p (800×480)",
            "720p" to "720p (1280×720)",
            "1080p" to "1080p (1920×1080)",
            "1440p" to "1440p (2560×1440)",
            "4k" to "4K (3840×2160)",
            // Portrait variants — AA enum has 9:16 mirrors of 720p+ for tall
            // panels. No 480p portrait in the spec.
            "720p_p" to "720p portrait (720×1280)",
            "1080p_p" to "1080p portrait (1080×1920)",
            "1440p_p" to "1440p portrait (1440×2560)",
            "4k_p" to "4K portrait (2160×3840)",
        ).forEach { (key, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.updateAaResolution(key) }
                    .padding(vertical = 10.dp)
                    .testTag("aaResolution_$key"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = uiState.aaResolution == key,
                    onClick = { viewModel.updateAaResolution(key) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (uiState.aaResolution == key) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }

        } // end if (!videoAutoNegotiate)

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader("Seed keyframe thresholds")

        Spacer(modifier = Modifier.height(4.dp))

        SettingsHelp(
            text = "Keyframes smaller than the selected codec's threshold are treated as " +
                "startup placeholders and decoded silently for 2.5 seconds. Set 0 to disable " +
                "the guard for that codec. Defaults come from uploaded vehicle logs: H.264 " +
                "10,000 bytes, H.265 4,096 bytes, VP9 disabled. Save changes, then reconnect your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )

        data class SeedThresholdOption(
            val testTag: String,
            val label: String,
            val value: Int,
            val onChange: (Int) -> Unit,
        )

        listOf(
            SeedThresholdOption(
                "seedThreshold_h264",
                "H.264 seed cutoff (bytes)",
                uiState.videoSeedThresholdH264,
                viewModel::updateVideoSeedThresholdH264,
            ),
            SeedThresholdOption(
                "seedThreshold_h265",
                "H.265 / HEVC seed cutoff (bytes)",
                uiState.videoSeedThresholdH265,
                viewModel::updateVideoSeedThresholdH265,
            ),
            SeedThresholdOption(
                "seedThreshold_vp9",
                "VP9 seed cutoff (bytes)",
                uiState.videoSeedThresholdVp9,
                viewModel::updateVideoSeedThresholdVp9,
            ),
        ).forEach { option ->
            LocalEchoTextField(
                value = option.value.toString(),
                onValueChange = { raw ->
                    option.onChange(raw.filter(Char::isDigit).take(7).toIntOrNull() ?: 0)
                },
                label = { Text(option.label) },
                supportingText = { Text("0 disables this codec's seed filter") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag(option.testTag),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("For Android Auto video changes, save changes, then reconnect your phone.", style = MaterialTheme.typography.bodySmall)
    }
}

// --- Input Tab: Key Remapping ---

/** AA actions that can be mapped to physical keys. */
private data class MappableAction(
    val aaKeycode: Int,
    val label: String,
    val description: String,
)

private val mappableActions = listOf(
    MappableAction(85, "Play / Pause", "Toggle media playback"),
    MappableAction(87, "Next Track", "Skip to next track"),
    MappableAction(88, "Previous Track", "Go to previous track"),
    MappableAction(86, "Stop", "Stop media playback"),
    MappableAction(126, "Play", "Start media playback"),
    MappableAction(127, "Pause", "Pause media playback"),
    MappableAction(84, "Voice Assistant", "Activate Google Assistant"),
    MappableAction(19, "DPAD Up", "Navigate up"),
    MappableAction(20, "DPAD Down", "Navigate down"),
    MappableAction(21, "DPAD Left", "Navigate left"),
    MappableAction(22, "DPAD Right", "Navigate right"),
    MappableAction(23, "DPAD Center", "Select / Confirm"),
    MappableAction(5, "Call", "Answer phone call"),
    MappableAction(6, "End Call", "Hang up phone call"),
)

@Composable
private fun DialogKeyCaptureWindowHook() {
    val dialogView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(dialogView) {
        val provider = com.openautolink.app.input.findDialogWindowProvider(dialogView)
        val window = provider?.window
        val original = window?.callback
        val callback = if (original != null) {
            object : android.view.Window.Callback by original {
                override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                    com.openautolink.app.diagnostics.OalLog.i(
                        "KeyRemapDialog",
                        "Dialog window dispatchKeyEvent: keycode=${event.keyCode} " +
                            "(${android.view.KeyEvent.keyCodeToString(event.keyCode)}) " +
                            "action=${event.action} source=0x${Integer.toHexString(event.source)}",
                    )
                    if (com.openautolink.app.input.KeyCaptureBus.handle(event)) return true
                    return original.dispatchKeyEvent(event)
                }
            }
        } else {
            null
        }
        com.openautolink.app.diagnostics.OalLog.i(
            "KeyRemapDialog",
            "Window hook setup: providerFound=${provider != null} " +
                "windowFound=${window != null} originalCallbackPresent=${original != null} " +
                "installed=${callback != null}",
        )
        if (window != null && callback != null) {
            window.callback = callback
        }
        onDispose {
            if (window != null && callback != null && window.callback === callback) {
                window.callback = original
            }
        }
    }
}

@Composable
private fun InputTab(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    // Parse current key map from JSON
    val currentMap = remember(uiState.keyRemap) {
        if (uiState.keyRemap.isBlank()) emptyMap()
        else try {
            val json = org.json.JSONObject(uiState.keyRemap)
            buildMap { for (key in json.keys()) put(key.toInt(), json.getInt(key)) }
        } catch (_: Exception) { emptyMap() }
    }

    // Reverse map: AA keycode → list of hardware keycodes mapped to it
    val reverseMap = remember(currentMap) {
        val rev = mutableMapOf<Int, MutableList<Int>>()
        for ((hwKey, aaKey) in currentMap) {
            rev.getOrPut(aaKey) { mutableListOf() }.add(hwKey)
        }
        rev
    }

    // Key capture dialog state
    var captureTarget by remember { mutableStateOf<MappableAction?>(null) }
    var lastDetectedKey by remember { mutableStateOf<Pair<Int, String>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader("Key Remapping")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Map physical buttons (steering wheel, remote) to Android Auto actions. " +
                    "Tap an action, then press the physical button you want to assign. " +
                    "Key assignments apply immediately and persist across reconnects.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Last detected key (diagnostic)
        if (lastDetectedKey != null) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Text(
                    "Last key: ${lastDetectedKey!!.second} (${lastDetectedKey!!.first})",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // List of mappable actions
        mappableActions.forEach { action ->
            val boundKeys = reverseMap[action.aaKeycode] ?: emptyList()
            val boundLabel = if (boundKeys.isNotEmpty()) {
                boundKeys.joinToString(", ") { code ->
                    android.view.KeyEvent.keyCodeToString(code)
                        .removePrefix("KEYCODE_")
                }
            } else "Not mapped"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { captureTarget = action }
                    .padding(vertical = 8.dp, horizontal = 4.dp)
                    .testTag("keyMap_${action.aaKeycode}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        action.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        boundLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (boundKeys.isNotEmpty()) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Tap to assign",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            HorizontalDivider(modifier = Modifier.fillMaxWidth())
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Reset all mappings button
        if (currentMap.isNotEmpty()) {
            FilledTonalButton(
                onClick = { viewModel.updateKeyRemap("") },
                modifier = Modifier.testTag("resetKeyMap"),
            ) {
                Text("Reset All Mappings")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // Key capture dialog
    if (captureTarget != null) {
        val target = captureTarget!!

        // Subscribe to the global key bus while the dialog is open. Compose
        // `onKeyEvent` does NOT receive steering-wheel / remote keys, and an
        // AlertDialog runs in its own Window so `Activity.dispatchKeyEvent`
        // is also bypassed — we have to hook the dialog window's callback.
        DisposableEffect(target) {
            com.openautolink.app.diagnostics.OalLog.i(
                "KeyRemapDialog",
                "Capture dialog opened for target=${target.label} (aaKeycode=${target.aaKeycode}) — installing listener",
            )
            com.openautolink.app.input.KeyCaptureBus.listener = { code ->
                val name = android.view.KeyEvent.keyCodeToString(code)
                    .removePrefix("KEYCODE_")
                com.openautolink.app.diagnostics.OalLog.i(
                    "KeyRemapDialog",
                    "Listener fired: keycode=$code name=$name",
                )
                lastDetectedKey = code to name
            }
            onDispose {
                com.openautolink.app.diagnostics.OalLog.i(
                    "KeyRemapDialog",
                    "Capture dialog closing — clearing listener",
                )
                com.openautolink.app.input.KeyCaptureBus.listener = null
            }
        }

        OalAlertDialog(
            onDismissRequest = { captureTarget = null; lastDetectedKey = null },
            title = { Text("Assign key to: ${target.label}") },
            text = {
                Column {
                    DialogKeyCaptureWindowHook()
                    Text("Press any physical button (steering wheel, remote, keyboard) now.")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "The next key press will be mapped to ${target.label} (AA keycode ${target.aaKeycode}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (lastDetectedKey != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Detected: ${lastDetectedKey!!.second} (${lastDetectedKey!!.first})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                if (lastDetectedKey != null) {
                    Button(onClick = {
                        val hwKey = lastDetectedKey!!.first
                        val newMap = currentMap.toMutableMap()
                        // Remove any existing mapping for this hardware key
                        newMap.keys.removeAll { it == hwKey }
                        newMap[hwKey] = target.aaKeycode
                        val json = org.json.JSONObject(newMap.mapKeys { it.key.toString() }).toString()
                        viewModel.updateKeyRemap(json)
                        captureTarget = null
                        lastDetectedKey = null
                    }) {
                        Text("Assign ${lastDetectedKey!!.second}")
                    }
                }
            },
            dismissButton = {
                Button(onClick = { captureTarget = null; lastDetectedKey = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
internal fun AudioTab(viewModel: SettingsViewModel, uiState: SettingsUiState, bindings: AudioSettingsBindings? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        AudioMicrophoneSettings(uiState.micSource, viewModel::updateMicSource, bindings)
        CarPlayCallSettings(bindings)
        AudioSectionTitle("Both")
        OutlinedButton(onClick = { bindings?.onOpenVehicleAudio?.invoke() }, enabled = bindings != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("openVehicleAudio")) {
            Text("Open OpenAutoLink in vehicle Audio")
        }
        if (bindings?.actionMessage?.isNotBlank() == true)
            Text(bindings.actionMessage, modifier = Modifier.testTag("audioActionMessage"))

        // --- Per-Purpose Volume Offsets ---
        SectionHeader("Android Auto · Volume offsets")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Saved for the next Android Auto connection; 0 is the default.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        VolumeOffsetSlider(
            label = "Media",
            value = uiState.volumeOffsetMedia,
            onValueChange = { viewModel.updateVolumeOffsetMedia(it) }
        )
        VolumeOffsetSlider(
            label = "Navigation",
            value = uiState.volumeOffsetNavigation,
            onValueChange = { viewModel.updateVolumeOffsetNavigation(it) }
        )
        VolumeOffsetSlider(
            label = "Assistant / Speech",
            value = uiState.volumeOffsetAssistant,
            onValueChange = { viewModel.updateVolumeOffsetAssistant(it) }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun VolumeOffsetSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            val display = if (value >= 0) "+$value%" else "$value%"
            Text(
                display,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (value == 0) MaterialTheme.colorScheme.onSurfaceVariant
                       else MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = -100f..100f,
            steps = 19, // 10% increments
            modifier = Modifier.testTag("volumeOffset_$label"),
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Explanations are opt-in; values, controls, errors and safety warnings remain visible. */
@Composable
internal fun SettingsHelp(
    text: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide details" else "Details")
        }
        if (expanded) Text(text, style = style, color = color)
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

/**
 * Format inset values as readable text, showing only non-zero sides.
 */
private fun formatInsetLabel(top: Int, bottom: Int, left: Int, right: Int): String {
    val parts = mutableListOf<String>()
    if (top > 0) parts += "Top: ${top}px"
    if (bottom > 0) parts += "Bottom: ${bottom}px"
    if (left > 0) parts += "Left: ${left}px"
    if (right > 0) parts += "Right: ${right}px"
    return parts.joinToString(", ")
}

private val logLevelOptions = listOf("DEBUG", "INFO", "WARN", "ERROR")

@Composable
private fun DiagnosticsSettingsTab(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    onNavigateToDiagnostics: () -> Unit,
) {
    val contributionStatus by com.openautolink.app.diagnostics.EvContributionService.status.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader("Diagnostics")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "View structured logs and telemetry in the diagnostics dashboard.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Link to full diagnostics screen
        FilledTonalButton(
            onClick = onNavigateToDiagnostics,
            modifier = Modifier.testTag("openDiagnosticsButton"),
        ) {
            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Diagnostics Dashboard")
        }

        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader("File Logging")

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Log to File", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Write OAL diagnostic logs (transport, video, audio, sensors, " +
                        "navigation) to a file in openautolink/logs/. Recommended " +
                        "only for troubleshooting — turn off for best performance.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = uiState.fileLoggingEnabled,
                onCheckedChange = { viewModel.updateFileLoggingEnabled(it) },
                modifier = Modifier.testTag("fileLoggingToggle"),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Always Log (maintainer)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Automatically start file logging whenever the projection " +
                        "screen comes up, so a whole drive is captured without " +
                        "remembering to toggle it on. Persisted — stays on across " +
                        "drives and reconnects until turned off. Uses internal " +
                        "storage (no USB required). Recommended only for the " +
                        "maintainer capturing intermittent issues.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = uiState.logPersistEnabled,
                onCheckedChange = { viewModel.updateLogPersistEnabled(it) },
                modifier = Modifier.testTag("logPersistToggle"),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Always Log to USB", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Automatically start file logging whenever the app is running, " +
                        "but ONLY if a USB drive is mounted. No fallback to internal " +
                        "storage. Logging starts as soon as a stick is attached and " +
                        "stops when it's removed. Independent of the overlay record " +
                        "button \u2014 it can still be used to stop a session early.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = uiState.fileLoggingAutoStartUsb,
                onCheckedChange = { viewModel.updateFileLoggingAutoStartUsb(it) },
                modifier = Modifier.testTag("fileLoggingAutoUsbToggle"),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Include Full Logcat", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "On by default. Captures the full Android logcat for this " +
                        "app's process (native C++/JNI, AOSP framework, MediaCodec, " +
                        "AudioTrack, Binder, Surface) alongside the normal log, so " +
                        "every log upload includes it. This is the only way to see " +
                        "native and framework messages on a head unit, where adb is " +
                        "not available — app-freeze diagnoses come entirely from " +
                        "here.\n\n" +
                        "AAOS will not let apps read system-wide logs without root, " +
                        "so other apps and PowerManager events are still not " +
                        "included. Makes uploads noticeably larger; turn it off if " +
                        "you are not troubleshooting.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = uiState.logcatCaptureEnabled,
                onCheckedChange = { viewModel.updateLogcatCaptureEnabled(it) },
                modifier = Modifier.testTag("logcatCaptureToggle"),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Automatic compact EV contribution", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Off by default and separate from manual diagnostic upload. Opt-in is bound to compact schema v2, " +
                        "the HTTPS server origin, and a fingerprint of the current upload token; changing either revokes consent and deletes retained contribution files until you opt in again. " +
                        "Completed drives are queued as compact ZIPs and sent only after a real Park or ignition-off sample, on validated internet, " +
                        "outside startup and with no active or reconnecting projection. Records use elapsed minute buckets; the manifest discloses only start/completion hour buckets. They include battery, distance, " +
                        "forecast, coarse capacity, gear/ignition and EV configuration fields. It includes no destinations, coordinates, VIN, device identifiers, or general logs. " +
                        "The upload label is a generic hash of the frozen vehicle class, never the mutable device label. No raw token is stored. " +
                        "Turning this off deletes every retained contribution artifact but keeps learned calibration.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Consent: ${if (contributionStatus.consentValid) "valid" else contributionStatus.consentInvalidReason}; " +
                        "retained=${contributionStatus.retainedCount}; last upload=${contributionStatus.lastUploadOutcome}; " +
                        "evicted=${contributionStatus.evictedCount}; quarantined=${contributionStatus.quarantinedCount}; " +
                        "upload stalled=${contributionStatus.uploadStalled}; delete=${contributionStatus.lastDeleteResult}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("evContributionStatus"),
                )
                Button(onClick = viewModel::deletePendingEvContributions) {
                    Text("Delete pending compact contributions")
                }
            }
            Switch(
                checked = uiState.evContributionConsent,
                onCheckedChange = viewModel::updateEvContributionConsent,
                modifier = Modifier.testTag("evContributionConsentToggle"),
            )
        }

        // ── Log Upload (maintainer) ────────────────────────────────────
        // OFF by default. Uploads recent diagnostic logs to the maintainer's
        // own server. Only meant for the maintainer's own car + phones.
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Upload Logs (maintainer)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Off by default. When on, Upload Logs is available in the OAL " +
                        "controls menu; selecting it zips recent logs and POSTs " +
                        "them to your own server for analysis. Only enable on your own " +
                        "devices — it never affects other users.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = uiState.logUploadEnabled,
                onCheckedChange = { viewModel.updateLogUploadEnabled(it) },
                modifier = Modifier.testTag("logUploadToggle"),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Simulate Ignition Cycle (maintainer)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Off by default. When on, Simulate ignition cycle is available " +
                        "in the OAL controls menu. Selecting it disconnects, drops the " +
                        "Bluetooth advertisement, waits 45 seconds, then brings it " +
                        "back.\n\n" +
                        "It cannot switch off the head unit's Bluetooth radio, so " +
                        "your phone's connection is never broken and it will not " +
                        "re-dial on its own the way it does after a real ignition " +
                        "cycle. Use it to test teardown and reconnection on the car " +
                        "side; a real ignition cycle is still the only full test.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = uiState.simulateIgnitionButton,
                onCheckedChange = { viewModel.updateSimulateIgnitionButton(it) },
                modifier = Modifier.testTag("simulateIgnitionToggle"),
            )
        }

        if (uiState.logUploadEnabled) {
            LocalEchoTextField(
                value = uiState.logUploadUrl,
                onValueChange = { viewModel.updateLogUploadUrl(it) },
                label = { Text("Upload URL") },
                placeholder = { Text("https://oal-logs.example.com/upload") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            // Disclosure is local to this field's composition, never saved or persisted.
            var uploadTokenVisible by remember { mutableStateOf(false) }
            LocalEchoTextField(
                value = uiState.logUploadToken,
                onValueChange = { viewModel.updateLogUploadToken(it) },
                label = { Text("Upload token") },
                singleLine = true,
                visualTransformation = if (uploadTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { uploadTokenVisible = !uploadTokenVisible }) {
                        Icon(
                            imageVector = if (uploadTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (uploadTokenVisible) "Hide upload token" else "Show upload token",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            LocalEchoTextField(
                value = uiState.logUploadDeviceLabel,
                onValueChange = { viewModel.updateLogUploadDeviceLabel(it) },
                label = { Text("Device label") },
                placeholder = { Text("Blazer Car") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
    }
}

/** Validate IPv4 address: 4 octets, each 0–255. */
private fun isValidIpv4(ip: String): Boolean {
    val parts = ip.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        val n = part.toIntOrNull() ?: return false
        n in 0..255 && part == n.toString() // rejects leading zeros like "01"
    }
}

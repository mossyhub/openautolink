package com.openautolink.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

import com.openautolink.app.session.SessionState
import com.openautolink.app.transport.ControlMessage
import androidx.compose.material3.FilterChip

private enum class SettingsTab(
    val title: String,
    val icon: ImageVector,
) {
    CONNECTION("Connection", Icons.Default.Router),
    PHONES("Phones", Icons.Default.PhoneAndroid),
    BRIDGE("Bridge", Icons.Default.SettingsRemote),
    DISPLAY("Display", Icons.Default.DisplaySettings),
    VIDEO("Video", Icons.Default.VideoSettings),
    AUDIO("Audio", Icons.Default.Mic),
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
) {
    val uiState by viewModel.settingsState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(SettingsTab.CONNECTION) }
    val bridgeConnected = sessionState != SessionState.IDLE &&
            sessionState != SessionState.ERROR &&
            sessionState != SessionState.CONNECTING

    // Bind bridge update manager from session manager (if running)
    LaunchedEffect(Unit) {
        val ctx = viewModel.getApplication<android.app.Application>()
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val sm = com.openautolink.app.session.SessionManager.getInstance(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main),
            ctx, am
        )
        viewModel.bindUpdateManager(sm.bridgeUpdateManager)
        sm.setPairedPhonesCallback { phones -> viewModel.onPairedPhonesReceived(phones) }
        sm.setPairingModeCallback { enabled -> viewModel.onPairingModeStatus(enabled) }
    }

    // Clean up callbacks when leaving Settings
    DisposableEffect(Unit) {
        onDispose {
            val ctx = viewModel.getApplication<android.app.Application>()
            val am = ctx.getSystemService(android.media.AudioManager::class.java)
            val sm = com.openautolink.app.session.SessionManager.getInstance(
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main),
                ctx, am
            )
            sm.setPairedPhonesCallback(null)
            sm.setPairingModeCallback(null)
        }
    }

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
            // Left rail — back button + tab icons
            Column(
                modifier = Modifier.fillMaxHeight(),
            ) {
                // Back button
                Box(modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp)) {
                    FilledTonalIconButton(
                        onClick = onBack,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                // Tab rail
                NavigationRail(
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    SettingsTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = selectedTab == tab,
                            onClick = {
                                selectedTab = tab
                            },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title,
                                )
                            },
                            label = { Text(tab.title) },
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            // Content pane — fills remaining width
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                // Connection status bar + Save & Connect button — always visible
                ConnectionStatusBar(
                    sessionState = sessionState,
                    isDirectMode = uiState.connectionMode == "direct",
                    onSaveAndConnect = onSaveAndConnect,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Tab content
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        SettingsTab.CONNECTION -> ConnectionTab(viewModel, uiState)
                        SettingsTab.PHONES -> PhonesTab(viewModel, uiState, bridgeConnected)
                        SettingsTab.BRIDGE -> BridgeTab(viewModel, uiState, sessionState)
                        SettingsTab.DISPLAY -> DisplayTab(
                            viewModel, uiState, bridgeConnected,
                            onNavigateToSafeAreaEditor,
                        )
                        SettingsTab.VIDEO -> VideoTab(viewModel, uiState, bridgeConnected)
                        SettingsTab.AUDIO -> AudioTab(viewModel, uiState)
                        SettingsTab.DIAGNOSTICS -> DiagnosticsSettingsTab(
                            viewModel, uiState, onNavigateToDiagnostics
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusBar(
    sessionState: SessionState,
    isDirectMode: Boolean = false,
    onSaveAndConnect: () -> Unit,
) {
    val statusColor = when (sessionState) {
        SessionState.STREAMING -> Color(0xFF4CAF50) // Green
        SessionState.PHONE_CONNECTED -> Color(0xFF8BC34A) // Light green
        SessionState.BRIDGE_CONNECTED -> Color(0xFFFF9800) // Orange
        SessionState.CONNECTING -> Color(0xFFFFC107) // Amber
        SessionState.IDLE -> Color(0xFF9E9E9E) // Grey
        SessionState.ERROR -> Color(0xFFF44336) // Red
    }
    val statusText = when (sessionState) {
        SessionState.STREAMING -> "Streaming"
        SessionState.PHONE_CONNECTED -> "Phone Connected"
        SessionState.BRIDGE_CONNECTED -> "Bridge Connected"
        SessionState.CONNECTING -> "Connecting..."
        SessionState.IDLE -> "Disconnected"
        SessionState.ERROR -> "Error"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Save & Connect button — left-aligned, first element
        val bridgeConnected = isDirectMode || (sessionState != SessionState.IDLE &&
                sessionState != SessionState.ERROR &&
                sessionState != SessionState.CONNECTING)
        Button(
            onClick = onSaveAndConnect,
            enabled = bridgeConnected,
            modifier = Modifier.testTag("saveAndConnectButton"),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save & Restart")
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
}

@Composable
private fun ConnectionTab(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    val discoveredBridges by viewModel.discoveredBridges.collectAsStateWithLifecycle()
    val isDiscovering by viewModel.isDiscovering.collectAsStateWithLifecycle()
    val networkInterfaces by viewModel.networkInterfaces.collectAsStateWithLifecycle()

    // Scan interfaces on first composition
    LaunchedEffect(Unit) {
        viewModel.scanNetworkInterfaces()
    }

    // Stop discovery when leaving the Connection tab
    DisposableEffect(Unit) {
        onDispose { viewModel.stopDiscovery() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // --- Network Interface Section ---
        SectionHeader("Network Interface")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Select the network interface used to reach the bridge.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        // Dropdown — always shown, even when empty
        var dropdownExpanded by remember { mutableStateOf(false) }
        val savedIfaceName = uiState.networkInterface
        val selectedIface = networkInterfaces.find { it.name == savedIfaceName }
        val effectiveSelection = selectedIface ?: networkInterfaces.firstOrNull()

        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        ExposedDropdownMenuBox(
            expanded = dropdownExpanded,
            onExpandedChange = { dropdownExpanded = it },
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .testTag("networkInterfaceDropdown"),
        ) {
            OutlinedTextField(
                value = when {
                    networkInterfaces.isEmpty() -> "No interfaces found"
                    effectiveSelection != null -> "${effectiveSelection.displayName} — ${effectiveSelection.ipAddress ?: "no IP"}"
                    savedIfaceName.isNotBlank() -> "$savedIfaceName (not present)"
                    else -> "Select interface"
                },
                onValueChange = {},
                readOnly = true,
                label = { Text("Network Adapter") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                modifier = Modifier
                    .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )

            if (networkInterfaces.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                ) {
                    networkInterfaces.forEach { iface ->
                        val isSelected = iface.name == savedIfaceName
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = iface.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    Text(
                                        text = "IP: ${iface.ipAddress ?: "not assigned"} | ${iface.macAddress}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (iface.ipAddress != null) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                viewModel.selectNetworkInterface(iface.name)
                                dropdownExpanded = false
                            },
                            modifier = Modifier.testTag("networkInterface_${iface.name}"),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Show details of the selected interface
        if (effectiveSelection != null) {
            Surface(
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(0.5f),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingRow("Interface", effectiveSelection.name)
                    SettingRow("Transport", effectiveSelection.transport)
                    if (effectiveSelection.macAddress.isNotBlank()) {
                        SettingRow("MAC", effectiveSelection.macAddress)
                    }
                    SettingRow("IP", effectiveSelection.ipAddress ?: "not assigned")
                    SettingRow("Status", if (effectiveSelection.isUp) "Up" else "Down")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = { viewModel.scanNetworkInterfaces() },
                modifier = Modifier.testTag("rescanInterfacesButton"),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Rescan")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.5f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Connection Mode Section ---
        SectionHeader("Connection Mode")

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("direct" to "Direct (No Bridge)", "bridge" to "Bridge (SBC)").forEach { (mode, label) ->
                FilterChip(
                    selected = uiState.connectionMode == mode,
                    onClick = { viewModel.updateConnectionMode(mode) },
                    label = { Text(label) },
                )
            }
        }

        Text(
            text = if (uiState.connectionMode == "direct")
                "Phone connects via WiFi hotspot directly to this head unit. No SBC hardware needed."
            else
                "Phone connects through an SBC bridge device via USB Ethernet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Hotspot Credentials (Direct mode) ---
        if (uiState.connectionMode == "direct") {
            SectionHeader("Phone Hotspot Credentials")

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Enter your phone's hotspot SSID and password. These are sent to the phone during the Bluetooth handshake so it knows which WiFi network to use. Leave blank to test without credentials.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            var ssidInput by remember(uiState.hotspotSsid) { mutableStateOf(uiState.hotspotSsid) }
            OutlinedTextField(
                value = ssidInput,
                onValueChange = { ssidInput = it; viewModel.updateHotspotSsid(it) },
                label = { Text("Hotspot SSID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            var pskInput by remember(uiState.hotspotPassword) { mutableStateOf(uiState.hotspotPassword) }
            OutlinedTextField(
                value = pskInput,
                onValueChange = { pskInput = it; viewModel.updateHotspotPassword(it) },
                label = { Text("Hotspot Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // --- Bridge Connection Section (only shown in bridge mode) ---
        if (uiState.connectionMode == "bridge") {
        SectionHeader("Bridge Connection")

        Spacer(modifier = Modifier.height(8.dp))

        var hostInput by remember(uiState.bridgeHost) {
            mutableStateOf(uiState.bridgeHost)
        }
        OutlinedTextField(
            value = hostInput,
            onValueChange = {
                hostInput = it
                viewModel.updateBridgeHost(it)
            },
            label = { Text("Bridge IP Address") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .testTag("bridgeHostInput")
        )

        Spacer(modifier = Modifier.height(12.dp))

        var portInput by remember(uiState.bridgePort) {
            mutableStateOf(uiState.bridgePort.toString())
        }
        OutlinedTextField(
            value = portInput,
            onValueChange = {
                portInput = it
                it.toIntOrNull()?.let { port ->
                    if (port in 1..65535) viewModel.updateBridgePort(port)
                }
            },
            label = { Text("Bridge Port") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .testTag("bridgePortInput")
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "The app connects to the bridge at this address. " +
                    "Default: 192.168.222.222:5288",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.5f))

        Spacer(modifier = Modifier.height(24.dp))
        } // end if (bridge mode)

        SectionHeader("Network Discovery")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Scan the local network for OpenAutoLink bridges via mDNS.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    if (isDiscovering) viewModel.stopDiscovery() else viewModel.startDiscovery()
                },
                modifier = Modifier.testTag("discoverBridgesButton"),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isDiscovering) "Stop" else "Discover")
            }

            if (isDiscovering) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(
                    text = "Scanning...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (discoveredBridges.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))

            discoveredBridges.forEach { bridge ->
                val isSelected = uiState.bridgeHost == bridge.host &&
                        uiState.bridgePort == bridge.port

                Surface(
                    tonalElevation = if (isSelected) 4.dp else 1.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .padding(vertical = 4.dp)
                        .clickable { viewModel.selectBridge(bridge) }
                        .testTag("discoveredBridge_${bridge.host}"),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bridge.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "${bridge.host}:${bridge.port}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isSelected) {
                            Text(
                                text = "Selected",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        } else if (!isDiscovering) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No bridges found. Make sure the bridge is running and on the same network.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BridgeDisabledBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Connect to bridge to edit these settings",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Wraps bridge-owned settings: shows a "connect to bridge" banner and dims content
 * when disconnected. When connected, renders content normally.
 */
@Composable
private fun BridgeSection(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (!enabled) BridgeDisabledBanner()
    Box(modifier = if (enabled) Modifier else Modifier
        .alpha(0.38f)
        .pointerInput(Unit) { /* consume all touch events when disabled */ }) {
        content()
    }
}

@Composable
private fun PhonesTab(viewModel: SettingsViewModel, uiState: SettingsUiState, bridgeConnected: Boolean = false) {
    val pairedPhones by viewModel.pairedPhones.collectAsStateWithLifecycle()
    val phonesLoading by viewModel.phonesLoading.collectAsStateWithLifecycle()
    val pairingEnabled by viewModel.pairingEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.requestPairedPhones()
    }

    BridgeSection(enabled = bridgeConnected) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader("Paired Phones")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Phones paired with the bridge via Bluetooth. " +
                    "Works with Android Auto devices.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = { viewModel.requestPairedPhones() },
                enabled = !phonesLoading,
                modifier = Modifier.testTag("refreshPhonesButton"),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refresh")
            }

            if (phonesLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (pairedPhones.isNotEmpty()) {
            pairedPhones.forEach { phone ->
                val isDefault = uiState.defaultPhoneMac == phone.mac

                Surface(
                    tonalElevation = if (isDefault) 4.dp else 1.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .padding(vertical = 4.dp)
                        .testTag("pairedPhone_${phone.mac}"),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (phone.aaActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = phone.name.ifBlank { "Unknown Device" },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = phone.mac,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (phone.aaActive) {
                                Text(
                                    text = "Streaming",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (isDefault) {
                                Text(
                                    text = "Default",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                FilledTonalButton(
                                    onClick = { viewModel.updateDefaultPhoneMac(phone.mac) },
                                    modifier = Modifier.testTag("setDefault_${phone.mac}"),
                                ) {
                                    Text("Set Default")
                                }
                            }
                            if (!phone.aaActive) {
                                FilledTonalButton(
                                    onClick = { viewModel.switchPhone(phone.mac) },
                                    modifier = Modifier.testTag("connect_${phone.mac}"),
                                ) {
                                    Text("Connect")
                                }
                            }
                            FilledTonalButton(
                                onClick = { viewModel.forgetPhone(phone.mac) },
                                modifier = Modifier.testTag("forget_${phone.mac}"),
                            ) {
                                Text("Forget", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        } else if (!phonesLoading) {
            Text(
                text = "No paired phones found. Make sure the bridge is running and phones are paired via Bluetooth.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Default Phone ---
        SectionHeader("Default Phone")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "When multiple paired phones are in range, the bridge will prefer " +
                    "connecting to the default phone. " +
                    "If no default is set, the bridge connects to the first phone it sees.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (uiState.defaultPhoneMac.isNotBlank()) {
            val defaultPhone = pairedPhones.find { it.mac == uiState.defaultPhoneMac }
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(0.5f),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = defaultPhone?.name ?: "Unknown",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = uiState.defaultPhoneMac,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = { viewModel.updateDefaultPhoneMac("") },
                        modifier = Modifier.testTag("clearDefaultPhone"),
                    ) {
                        Text("Clear")
                    }
                }
            }
        } else {
            Text(
                text = "No default phone set. The bridge will connect to the first paired phone it finds.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Pairing Mode ---
        SectionHeader("New Phone Pairing")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "When disabled, the bridge stops advertising via Bluetooth and " +
                    "will not accept new phone pairings. Existing paired phones can " +
                    "still connect. Useful once all your phones are paired.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .testTag("pairingModeToggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (pairingEnabled) "Pairing enabled" else "Pairing disabled",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (pairingEnabled) "Bridge is discoverable — new phones can pair"
                           else "Bridge is hidden — only existing phones can connect",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = pairingEnabled,
                onCheckedChange = { viewModel.setPairingMode(it) },
                modifier = Modifier.testTag("pairingModeSwitch"),
            )
        }
    }
    } // BridgeSection
}

@Composable
private fun DisplayTab(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    bridgeConnected: Boolean = false,
    onNavigateToSafeAreaEditor: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader("Display Mode")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
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
                    .fillMaxWidth(0.7f)
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
                Column {
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

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- AA Display Insets ---
        SectionHeader("AA Display Insets")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Insets the AA projection from the display edges.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(0.7f),
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

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Drive Side (bridge-owned) ---
        BridgeSection(enabled = bridgeConnected) {
        Column {
        SectionHeader("Drive Side")

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
                    .fillMaxWidth(0.7f)
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
        } // Column
        } // BridgeSection (Drive Side)

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Features ---
        SectionHeader("Features")

        Spacer(modifier = Modifier.height(8.dp))

        // GPS Forwarding
        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
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

        // Cluster Navigation
        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cluster Navigation",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Show turn-by-turn directions on the instrument cluster.",
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
                .fillMaxWidth(0.7f)
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

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Overlay Buttons ---
        SectionHeader("Overlay Buttons")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Show or hide floating overlay buttons during streaming.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Settings Button",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Floating gear icon to access settings during projection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.overlaySettingsButton,
                onCheckedChange = { viewModel.updateOverlaySettingsButton(it) },
                modifier = Modifier.testTag("overlaySettingsToggle"),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Stats Button",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Floating button to toggle performance stats overlay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.overlayStatsButton,
                onCheckedChange = { viewModel.updateOverlayStatsButton(it) },
                modifier = Modifier.testTag("overlayStatsToggle"),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Phone Switch Button",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Floating button to quickly switch between paired phones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.overlayPhoneSwitchButton,
                onCheckedChange = { viewModel.updateOverlayPhoneSwitchButton(it) },
                modifier = Modifier.testTag("overlayPhoneSwitchToggle"),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- AA UI Customization ---
        SectionHeader("Android Auto UI")

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
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

        // Hide flags are bridge-owned: greyed out when disconnected
        BridgeSection(enabled = bridgeConnected) {
        Column {
        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
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
                .fillMaxWidth(0.7f)
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
                .fillMaxWidth(0.7f)
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
        } // Column
        } // BridgeSection (hide flags)

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
                    .fillMaxWidth(0.7f)
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
private fun VideoTab(viewModel: SettingsViewModel, uiState: SettingsUiState, bridgeConnected: Boolean = false) {
    BridgeSection(enabled = bridgeConnected) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader("Video Negotiation")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "When enabled, the phone picks the best codec and resolution it supports. " +
                    "Disable to manually choose a specific codec and resolution.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
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

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        SectionHeader("Video Codec")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Video codec the phone uses to encode the AA stream. " +
                    "H.265 is recommended for 1440p/4K. H.264 may work at higher resolutions " +
                    "depending on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        val isHighRes = uiState.aaResolution in listOf("1440p", "4k")
        listOf(
            Triple("h264", "H.264", if (isHighRes) "" else " (Recommended)"),
            Triple("h265", "H.265 / HEVC", if (isHighRes) " (Recommended)" else ""),
            Triple("vp9", "VP9", ""),
        ).forEach { (key, label, suffix) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
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

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

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
                    .fillMaxWidth(0.7f)
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

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Resolution Tier ---
        SectionHeader("AA Resolution")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Resolution tier the phone encodes at. Higher = better quality, more bandwidth. " +
                    "H.265 or VP9 recommended for 1440p/4K. Phone AA developer settings may need " +
                    "to be enabled for resolutions above 1080p.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        listOf(
            Triple("480p", "480p (800×480)", false),
            Triple("720p", "720p (1280×720)", false),
            Triple("1080p", "1080p (1920×1080)", false),
            Triple("1440p", "1440p (2560×1440)", true),
            Triple("4k", "4K (3840×2160)", true),
        ).forEach { (key, label, isHighRes) ->
            val warningColor = Color(0xFFFFB74D)
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
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
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (uiState.aaResolution == key) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isHighRes) warningColor else Color.Unspecified,
                    )
                    if (isHighRes) {
                        Text(
                            text = "AA Developer Mode required on phone",
                            style = MaterialTheme.typography.bodySmall,
                            color = warningColor,
                        )
                    }
                }
            }
        }

        } // end if (!videoAutoNegotiate)

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- DPI ---
        SectionHeader("AA Display Density (DPI)")

        Spacer(modifier = Modifier.height(4.dp))

        val recommendedDpi = when (uiState.aaResolution) {
            "480p" -> 80
            "720p" -> 107
            "1440p" -> 213
            "4k" -> 320
            else -> 160
        }

        Text(
            text = "Controls how Android Auto sizes its UI elements. " +
                    "160 is standard for 1080p. For higher resolutions, scale DPI proportionally " +
                    "to keep UI elements the same visual size (e.g. 320 for 4K). " +
                    "Lower = more content, smaller controls. Higher = bigger controls, less content.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

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

        // Slider for custom DPI (80-400 range)
        var sliderDpi by remember(uiState.aaDpi) { mutableIntStateOf(uiState.aaDpi) }
        Text(
            text = "DPI: $sliderDpi",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Slider(
            value = sliderDpi.toFloat(),
            onValueChange = { sliderDpi = it.toInt() },
            onValueChangeFinished = { viewModel.updateAaDpi(sliderDpi) },
            valueRange = 80f..400f,
            steps = 0,
            modifier = Modifier.fillMaxWidth(0.7f)
        )

        // Quick presets — laid out as chips, not aligned to slider
        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(120, 160, 200, 240, 320).forEach { preset ->
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

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Video Scaling Mode ---
        SectionHeader("Video Scaling")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "How the video fits your screen. " +
                    "Letterbox shows the full frame with black bars on the sides. " +
                    "Crop fills the screen but cuts off top/bottom. " +
                    "Requires reconnect (Save & Restart).",
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
                    .fillMaxWidth(0.7f)
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

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- AA Video Margins ---
        SectionHeader("AA Video Margins")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Override how AA layouts its UI within the video frame. " +
                    "0 = auto-computed from your display aspect ratio. " +
                    "Use these to adjust if AA buttons appear behind letterbox bars or display curves.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Width Margin
        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Width Margin",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.width(140.dp),
            )
            OutlinedTextField(
                value = if (uiState.aaWidthMargin == 0) "" else uiState.aaWidthMargin.toString(),
                onValueChange = { value ->
                    val margin = value.filter { it.isDigit() }.toIntOrNull() ?: 0
                    viewModel.updateAaWidthMargin(margin.coerceIn(0, 1000))
                },
                placeholder = { Text("0 (auto)") },
                singleLine = true,
                modifier = Modifier.width(120.dp).testTag("aaWidthMargin"),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("px", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Height Margin
        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Height Margin",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.width(140.dp),
            )
            OutlinedTextField(
                value = if (uiState.aaHeightMargin == 0) "" else uiState.aaHeightMargin.toString(),
                onValueChange = { value ->
                    val margin = value.filter { it.isDigit() }.toIntOrNull() ?: 0
                    viewModel.updateAaHeightMargin(margin.coerceIn(0, 1000))
                },
                placeholder = { Text("0 (auto)") },
                singleLine = true,
                modifier = Modifier.width(120.dp).testTag("aaHeightMargin"),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("px", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Pixel Aspect Ratio
        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.width(140.dp)) {
                Text(
                    text = "Pixel Aspect",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "×10000. 0 = auto from display. Only set to override.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = if (uiState.aaPixelAspect == 0) "" else uiState.aaPixelAspect.toString(),
                onValueChange = { value ->
                    val v = value.filter { it.isDigit() }.toIntOrNull() ?: 0
                    viewModel.updateAaPixelAspect(v.coerceIn(0, 30000))
                },
                placeholder = { Text("0 (auto)") },
                singleLine = true,
                modifier = Modifier.width(120.dp).testTag("aaPixelAspect"),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Changes to video settings require an AA session restart. " +
                    "The bridge will reconnect the phone with the new configuration.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    } // BridgeSection
}

@Composable
private fun AudioTab(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // --- Audio Source ---
        SectionHeader("Audio Source")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "How audio from Android Auto reaches the head unit.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        audioSourceOptions.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .clickable { viewModel.updateAudioSource(option.key) }
                    .padding(vertical = 10.dp)
                    .testTag("audioSource_${option.key}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = uiState.audioSource == option.key,
                    onClick = { viewModel.updateAudioSource(option.key) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (uiState.audioSource == option.key) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = option.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Microphone Source ---
        SectionHeader("Microphone Source")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Choose which microphone is used for voice assistant " +
                    "and phone calls during Android Auto.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        micSourceOptions.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .clickable { viewModel.updateMicSource(option.key) }
                    .padding(vertical = 10.dp)
                    .testTag("micSource_${option.key}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = uiState.micSource == option.key,
                    onClick = { viewModel.updateMicSource(option.key) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (uiState.micSource == option.key) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = option.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Call Quality ---
        SectionHeader("Call Quality")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Audio quality for phone calls via Android Auto.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        callQualityOptions.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .clickable { viewModel.updateCallQuality(option.key) }
                    .padding(vertical = 10.dp)
                    .testTag("callQuality_${option.key}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = uiState.callQuality == option.key,
                    onClick = { viewModel.updateCallQuality(option.key) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (uiState.callQuality == option.key) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = option.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class MicSourceOption(
    val key: String,
    val label: String,
    val description: String,
)

private val micSourceOptions = listOf(
    MicSourceOption(
        "car",
        "Car Microphone",
        "Uses the head unit's built-in microphone. Best for in-car voice control."
    ),
    MicSourceOption(
        "phone",
        "Phone Microphone",
        "Uses the phone's microphone via Bluetooth. Useful if the car mic is poor quality."
    ),
)

private data class AudioSourceOption(
    val key: String,
    val label: String,
    val description: String,
)

private val audioSourceOptions = listOf(
    AudioSourceOption(
        "bridge",
        "Bridge (TCP)",
        "Audio streams over TCP from the bridge. Recommended for OpenAutoLink."
    ),
    AudioSourceOption(
        "bluetooth",
        "Bluetooth",
        "Audio via Bluetooth A2DP. Use if the bridge doesn't handle audio."
    ),
)

private data class CallQualityOption(
    val key: String,
    val label: String,
    val description: String,
)

private val callQualityOptions = listOf(
    CallQualityOption("normal", "Normal", "Standard call audio quality."),
    CallQualityOption("clear", "Clear", "Enhanced clarity for calls."),
    CallQualityOption("hd", "HD", "Highest quality. Default."),
)

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.5f)
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

@Composable
private fun BridgeTab(viewModel: SettingsViewModel, uiState: SettingsUiState, sessionState: SessionState = SessionState.IDLE) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // --- Bridge Auto-Update (first, most important) ---
        SectionHeader("Bridge Updates")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Automatically update the bridge binary from GitHub Releases. " +
                    "Disable this if you build the bridge locally for development.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = uiState.bridgeAutoUpdate,
                onCheckedChange = { viewModel.updateBridgeAutoUpdate(it) },
                modifier = Modifier.testTag("bridgeAutoUpdateToggle"),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "Auto-update bridge binary",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (uiState.bridgeAutoUpdate) "Enabled — checks GitHub on connect"
                           else "Disabled — using local/manual builds",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Auto-apply toggle (only shown when auto-update is on)
        if (uiState.bridgeAutoUpdate) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = uiState.bridgeAutoApply,
                    onCheckedChange = { viewModel.updateBridgeAutoApply(it) },
                    modifier = Modifier.testTag("bridgeAutoApplyToggle"),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Apply automatically",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (uiState.bridgeAutoApply)
                            "Applies immediately — brief 5s reconnect"
                        else
                            "Waits for phone disconnect. Or disconnect phone and press Check for Update.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Version info
        val updateState by viewModel.bridgeUpdateState.collectAsStateWithLifecycle()
        val updateMessage by viewModel.bridgeUpdateMessage.collectAsStateWithLifecycle()
        val bridgeVersion by viewModel.bridgeVersion.collectAsStateWithLifecycle()
        val latestVersion by viewModel.latestVersion.collectAsStateWithLifecycle()
        val lastCheckTime by viewModel.lastCheckTime.collectAsStateWithLifecycle()
        val updateHistory by viewModel.updateHistory.collectAsStateWithLifecycle()

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(0.7f),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "Bridge version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = bridgeVersion ?: "Not connected",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column {
                Text(
                    text = "Latest release",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = latestVersion ?: "Unknown",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (latestVersion != null && bridgeVersion != null && latestVersion != bridgeVersion)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            Column {
                Text(
                    text = "Last checked",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = lastCheckTime?.let { ts ->
                        val ago = (System.currentTimeMillis() - ts) / 1000
                        when {
                            ago < 60 -> "Just now"
                            ago < 3600 -> "${ago / 60}m ago"
                            ago < 86400 -> "${ago / 3600}h ago"
                            else -> "${ago / 86400}d ago"
                        }
                    } ?: "Never",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        // Status message
        if (updateMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = updateMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = when (updateState) {
                    com.openautolink.app.transport.BridgeUpdateState.FAILED ->
                        MaterialTheme.colorScheme.error
                    com.openautolink.app.transport.BridgeUpdateState.UP_TO_DATE,
                    com.openautolink.app.transport.BridgeUpdateState.APPLIED,
                    com.openautolink.app.transport.BridgeUpdateState.DEFERRED ->
                        MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (updateState == com.openautolink.app.transport.BridgeUpdateState.TRANSFERRING ||
            updateState == com.openautolink.app.transport.BridgeUpdateState.CHECKING ||
            updateState == com.openautolink.app.transport.BridgeUpdateState.OFFERING) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(0.5f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        FilledTonalButton(
            onClick = { viewModel.checkForBridgeUpdate() },
            enabled = updateState != com.openautolink.app.transport.BridgeUpdateState.TRANSFERRING &&
                      updateState != com.openautolink.app.transport.BridgeUpdateState.APPLYING,
            modifier = Modifier.testTag("checkBridgeUpdateButton"),
        ) {
            Text("Check for Update")
        }

        // Update history
        if (updateHistory.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Update History",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            updateHistory.take(10).forEach { entry ->
                val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(entry.timestamp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(70.dp),
                    )
                    Text(
                        text = entry.event,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Phone Connection ---
        SectionHeader("Phone Connection")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "How the phone connects to the bridge.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        listOf(
            "wireless" to "Wireless" to "Phone connects via WiFi (needs WiFi module on SBC).",
            "usb" to "USB Wired" to "Phone plugs into SBC USB host port.",
        ).forEach { (pair, desc) ->
            val (key, label) = pair
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .clickable { viewModel.updatePhoneMode(key) }
                    .padding(vertical = 10.dp)
                    .testTag("phoneMode_$key"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = uiState.phoneMode == key,
                    onClick = { viewModel.updatePhoneMode(key) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (uiState.phoneMode == key) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // --- Wireless Settings (only when wireless mode) ---
        if (uiState.phoneMode == "wireless") {
            Spacer(modifier = Modifier.height(24.dp))

            HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader("Wireless Settings")

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "WiFi hotspot configuration on the bridge. " +
                        "The phone connects to this network for Android Auto.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // WiFi Band
            Text(
                text = "WiFi Band",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            listOf("5ghz" to "5 GHz (Lower latency)", "24ghz" to "2.4 GHz (Better range)").forEach { (key, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .clickable { viewModel.updateWifiBand(key) }
                        .padding(vertical = 6.dp)
                        .testTag("wifiBand_$key"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = uiState.wifiBand == key,
                        onClick = { viewModel.updateWifiBand(key) }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (uiState.wifiBand == key) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Country Code
            var countryInput by remember(uiState.wifiCountry) {
                mutableStateOf(uiState.wifiCountry)
            }
            OutlinedTextField(
                value = countryInput,
                onValueChange = {
                    val filtered = it.uppercase().take(2)
                    countryInput = filtered
                    if (filtered.length == 2) viewModel.updateWifiCountry(filtered)
                },
                label = { Text("Country Code") },
                placeholder = { Text("US") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .testTag("wifiCountryInput"),
            )

            Text(
                text = "2-letter country code for WiFi regulatory domain (e.g., US, GB, DE).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // WiFi SSID
            var ssidInput by remember(uiState.wifiSsid) {
                mutableStateOf(uiState.wifiSsid)
            }
            OutlinedTextField(
                value = ssidInput,
                onValueChange = {
                    ssidInput = it
                    viewModel.updateWifiSsid(it)
                },
                label = { Text("WiFi Name (SSID)") },
                placeholder = { Text("Auto-generated from MAC if empty") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .testTag("wifiSsidInput"),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // WiFi Password
            var passwordInput by remember(uiState.wifiPassword) {
                mutableStateOf(uiState.wifiPassword)
            }
            var passwordVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = passwordInput,
                onValueChange = {
                    passwordInput = it
                    viewModel.updateWifiPassword(it)
                },
                label = { Text("WiFi Password") },
                placeholder = { Text("Auto-generated if empty") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    FilledTonalIconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide" else "Show",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .testTag("wifiPasswordInput"),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Identity ---
        SectionHeader("Identity")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "How the bridge identifies itself during Android Auto pairing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        var headUnitInput by remember(uiState.headUnitName) {
            mutableStateOf(uiState.headUnitName)
        }
        OutlinedTextField(
            value = headUnitInput,
            onValueChange = {
                headUnitInput = it
                viewModel.updateHeadUnitName(it)
            },
            label = { Text("Head Unit Name") },
            placeholder = { Text("OpenAutoLink") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .testTag("headUnitNameInput"),
        )

        Text(
            text = "Display name shown to the phone during Android Auto pairing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        var btMacInput by remember(uiState.btMac) {
            mutableStateOf(uiState.btMac)
        }
        OutlinedTextField(
            value = btMacInput,
            onValueChange = {
                btMacInput = it.uppercase()
                viewModel.updateBtMac(it.uppercase())
            },
            label = { Text("Bluetooth MAC Override") },
            placeholder = { Text("XX:XX:XX:XX:XX:XX (empty = auto-detect)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .testTag("btMacInput"),
        )

        Text(
            text = "Manual Bluetooth MAC address override. Leave empty for auto-detection. " +
                    "Set this if auto-detect fails on your SBC.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.5f))

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Bridge settings are sent to the SBC automatically. Press below to restart the bridge " +
                    "and force the phone to renegotiate (required after codec, resolution, or WiFi changes).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        val bridgeConnected = sessionState != SessionState.IDLE &&
                sessionState != SessionState.ERROR &&
                sessionState != SessionState.CONNECTING
        FilledTonalButton(
            onClick = { viewModel.saveAndRestart(restartWireless = false, restartBluetooth = true) },
            enabled = bridgeConnected,
            modifier = Modifier.testTag("fullRestartButton"),
        ) {
            Text("Restart Bridge Services")
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader("Remote Diagnostics")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Send structured logs and telemetry to the bridge over the control channel. " +
                    "View in real time via SSH: journalctl -u openautolink.service -f | grep '[CAR]'",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        // Enable/disable toggle
        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Enable Remote Diagnostics",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (uiState.remoteDiagnosticsEnabled)
                        "Logs and telemetry are being sent to the bridge"
                    else
                        "No diagnostic data is sent to the bridge",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.remoteDiagnosticsEnabled,
                onCheckedChange = { enabled ->
                    viewModel.updateRemoteDiagnosticsEnabled(enabled)
                },
                modifier = Modifier.testTag("remoteDiagnosticsToggle"),
            )
        }

        // Log level selector — only visible when enabled
        if (uiState.remoteDiagnosticsEnabled) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Minimum Log Level",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Only log events at or above this level are sent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            logLevelOptions.forEach { level ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .clickable { viewModel.updateRemoteDiagnosticsMinLevel(level) }
                        .padding(vertical = 6.dp)
                        .testTag("logLevel_$level"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = uiState.remoteDiagnosticsMinLevel == level,
                        onClick = { viewModel.updateRemoteDiagnosticsMinLevel(level) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = level,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (uiState.remoteDiagnosticsMinLevel == level)
                            FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Telemetry snapshots (video/audio/session/cluster stats) are sent every 5 seconds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider()

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
    }
}

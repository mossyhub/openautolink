package com.openautolink.app.ui.settings

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
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VideoSettings
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class SettingsTab(
    val title: String,
    val icon: ImageVector,
) {
    CONNECTION("Connection", Icons.Default.Router),
    DISPLAY("Display", Icons.Default.DisplaySettings),
    VIDEO("Video", Icons.Default.VideoSettings),
    AUDIO("Audio", Icons.Default.Mic),
    UPDATES("Updates", Icons.Default.SystemUpdate),
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
        "Status bar and nav bar always visible. Recommended for GM."
    ),
    DisplayModeOption(
        "status_bar_hidden",
        "Status Bar Hidden",
        "Hides status bar only. Nav bar stays visible."
    ),
    DisplayModeOption(
        "nav_bar_hidden",
        "Nav Bar Hidden",
        "Hides nav bar/dock only. Status bar stays visible."
    ),
    DisplayModeOption(
        "fullscreen_immersive",
        "Fullscreen Immersive",
        "Hides all system bars. Swipe edge to reveal."
    ),
)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onBack: () -> Unit = {},
    onNavigateToDiagnostics: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val updateStatus by viewModel.updateStatus.collectAsStateWithLifecycle()
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
                                if (tab == SettingsTab.DIAGNOSTICS) {
                                    onNavigateToDiagnostics()
                                } else {
                                    selectedTab = tab
                                }
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
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                when (selectedTab) {
                    SettingsTab.CONNECTION -> ConnectionTab(viewModel, uiState)
                    SettingsTab.DISPLAY -> DisplayTab(viewModel, uiState)
                    SettingsTab.VIDEO -> VideoTab(uiState)
                    SettingsTab.AUDIO -> AudioTab(viewModel, uiState)
                    SettingsTab.UPDATES -> UpdatesTab(viewModel, uiState, updateStatus)
                    SettingsTab.DIAGNOSTICS -> {} // Navigated away
                }
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
            text = "Select the USB Ethernet adapter used to reach the bridge. " +
                    "The selected interface is saved for automatic reconnection.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (networkInterfaces.isEmpty()) {
            Text(
                text = "No Ethernet adapters detected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            FilledTonalButton(
                onClick = { viewModel.scanNetworkInterfaces() },
                modifier = Modifier.testTag("rescanInterfacesButton"),
            ) {
                Icon(
                    imageVector = Icons.Default.Usb,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Rescan")
            }
        } else {
            var dropdownExpanded by remember { mutableStateOf(false) }
            val selectedIface = networkInterfaces.find { it.name == uiState.networkInterface }
            // Auto-select the first/only interface if none saved yet
            val effectiveSelection = selectedIface ?: networkInterfaces.firstOrNull()

            LaunchedEffect(effectiveSelection, uiState.networkInterface) {
                if (uiState.networkInterface.isBlank() && effectiveSelection != null) {
                    viewModel.selectNetworkInterface(effectiveSelection.name)
                }
            }

            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = it },
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .testTag("networkInterfaceDropdown"),
            ) {
                OutlinedTextField(
                    value = effectiveSelection?.let {
                        "${it.name} — ${it.ipAddress ?: "no IP"}"
                    } ?: "Select interface",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Ethernet Adapter") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                    modifier = Modifier
                        .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )

                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                ) {
                    networkInterfaces.forEach { iface ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = iface.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "MAC: ${iface.macAddress}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "IP: ${iface.ipAddress ?: "not assigned"}",
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

            // Show details of the currently selected interface
            if (effectiveSelection != null) {
                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(0.5f),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingRow("Interface", effectiveSelection.name)
                        SettingRow("MAC", effectiveSelection.macAddress)
                        SettingRow("IP", effectiveSelection.ipAddress ?: "not assigned")
                        SettingRow("Status", if (effectiveSelection.isUp) "Up" else "Down")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = { viewModel.scanNetworkInterfaces() },
                    modifier = Modifier.testTag("rescanInterfacesButton"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Rescan")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.5f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Bridge Connection Section ---
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
                    "Default: 192.168.0.100:5288",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.5f))

        Spacer(modifier = Modifier.height(24.dp))

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
private fun DisplayTab(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader("Display Mode")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Controls how the app interacts with AAOS system bars. " +
                    "App restart required after changing.",
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
    }
}

@Composable
private fun VideoTab(uiState: SettingsUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader("Video Settings")

        Spacer(modifier = Modifier.height(8.dp))

        SettingRow("Codec", uiState.videoCodec.uppercase())
        SettingRow("Target FPS", uiState.videoFps.toString())

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Codec and FPS selection will be configurable " +
                    "once bridge config sync is implemented.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AudioTab(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
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
private fun UpdatesTab(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
    updateStatus: UpdateStatus,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader("Self-Update")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Check for app updates from a GitHub Pages manifest. " +
                "The app downloads and installs updates directly, bypassing the Play Store.",
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
                    text = "Enable Self-Update",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (uiState.selfUpdateEnabled == "on")
                        "App will check for updates from the configured URL"
                    else
                        "Updates only via Play Store / AAB install",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.selfUpdateEnabled == "on",
                onCheckedChange = { enabled ->
                    viewModel.updateSelfUpdateEnabled(if (enabled) "on" else "off")
                },
                modifier = Modifier.testTag("selfUpdateToggle"),
            )
        }

        // URL field — only visible when enabled
        if (uiState.selfUpdateEnabled == "on") {
            Spacer(modifier = Modifier.height(12.dp))

            var urlInput by remember(uiState.updateManifestUrl) {
                mutableStateOf(uiState.updateManifestUrl)
            }
            OutlinedTextField(
                value = urlInput,
                onValueChange = {
                    urlInput = it
                    viewModel.updateManifestUrl(it)
                },
                label = { Text("Update Manifest URL") },
                placeholder = { Text("https://user.github.io/repo/update.json") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .testTag("updateManifestUrlInput"),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "HTTPS URL to a JSON manifest with version info and APK download link.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

            Spacer(modifier = Modifier.height(16.dp))

            // Check for updates button + status
            Row(
                modifier = Modifier.fillMaxWidth(0.7f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { viewModel.checkForUpdate(uiState.updateManifestUrl) },
                    enabled = updateStatus !is UpdateStatus.Checking &&
                        updateStatus !is UpdateStatus.Downloading &&
                        uiState.updateManifestUrl.isNotBlank(),
                    modifier = Modifier.testTag("checkForUpdateButton"),
                ) {
                    Text("Check Now")
                }

                when (updateStatus) {
                    is UpdateStatus.Checking -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            "Checking...",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    else -> {}
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status display
            when (updateStatus) {
                is UpdateStatus.UpdateAvailable -> {
                    val manifest = updateStatus.manifest
                    Surface(
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(0.7f),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Update Available: v${manifest.latestVersionName}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (manifest.changelog.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = manifest.changelog,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            if (!viewModel.canInstallPackages()) {
                                Text(
                                    text = "This device does not allow installing apps from this source. " +
                                        "Enable \"Install unknown apps\" for OpenAutoLink in system settings.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }

                            Button(
                                onClick = { viewModel.downloadAndInstall(manifest.apkUrl) },
                                modifier = Modifier.testTag("downloadAndInstallButton"),
                            ) {
                                Text("Download & Install")
                            }
                        }
                    }
                }
                is UpdateStatus.UpToDate -> {
                    Text(
                        text = "You're on the latest version.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is UpdateStatus.Downloading -> {
                    Column(modifier = Modifier.fillMaxWidth(0.7f)) {
                        Text(
                            text = "Downloading update... ${(updateStatus.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { updateStatus.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                is UpdateStatus.Installing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            text = "Preparing install...",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is UpdateStatus.Error -> {
                    Surface(
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(0.7f),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = updateStatus.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(onClick = { viewModel.dismissUpdateStatus() }) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
                is UpdateStatus.Idle, is UpdateStatus.Checking -> {}
            }
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

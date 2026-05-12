package com.openautolink.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.openautolink.app.ui.components.LocalEchoTextField
import androidx.compose.material3.FilterChip

private enum class SettingsTab(
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
) {
    val uiState by viewModel.settingsState.collectAsStateWithLifecycle()
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
                    onSaveAndConnect = onSaveAndConnect,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Tab content
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        SettingsTab.CONNECTION -> ConnectionTab(viewModel, uiState)
                        SettingsTab.DISPLAY -> DisplayTab(
                            viewModel, uiState,
                            onNavigateToSafeAreaEditor,
                        )
                        SettingsTab.VIDEO -> VideoTab(viewModel, uiState)
                        SettingsTab.AUDIO -> AudioTab(viewModel, uiState)
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

@Composable
private fun ConnectionStatusBar(
    sessionState: SessionState,
    onSaveAndConnect: () -> Unit,
) {
    val statusColor = when (sessionState) {
        SessionState.STREAMING -> Color(0xFF4CAF50) // Green
        SessionState.CONNECTED -> Color(0xFFFF9800) // Orange
        SessionState.CONNECTING -> Color(0xFFFFC107) // Amber
        SessionState.IDLE -> Color(0xFF9E9E9E) // Grey
        SessionState.ERROR -> Color(0xFFF44336) // Red
    }
    val statusText = when (sessionState) {
        SessionState.STREAMING -> "Streaming"
        SessionState.CONNECTED -> "Connected"
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
        Button(
            onClick = onSaveAndConnect,
            modifier = Modifier.testTag("saveAndConnectButton"),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save & Reconnect")
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // --- Connection Mode (phone-hotspot vs car-hotspot) ---
        val connectionMode by viewModel.connectionMode.collectAsStateWithLifecycle()
        val knownPhones by viewModel.knownPhones.collectAsStateWithLifecycle()
        val defaultPhoneId by viewModel.defaultPhoneId.collectAsStateWithLifecycle()

        // --- Transport (WiFi vs USB) ---
        SectionHeader("Transport")
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "hotspot" to "Wi-Fi",
                "usb" to "USB",
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = uiState.directTransport == mode,
                    onClick = { viewModel.updateDirectTransport(mode) },
                    label = { Text(label) },
                )
            }
        }
        Text(
            text = when (uiState.directTransport) {
                "usb" -> "Phone connects to the car over a USB cable using AOA v2. The Wi-Fi connection mode below is ignored. A device picker is shown on the projection screen so the OS only prompts for the phone you select."
                else -> "Phone and car talk over the shared Wi-Fi network configured below."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))

        SectionHeader("Connection Mode")
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                AppPreferences.CONNECTION_MODE_CAR_HOTSPOT to "Car Hotspot",
                AppPreferences.CONNECTION_MODE_PHONE_HOTSPOT to "Phone Hotspot",
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = connectionMode == mode,
                    onClick = { viewModel.updateConnectionMode(mode) },
                    label = { Text(label) },
                )
            }
        }
        Text(
            text = when (connectionMode) {
                AppPreferences.CONNECTION_MODE_PHONE_HOTSPOT ->
                    "Phone is the WiFi access point; the car connects to it. Optimized for one phone."
                AppPreferences.CONNECTION_MODE_CAR_HOTSPOT ->
                    "Car's hotspot is the WiFi network; phones connect to it. Multiple phones can be online at once and the floating switcher button picks the active one."
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // Known phones list — only relevant in Car Hotspot mode.
        if (connectionMode == AppPreferences.CONNECTION_MODE_CAR_HOTSPOT) {
            val alwaysAsk by viewModel.alwaysAskPhone.collectAsStateWithLifecycle()
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Always ask which phone to use", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "When on, the chooser appears on every connect even if a default phone is set. Useful for shared cars.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = alwaysAsk,
                    onCheckedChange = { viewModel.setAlwaysAskPhone(it) },
                )
            }

            // Network interface selector — auto by default, manual override
            // for users on hardware where auto-detection picks the wrong NIC.
            val autoIface by viewModel.carHotspotAutoInterface.collectAsStateWithLifecycle()
            val manualIfaceName by viewModel.carHotspotInterfaceName.collectAsStateWithLifecycle()
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-detect network interface", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "When on, the car app picks the right network interface automatically (works for most GM head units). Turn off to choose one yourself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = autoIface,
                    onCheckedChange = { viewModel.setCarHotspotAutoInterface(it) },
                )
            }

            if (!autoIface) {
                // Snapshot the live interface list when the dropdown opens so
                // we don't enumerate every recomposition. Pulled from the
                // singleton PhoneDiscovery via the ViewModel.
                var ifaceMenuOpen by remember { mutableStateOf(false) }
                var interfaces by remember { mutableStateOf(viewModel.listCarHotspotInterfaces()) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Network interface", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (manualIfaceName.isBlank()) "(none selected)" else manualIfaceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        FilledTonalButton(
                            onClick = {
                                interfaces = viewModel.listCarHotspotInterfaces()
                                ifaceMenuOpen = true
                            },
                        ) {
                            Text("Choose")
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = ifaceMenuOpen,
                            onDismissRequest = { ifaceMenuOpen = false },
                        ) {
                            if (interfaces.isEmpty()) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("No real interfaces found") },
                                    onClick = { ifaceMenuOpen = false },
                                    enabled = false,
                                )
                            } else {
                                interfaces.forEach { (name, ip) ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("$name  ($ip)") },
                                        onClick = {
                                            viewModel.setCarHotspotInterfaceName(name)
                                            ifaceMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(modifier = Modifier.fillMaxWidth(0.5f))
            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader("Known Phones")
            Spacer(modifier = Modifier.height(4.dp))
            if (knownPhones.isEmpty()) {
                Text(
                    "No phones connected yet. The first phone that connects will be added here automatically and can be set as the default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    knownPhones.forEach { kp ->
                        val isDefault = kp.phoneId == defaultPhoneId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isDefault) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        kp.friendlyName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if (isDefault) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "DEFAULT",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                Text(
                                    "id ${kp.phoneId.take(8)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!isDefault) {
                                androidx.compose.material3.TextButton(
                                    onClick = { viewModel.setDefaultPhoneId(kp.phoneId) },
                                ) {
                                    Text("Set Default", fontSize = 12.sp)
                                }
                            }
                            androidx.compose.material3.TextButton(
                                onClick = { viewModel.forgetKnownPhone(kp.phoneId) },
                            ) {
                                Text("Forget", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.5f))
        Spacer(modifier = Modifier.height(16.dp))

        // --- Manual IP (emulator/testing) ---
        SectionHeader("Manual IP Address")
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = uiState.manualIpEnabled,
                onCheckedChange = { viewModel.updateManualIpEnabled(it) },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Specify companion IP manually",
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Text(
            text = "For testing environments only (e.g., AAOS emulator on a PC). " +
                    "In normal use, the car and phone find each other automatically via discovery.",
            style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (uiState.manualIpEnabled) {
                Spacer(modifier = Modifier.height(8.dp))

                val isValidIp = remember(uiState.manualIpAddress) {
                    uiState.manualIpAddress.isBlank() || isValidIpv4(uiState.manualIpAddress)
                }

                LocalEchoTextField(
                    value = uiState.manualIpAddress,
                    onValueChange = { filtered ->
                        viewModel.updateManualIpAddress(filtered)
                    },
                    filter = { input -> input.filter { it.isDigit() || it == '.' } },
                    label = { Text("Phone IP Address") },
                    placeholder = { Text("e.g. 192.168.1.100") },
                    singleLine = true,
                    isError = !isValidIp,
                    supportingText = if (!isValidIp) {
                        { Text("Enter a valid IPv4 address (e.g. 192.168.1.100)") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .padding(horizontal = 16.dp),
                )
                Text(
                    text = "The IP address of the phone running the companion app. " +
                            "Requires Save & Reconnect to take effect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun DisplayTab(
    viewModel: SettingsViewModel,
    uiState: SettingsUiState,
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

        // --- Drive Side ---
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
        } // Column (Drive Side)

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
            text = "Customize floating overlay buttons during streaming.",
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

        // Hide flags
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
private fun VideoTab(viewModel: SettingsViewModel, uiState: SettingsUiState) {
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
                    "H.264 is the safe default — instant clean startup, capped at 1080p. " +
                    "H.265 supports up to 4K but may show a brief green/distorted picture " +
                    "for the first 30–60 seconds while the phone's encoder warms up.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        val isHighRes = uiState.aaResolution in listOf("1440p", "4k", "1440p_p", "4k_p")
        listOf(
            Triple("h264", "H.264", " (Recommended)"),
            Triple("h265", "H.265 / HEVC", if (isHighRes) " — needed for 1440p/4K" else " — green-startup risk"),
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
            // Portrait variants — AA enum has 9:16 mirrors of 720p+ for tall
            // panels. No 480p portrait in the spec.
            Triple("720p_p", "720p portrait (720×1280)", false),
            Triple("1080p_p", "1080p portrait (1080×1920)", false),
            Triple("1440p_p", "1440p portrait (1440×2560)", true),
            Triple("4k_p", "4K portrait (2160×3840)", true),
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

        // --- Layout Width Target (auto-negotiate only) ---
        if (uiState.videoAutoNegotiate) {
            Text(
                text = "Layout Width Target — scales DPI per resolution tier so AA always " +
                        "sees the same dp width regardless of which tier the phone picks. " +
                        "Controls card/search results sizing. 0 = off (use DPI slider below).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
            Text(
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

            Text(
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
                    .fillMaxWidth(0.7f)
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
                .fillMaxWidth(0.7f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto DPI",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
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
            modifier = Modifier.fillMaxWidth(0.7f)
        )

        // Quick presets — laid out as chips, not aligned to slider
        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Video Scaling Mode ---
        SectionHeader("Video Scaling")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "How the video fits your screen. " +
                    "Letterbox shows the full frame with black bars on the sides. " +
                    "Crop fills the screen but cuts off top/bottom. " +
                    "Requires reconnect (Save & Reconnect).",
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

        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Auto-compute margins",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.width(220.dp),
            )
            Switch(
                checked = uiState.aaAutoMargins,
                onCheckedChange = { viewModel.updateAaAutoMargins(it) },
                modifier = Modifier.testTag("aaAutoMargins"),
            )
        }

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
        Text(
            text = "Sent in VideoConfiguration. Viewing distance = mm from driver eye " +
                    "to display (GM uses ~700 for centre IP screens; tune per vehicle). " +
                    "Decoder depth = extra reordered frames the head unit can buffer " +
                    "(GM uses 1; raise only if you see B-frame stalls). 0 = omit field.",
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
            Text(
                text = "Viewing Distance",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.width(140.dp),
            )
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

        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Decoder Add'l Depth",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.width(140.dp),
            )
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

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Pixel Aspect Ratio ---
        SectionHeader("Pixel Aspect Ratio")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
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
                    .fillMaxWidth(0.7f)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Value (×10⁴)",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(180.dp),
                )
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
            Text(
                text = "10000 = 1:1 (no compensation). " +
                        "Blazer EV 2914×1134 at 1080p ≈ 14454. " +
                        "Higher = wider pixel compensation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Changes to video settings require an AA session reconnect (Save & Reconnect).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                    "Requires Save & Reconnect.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Last detected key (diagnostic)
        if (lastDetectedKey != null) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(0.7f).padding(bottom = 12.dp),
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
                    .fillMaxWidth(0.7f)
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
            HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))
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

        // Hook the dialog's own Window so steering-wheel keys delivered to
        // the dialog window get routed into KeyCaptureBus.
        val dialogView = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(target, dialogView) {
            var node: android.view.ViewParent? = dialogView.parent
            var provider: androidx.compose.ui.window.DialogWindowProvider? = null
            while (node != null) {
                if (node is androidx.compose.ui.window.DialogWindowProvider) {
                    provider = node
                    break
                }
                node = (node as? android.view.View)?.parent
            }
            val window = provider?.window
            val original = window?.callback
            com.openautolink.app.diagnostics.OalLog.i(
                "KeyRemapDialog",
                "Window hook setup: providerFound=${provider != null} " +
                    "windowFound=${window != null} originalCallbackPresent=${original != null}",
            )
            if (window != null && original != null) {
                window.callback = object : android.view.Window.Callback by original {
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
            }
            onDispose {
                if (window != null && original != null) window.callback = original
            }
        }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { captureTarget = null; lastDetectedKey = null },
            title = { Text("Assign key to: ${target.label}") },
            text = {
                Column {
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
                Button(onClick = { captureTarget = null }) {
                    Text("Cancel")
                }
            },
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

        // --- Phone Call Routing ---
        SectionHeader("Phone Calls")

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Route calls through car",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Stream in-call audio over Android Auto so the car " +
                        "mic and speakers are used even when Bluetooth Call " +
                        "audio is disabled on the phone. Requires Save & Reconnect. " +
                        "Disable if calls fail to connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.callAudioViaCar,
                onCheckedChange = { viewModel.updateCallAudioViaCar(it) },
                modifier = Modifier.testTag("callAudioViaCarToggle"),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        HorizontalDivider(modifier = Modifier.fillMaxWidth(0.7f))

        Spacer(modifier = Modifier.height(24.dp))

        // --- Per-Purpose Volume Offsets ---
        SectionHeader("Volume Offsets")

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Adjust relative volume for each audio purpose. " +
                    "0 = default. Positive = louder, negative = quieter. " +
                    "Requires Save & Reconnect.",
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
    Column(modifier = Modifier.fillMaxWidth(0.7f).padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
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
            modifier = Modifier.fillMaxWidth(0.7f).padding(vertical = 8.dp),
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
            modifier = Modifier.fillMaxWidth(0.7f).padding(vertical = 8.dp),
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
            modifier = Modifier.fillMaxWidth(0.7f).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Include Full Logcat", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Also capture the full Android logcat for our app's process " +
                        "(native C++/JNI, AOSP framework, MediaCodec, AudioTrack, " +
                        "Binder, Surface). Saves to logcat_<timestamp>.log. " +
                        "AAOS does not allow apps to read system-wide logs without " +
                        "root, so other apps and PowerManager events are not " +
                        "included. Significantly larger files — only enable when " +
                        "actively troubleshooting.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = uiState.logcatCaptureEnabled,
                onCheckedChange = { viewModel.updateLogcatCaptureEnabled(it) },
                modifier = Modifier.testTag("logcatCaptureToggle"),
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

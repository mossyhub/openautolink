package com.openautolink.app.ui.diagnostics

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Abc
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openautolink.app.ui.diagnostics.carplay.CarPlayReconScreen

private enum class DiagnosticsTab(
    val title: String,
    val icon: ImageVector,
) {
    SYSTEM("System", Icons.Default.Info),
    NETWORK("Network", Icons.Default.NetworkCheck),
    CAR("Car", Icons.Default.DirectionsCar),
    DEBUG("Debug", Icons.Default.BugReport),
    LOGS("Logs", Icons.Default.Terminal),
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel = viewModel(),
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(DiagnosticsTab.SYSTEM) }
    var showReconScreen by remember { mutableStateOf(false) }

    // If recon screen is active, render it instead
    if (showReconScreen) {
        CarPlayReconScreen(onBack = { showReconScreen = false })
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag("diagnosticsScreen"),
        ) {
            // Left rail — back button + tab icons
            Column(modifier = Modifier.fillMaxHeight()) {
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

                NavigationRail(
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    DiagnosticsTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title,
                                )
                            },
                            label = { Text(tab.title) },
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .then(
                                    if (tab == DiagnosticsTab.DEBUG) {
                                        Modifier.combinedClickable(
                                            onClick = { selectedTab = tab },
                                            onLongClick = { showReconScreen = true },
                                        )
                                    } else {
                                        Modifier
                                    }
                                ),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            // Content pane
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.TopStart,
            ) {
              Box(modifier = Modifier.widthIn(max = 720.dp)) {
                when (selectedTab) {
                    DiagnosticsTab.SYSTEM -> SystemTab(uiState.system)
                    DiagnosticsTab.NETWORK -> NetworkTab(uiState.network, uiState.networkProbe, viewModel)
                    DiagnosticsTab.CAR -> CarTab(uiState.car)
                    DiagnosticsTab.DEBUG -> DebugTab(uiState.debugProbe, viewModel)
                    DiagnosticsTab.LOGS -> LogsTab(uiState.logs, uiState.logFilter, viewModel)
                }
              }
            }
        }
    }
}

// --- System Tab ---

@Composable
private fun SystemTab(info: SystemInfo) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader("Device")
        DiagRow("Android", "${info.androidVersion} (API ${info.sdkLevel})")
        DiagRow("Manufacturer", info.manufacturer)
        DiagRow("Model", info.model)
        DiagRow("Device", info.device)
        DiagRow("SoC", info.soc)

        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader("Display")
        DiagRow("Resolution", "${info.displayWidth} × ${info.displayHeight}")
        DiagRow("Density", "${info.displayDpi} dpi")

        // Show live inset values for debugging display mode behavior
        val diagView = LocalView.current
        val diagRootInsets = diagView.rootWindowInsets
        val diagSysBars = diagRootInsets?.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars()
        )
        val diagCutout = diagRootInsets?.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.displayCutout()
        )
        if (diagSysBars != null) {
            DiagRow("System Bars", "T:${diagSysBars.top} B:${diagSysBars.bottom} L:${diagSysBars.left} R:${diagSysBars.right}")
        }
        if (diagCutout != null && (diagCutout.top != 0 || diagCutout.bottom != 0 || diagCutout.left != 0 || diagCutout.right != 0)) {
            DiagRow("Display Cutout", "T:${diagCutout.top} B:${diagCutout.bottom} L:${diagCutout.left} R:${diagCutout.right}")
        }

        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader("H.264 Decoders")
        if (info.h264Decoders.isEmpty()) {
            DiagRow("", "None available")
        } else {
            info.h264Decoders.forEach { codec ->
                DiagRow(
                    codec.name,
                    if (codec.hwAccelerated) "HW" else "SW",
                    valueColor = if (codec.hwAccelerated) Color(0xFF4CAF50) else Color(0xFFFF9800),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader("H.265 Decoders")
        if (info.h265Decoders.isEmpty()) {
            DiagRow("", "None available")
        } else {
            info.h265Decoders.forEach { codec ->
                DiagRow(
                    codec.name,
                    if (codec.hwAccelerated) "HW" else "SW",
                    valueColor = if (codec.hwAccelerated) Color(0xFF4CAF50) else Color(0xFFFF9800),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader("VP9 Decoders")
        if (info.vp9Decoders.isEmpty()) {
            DiagRow("", "None available")
        } else {
            info.vp9Decoders.forEach { codec ->
                DiagRow(
                    codec.name,
                    if (codec.hwAccelerated) "HW" else "SW",
                    valueColor = if (codec.hwAccelerated) Color(0xFF4CAF50) else Color(0xFFFF9800),
                )
            }
        }
    }
}

// --- Network Tab ---

@Composable
private fun NetworkTab(info: NetworkInfo, probe: NetworkProbeState, viewModel: DiagnosticsViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionHeader("Session")
        DiagRow("Session", info.sessionState.name, valueColor = sessionStateColor(info.sessionState))

        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader("Network Probe")

        // Show all network interfaces
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Interfaces", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.width(100.dp))
            androidx.compose.material3.FilledTonalButton(
                onClick = { viewModel.refreshInterfaces() },
                modifier = Modifier.height(32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                Text("Refresh", fontSize = 12.sp)
            }
        }
        if (probe.interfaces.isEmpty()) {
            DiagRow("", "No interfaces found")
        } else {
            for (iface in probe.interfaces) {
                DiagRow("  ${iface.name}", iface.ip, valueColor = Color(0xFF4CAF50))
            }
        }

        // Ping test
        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader("Ping Test")
        var pingInput by remember { mutableStateOf(probe.pingTarget) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = pingInput,
                onValueChange = { pingInput = it; viewModel.setPingTarget(it) },
                label = { Text("Target IP", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f).height(56.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Color.White),
            )
            Spacer(modifier = Modifier.width(8.dp))
            androidx.compose.material3.FilledTonalButton(
                onClick = { viewModel.setPingTarget(pingInput); viewModel.runPing() },
                enabled = !probe.pingInProgress && pingInput.isNotBlank(),
                modifier = Modifier.height(40.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                Text(if (probe.pingInProgress) "..." else "Ping", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(4.dp))
            androidx.compose.material3.FilledTonalButton(
                onClick = { viewModel.setPingTarget(pingInput); viewModel.runTcpConnect(pingInput) },
                enabled = !probe.pingInProgress && pingInput.isNotBlank(),
                modifier = Modifier.height(40.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                Text(if (probe.pingInProgress) "..." else "TCP", fontSize = 12.sp)
            }
        }
        if (probe.pingResult != null) {
            val color = if (probe.pingResult.startsWith("✓")) Color(0xFF4CAF50) else Color(0xFFFF5722)
            DiagRow("Result", probe.pingResult, valueColor = color)
        }

        // TCP Listener
        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader("TCP Listener (port ${probe.tcpListenerPort})")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (probe.tcpListenerActive) "Listening..." else "Stopped",
                color = if (probe.tcpListenerActive) Color(0xFF4CAF50) else Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.FilledTonalButton(
                onClick = { viewModel.toggleTcpListener() },
                modifier = Modifier.height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                Text(
                    if (probe.tcpListenerActive) "Stop" else "Start Listener",
                    fontSize = 12.sp,
                )
            }
        }
        for (log in probe.tcpListenerLog) {
            val color = when {
                log.startsWith("✓") -> Color(0xFF4CAF50)
                log.startsWith("✗") -> Color(0xFFFF5722)
                else -> Color(0xFFB0BEC5)
            }
            Text(
                log,
                color = color,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
            )
        }

        // Local-Only Hotspot probe — tests if head unit can act as AP for phones
        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader("Local-Only Hotspot (Head-Unit-as-AP test)")
        Text(
            "Attempts to start a SoftAP on this device. If it works, phones can join the head unit's WiFi instead of the other way around. SSID may be randomized per session.",
            color = Color(0xFF808080),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (probe.localHotspotActive) "Active" else "Stopped",
                color = if (probe.localHotspotActive) Color(0xFF4CAF50) else Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.FilledTonalButton(
                onClick = { viewModel.toggleLocalHotspot() },
                modifier = Modifier.height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                Text(
                    if (probe.localHotspotActive) "Stop" else "Start Hotspot",
                    fontSize = 12.sp,
                )
            }
        }
        if (probe.localHotspotStatus.isNotEmpty()) {
            val color = when {
                probe.localHotspotStatus.startsWith("✓") -> Color(0xFF4CAF50)
                probe.localHotspotStatus.startsWith("✗") -> Color(0xFFFF5722)
                else -> Color(0xFFB0BEC5)
            }
            DiagRow("Status", probe.localHotspotStatus, valueColor = color)
        }
        probe.localHotspotSsid?.let { DiagRow("SSID", it, valueColor = Color(0xFF90CAF9)) }
        probe.localHotspotPassword?.let { DiagRow("Password", it, valueColor = Color(0xFF90CAF9)) }

        // WiFi Direct (P2P) probe — does Nearby's preferred medium work here?
        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader("WiFi Direct / P2P (Nearby preferred medium)")
        Text(
            "Tries to create a WiFi Direct group. This is the medium Nearby Connections prefers; if it fails here, that's why Nearby mode never created its own network.",
            color = Color(0xFF808080),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (probe.p2pActive) "Active" else "Stopped",
                color = if (probe.p2pActive) Color(0xFF4CAF50) else Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.FilledTonalButton(
                onClick = { viewModel.toggleP2pProbe() },
                modifier = Modifier.height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                Text(if (probe.p2pActive) "Stop" else "Create P2P Group", fontSize = 12.sp)
            }
        }
        probe.p2pSupported?.let {
            DiagRow("Supported", if (it) "yes" else "NO", valueColor = if (it) Color(0xFF4CAF50) else Color(0xFFFF5722))
        }
        if (probe.p2pStatus.isNotEmpty()) {
            val color = when {
                probe.p2pStatus.startsWith("✓") -> Color(0xFF4CAF50)
                probe.p2pStatus.startsWith("✗") -> Color(0xFFFF5722)
                else -> Color(0xFFB0BEC5)
            }
            DiagRow("Status", probe.p2pStatus, valueColor = color)
        }
        probe.p2pSsid?.let { DiagRow("SSID", it, valueColor = Color(0xFF90CAF9)) }
        probe.p2pPassphrase?.let { DiagRow("Passphrase", it, valueColor = Color(0xFF90CAF9)) }
        probe.p2pOwnerIp?.let { DiagRow("Owner IP", it, valueColor = Color(0xFF90CAF9)) }
        for (log in probe.p2pLog) {
            val color = when {
                log.startsWith("✓") -> Color(0xFF4CAF50)
                log.startsWith("✗") -> Color(0xFFFF5722)
                else -> Color(0xFFB0BEC5)
            }
            Text(
                log,
                color = color,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
            )
        }

        // Phone Discovery (Car Hotspot mode) — finds companion apps via mDNS + sweep
        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader("Phone Discovery (Car Hotspot mode)")
        Text(
            "Discovers companion apps on the current network. Runs mDNS (passive, preferred) and a TCP subnet sweep (active, fallback) in parallel. Each discovered phone shows its source so you can tell which mechanism worked.",
            color = Color(0xFF808080),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (probe.phoneDiscoveryActive) "Scanning…" else "Stopped",
                color = if (probe.phoneDiscoveryActive) Color(0xFF4CAF50) else Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            if (probe.phoneDiscoveryActive) {
                androidx.compose.material3.FilledTonalButton(
                    onClick = { viewModel.rescanPhones() },
                    enabled = !probe.phoneSweepActive,
                    modifier = Modifier.height(36.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                ) {
                    Text(if (probe.phoneSweepActive) "Sweeping…" else "Re-sweep", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = { viewModel.togglePhoneDiscovery() },
                modifier = Modifier.height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                Text(if (probe.phoneDiscoveryActive) "Stop" else "Scan for Phones", fontSize = 12.sp)
            }
        }
        if (probe.phoneSweepProgress.isNotEmpty()) {
            DiagRow("Sweep", probe.phoneSweepProgress, valueColor = Color(0xFF90CAF9))
        }
        if (probe.discoveredPhones.isEmpty()) {
            DiagRow("Phones", if (probe.phoneDiscoveryActive) "(searching…)" else "(none)")
        } else {
            for (phone in probe.discoveredPhones) {
                val sourceTag = when (phone.source) {
                    com.openautolink.app.transport.PhoneDiscovery.Source.MDNS -> "mDNS"
                    com.openautolink.app.transport.PhoneDiscovery.Source.SWEEP -> "sweep"
                    com.openautolink.app.transport.PhoneDiscovery.Source.BOTH -> "mDNS+sweep"
                }
                val sourceColor = when (phone.source) {
                    com.openautolink.app.transport.PhoneDiscovery.Source.MDNS -> Color(0xFF4CAF50)
                    com.openautolink.app.transport.PhoneDiscovery.Source.SWEEP -> Color(0xFFFFA726)
                    com.openautolink.app.transport.PhoneDiscovery.Source.BOTH -> Color(0xFF64B5F6)
                }
                val display = buildString {
                    append(phone.friendlyName ?: phone.serviceName)
                    phone.host?.let { append("  ").append(it).append(":").append(phone.port) }
                    phone.phoneId?.let { append("  id=").append(it.take(8)) }
                    if (!phone.isResolved) append("  (resolving…)")
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "[$sourceTag]",
                        color = sourceColor,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(96.dp).padding(horizontal = 4.dp),
                    )
                    Text(
                        display,
                        color = if (phone.isResolved) Color(0xFFE0E0E0) else Color(0xFFB0BEC5),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Port Scanner
        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader("Port Scanner")
        Text(
            "Scans localhost, interface IPs, gateways, and ping target for open ports",
            color = Color(0xFF808080),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                probe.portScanProgress.ifEmpty { "Ready" },
                color = if (probe.portScanRunning) Color(0xFF64B5F6) else Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.FilledTonalButton(
                onClick = {
                    if (probe.portScanRunning) viewModel.stopPortScan()
                    else viewModel.startPortScan()
                },
                modifier = Modifier.height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                Text(
                    if (probe.portScanRunning) "Stop" else "Scan Ports",
                    fontSize = 12.sp,
                )
            }
        }

        // Show open ports first, then closed
        val openPorts = probe.portScanResults.filter { it.open }
        if (openPorts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Open Ports (${openPorts.size})",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF4CAF50),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            for (entry in openPorts.sortedWith(compareBy({ it.host }, { it.port }))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                        .background(
                            color = Color(0xFF4CAF50).copy(alpha = 0.08f),
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${entry.host}:${entry.port}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.width(160.dp),
                    )
                    Text(
                        entry.label,
                        fontSize = 11.sp,
                        color = Color(0xFF90CAF9),
                        modifier = Modifier.weight(1f),
                    )
                    entry.latencyMs?.let {
                        Text(
                            "${it}ms",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF808080),
                            modifier = Modifier.width(40.dp),
                            textAlign = TextAlign.End,
                        )
                    }
                }
                if (entry.banner != null) {
                    Text(
                        "  banner: ${entry.banner}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFB0BEC5),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }

        // Summary of closed ports (don't list every one, just count per host)
        val closedPorts = probe.portScanResults.filter { !it.open }
        if (closedPorts.isNotEmpty() && !probe.portScanRunning) {
            Spacer(modifier = Modifier.height(8.dp))
            val closedByHost = closedPorts.groupBy { it.host }
            for ((host, entries) in closedByHost) {
                Text(
                    "  $host: ${entries.size} ports closed",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF606060),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

// --- Streaming Stats Tab (replaces Bridge Tab) ---
// Video/audio stats are now shown in the System tab or Logs.

// --- Debug Tab ---

@Composable
private fun DebugTab(debug: DebugProbeState, viewModel: DiagnosticsViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // ADB / Debug Port Scan
        SectionHeader("ADB Port Scan (localhost)")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (debug.portScanInProgress) "Scanning..." else "Scans ADB & debug ports on this device",
                color = if (debug.portScanInProgress) Color(0xFF64B5F6) else Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.FilledTonalButton(
                onClick = { viewModel.scanAdbPorts() },
                enabled = !debug.portScanInProgress,
                modifier = Modifier.height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                Text("Scan ADB Ports", fontSize = 12.sp)
            }
        }
        val openAdbPorts = debug.portScanResults.filter { it.open }
        val closedAdbPorts = debug.portScanResults.filter { !it.open }
        if (openAdbPorts.isNotEmpty()) {
            for (r in openAdbPorts) {
                DiagRow(
                    ":${r.port} ${r.label}",
                    "OPEN" + (r.banner?.let { " — $it" } ?: ""),
                    valueColor = Color(0xFF4CAF50),
                )
            }
        }
        if (closedAdbPorts.isNotEmpty() && !debug.portScanInProgress) {
            Text(
                "${closedAdbPorts.size} ports closed",
                fontSize = 11.sp,
                color = Color(0xFF606060),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        // Debug Properties
        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader("Debug Properties")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (debug.propsLoading) "Loading..." else "System properties related to ADB & debugging",
                color = if (debug.propsLoading) Color(0xFF64B5F6) else Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.FilledTonalButton(
                onClick = { viewModel.loadDebugProperties() },
                enabled = !debug.propsLoading,
                modifier = Modifier.height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                Text("Load Props", fontSize = 12.sp)
            }
        }
        debug.debugProps?.let { props ->
            DiagRow("ADB enabled", if (props.adbEnabled) "YES" else "NO",
                valueColor = if (props.adbEnabled) Color(0xFF4CAF50) else Color(0xFFFF5722))
            DiagRow("ADB TCP port", props.adbTcpPort?.toString() ?: "not set",
                valueColor = if (props.adbTcpPort != null) Color(0xFF4CAF50) else Color.Gray)
            DiagRow("USB debugging", if (props.usbDebugging) "YES" else "NO",
                valueColor = if (props.usbDebugging) Color(0xFF4CAF50) else Color(0xFFFF5722))
            DiagRow("ro.debuggable", if (props.debuggable) "1 (debug build)" else "0 (release)",
                valueColor = if (props.debuggable) Color(0xFF4CAF50) else Color.Gray)
            DiagRow("Secure ADB", if (props.secureAdb) "YES (auth required)" else "NO",
                valueColor = if (props.secureAdb) Color(0xFFFFC107) else Color.Gray)
            DiagRow("Build type", props.buildType)
            DiagRow("Fingerprint", props.buildFingerprint)

            if (props.allProps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "All debug-related properties:",
                    color = Color(0xFF808080),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                for ((key, value) in props.allProps) {
                    DiagRow(key, value)
                }
            }
        }

        // ADB WiFi status from Settings.Global
        if (debug.adbWifiStatus.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            DiagRow("Settings.Global", debug.adbWifiStatus)
        }

        // Device Info
        if (debug.deviceInfo.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            SectionHeader("Device Identity")
            for ((key, value) in debug.deviceInfo) {
                DiagRow(key, value)
            }
        }

        // Developer Settings Launcher
        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader("Developer Settings")
        Text(
            "Probe which settings activities exist and try to launch them",
            color = Color(0xFF808080),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            androidx.compose.material3.FilledTonalButton(
                onClick = { viewModel.probeAllDevSettingsIntents() },
                modifier = Modifier.height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                Text("Probe Intents", fontSize = 12.sp)
            }
        }
        val devIntents = com.openautolink.app.diagnostics.DeviceDebugProbe.getDeveloperSettingsIntents()
        for ((desc, intent) in devIntents) {
            val probeResult = debug.intentResults.firstOrNull { it.first == desc }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 1.dp)
                    .background(
                        color = Color.White.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(desc, fontSize = 12.sp, color = Color.White)
                    if (probeResult != null) {
                        Text(
                            if (probeResult.second) "✓ Available" else "✗ Not found",
                            fontSize = 10.sp,
                            color = if (probeResult.second) Color(0xFF4CAF50) else Color(0xFF808080),
                        )
                    }
                }
                if (probeResult?.second == true) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { viewModel.tryLaunchDevSettings(desc, intent) },
                        modifier = Modifier.height(28.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    ) {
                        Text("Launch", fontSize = 11.sp)
                    }
                }
            }
        }

        // USB Devices
        Spacer(modifier = Modifier.height(24.dp))
        SectionHeader("USB Devices")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Enumerate USB devices via UsbManager + sysfs",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.FilledTonalButton(
                onClick = { viewModel.scanUsbDevices() },
                modifier = Modifier.height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                Text("Scan USB", fontSize = 12.sp)
            }
        }

        if (debug.usbScanDone) {
            // UsbManager devices
            if (debug.usbDevices.isEmpty()) {
                DiagRow("UsbManager", "No devices found", valueColor = Color(0xFF808080))
            } else {
                Text(
                    "UsbManager Devices (${debug.usbDevices.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF64B5F6),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                for (device in debug.usbDevices) {
                    val adbBadge = if (device.hasAdbInterface) " [ADB!]" else ""
                    val header = "${device.productName ?: device.name}$adbBadge"
                    val headerColor = if (device.hasAdbInterface) Color(0xFF4CAF50) else Color.White
                    DiagRow(header, device.devicePath, valueColor = headerColor)
                    DiagRow("  Vendor", "0x${device.vendorId.toString(16).padStart(4, '0')} ${device.vendorName ?: ""}")
                    DiagRow("  Product", "0x${device.productId.toString(16).padStart(4, '0')}")
                    DiagRow("  Class", device.deviceClass)
                    device.serialNumber?.let { DiagRow("  Serial", it) }
                    for (iface in device.interfaces) {
                        val ifColor = if (iface.isAdb) Color(0xFF4CAF50) else Color(0xFFB0BEC5)
                        val adbLabel = if (iface.isAdb) " ★ ADB" else ""
                        DiagRow(
                            "  Interface ${iface.id}",
                            "${iface.interfaceClass} sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} ep=${iface.endpointCount}$adbLabel",
                            valueColor = ifColor,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            // sysfs devices
            if (debug.sysfsDevices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "sysfs /sys/bus/usb/devices/ (${debug.sysfsDevices.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF64B5F6),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                for (device in debug.sysfsDevices) {
                    val path = device["path"] ?: "?"
                    val product = device["product"] ?: device["manufacturer"] ?: ""
                    val vid = device["idVendor"] ?: ""
                    val pid = device["idProduct"] ?: ""
                    val speed = device["speed"] ?: ""
                    val label = buildString {
                        if (product.isNotEmpty()) append(product)
                        if (vid.isNotEmpty()) {
                            if (isNotEmpty()) append(" ")
                            append("[$vid:$pid]")
                        }
                        if (speed.isNotEmpty()) {
                            if (isNotEmpty()) append(" ")
                            append(speed)
                        }
                    }.ifEmpty { "device" }

                    val cls = device["class"] ?: ""
                    // Highlight ADB-capable: class ff, subclass 42, protocol 01
                    val isAdbLike = cls == "ff" && device["subclass"] == "42"
                    DiagRow(
                        path,
                        label,
                        valueColor = if (isAdbLike) Color(0xFF4CAF50) else Color(0xFFB0BEC5),
                    )
                }
            }
        }
    }
}

// --- Car Tab ---

@Composable
private fun CarTab(car: CarInfo) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (!car.isActive) {
            SectionHeader("Vehicle Sensors")
            DiagRow("Status", "Unavailable", valueColor = Color(0xFF808080))
            DiagRow("", "VHAL not connected — requires AAOS vehicle")
        } else {
            SectionHeader("Powertrain")
            DiagRow("Speed", car.speedKmh?.let { "${"%.1f".format(it)} km/h" } ?: "—")
            DiagRow("Gear", car.gear ?: "—",
                valueColor = when (car.gear) {
                    "P" -> Color(0xFF4CAF50)
                    "R" -> Color(0xFFFF9800)
                    "D", "1", "2", "3", "4" -> Color(0xFF2196F3)
                    else -> Color.White
                })
            DiagRow("Parking Brake", car.parkingBrake?.let { if (it) "ON" else "OFF" } ?: "—",
                valueColor = if (car.parkingBrake == true) Color(0xFFFF9800) else Color(0xFF4CAF50))
            car.rpmE3?.let {
                DiagRow("RPM", "${"%.0f".format(it / 1000f)}")
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader("Energy")
            car.batteryPct?.let { DiagRow("EV Battery", "$it%",
                valueColor = when {
                    it > 50 -> Color(0xFF4CAF50)
                    it > 20 -> Color(0xFFFFC107)
                    else -> Color(0xFFFF5722)
                }) }
            car.evBatteryLevelWh?.let {
                val capStr = car.evBatteryCapacityWh?.let { c -> " / ${"%.0f".format(c)}" } ?: ""
                DiagRow("Battery Energy", "${"%.0f".format(it)}$capStr Wh")
            }
            car.evCurrentBatteryCapacityWh?.let {
                DiagRow("Usable Capacity", "${"%.0f".format(it)} Wh")
            }
            // Show which capacity value the bridge uses for Maps VEM SOC calculation
            if (car.evBatteryCapacityWh != null) {
                val usable = car.evCurrentBatteryCapacityWh
                val gross = car.evBatteryCapacityWh
                val vemCap: Float
                val source: String
                if (usable != null && usable > 0f) {
                    vemCap = usable
                    source = "usable (live)"
                } else {
                    vemCap = gross!!
                    source = "gross"
                }
                val socPct = car.evBatteryLevelWh?.let { level ->
                    if (vemCap > 0f) "%.0f".format(level / vemCap * 100f) + "%" else "—"
                } ?: "—"
                DiagRow("VEM Capacity", "${"%.0f".format(vemCap)} Wh ($source) → $socPct",
                    valueColor = if (usable != null) Color(0xFF4CAF50) else Color.White)
            }
            car.evBatteryTempC?.let {
                DiagRow("Battery Temp", "${"%.1f".format(it)} °C")
            }
            car.evChargeRateW?.let { DiagRow("Charge Rate", "${"%.0f".format(it)} W",
                valueColor = if (it > 0) Color(0xFF4CAF50) else Color.White) }
            car.evChargeState?.let { DiagRow("Charge State", evChargeStateToString(it),
                valueColor = when (it) {
                    2 -> Color(0xFF4CAF50) // CHARGING
                    4 -> Color(0xFF64B5F6) // FULLY_CHARGED
                    else -> Color.White
                }) }
            car.evChargeTimeRemainingSec?.let {
                val mins = it / 60
                val hrs = mins / 60
                val display = if (hrs > 0) "${hrs}h ${mins % 60}m" else "${mins}m"
                DiagRow("Charge Time Left", display)
            }
            car.evChargePercentLimit?.let { DiagRow("Charge Limit", "${"%.0f".format(it)}%") }
            car.evChargeCurrentDrawLimitA?.let { DiagRow("AC Draw Limit", "${"%.0f".format(it)} A") }
            car.chargePortOpen?.let { DiagRow("Charge Port", if (it) "Open" else "Closed",
                valueColor = if (it) Color(0xFF64B5F6) else Color.White) }
            car.chargePortConnected?.let { DiagRow("Charger", if (it) "Connected" else "—",
                valueColor = if (it) Color(0xFF4CAF50) else Color.White) }
            car.fuelLevelPct?.let { DiagRow("Fuel Level", "$it%") }
            car.rangeKm?.let { DiagRow("Range", "${"%.1f".format(it)} km") }
            car.lowFuel?.let { DiagRow("Low Fuel", if (it) "YES" else "No",
                valueColor = if (it) Color(0xFFFF5722) else Color(0xFF4CAF50)) }

            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader("EV Driving")
            car.evRegenBrakingLevel?.let { DiagRow("Regen Braking", "Level $it") }
            car.evStoppingMode?.let { DiagRow("Stopping Mode", evStoppingModeToString(it)) }

            Spacer(modifier = Modifier.height(16.dp))
            SectionHeader("Environment")
            DiagRow("Night Mode", car.nightMode?.let { if (it) "ON" else "OFF" } ?: "—",
                valueColor = if (car.nightMode == true) Color(0xFF64B5F6) else Color(0xFFFFC107))
            car.ambientTempC?.let { DiagRow("Outside Temp", "${"%.1f".format(it)} °C") }
            car.ignitionState?.let { DiagRow("Ignition", ignitionStateToString(it)) }
            car.distanceDisplayUnits?.let { DiagRow("Distance Units", distanceUnitsToString(it)) }

            if (car.turnSignal != null || car.headlight != null || car.hazardLights != null) {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader("Lights")
                car.turnSignal?.let { DiagRow("Turn Signal", it.replaceFirstChar { c -> c.uppercase() }) }
                car.headlight?.let { DiagRow("Headlights", headlightToString(it)) }
                car.hazardLights?.let { DiagRow("Hazard Lights", if (it) "ON" else "OFF",
                    valueColor = if (it) Color(0xFFFF9800) else Color.White) }
            }

            if (car.steeringAngleDeg != null || car.odometerKm != null) {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader("Other")
                car.steeringAngleDeg?.let { DiagRow("Steering Angle", "${"%.1f".format(it)}°") }
                car.odometerKm?.let { DiagRow("Odometer", "${"%.1f".format(it)} km") }
            }

            // Property access status section — shows what worked and what didn't
            if (car.propertyStatus.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                SectionHeader("VHAL Property Status")
                car.propertyStatus.entries.sortedBy { it.key }.forEach { (prop, status) ->
                    val (label, color) = when {
                        status == "subscribed" -> "✓ Subscribed" to Color(0xFF4CAF50)
                        status == "not_exposed" -> "✗ Not exposed by HAL" to Color(0xFF808080)
                        status == "not_in_sdk" -> "✗ Not in SDK" to Color(0xFF808080)
                        status == "rejected" -> "✗ Rejected" to Color(0xFFFF9800)
                        status.startsWith("permission_denied") -> {
                            val perm = status.substringAfter(":")
                                .substringAfterLast(".")
                            "✗ No permission ($perm)" to Color(0xFFFF5722)
                        }
                        else -> status to Color.White
                    }
                    DiagRow(prop.replace("_", " ").lowercase()
                        .replaceFirstChar { it.uppercase() }, label, valueColor = color)
                }
            }
        }
    }
}

private fun headlightToString(state: Int): String = when (state) {
    0 -> "Off"
    1 -> "On"
    2 -> "Daytime Running"
    else -> "Unknown ($state)"
}

private fun ignitionStateToString(state: Int): String = when (state) {
    0 -> "Undefined"
    1 -> "Lock"
    2 -> "Off"
    3 -> "Accessory"
    4 -> "On"
    5 -> "Start"
    else -> "Unknown ($state)"
}

private fun evChargeStateToString(state: Int): String = when (state) {
    0 -> "Unknown"
    1 -> "Not Charging"
    2 -> "Charging"
    3 -> "Error"
    4 -> "Fully Charged"
    else -> "Unknown ($state)"
}

private fun evStoppingModeToString(mode: Int): String = when (mode) {
    0 -> "Unknown"
    1 -> "Creep"
    2 -> "Roll"
    3 -> "Hold (One Pedal)"
    else -> "Unknown ($mode)"
}

private fun distanceUnitsToString(units: Int): String = when (units) {
    0x21 -> "Meters"
    0x23 -> "Kilometers"
    0x24 -> "Miles"
    else -> "Unknown (0x${units.toString(16)})"
}

// --- Logs Tab ---

@Composable
private fun LogsTab(
    logs: List<LogEntry>,
    currentFilter: LogSeverity,
    viewModel: DiagnosticsViewModel,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Filter chips row + clear button
        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogSeverity.entries.forEach { severity ->
                FilterChip(
                    selected = currentFilter == severity,
                    onClick = { viewModel.setLogFilter(severity) },
                    label = { Text(severity.name, fontSize = 11.sp) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.clearLogs() }) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Clear logs",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        HorizontalDivider()

        // Log entries
        val listState = rememberLazyListState()
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = "No log entries",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(logs) { entry ->
                    LogEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 4.dp),
    ) {
        // Timestamp
        Text(
            text = formatTimestamp(entry.timestamp),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF808080),
            modifier = Modifier.width(60.dp),
        )
        // Severity indicator
        Text(
            text = entry.severity.name.first().toString(),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = severityColor(entry.severity),
            modifier = Modifier.width(14.dp),
        )
        // Tag
        Text(
            text = entry.tag,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF64B5F6),
            modifier = Modifier.width(72.dp),
        )
        // Message
        Text(
            text = entry.message,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = Color.White,
        )
    }
}

// --- Shared composables ---

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun DiagRow(
    label: String,
    value: String,
    valueColor: Color = Color.White,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .background(
                color = Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
        )
    }
}

// --- Helpers ---

private fun sessionStateColor(state: com.openautolink.app.session.SessionState): Color = when (state) {
    com.openautolink.app.session.SessionState.STREAMING -> Color(0xFF4CAF50)
    com.openautolink.app.session.SessionState.CONNECTED -> Color(0xFFFFC107)
    com.openautolink.app.session.SessionState.CONNECTING -> Color(0xFF2196F3)
    com.openautolink.app.session.SessionState.IDLE -> Color(0xFF808080)
    com.openautolink.app.session.SessionState.ERROR -> Color(0xFFFF5722)
}

private fun severityColor(severity: LogSeverity): Color = when (severity) {
    LogSeverity.DEBUG -> Color(0xFF808080)
    LogSeverity.INFO -> Color(0xFF4CAF50)
    LogSeverity.WARN -> Color(0xFFFFC107)
    LogSeverity.ERROR -> Color(0xFFFF5722)
}

private fun formatTimestamp(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    return sdf.format(java.util.Date(ms))
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

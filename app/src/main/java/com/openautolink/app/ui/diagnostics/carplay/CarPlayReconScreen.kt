package com.openautolink.app.ui.diagnostics.carplay

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun CarPlayReconScreen(
    viewModel: CarPlayReconViewModel = viewModel(),
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // Left: Back button
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
            }

            // Content
            CarPlayReconContent(viewModel = viewModel, uiState = uiState)
        }
    }
}

/** Embeddable content — used both as a standalone screen and as a Diagnostics tab. */
@Composable
fun CarPlayReconContent(
    viewModel: CarPlayReconViewModel = viewModel(),
    uiState: CarPlayReconUiState = viewModel.uiState.collectAsStateWithLifecycle().value,
) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // Header
                Text(
                    "CarPlay Recon",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFFFF9800),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Hidden investigation tool — probes system for CarPlay/iAP/MFi stack",
                    color = Color(0xFF808080),
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Controls row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.runFullScan() },
                        enabled = !uiState.scanRunning,
                        contentPadding = PaddingValues(horizontal = 24.dp),
                    ) {
                        if (uiState.scanRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (uiState.scanRunning) "Scanning..." else "Run Full Scan")
                    }

                    // Save to USB button
                    FilledTonalButton(
                        onClick = { viewModel.saveToUsb() },
                        enabled = uiState.fullReport.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        Text("Save to USB", fontSize = 12.sp)
                    }
                }

                // Save status
                if (uiState.saveStatus.isNotEmpty()) {
                    Text(
                        uiState.saveStatus,
                        color = Color(0xFF4CAF50),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (uiState.saveProgress.isNotEmpty()) {
                    Text(
                        uiState.saveProgress,
                        color = Color(0xFF64B5F6),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                // --- All APKs Catalog ---
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    "All APKs on Device",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFFFF9800),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Scan for every installed APK — shows which are readable and extractable",
                    color = Color(0xFF808080),
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.scanAllApks() },
                        enabled = !uiState.apkCatalogScanning,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        if (uiState.apkCatalogScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            if (uiState.apkCatalogScanning) "Scanning..." else "Scan All APKs",
                            fontSize = 12.sp,
                        )
                    }
                    if (uiState.allApks.isNotEmpty()) {
                        val readable = uiState.allApks.count { it.readable }
                        val totalSize = uiState.allApks.filter { it.readable }.sumOf { it.apkSizeBytes }
                        Text(
                            "${uiState.allApks.size} packages, $readable readable (${CarPlayReconViewModel.formatSizeVm(totalSize)})",
                            color = Color(0xFF4CAF50),
                            fontSize = 11.sp,
                        )
                    }
                }

                if (uiState.allApks.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // APK list
                    for (apk in uiState.allApks) {
                        ApkRow(apk)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(12.dp))

                // Probe results
                for (probe in uiState.probes) {
                    ProbeSection(probe)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
}

@Composable
private fun ProbeSection(probe: ReconProbeResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color.White.copy(alpha = 0.04f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Status indicator
            val (statusText, statusColor) = when (probe.status) {
                ProbeStatus.PENDING -> "⏳" to Color(0xFF808080)
                ProbeStatus.RUNNING -> "🔄" to Color(0xFF2196F3)
                ProbeStatus.DONE -> "✅" to Color(0xFF4CAF50)
                ProbeStatus.ERROR -> "❌" to Color(0xFFFF5722)
            }
            Text(statusText, fontSize = 14.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                probe.name,
                style = MaterialTheme.typography.titleMedium,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
            )
            if (probe.status == ProbeStatus.RUNNING) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF2196F3),
                )
            }
        }

        if (probe.output.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color.Black.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(4.dp),
                    )
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                Text(
                    text = probe.output,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFFE0E0E0),
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun ApkRow(apk: ApkEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .background(
                color = Color.White.copy(alpha = 0.04f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Readable indicator
        Text(
            if (apk.readable) "✓" else "✗",
            color = if (apk.readable) Color(0xFF4CAF50) else Color(0xFF808080),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.width(6.dp))
        // System/User badge
        Text(
            if (apk.isSystemApp) "SYS" else "USR",
            color = if (apk.isSystemApp) Color(0xFF64B5F6) else Color(0xFFFFC107),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        // Package name
        Column(modifier = Modifier.weight(1f)) {
            Text(
                apk.packageName,
                fontSize = 11.sp,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            if (apk.nativeLibs.isNotEmpty()) {
                Text(
                    "libs: ${apk.nativeLibs.joinToString()}",
                    fontSize = 9.sp,
                    color = Color(0xFF808080),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        // Size
        Text(
            CarPlayReconViewModel.formatSizeVm(apk.apkSizeBytes),
            fontSize = 10.sp,
            color = Color(0xFF808080),
            fontFamily = FontFamily.Monospace,
        )
    }
}

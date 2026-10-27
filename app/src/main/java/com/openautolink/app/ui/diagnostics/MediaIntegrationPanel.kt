package com.openautolink.app.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MediaIntegrationPanel(viewModel: MediaDiagnosticsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Media integration", style = MaterialTheme.typography.titleLarge)
        Text("Read-only public registrations. Comparison names stay in memory; manual log ZIPs contain finite own-state/coverage only. This does not establish OEM receipt, selected source or audible output.")
        Button(onClick = viewModel::refresh, enabled = !state.refreshing) { Text(if (state.refreshing) "Refreshing…" else "Refresh comparison") }
        Text("Save media-host APK ZIP", style = MaterialTheme.typography.titleMedium)
        Text("Fixed system com.android.car.media only. Unencrypted local save to any document provider; APKs may contain vendor IP. Saving grants no redistribution license. No APK bytes enter log uploads.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::requestExport, enabled = !state.exportBusy) { Text("Choose destination and save") }
            if (state.exportBusy) OutlinedButton(onClick = viewModel::cancelExport) { Text("Cancel save") }
        }
        Text("${state.exportPhase}: ${state.files} APK files, ${state.bytes} bytes")
        if (state.result.isNotBlank()) Text(state.result)
        if (state.partialWarning) Text("Save incomplete; a partial file may remain at the selected location.", color = MaterialTheme.colorScheme.error)
        SelectionContainer { Text(state.localText, fontFamily = FontFamily.Monospace) }
    }
}

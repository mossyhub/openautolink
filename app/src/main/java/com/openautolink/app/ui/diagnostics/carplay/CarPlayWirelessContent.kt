package com.openautolink.app.ui.diagnostics.carplay

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openautolink.app.transport.carplay.wireless.CarPlayMdnsResponder
import com.openautolink.app.transport.carplay.wireless.CarPlayTcpListener
import com.openautolink.app.transport.carplay.wireless.CarPlayWirelessProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LOG_CAP = 256

data class CarPlayWirelessUiState(
    val running: Boolean = false,
    val mdnsLabel: String = "idle",
    val tcpLabel: String = "stopped",
    val localIps: List<String> = emptyList(),
    val log: List<String> = emptyList(),
)

class CarPlayWirelessViewModel(application: Application) : AndroidViewModel(application) {
    private val probe = CarPlayWirelessProbe(application.applicationContext)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _ui = MutableStateFlow(CarPlayWirelessUiState(localIps = probe.localIp4Addresses()))
    val ui: StateFlow<CarPlayWirelessUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            probe.tcpEvents.collect { ev ->
                appendLog(formatEvent(ev))
            }
        }
        viewModelScope.launch {
            probe.mdnsState.collect { s ->
                _ui.update { it.copy(mdnsLabel = formatMdns(s)) }
            }
        }
        viewModelScope.launch {
            probe.tcpState.collect { s ->
                _ui.update { it.copy(tcpLabel = formatTcp(s)) }
            }
        }
        viewModelScope.launch {
            probe.running.collect { running ->
                _ui.update { it.copy(running = running) }
            }
        }
    }

    fun start() {
        appendLog("--- starting probe ---")
        _ui.update { it.copy(localIps = probe.localIp4Addresses()) }
        probe.start()
    }

    fun stop() {
        probe.stop()
        appendLog("--- stopped ---")
    }

    fun clearLog() {
        _ui.update { it.copy(log = emptyList()) }
    }

    override fun onCleared() {
        probe.stop()
        super.onCleared()
    }

    private fun appendLog(line: String) {
        val ts = timeFmt.format(Date())
        _ui.update { state ->
            val combined = state.log + "$ts  $line"
            val trimmed = if (combined.size > LOG_CAP) combined.takeLast(LOG_CAP) else combined
            state.copy(log = trimmed)
        }
    }

    private fun formatEvent(ev: CarPlayTcpListener.Event): String = when (ev) {
        is CarPlayTcpListener.Event.Started  -> "LISTEN  :${ev.port}"
        is CarPlayTcpListener.Event.Accepted -> "ACCEPT  ${ev.remoteAddr}:${ev.remotePort}"
        is CarPlayTcpListener.Event.Bytes    -> "BYTES   ${ev.remoteAddr}  ${ev.len}B  ${ev.hex}"
        is CarPlayTcpListener.Event.Closed   -> "CLOSE   ${ev.remoteAddr}  ${ev.reason}"
        is CarPlayTcpListener.Event.Error    -> "ERROR   ${ev.message}"
        CarPlayTcpListener.Event.Stopped     -> "STOPPED"
    }

    private fun formatMdns(s: CarPlayMdnsResponder.State): String = when (s) {
        CarPlayMdnsResponder.State.Idle           -> "idle"
        CarPlayMdnsResponder.State.Registering    -> "registering…"
        is CarPlayMdnsResponder.State.Registered  -> "registered as '${s.name}' on :${s.port}"
        is CarPlayMdnsResponder.State.Failed      -> "failed (code ${s.errorCode})"
    }

    private fun formatTcp(s: CarPlayTcpListener.State): String = when (s) {
        CarPlayTcpListener.State.Stopped     -> "stopped"
        is CarPlayTcpListener.State.Listening -> "listening on :${s.port}"
        is CarPlayTcpListener.State.Failed    -> "failed: ${s.message}"
    }
}

@Composable
fun CarPlayWirelessContent(
    viewModel: CarPlayWirelessViewModel = viewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "CarPlay Wireless Probe",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Advertises _carplay._tcp and listens on TCP :7000. " +
                "Logs every byte received. Use while iPhone is on the same WiFi " +
                "(e.g. CPC200 AP in lean mode) to confirm reachability.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        // Controls
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.start() },
                enabled = !ui.running,
            ) { Text("Start") }
            OutlinedButton(
                onClick = { viewModel.stop() },
                enabled = ui.running,
            ) { Text("Stop") }
            OutlinedButton(onClick = { viewModel.clearLog() }) { Text("Clear log") }
        }

        Spacer(Modifier.height(12.dp))

        // Status panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            StatusLine("mDNS  ", ui.mdnsLabel)
            StatusLine("TCP   ", ui.tcpLabel)
            if (ui.localIps.isEmpty()) {
                StatusLine("IPv4  ", "(no non-loopback IPv4 found)")
            } else {
                ui.localIps.forEach { ip -> StatusLine("IPv4  ", ip) }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "Event log (${ui.log.size}/${LOG_CAP})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))

        val listState = rememberLazyListState()
        LaunchedEffect(ui.log.size) {
            if (ui.log.isNotEmpty()) listState.animateScrollToItem(ui.log.size - 1)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101418), RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            LazyColumn(state = listState) {
                items(ui.log) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFB8C7D6),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

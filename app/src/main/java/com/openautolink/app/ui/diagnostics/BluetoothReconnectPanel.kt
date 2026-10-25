package com.openautolink.app.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.openautolink.app.diagnostics.BluetoothReconnectProcess
import kotlinx.coroutines.delay

@Composable
internal fun BluetoothReconnectPanel(process: BluetoothReconnectProcess?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bluetooth reconnect flight recorder", style = MaterialTheme.typography.titleLarge)
        Text("Optional local connection/state timing, not audio or phone identity. Observes in the background for at most 10 minutes while OAL's process lives. Cannot prevent Media turning on or read its policy. No automatic upload or restart.")
        Text("Arm before returning to the car. Keep Phone ON / Media OFF and OAL selected only if that is your intended setup; the Bluetooth-selected fallback is unchanged. A source bookmark is your observation, not a privileged source query.")
        if (process == null) {
            Text("Unavailable — application recorder storage not available; no observation")
            return@Column
        }
        val status by process.recorder.status.collectAsState()
        val coverage by process.coverage.collectAsState()
        val busy by process.busy.collectAsState()
        val notice by process.notice.collectAsState()
        var consent by remember { mutableStateOf(false) }
        var setupMediaOff by remember { mutableStateOf(false) }
        var remaining by remember { mutableLongStateOf(0) }
        LaunchedEffect(status) {
            do { remaining = process.recorder.remainingMs(); if (remaining > 0) delay(1000) } while (remaining > 0)
        }
        val running = status.startsWith("Recording locally")
        Text(status, Modifier.testTag("bt-recorder-status"))
        if (running) Text("Remaining: ${remaining / 1000} seconds (best effort; no wake/keepalive)")
        Text("Bluetooth receive capability: $coverage. Delivery not guaranteed; no receipt is not disconnected. WP PRE: unsupported/not registered. Private policy and selected phone: unknown.")
        if (notice.isNotEmpty()) Text(notice)
        Row {
            Checkbox(consent, { consent = it }, enabled = !running && !busy, modifier = Modifier.testTag("bt-recorder-consent"))
            Text("I consent to bounded background local state timing. Upload remains a separate manual action.")
        }
        Row {
            Checkbox(setupMediaOff, { setupMediaOff = it }, enabled = !running && !busy, modifier = Modifier.testTag("bt-recorder-setup-media-off"))
            Text("I have set Media OFF (optional setup attestation recorded at Arm, not readback or a known current setting).")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { process.requestStart(consent, setupMediaOff); consent = false; setupMediaOff = false },
                enabled = consent && !running && !busy, modifier = Modifier.testTag("bt-recorder-arm")) { Text("Arm / Start (10 min)") }
            Button(onClick = { process.stop() }, enabled = running && !busy, modifier = Modifier.testTag("bt-recorder-stop")) { Text("Stop / save locally") }
        }
        Text("Bookmarks: user_report only; device binding unknown. Never assumed to be the last observed phone.")
        listOf("phone_on_media_off" to "I have set Media OFF", "media_seen_on" to "I see Media ON", "returned_to_car" to "I returned to the car", "oal_selected" to "I see OAL selected", "bt_selected" to "I see Bluetooth selected", "play_pressed" to "I pressed music Play").forEach { (kind, label) ->
            OutlinedButton(onClick = { process.recorder.bookmark(kind) }, enabled = running && !busy, modifier = Modifier.testTag("bt-bookmark-$kind")) { Text(label) }
        }
        OutlinedButton(onClick = { consent = false; setupMediaOff = false; process.requestDelete() },
            modifier = Modifier.testTag("bt-recorder-delete")) { Text("Revoke consent / delete local recorder evidence") }
        Text("Stop saves up to three bounded runs for seven days. Process death leaves an unknown coverage gap and requires fresh consent. Use existing Upload Logs only when you choose to send saved diagnostics. Do not change pairing, Bluetooth policies or Siri for this recorder.")
    }
}

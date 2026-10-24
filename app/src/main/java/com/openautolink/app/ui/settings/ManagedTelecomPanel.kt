package com.openautolink.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.openautolink.carplaylab.telecom.ManagedTelecomExperiment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Diagnostic choices last until Off or app restart; Android owns account enablement. */
@Composable
internal fun ManagedTelecomPanel() {
    val context = LocalContext.current
    val experiment = remember(context) { ManagedTelecomExperiment.get(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(experiment.enabledForProcess) }
    var controls by remember { mutableStateOf(experiment.nativeControlsEnabled) }
    var accountReady by remember { mutableStateOf(experiment.armed) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableLongStateOf(0) }
    var status by remember { mutableStateOf("Off by default. Reconnect CarPlay after changing controls.") }
    fun accountAction(action: () -> String) {
        val request = ++operation
        busy = true; status = "WORKING"
        scope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            if (operation == request) {
                enabled = experiment.enabledForProcess
                controls = experiment.nativeControlsEnabled
                accountReady = experiment.armed
                status = result; busy = false
            }
        }
    }
    Column {
        Text("Native call integration")
        Text("Diagnostic · until Off or app restart. Android may show calls, ring and change call audio. Reject unsupported; decline on iPhone or CarPlay.")
        Row {
            Text("Native call integration")
            Switch(checked = enabled, enabled = !busy, onCheckedChange = { value ->
                accountAction { if (value) experiment.enable().name else experiment.disable().name }
            }, modifier = Modifier.testTag("telecomEnabled"))
        }
        Row {
            Text("Native Answer/End")
            Switch(checked = controls, enabled = !busy && (controls || accountReady), onCheckedChange = { value ->
                if (value) accountAction { experiment.enableNativeControls().name }
                else {
                    experiment.disableNativeControls(); controls = false
                    status = "Native Answer/End off"
                }
            }, modifier = Modifier.testTag("telecomControlsEnabled"))
        }
        Text("Known single incoming calls only; ambiguous calls are withheld. Reconnect to apply. No outgoing, hold or conference controls.")
        OutlinedButton(enabled = enabled && !busy, onClick = {
            status = if (experiment.openEnableSettings()) "Enable only OAL's account in Android, then refresh."
                else "Account settings unavailable."
        }, modifier = Modifier.testTag("telecomEnableSettings")) { Text("Enable OAL account in Android") }
        OutlinedButton(enabled = !busy, onClick = { accountAction { experiment.refresh().name } },
            modifier = Modifier.testTag("telecomReadback")) { Text("Refresh account status") }
        OutlinedButton(enabled = !busy, onClick = { accountAction { experiment.disable().name } },
            modifier = Modifier.testTag("telecomCleanup")) { Text("Remove OAL account") }
        Text(status, modifier = Modifier.testTag("telecomStatus"))
    }
}

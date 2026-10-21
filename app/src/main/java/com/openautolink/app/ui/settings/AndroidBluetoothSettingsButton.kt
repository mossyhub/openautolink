package com.openautolink.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openautolink.app.ui.diagnostics.MicrophoneDiagnosticPark

/** Alternate destination; never opened as a composition/lifecycle side effect. */
@Composable
internal fun AndroidBluetoothSettingsButton() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var result by remember { mutableStateOf<AndroidBluetoothSettingsLauncher.Result?>(null) }
    Column {
        OutlinedButton(onClick = {
            // Check at the click, not a remembered snapshot or projection Always-in-Park override.
            result = when {
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ->
                    AndroidBluetoothSettingsLauncher.Result.FOREGROUND_REQUIRED
                !MicrophoneDiagnosticPark.isFreshPark() -> AndroidBluetoothSettingsLauncher.Result.PARK_REQUIRED
                else -> AndroidBluetoothSettingsLauncher.launch(context)
            }
        }, modifier = Modifier.testTag("androidBluetoothSettings")) { Text("Android Bluetooth settings") }
        Text("Alternate Android Settings screen, if installed. Open only while safely parked. You operate any phone/media controls; OAL does not automatically change Bluetooth profiles. Vehicle restrictions still apply.",
            style = MaterialTheme.typography.bodySmall)
        result?.let { Text(it.message, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("androidBluetoothSettingsResult")) }
    }
}

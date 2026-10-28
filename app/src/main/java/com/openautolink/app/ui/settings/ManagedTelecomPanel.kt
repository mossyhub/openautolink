package com.openautolink.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.openautolink.carplaylab.telecom.ManagedTelecomExperiment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Advanced repair/census only. Production intent and setup live in the single Audio destination. */
@Composable
internal fun ManagedTelecomPanel() {
    val context = LocalContext.current
    val experiment = remember(context) { ManagedTelecomExperiment.get(context) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    fun inspect(action: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            status = try { withContext(Dispatchers.IO) { action() } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { "Account diagnostic unavailable" }
            busy = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Native call repair", style = MaterialTheme.typography.titleLarge)
        Text("Call handling, setup and ringtone are in Settings → Audio.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(enabled = !busy, onClick = { inspect { experiment.census().toString() } },
            modifier = Modifier.heightIn(min = 48.dp).testTag("telecomInspect")) { Text("Inspect native call support") }
        // Explicit destructive maintenance, never used for ordinary handler changes.
        OutlinedButton(enabled = !busy, onClick = { inspect { experiment.disable().name } },
            modifier = Modifier.heightIn(min = 48.dp).testTag("telecomCleanup")) { Text("Remove OAL account") }
        if (status.isNotBlank()) Text(status, modifier = Modifier.testTag("telecomStatus"))
    }
}

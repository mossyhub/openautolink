package com.openautolink.app.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun CarPlayPlayingPublicationControls(controller: MicrophoneDiagnosticController) {
    HorizontalDivider()
    Text("CarPlay media experiment", style = MaterialTheme.typography.titleLarge)
    val status = controller.playingStatus
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Media experiment")
        Switch(checked = status.armed, enabled = status.armed || controller.canArmPlaying,
            onCheckedChange = { if (it) controller.armPlaying() else controller.disarmPlaying() },
            modifier = Modifier.testTag("cp-media-enabled"))
    }
    Text(if (status.armed) "On · ${status.mode} · until Off or app restart" else "OFF · normal publication",
        Modifier.testTag("cp-playing-status"), style = MaterialTheme.typography.titleMedium)
    Text("Apply on reconnect. Active owners keep their original mode. Suppress Playing publishes None; INACTIVE also withholds title/art. Native source controls may change; audio and Bluetooth are unchanged.",
        style = MaterialTheme.typography.bodySmall)
    Text(controller.currentHostMediaStatus, Modifier.testTag("cp-host-media-current"), style = MaterialTheme.typography.bodySmall)
    Text(if (controller.canArmPlaying) "Ready · reconnect to apply."
        else "Unavailable while backgrounded, projection is active or a local test owns the microphone.",
        Modifier.testTag("cp-playing-reason"), style = MaterialTheme.typography.bodySmall)
    Button(controller::armPlaying, enabled = controller.canArmPlaying,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cp-playing-arm")) {
        Text("Suppress Playing · reconnect")
    }
    Button(controller::armInactive, enabled = controller.canArmPlaying,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cp-inactive-arm")) {
        Text("INACTIVE · withhold title/art · reconnect")
    }
    OutlinedButton(controller::disarmPlaying,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cp-playing-disarm")) {
        Text("Off · future owners only")
    }
}

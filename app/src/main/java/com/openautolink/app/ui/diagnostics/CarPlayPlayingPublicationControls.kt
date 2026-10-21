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
    Text("Test: suppress native Playing status", style = MaterialTheme.typography.titleLarge)
    val status = controller.playingStatus
    Text(when {
        status.armed -> "Armed for future CarPlay owners · expires in ${(status.remainingMillis + 999) / 1000} seconds"
        controller.playingExpired -> "Expired · OFF for future owners"
        else -> "OFF for future owners · normal publication"
    }, Modifier.testTag("cp-playing-status"), style = MaterialTheme.typography.titleMedium)
    Text("Unproven CarPlay-only experiment: submit Playing as None to the native media session. Paused/unknown, title/art, session identity and transport callbacks stay unchanged. Native play indicator, progress, controls or targeting may change; this does not mute audio, alter calls or Bluetooth, or prove which OEM source is selected.", style = MaterialTheme.typography.bodySmall)
    Text("Memory only · 15 minutes · frozen once per CarPlay metadata owner. Reconnect to apply arming or disarming. Expiry/disarm affects future owners only; an active owner keeps its mode until retirement. Leaving clears consent, not the arm.", style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(controller.playingConsent, controller::updatePlayingConsent, Modifier.testTag("cp-playing-consent"))
        Text("I consent to this future CarPlay media-publication test")
    }
    Text(if (controller.canArmPlaying) "Ready for future-owner arm; reconnect to apply."
        else "Unavailable: requires explicit consent, foreground panel, fresh actual Park, no active projection and no local test. No setting was applied by an unavailable arm.",
        Modifier.testTag("cp-playing-reason"), style = MaterialTheme.typography.bodySmall)
    Button(controller::armPlaying, enabled = controller.canArmPlaying,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cp-playing-arm")) {
        Text("Arm suppression · 15 minutes · reconnect")
    }
    OutlinedButton(controller::disarmPlaying,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cp-playing-disarm")) {
        Text("Disarm · future owners only · reconnect")
    }
}

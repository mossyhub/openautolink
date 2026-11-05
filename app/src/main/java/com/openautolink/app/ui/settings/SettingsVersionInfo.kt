package com.openautolink.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.openautolink.app.BuildConfig
import com.openautolink.app.ui.components.OalAlertDialog

/** Passive build/update information; keep it in the connection pane's scroll content. */
@Composable
internal fun SettingsVersionInfo(modifier: Modifier = Modifier) {
    // Local presentation only: reopening Settings must not restore a legal-notices popup.
    var showLegalNotices by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "OAL ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("settingsInstalledVersion"),
        )
        Text(
            text = "Connect your car to Wi-Fi with internet regularly so OAL and other Play Store apps can update.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("settingsUpdateReminder"),
        )
        TextButton(
            onClick = { showLegalNotices = true },
            modifier = Modifier.heightIn(min = 48.dp).testTag("settingsLegalNotices"),
        ) {
            Text("Credits & legal notices")
        }
    }
    if (showLegalNotices) {
        CarPlayLegalNotices(onClose = { showLegalNotices = false })
    }
}

@Composable
private fun CarPlayLegalNotices(onClose: () -> Unit) {
    OalAlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("settingsLegalNoticesDialog"),
        title = { Text("Credits & legal notices") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI)",
                    modifier = Modifier.fillMaxWidth().testTag("settingsLiviAttribution"),
                )
                Text(
                    text = "CarPlay receiver adapted through shilapi/xcertplay and shihabal3amri/DiPlay. " +
                        "OpenAutoLink is licensed under GNU GPL v3; component notices and source: " +
                        "https://github.com/mossyhub/openautolink",
                    modifier = Modifier.fillMaxWidth().testTag("settingsCarPlayAttribution"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onClose,
                modifier = Modifier.heightIn(min = 48.dp).testTag("settingsLegalNoticesClose"),
            ) {
                Text("Close")
            }
        },
    )
}

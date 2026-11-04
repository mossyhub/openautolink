package com.openautolink.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.openautolink.app.BuildConfig

/** Passive build/update information; keep it in the connection pane's scroll content. */
@Composable
internal fun SettingsVersionInfo(modifier: Modifier = Modifier) {
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
        Text(
            text = "Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("settingsLiviAttribution"),
        )
        Text(
            text = "CarPlay receiver adapted through shilapi/xcertplay and shihabal3amri/DiPlay. " +
                "OpenAutoLink is licensed under GNU GPL v3; component notices and source: " +
                "https://github.com/mossyhub/openautolink",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("settingsCarPlayAttribution"),
        )
    }
}

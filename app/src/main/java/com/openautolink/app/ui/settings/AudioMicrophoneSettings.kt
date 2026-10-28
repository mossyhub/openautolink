package com.openautolink.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Protocol scope is visible even when disconnected or projecting the other protocol. */
@Composable
internal fun AudioMicrophoneSettings(source: String, onSourceChange: (String) -> Unit,
    bindings: AudioSettingsBindings? = null) {
    AudioSectionTitle("Microphone")
    AudioSectionTitle("Android Auto")
    Text("Assistant microphone", style = MaterialTheme.typography.titleMedium)
    AudioChoice("Car", source == "car", "micSource_car") { onSourceChange("car") }
    AudioChoice("Phone", source == "phone", "micSource_phone") { onSourceChange("phone") }
    AudioReadOnlyRow("Calls", "Car Bluetooth")
    AudioSectionTitle("CarPlay")
    AudioReadOnlyRow("Calls and Siri", "Car microphone")
    if (!bindings?.microphoneMessage.isNullOrBlank()) Text(bindings!!.microphoneMessage,
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("carPlayMicrophoneStatus"))
    bindings?.microphoneAction?.let { label ->
        OutlinedButton(onClick = bindings.onMicrophoneAction,
            modifier = Modifier.heightIn(min = 48.dp).testTag("carPlayMicrophoneAction")) { Text(label) }
    }
}

@Composable
internal fun AudioSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp))
}

@Composable
internal fun AudioChoice(label: String, selected: Boolean, tag: String, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .testTag(tag).padding(vertical = 8.dp)) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun AudioReadOnlyRow(label: String, value: String) {
    // Stack at every width: long translated/large-font values never collide with their label.
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

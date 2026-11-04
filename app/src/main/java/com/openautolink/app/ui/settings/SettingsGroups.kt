package com.openautolink.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Flat, owned control/helper group. Never merge an entire section's accessibility nodes. */
@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

/** Label and visible supporting copy share one column; the sole switch owns the sole callback. */
@Composable
internal fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    controlModifier: Modifier = Modifier,
    toggleOnRow: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rowAction = if (toggleOnRow) Modifier.toggleable(checked, enabled = enabled,
        role = Role.Switch, onValueChange = onCheckedChange) else Modifier
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).testTag("settingGroup_$label").then(rowAction),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        Switch(checked = checked, onCheckedChange = if (toggleOnRow) null else onCheckedChange, enabled = enabled,
            modifier = controlModifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = label })
    }
}

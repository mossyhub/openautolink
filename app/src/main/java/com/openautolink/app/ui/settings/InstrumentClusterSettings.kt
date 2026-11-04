package com.openautolink.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openautolink.app.cluster.ClusterDisplayMode
import com.openautolink.app.cluster.ClusterOutputSelection
import com.openautolink.app.cluster.ClusterOutputPolicy

/** Observations supplied by the actual owner, never inferred from saved intent or a decoder callback. */
data class ClusterSettingsStatus(
    val source: String?,
    val profile: String?,
    val host: String?,
    val phoneStream: String?,
    val renderedFrames: String?,
)

/** Required production integration boundary. No default/no-op adapter is installed by settings.
 * Geometry changes stage a next-session draft. commitLayoutDraft must acknowledge real persistence
 * keyed by vehicle product/build/role/panel, never Android display ID or center-display preferences.
 */
data class ClusterSettingsBindings(
    val status: ClusterSettingsStatus,
    val layout: ClusterLayoutModel?,
    val automaticLayout: ClusterLayoutModel?,
    val actualSurface: com.openautolink.app.cluster.PixelSize?,
    val isCustomLayout: Boolean,
    val previewActive: Boolean,
    /** Opaque runtime lease identity; stable across recomposition, replaced on owner/lease change.
     * onStopPreview must be an idempotent exact-owner stop handle, never "stop current".
     * Externally active previews require the same owner identity and stop handle.
     */
    val previewOwner: Any,
    val onStageLayout: (ClusterLayoutModel) -> Unit,
    val onResetLayout: () -> Unit,
    val onStartPreview: (ClusterLayoutModel, Int) -> Unit,
    val onStopPreview: () -> Unit,
    val commitLayoutDraft: suspend () -> Boolean,
    val liveGrid: LiveGridPreviewBinding? = null,
    val useLiveGridEditor: Boolean = false,
)

const val CLUSTER_LAYOUT_NOTICE = "Automatic layout should work without adjustment. Use the editor only if placement or important content is cut off."

/** Explicit fail-closed boot binding until the parent integrates the real cluster owner.
 * No host/profile/stream is asserted. Geometry editing/preview are unreachable without a model.
 * There is no layout draft in this adapter, so its save acknowledgement writes nothing.
 */
fun unavailableClusterSettingsBindings(): ClusterSettingsBindings {
    fun unavailable(): Nothing = error("Cluster runtime adapter is not integrated")
    return ClusterSettingsBindings(
        status = ClusterSettingsStatus(null, null, "Unavailable — runtime adapter not integrated", null, null),
        layout = null, automaticLayout = null, actualSurface = null, isCustomLayout = false, previewActive = false, previewOwner = Unit,
        onStageLayout = { unavailable() }, onResetLayout = { unavailable() },
        onStartPreview = { _, _ -> unavailable() }, onStopPreview = { unavailable() },
        commitLayoutDraft = { true }, // No model can be edited/staged; no persistence is claimed.
    )
}

@Composable
fun InstrumentClusterSettings(
    selection: ClusterOutputSelection,
    distanceUnits: String,
    bindings: ClusterSettingsBindings,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDisplayChange: (ClusterDisplayMode) -> Unit,
    onSelectionChange: ((ClusterOutputSelection) -> ClusterOutputSelection) -> Unit,
    onDistanceUnitsChange: (String) -> Unit,
    onAdjustLayout: () -> Unit,
    embedded: Boolean = false,
) {
    val pane = if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    val gm = ClusterOutputPolicy.isGmVehicle()
    Column(pane.testTag("instrumentClusterSettings"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Secondary displays", style = MaterialTheme.typography.headlineSmall)
        ClusterSwitch("Driver-display video", selection.mode == ClusterDisplayMode.MAP_VIDEO, enabled,
            { video -> onDisplayChange(if (video) ClusterDisplayMode.MAP_VIDEO else ClusterDisplayMode.NATIVE_ICONS) },
            "clusterDisplay_MAP_VIDEO") {
        Text("Show Android Auto or CarPlay maps on the driver display instead of native navigation icons and HUD guidance.",
            style = MaterialTheme.typography.bodyMedium)
        if (gm) Text("Video disables navigation icons on the driver display and HUD.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gmVideoConsequence"))
        if (selection.mode == ClusterDisplayMode.OFF) Text("Save changes to use navigation icons with video off.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryLegacyOff"))
        if (selection.mode == ClusterDisplayMode.MAP_VIDEO) Text("Connect a display to configure video.",
            style = MaterialTheme.typography.bodyMedium)
        }
        SecondaryGmSetupHelp()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun ClusterSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit,
    tag: String, accessibleName: String = label, supporting: @Composable ColumnScope.() -> Unit = {}) {
    SettingsToggleRow(accessibleName, checked, onChange, enabled, Modifier.testTag(tag)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        supporting()
    }
}

@Composable
internal fun ClusterChoice(label: String, tag: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled,
        role = Role.RadioButton, onClick = onClick).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

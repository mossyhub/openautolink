package com.openautolink.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openautolink.app.cluster.ClusterDisplayMode
import com.openautolink.app.cluster.ClusterOutputPolicy
import com.openautolink.app.cluster.ClusterOutputSelection
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.cluster.PixelSize
import com.openautolink.app.displays.DiscoveredSecondaryDisplay
import com.openautolink.app.secondary.*
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Live observations remain available to diagnostics, not ordinary configuration. */
data class SecondaryDisplaysSource(
    val inventory: StateFlow<SecondaryInventorySnapshot>,
    val rawDisplays: StateFlow<List<DiscoveredSecondaryDisplay>>,
    val statuses: StateFlow<Map<SecondaryOutputId, SecondaryOutputStatus>>?,
)
internal data class SecondaryDisplayRow(
    val rowKey: String, val id: SecondaryOutputId?, val name: String,
    val raw: DiscoveredSecondaryDisplay?, val descriptor: SecondaryDisplayDescriptor?,
    val preference: SecondaryOutputPreference,
) {
    val appHostVirtual: Boolean get() = raw?.name?.let { it.contains("AppHost", true) && it.contains("CarAppService", true) } == true
    val usable: Boolean get() = !appHostVirtual && id != null && raw?.available == true && descriptor != null &&
        descriptor.route != SecondaryRoute.INVENTORY_ONLY && descriptor.reason == SecondaryReason.NONE
}
internal fun secondaryDisplayRows(raw: List<DiscoveredSecondaryDisplay>, inventory: SecondaryInventorySnapshot,
    preferences: Map<SecondaryOutputId, SecondaryOutputPreference>): List<SecondaryDisplayRow> {
    val detected = raw.mapIndexed { index, display ->
        val id = display.key?.let(::SecondaryOutputId)
        SecondaryDisplayRow("detected:${display.displayId}:$index", id, display.name.ifBlank { "Detected display ${display.displayId}" },
            display, inventory.displays.singleOrNull { it.id == id && it.displayId == display.displayId },
            (preferences[id] ?: SecondaryOutputPreference()).withDefaultAaRole(android.os.Build.PRODUCT))
    }
    val connected = detected.mapNotNull { it.id }.toSet()
    return detected + preferences.filterKeys { it !in connected }.map { (id, pref) ->
        SecondaryDisplayRow("saved:${id.value}", id, "Saved display · ${id.value}", null, null, pref)
    }
}

@Composable
fun SecondaryDisplaysSettings(source: SecondaryDisplaysSource, preferences: SecondaryPreferencesSnapshot?, enabled: Boolean,
    onOutputChange: (SecondaryOutputId, (SecondaryOutputPreference) -> SecondaryOutputPreference) -> Unit,
    onAdjustOutputLayout: (SecondaryDisplayDescriptor) -> Unit = {},
    legacyCluster: @Composable () -> Unit = {},
    selection: ClusterOutputSelection = preferences?.legacyCluster?.selection ?: ClusterOutputSelection(),
    onVideoChange: (Boolean) -> Unit = {},
    onTargetSelected: (SecondaryDisplayDescriptor?) -> Unit = {},
    initialTargetId: String? = null,
) {
    val inventory by source.inventory.collectAsStateWithLifecycle()
    val raw by source.rawDisplays.collectAsStateWithLifecycle()
    val rows = secondaryDisplayRows(raw, inventory, preferences?.outputs.orEmpty())
    val candidates = rows.filter { !it.appHostVirtual && it.id != null &&
        it.descriptor?.route in setOf(SecondaryRoute.PUBLIC_ACTIVITY, SecondaryRoute.GM_HOSTED) }
    var selectedId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialTargetId) }
    LaunchedEffect(candidates.map { it.id }, preferences?.revision) {
        if (selectedId == null && preferences != null) selectedId =
            preferences.outputs.filterValues { it.enabled }.keys.singleOrNull()?.value
                ?: candidates.firstOrNull { it.usable }?.id?.value
    }
    // Never silently move a selection when its physical display disappears.
    val selected = candidates.singleOrNull { it.id?.value == selectedId }
    val currentTargetCallback by rememberUpdatedState(onTargetSelected)
    LaunchedEffect(selected?.descriptor, selected?.usable) {
        // Identity observation only: the ViewModel writes no draft or preference here.
        currentTargetCallback(selected?.takeIf { it.usable }?.descriptor)
    }
    val editable = enabled && preferences != null && preferences.error == null
    val video = selection.mode == ClusterDisplayMode.MAP_VIDEO
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("secondaryDisplaysSettings"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Secondary displays", style = MaterialTheme.typography.headlineSmall)
        ClusterSwitch("Driver-display video", video, editable, onVideoChange, "clusterDisplay_MAP_VIDEO") {
            if (ClusterOutputPolicy.isGmVehicle()) Text("Video disables navigation icons on the driver display and HUD.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gmVideoConsequence"))
            if (selection.mode == ClusterDisplayMode.OFF) Text("Save changes to use navigation icons with video off.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryLegacyOff"))
            else Text("Show Android Auto or CarPlay maps on the driver display instead of native navigation icons and HUD guidance.", style = MaterialTheme.typography.bodyMedium)
        }
        SecondaryGmSetupHelp()
        preferences?.error?.let { Text("Display settings could not be read. Reopen Settings to retry.",
            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("secondaryPreferencesError")) }
        if (preferences == null) Text("Display settings unavailable. Reopen Settings to retry.",
            modifier = Modifier.testTag("secondaryPreferencesUnavailable"))
        if (video) {
            if (candidates.size > 1 || selected == null && candidates.isNotEmpty()) {
                Text("Video display", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.selectableGroup()) {
                    candidates.forEach { row ->
                        ClusterChoice(row.name, "secondaryEditorTarget_${row.id?.value}", row.id?.value == selectedId,
                            editable && row.usable) { selectedId = row.id?.value }
                    }
                }
            }
            selected?.let { row ->
                SettingsGroup(Modifier.testTag("secondaryRoleGroup")) {
                    Text("Android Auto display mode", style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.selectableGroup()) {
                        AaSecondaryRole.entries.forEach { role -> ClusterChoice(
                            if (role == AaSecondaryRole.CLUSTER) "Cluster" else "Auxiliary",
                            "secondaryRole_${row.id?.value}_${role.name}", row.preference.aaRole == role,
                            editable && row.usable) { row.id?.let { id -> onOutputChange(id) { it.copy(aaRole = role) } } }
                        }
                    }
                    Text(if (row.preference.aaRole == AaSecondaryRole.AUXILIARY)
                        "Full-screen map with destination details, speed limit and current speed in Google Maps. Recommended for larger driver displays, including most GM EVs."
                        else "Full-screen map with a compact next-turn instruction at the top.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryRoleDescription"))
                    Text("Appearance depends on Android Auto and your navigation app. These descriptions reflect Google Maps; Waze may look the same in both modes. OAL displays the supplied video—its content may change with phone-app updates.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryRoleAppearanceHelp"))
                    val customDensity = row.preference.aaDensityDpi
                    ClusterSwitch("Custom Android Auto density", customDensity != null,
                        editable && row.usable, { custom ->
                            row.id?.let { id -> onOutputChange(id) { current ->
                                current.copy(aaDensityDpi = if (custom)
                                    row.descriptor?.densityDpi?.coerceIn(80, 320) ?: 160 else null)
                            } }
                        }, "secondaryDensityCustom") {
                        Text(if (customDensity == null)
                            "Automatic · ${row.descriptor?.densityDpi ?: 160} DPI" else "$customDensity DPI",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (customDensity != null) {
                        Slider(value = customDensity.toFloat(),
                            onValueChange = { value -> row.id?.let { id -> onOutputChange(id) {
                                it.copy(aaDensityDpi = value.roundToInt().coerceIn(80, 320))
                            } } },
                            valueRange = 80f..320f, enabled = editable && row.usable,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .semantics { contentDescription = "Android Auto secondary density" }
                                .testTag("secondaryDensitySlider"))
                    }
                    Text("Lower DPI makes text and cards smaller. Applies to this Android Auto display after Save and reconnect.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryDensityHelp"))
                    Text("CarPlay has no equivalent Cluster/Auxiliary selector in OAL; its driver-display view is controlled by iOS and the navigation app.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryCarPlayRoleNote"))
                }

            }
            SettingsGroup(Modifier.testTag("secondaryDirectionsGroup")) {
                OutlinedButton(onClick = { selected?.descriptor?.let(onAdjustOutputLayout) },
                    enabled = editable && selected?.usable == true,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("secondaryImportantContentEditor")) {
                    Text("Directions area")
                }
                Text("Usually no adjustment is needed. Edit only if directions or other non-map content are hidden or cut off. The map still fills the whole display.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryDirectionsHelp"))
                if (selected?.usable != true) Text("Connect or choose a display to configure video.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryEditorUnavailable"))
            }

        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun SecondaryGmSetupHelp() {
    if (ClusterOutputPolicy.isGmVehicle()) {
        SettingsGroup(Modifier.testTag("secondaryGmSetupGroup")) {
        Text("GM first setup", style = MaterialTheme.typography.titleMedium)
        Text("After first installing or enabling OAL, reboot the headunit. While parked, hold the steering-wheel call-end button for about 30 seconds, then wait for it to reboot.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryGmSetup"))
        }
    }
}

internal fun secondaryLayoutScope(display: SecondaryDisplayDescriptor): SecondaryLayoutScope? = runCatching {
    SecondaryLayoutScope(display.id, android.os.Build.PRODUCT, android.os.Build.FINGERPRINT, display.width, display.height, display.densityDpi)
}.getOrNull()

/** Same persisted output scope; no synthetic GM identity or video/host ownership. */
@Composable
internal fun SecondaryOutputLayoutEditor(display: SecondaryDisplayDescriptor, live: SecondaryDisplayDescriptor?,
    saved: SecondaryLayoutPreference?, enabled: Boolean, onStage: (SecondaryLayoutPreference?) -> Unit,
    onBack: () -> Unit, projectionIdle: () -> Boolean = { false }, safeOnly: Boolean = false) {
    val context = LocalContext.current
    val scope = remember(display) { secondaryLayoutScope(display) }
    val restored = saved?.takeIf { it.scope == scope }
    val panel = PixelSize(display.width, display.height)
    val full = panel.bounds
    fun pixels(box: SecondaryLayoutBox): PixelRect = PixelRect((box.left * panel.width).roundToInt(),
        (box.top * panel.height).roundToInt(), (box.right * panel.width).roundToInt(), (box.bottom * panel.height).roundToInt())
    val geometry = remember(display) { runCatching { LiveGridGeometry(panel, full,
        if (safeOnly) full else restored?.output?.let(::pixels) ?: full,
        (if (safeOnly && restored?.directSafeAutomatic == true) null else if (safeOnly) restored?.directSafe ?: restored?.safe else restored?.safe)?.let(::pixels)
            ?: if (safeOnly) SecondaryProjectionActivity.directDefaultSafe(panel.width, panel.height) else full) }.getOrElse {
            LiveGridGeometry(panel, full, full, if (safeOnly) SecondaryProjectionActivity.directDefaultSafe(panel.width, panel.height) else full) } }
    val editable = enabled && live == display && scope != null && (display.route == SecondaryRoute.PUBLIC_ACTIVITY || safeOnly && display.route == SecondaryRoute.GM_HOSTED)
    val currentIdle by rememberUpdatedState(projectionIdle)
    val binding = remember(context, display, live, safeOnly) { if (live == display) LiveGridWindows.binding(context, display, directionsArea = safeOnly) { currentIdle() } else null }
    LiveGridEditor(if (safeOnly) "Directions area" else "Layout · ${display.name}", geometry, editable, binding,
        onStage = { next -> if (editable) {
            fun fraction(r: PixelRect) = SecondaryLayoutBox(r.left.toDouble() / panel.width, r.top.toDouble() / panel.height,
                r.right.toDouble() / panel.width, r.bottom.toDouble() / panel.height)
            // Preserve a legacy outer rectangle durably; direct runtime ignores it.
            if (safeOnly) onStage((restored ?: SecondaryLayoutPreference(requireNotNull(scope), fraction(full), fraction(full)))
                .copy(directSafe = fraction(next.safe), directSafeAutomatic = false))
            else onStage(SecondaryLayoutPreference(requireNotNull(scope), fraction(next.output), fraction(next.safe)))
        } }, onReset = { if (editable) {
            if (safeOnly && restored != null) {
                onStage(restored.copy(directSafe = null, directSafeAutomatic = true))
            } else onStage(null)
        } }, onBack = onBack, automatic = LiveGridGeometry(panel, full, full,
            if (safeOnly) SecondaryProjectionActivity.directDefaultSafe(panel.width, panel.height) else full), safeOnly = safeOnly,
        previewUnavailableReason = if (safeOnly && !projectionIdle()) "Disconnect your phone to preview on driver display." else null)
}

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
import com.openautolink.app.cluster.ClusterProjectionProtocol
import com.openautolink.app.cluster.ClusterOutputPolicy
import com.openautolink.app.cluster.ClusterOutputSelection
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.cluster.PixelSize
import com.openautolink.app.displays.DiscoveredSecondaryDisplay
import com.openautolink.app.secondary.*
import com.openautolink.carplaylab.media.CarPlayClusterContent
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
    onAdjustDriveCard: (SecondaryDisplayDescriptor) -> Unit = {},
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
                ClusterSwitch("Drive Card", row.preference.driveCardEnabled, editable && row.usable, { checked ->
                    row.id?.let { id -> onOutputChange(id) { it.copy(driveCardEnabled = checked) } }
                }, "secondaryDriveCard") {
                    Text("Android Auto and CarPlay · Adds efficiency and supported arrival estimates over video.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (row.preference.driveCardEnabled) OutlinedButton(
                    onClick = { row.descriptor?.let(onAdjustDriveCard) },
                    enabled = editable && row.usable,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("secondaryAdjustDriveCard")) {
                    Text("Adjust Drive Card")
                }
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
                    Text("Android Auto only. Changes text and card size on this secondary display after Save and reconnect. This control does not affect CarPlay or the main display.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryDensityProtocolHelp"))
                    val customDensity = row.preference.aaDensityDpi
                    ClusterSwitch("Custom Android Auto density", customDensity != null,
                        editable && row.usable, { custom ->
                            row.id?.let { id -> onOutputChange(id) { current ->
                                current.copy(aaDensityDpi = if (custom)
                                    SecondaryCalibratedDefaults.AA_DENSITY_DPI else null)
                            } }
                        }, "secondaryDensityCustom") {
                        Text(if (customDensity == null)
                            "Automatic · ${SecondaryCalibratedDefaults.AA_DENSITY_DPI} DPI" else "$customDensity DPI",
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
                }
                // GM's hosted cluster channel carries only its qualified 720p H.264 stream.
                if (row.descriptor?.route != SecondaryRoute.GM_HOSTED) SettingsGroup(Modifier.testTag("secondaryAaResolutionGroup")) {
                    Text("Android Auto video resolution", style = MaterialTheme.typography.titleMedium)
                    val display = row.descriptor?.let { PixelSize(it.width, it.height) }
                    val resolution = row.preference.aaResolution ?: AaSecondaryResolution.HD
                    Column(Modifier.selectableGroup()) {
                        AaSecondaryResolution.entries.forEach { choice -> ClusterChoice(aaResolutionLabel(choice, display),
                            "secondaryAaResolution_${row.id?.value}_${choice.name}", resolution == choice,
                            editable && row.usable) { row.id?.let { id -> onOutputChange(id) { it.copy(aaResolution = choice) } } }
                        }
                    }
                    Text((display?.let { "This display is ${it.width} × ${it.height}; the multiplier is how far each size is stretched to fill it. " } ?: "") +
                        "Closest to 1.0x is sharpest. Larger sizes ask more of the phone and the car's video decoder, and below 1.0x add no detail. Text and cards keep their size.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryAaResolutionDescription"))
                    Text("Android Auto only, and it offers only these sizes. Applies after Save and reconnect. If the driver display stutters or stays blank, choose a smaller size.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryAaResolutionHelp"))
                }
                SettingsGroup(Modifier.testTag("secondaryCarPlayContentGroup")) {
                    Text("CarPlay display view", style = MaterialTheme.typography.titleMedium)
                    val content = row.preference.carPlayContent ?: CarPlayClusterContent.DEFAULT
                    Column(Modifier.selectableGroup()) {
                        CarPlayClusterContent.entries.forEach { choice -> ClusterChoice(carPlayContentLabel(choice),
                            "secondaryCarPlayContent_${row.id?.value}_${choice.name}", content == choice,
                            editable && row.usable) { row.id?.let { id -> onOutputChange(id) { it.copy(carPlayContent = choice) } } }
                        }
                    }
                    Text(carPlayContentDescription(content),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryCarPlayContentDescription"))
                    Text("CarPlay only. Applies after Save and reconnect. The turn card appears only during route guidance; iOS and the navigation app decide what it shows.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryCarPlayContentHelp"))
                }

            }
            SettingsGroup(Modifier.testTag("secondaryDirectionsGroup")) {
                OutlinedButton(onClick = { selected?.descriptor?.let(onAdjustOutputLayout) },
                    enabled = editable && selected?.usable == true,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("secondaryImportantContentEditor")) {
                    Text("Directions area")
                }
                Text("Try the Directions area editor. The default may not fit your car’s screen shape or layout.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryDirectionsHelp"))
                if (selected?.usable != true) Text("Connect or choose a display to configure video.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("secondaryEditorUnavailable"))
            }

        }
        Spacer(Modifier.height(16.dp))
    }
}

/** "1440p · H.265 (1.5x)": the multiplier is how far that size is stretched to fill [display]. */
internal fun aaResolutionLabel(resolution: AaSecondaryResolution, display: PixelSize?): String {
    val name = when (resolution) {
        AaSecondaryResolution.HD -> "720p · H.264"
        AaSecondaryResolution.FULL_HD -> "1080p · H.265"
        AaSecondaryResolution.QUAD_HD -> "1440p · H.265"
        AaSecondaryResolution.ULTRA_HD -> "4K · H.265"
    }
    return if (display == null) name else "$name (${"%.1f".format(java.util.Locale.US, resolution.stretchFor(display))}x)"
}

internal fun carPlayContentLabel(content: CarPlayClusterContent): String = when (content) {
    CarPlayClusterContent.MAP_WITH_CARD -> "Apple's cluster view"
    CarPlayClusterContent.MAP -> "Map only"
    CarPlayClusterContent.INSTRUCTION_CARD -> "Turn card only"
}

internal fun carPlayContentDescription(content: CarPlayClusterContent): String = when (content) {
    CarPlayClusterContent.MAP_WITH_CARD -> "Requests Apple's cluster view. iOS and the navigation app choose its layout; a map with a turn card, camera following and 3D are not guaranteed."
    CarPlayClusterContent.MAP -> "Map and route without a turn card."
    CarPlayClusterContent.INSTRUCTION_CARD -> "Only the next-turn card, without the map."
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

/** Read-only context captured when the one shared editor opens; never persisted. */
internal data class DirectionsPreviewContext(
    val protocol: ClusterProjectionProtocol,
    val currentConnection: Boolean,
) {
    val label: String get() = "${if (protocol == ClusterProjectionProtocol.CARPLAY) "CarPlay" else "Android Auto"} · " +
        if (currentConnection) "current connection" else "next connection (USB choice may differ)"
}

/** Same persisted output scope; no synthetic GM identity or video/host ownership. */
@Composable
internal fun SecondaryOutputLayoutEditor(display: SecondaryDisplayDescriptor, live: SecondaryDisplayDescriptor?,
    saved: SecondaryLayoutPreference?, enabled: Boolean, onStage: (SecondaryLayoutPreference?) -> Unit,
    onBack: () -> Unit, projectionIdle: () -> Boolean = { false }, safeOnly: Boolean = false,
    previewContext: DirectionsPreviewContext = DirectionsPreviewContext(ClusterProjectionProtocol.ANDROID_AUTO, false)) {
    val context = LocalContext.current
    val scope = remember(display) { secondaryLayoutScope(display) }
    val restored = saved?.takeIf { it.scope == scope }
    val panel = PixelSize(display.width, display.height)
    val full = panel.bounds
    fun pixels(box: SecondaryLayoutBox): PixelRect = PixelRect((box.left * panel.width).roundToInt(),
        (box.top * panel.height).roundToInt(), (box.right * panel.width).roundToInt(), (box.bottom * panel.height).roundToInt())
    // Settings owns no measured window or decoder receipt. Estimate the full descriptor canvas
    // explicitly, using exactly the runtime's protocol precedence and inward-edge policy.
    // A valid shared custom rectangle is never intersected with this Automatic fallback.
    fun directionsTarget(layout: SecondaryLayoutPreference?) = SecondaryRuntimeLayouts.target(
        display, layout, panel, SecondaryProjectionActivity.directDefaultSafe(panel.width, panel.height),
        android.os.Build.PRODUCT, android.os.Build.FINGERPRINT,
        carPlay = previewContext.protocol == ClusterProjectionProtocol.CARPLAY, safeOnly = true,
        carPlayCodedSize = panel)
    val automaticSafe = remember(display, previewContext, safeOnly) { if (safeOnly) directionsTarget(null).safe else full }
    val geometry = remember(display, previewContext, safeOnly) { runCatching { LiveGridGeometry(panel, full,
        if (safeOnly) full else restored?.output?.let(::pixels) ?: full,
        if (safeOnly) directionsTarget(restored).safe else restored?.safe?.let(::pixels) ?: automaticSafe) }.getOrElse {
            LiveGridGeometry(panel, full, full, automaticSafe) } }
    val editable = enabled && live == display && scope != null && (display.route == SecondaryRoute.PUBLIC_ACTIVITY || safeOnly && display.route == SecondaryRoute.GM_HOSTED)
    val currentIdle by rememberUpdatedState(projectionIdle)
    val binding = remember(context, display, live, safeOnly) { if (live == display) LiveGridWindows.binding(context, display, directionsArea = safeOnly) { currentIdle() } else null }
    key(display, previewContext) {
    LiveGridEditor(if (safeOnly) "Directions area" else "Layout · ${display.name}", geometry, editable, binding,
        onStage = { next -> if (editable) {
            fun fraction(r: PixelRect) = SecondaryLayoutBox(r.left.toDouble() / panel.width, r.top.toDouble() / panel.height,
                r.right.toDouble() / panel.width, r.bottom.toDouble() / panel.height)
            // Preserve a legacy outer rectangle durably; direct runtime ignores it.
            if (safeOnly) onStage((restored ?: SecondaryLayoutPreference(requireNotNull(scope), fraction(full), fraction(full)))
                .copy(aaDirectSafe = null, directSafe = fraction(next.safe), directSafeAutomatic = false))
            else onStage(SecondaryLayoutPreference(requireNotNull(scope), fraction(next.output), fraction(next.safe)))
        } }, onReset = { if (editable) {
            if (safeOnly && restored != null) {
                onStage(restored.copy(aaDirectSafe = null, directSafe = null, directSafeAutomatic = true))
            } else onStage(null)
        } }, onBack = onBack, automatic = LiveGridGeometry(panel, full, full,
            automaticSafe), safeOnly = safeOnly,
        previewUnavailableReason = if (safeOnly && !projectionIdle()) "Disconnect your phone to preview on driver display." else null,
        directionsContextLabel = if (safeOnly) "${previewContext.label}\n" +
            "Edits apply to Android Auto and CarPlay. Automatic uses an estimated area." else null)
    }
}

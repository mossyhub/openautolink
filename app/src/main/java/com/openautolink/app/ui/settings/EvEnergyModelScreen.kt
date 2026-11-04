@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.openautolink.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openautolink.app.data.AppPreferences
import kotlin.math.roundToInt

/**
 * EV energy-model tuning content — rendered inside [SettingsScreen]'s
 * EV tab. See docs/ev-energy-model-tuning-plan.md.
 *
 * The tab does not check whether the connected vehicle is an EV; non-EV
 * vehicles get an empty live-readout card explaining the situation.
 */
@Composable
fun EvEnergyModelTab(
    viewModel: EvEnergyModelViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val readout by viewModel.readout.collectAsStateWithLifecycle()
    val match by viewModel.profileMatch.collectAsStateWithLifecycle()
    val learned by viewModel.learned.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(event) {
        val msg = event ?: return@LaunchedEffect
        snackbarHost.showSnackbar(msg)
        viewModel.consumeEvent()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("evEnergyModelTab"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            ExplanationCard()
            Spacer(Modifier.height(16.dp))
            DetectedVehicleCard(match = match, onApply = viewModel::applyEpaValues)
            Spacer(Modifier.height(16.dp))
            MasterToggleRow(
                enabled = state.tuningEnabled,
                onToggle = viewModel::setTuningEnabled,
            )
            Spacer(Modifier.height(8.dp))
            EpaBaselineRow(
                useEpa = state.useEpaBaseline,
                masterEnabled = state.tuningEnabled,
                hasMatch = match.matched,
                onToggle = viewModel::setUseEpaBaseline,
            )
            Spacer(Modifier.height(16.dp))
            LiveReadoutCard(readout, state.tuningEnabled)
            Spacer(Modifier.height(8.dp))
            SettingsGroup {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Safety and shadow status", fontWeight = FontWeight.SemiBold)
                    Text("Selected: ${state.drivingMode}", style = MaterialTheme.typography.bodyMedium)
                    Text("Effective on Maps wire: existing unlearned model", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Safety hold: learned, manual, multiplier, and profile values are not sent while the external contract is unvalidated.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Shadow learner: always observing valid consumption samples (${learned.lastTickStatus.ifBlank { "awaiting data" }}); navigation is not required.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))

            TuningControls(state, viewModel, enabled = state.tuningEnabled, learned = learned)

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            ProfilesDatabaseCard(
                lastUpdateMs = state.profilesLastUpdateMs,
                url = state.profilesUpdateUrl,
                refreshing = state.refreshing,
                onRefresh = viewModel::refreshProfilesFromNetwork,
            )

            Spacer(Modifier.height(24.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = viewModel::sendNow,
                    modifier = Modifier.testTag("evSendNow"),
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Send now")
                }
                FilledTonalButton(
                    onClick = viewModel::resetToDefaults,
                    modifier = Modifier.testTag("evReset"),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset to defaults")
                }
            }
            Spacer(Modifier.height(48.dp))
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

@Composable
private fun ExplanationCard() {
    SettingsGroup {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "What this does",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text =
                    "Google Maps shows a battery-percent estimate when you arrive at a destination " +
                        "and when you get back home. Behind the scenes, Maps needs to know how much " +
                        "energy your car uses per kilometer.\n\n" +
                        "Native AAOS Maps has a built-in EV profile per vehicle (charge curves, " +
                        "aerodynamics, charge speed). That profile is private — apps cannot read it.\n\n" +
                        "OpenAutoLink builds a simpler model from what your car reports: current " +
                        "battery, total capacity, and the dashboard's range estimate. Range is " +
                        "usually conservative, so Maps may show lower battery-on-arrival than the " +
                        "native experience. Override the values below to nudge it.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MasterToggleRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    SettingsToggleRow(if (enabled) "Customize" else "Use default calculation", enabled, onToggle,
        controlModifier = Modifier.testTag("evMasterToggle")) {
            Text(
                text = if (enabled) "Customize" else "Use default calculation",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (enabled)
                    "Sliders below override the default model."
                else
                    "Driving rate is derived from the dashboard's range estimate.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }

}

@Composable
private fun LiveReadoutCard(r: EvEnergyModelViewModel.LiveReadout, tuningEnabled: Boolean) {
    SettingsGroup {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Live readout",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            if (!r.hasData) {
                Text(
                    text = "Waiting for vehicle data… connect a phone and start the car. " +
                        "Non-EV vehicles never populate this card.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            ReadoutRow("Battery", "${r.batteryWh} Wh / ${r.capacityWh} Wh (${"%.1f".format(r.socPct)}%)")
            ReadoutRow("Range", "${(r.rangeM / 1000f).let { "%.1f km".format(it) }}")
            ReadoutRow("Charging", if (r.chargeW > 0) "${r.chargeW / 1000} kW" else "—")
            ReadoutRow("Derived rate", "%.0f Wh/km".format(r.derivedWhPerKm))
            ReadoutRow(
                label = "Selected preview rate (not sent)",
                value = "%.0f Wh/km".format(r.effectiveWhPerKm),
                emphasized = tuningEnabled && r.effectiveWhPerKm != r.derivedWhPerKm,
            )
        }
    }
}

@Composable
private fun ReadoutRow(label: String, value: String, emphasized: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasized) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun TuningControls(
    state: EvEnergyModelViewModel.UiState,
    vm: EvEnergyModelViewModel,
    enabled: Boolean,
    learned: com.openautolink.app.data.EvLearnedRateEstimator.Snapshot,
) {
    val alpha = if (enabled) 1f else 0.4f
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle("Driving consumption", alpha)
        Spacer(Modifier.height(8.dp))

        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val modes = listOf(
                AppPreferences.EV_DRIVING_MODE_DERIVED to "Derived",
                AppPreferences.EV_DRIVING_MODE_MULTIPLIER to "Multiplier",
                AppPreferences.EV_DRIVING_MODE_MANUAL to "Manual",
                AppPreferences.EV_DRIVING_MODE_LEARNED to "Learned",
            )
            modes.forEachIndexed { i, (mode, label) ->
                androidx.compose.material3.FilterChip(
                    selected = state.drivingMode == mode,
                    onClick = { if (enabled) vm.setDrivingMode(mode) },
                    enabled = enabled,
                    label = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        when (state.drivingMode) {
            AppPreferences.EV_DRIVING_MODE_MANUAL -> SliderRow(
                label = "Manual rate",
                value = state.drivingWhPerKm.toFloat(),
                range = 80f..300f,
                step = 1f,
                format = { "${it.roundToInt()} Wh/km" },
                enabled = enabled,
                onChange = { vm.setDrivingWhPerKm(it.roundToInt()) },
            )
            AppPreferences.EV_DRIVING_MODE_MULTIPLIER -> SliderRow(
                label = "Multiplier",
                value = state.drivingMultiplierPct.toFloat(),
                range = 50f..150f,
                step = 1f,
                format = { "%.2fx".format(it / 100f) },
                enabled = enabled,
                onChange = { vm.setDrivingMultiplierPct(it.roundToInt()) },
            )
            AppPreferences.EV_DRIVING_MODE_LEARNED -> LearnedStatusCard(
                learned = learned,
                enabled = enabled,
                onReset = vm::resetLearnedRate,
            )
            else -> Text(
                text = "Driving rate = (current Wh ÷ remaining range) × 1000.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Other parameters", alpha)
        Spacer(Modifier.height(8.dp))

        SliderRow(
            label = "Auxiliary",
            value = state.auxWhPerKmX10.toFloat(),
            range = 0f..100f,
            step = 1f,
            format = { "%.1f Wh/km".format(it / 10f) },
            enabled = enabled,
            onChange = { vm.setAuxWhPerKmX10(it.roundToInt()) },
        )
        SliderRow(
            label = "Aerodynamic coefficient",
            value = state.aeroCoefX100.toFloat(),
            range = 20f..45f,
            step = 1f,
            format = { "%.2f".format(it / 100f) },
            enabled = enabled,
            onChange = { vm.setAeroCoefX100(it.roundToInt()) },
        )
        SliderRow(
            label = "Reserve",
            value = state.reservePct.toFloat(),
            range = 0f..15f,
            step = 1f,
            format = { "${it.roundToInt()}%" },
            enabled = enabled,
            onChange = { vm.setReservePct(it.roundToInt()) },
        )
        SliderRow(
            label = "Max charge power",
            value = state.maxChargeKw.toFloat(),
            range = 50f..350f,
            step = 5f,
            format = { "${it.roundToInt()} kW" },
            enabled = enabled,
            onChange = { vm.setMaxChargeKw(it.roundToInt()) },
        )
        SliderRow(
            label = "Max discharge power",
            value = state.maxDischargeKw.toFloat(),
            range = 50f..300f,
            step = 5f,
            format = { "${it.roundToInt()} kW" },
            enabled = enabled,
            onChange = { vm.setMaxDischargeKw(it.roundToInt()) },
        )
    }
}

@Composable
private fun SectionTitle(text: String, alpha: Float) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    @Suppress("UNUSED_PARAMETER") step: Float,
    format: (Float) -> String,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = format(value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            steps = ((range.endInclusive - range.start).toInt()).coerceAtLeast(0) - 1,
        )
    }
}

@Composable
private fun DetectedVehicleCard(
    match: EvEnergyModelViewModel.ProfileMatch,
    onApply: () -> Unit,
) {
    val title = if (match.matched) "Detected vehicle (matched)" else "Detected vehicle"
    SettingsGroup {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            val identity = listOfNotNull(match.carMake, match.carModel, match.carYear)
                .joinToString(" ")
                .ifBlank { "Waiting for VHAL identity…" }
            Text(identity, style = MaterialTheme.typography.bodyMedium)
            if (match.matched) {
                if (!match.displayName.isNullOrBlank() && match.displayName != "${match.carMake} ${match.carModel} ${match.carYear}") {
                    Text(
                        "= ${match.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                match.drivingWhPerKm?.let { ReadoutRow("EPA driving rate", "$it Wh/km") }
                match.maxChargeKw?.let { ReadoutRow("DCFC max", "$it kW") }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onApply,
                    modifier = Modifier.testTag("evApplyEpa"),
                ) { Text("Apply these values") }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Not in the bundled profile database. You can still tune manually below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EpaBaselineRow(
    useEpa: Boolean,
    masterEnabled: Boolean,
    hasMatch: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    // Sub-toggle is only meaningful when master tuning is OFF and a profile
    // matched. Otherwise show it disabled with explanatory text.
    val effectivelyActionable = !masterEnabled && hasMatch
    SettingsToggleRow(
        label = "Use EPA values as baseline",
        checked = useEpa,
        onCheckedChange = onToggle,
        enabled = effectivelyActionable,
        controlModifier = Modifier.testTag("evUseEpaBaseline"),
    ) {
            Text(
                "Use EPA values as baseline",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (effectivelyActionable) 1f else 0.5f
                ),
            )
            Text(
                text = when {
                    masterEnabled ->
                        "Ignored while \"Customize\" is on — sliders win."
                    !hasMatch ->
                        "No matching profile bundled. Try refreshing below."
                    useEpa ->
                        "Maps will use the bundled EPA Wh/km and DCFC kW for this vehicle."
                    else ->
                        "Maps will use the dashboard's range-derived Wh/km (default)."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

    }
}

@Composable
private fun LearnedStatusCard(
    learned: com.openautolink.app.data.EvLearnedRateEstimator.Snapshot,
    enabled: Boolean,
    onReset: () -> Unit,
) {
    SettingsGroup {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Learned rate",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            if (learned.usable) {
                Text(
                    "${learned.whPerKm.roundToInt()} Wh/km",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Confidence: %.1f km of valid samples".format(learned.sampleKm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Warming up — falls back to derived rate until ~1.0 km of " +
                        "samples accumulate. So far: %.2f km.".format(learned.sampleKm),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (learned.lastUpdateMs > 0L) {
                Spacer(Modifier.height(4.dp))
                val ago = ((System.currentTimeMillis() - learned.lastUpdateMs) / 60_000L)
                    .coerceAtLeast(0)
                Text(
                    "Last sample: ${if (ago == 0L) "just now" else "${ago} min ago"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Status: ${learned.lastTickStatus.ifBlank { "—" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = onReset,
                enabled = enabled,
                modifier = Modifier.testTag("evLearnedReset"),
            ) { Text("Reset learned rate") }
            Spacer(Modifier.height(6.dp))
            Text(
                "Stored per compatible model/capacity band with estimator revision and energy basis (no VIN). " +
                    "Not learned while charging; stale observations and long gaps are excluded.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProfilesDatabaseCard(
    lastUpdateMs: Long,
    url: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    SettingsGroup {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "EV profile database",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Driving Wh/km and DCFC kW per make/model/year. Bundled with the app " +
                    "and updated only when you tap Refresh.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val lastLabel = if (lastUpdateMs <= 0L) "Never (using bundled)"
                else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(lastUpdateMs))
            ReadoutRow("Last refresh", lastLabel)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = onRefresh,
                    enabled = !refreshing,
                    modifier = Modifier.testTag("evRefreshProfiles"),
                ) { Text(if (refreshing) "Refreshing…" else "Refresh from network") }
                if (refreshing) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Source: $url",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

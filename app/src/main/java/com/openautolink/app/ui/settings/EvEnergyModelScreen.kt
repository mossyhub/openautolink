package com.openautolink.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openautolink.app.data.EvLearnQualification.Reason
import com.openautolink.app.navigation.DistanceFormatter

/** Current vehicle readings and the next-connection CarPlay EV settings. */
@Composable
fun EvEnergyModelTab(
    viewModel: EvEnergyModelViewModel = viewModel(),
    carPlaySettingsViewModel: SettingsViewModel? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val readout by viewModel.readout.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val evaluation by viewModel.learnEvaluationStatus.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().testTag("evEnergyModelTab")
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("EV & routing", style = MaterialTheme.typography.headlineSmall)
        SettingsGroup {
            Text("Vehicle data", style = MaterialTheme.typography.titleMedium)
            val battery = when {
                !readout.batteryAvailable -> "Unavailable"
                readout.capacityWh > 0 -> "%.0f%%".format(readout.socPct)
                else -> "%.1f kWh".format(readout.batteryWh / 1000.0)
            }
            val range = if (readout.rangeAvailable) {
                DistanceFormatter.format(readout.rangeM, unitPref = state.distanceUnits)
            } else "Unavailable"
            Text("Battery: $battery", style = MaterialTheme.typography.bodyMedium)
            Text("Range: $range", style = MaterialTheme.typography.bodyMedium)
        }
        SettingsGroup(Modifier.testTag("evAndroidAutoStatus")) {
            Text("Android Auto", style = MaterialTheme.typography.titleMedium)
            Text(
                if (readout.hasData) "Current battery and range are available to share when connected."
                else "Vehicle data is shared automatically when available.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SettingsGroup(Modifier.testTag("evAppleMapsStatus")) {
            Text("Apple Maps", style = MaterialTheme.typography.titleMedium)
            val settings = carPlaySettingsViewModel
            if (settings == null) {
                Text("Routing settings are unavailable in this view.", style = MaterialTheme.typography.bodyMedium)
            } else {
                val draft by settings.carPlaySettings.collectAsStateWithLifecycle()
                val loaded by settings.carPlaySettingsLoaded.collectAsStateWithLifecycle()
                val saving by settings.carPlaySettingsSaving.collectAsStateWithLifecycle()
                SettingsToggleRow(
                    label = "CarPlay EV data",
                    checked = loaded && draft.evDataEnabled,
                    enabled = loaded && !saving,
                    onCheckedChange = { enabled -> settings.updateCarPlayDraft { it.copy(evDataEnabled = enabled) } },
                    controlModifier = Modifier.testTag("carPlayEvData"),
                ) {
                    Text("CarPlay EV data", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Shares EV data for Apple Maps routing on supported vehicles. Estimates are experimental; check the car’s battery gauge.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (loaded) "Applies after Save changes and reconnect."
                        else "Loading routing settings…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                var advanced by rememberSaveable { mutableStateOf(false) }
                val editable = loaded && !saving
                TextButton(
                    onClick = { advanced = !advanced },
                    enabled = editable,
                    modifier = Modifier.testTag("carPlayEvAdvanced"),
                ) { Text(if (advanced) "Hide advanced" else "Advanced") }
                if (advanced) {
                    Text(
                        "Optional estimate of average power into the battery during DC fast charging, not peak or live power. " +
                            "Leave blank to use OAL’s automatic estimate. Changes apply after Save changes and reconnect.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val error = draft.evAverageDcChargeError()
                    Text(
                        "OAL sends this estimate to Apple Maps. Changing it hasn’t shown a consistent effect on trip estimates and may break EV setup or routing. Future Apple Maps updates may use it differently.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = draft.evAverageDcChargeKw,
                        onValueChange = { raw -> settings.updateCarPlayDraft { it.copy(evAverageDcChargeKw = raw) } },
                        enabled = editable,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("Average DC charging power (kW)") },
                        isError = error != null,
                        supportingText = { if (error != null) Text(error) },
                        modifier = Modifier.fillMaxWidth().testTag("carPlayEvChargeKw"),
                    )
                    TextButton(
                        onClick = { settings.updateCarPlayDraft { it.copy(evAverageDcChargeKw = "") } },
                        enabled = editable && draft.evAverageDcChargeKw.isNotEmpty(),
                        modifier = Modifier.testTag("carPlayEvChargeReset"),
                    ) { Text("Use automatic") }
                }
            }
        }
        SettingsGroup {
            Text("Learn Mode (experimental)", style = MaterialTheme.typography.titleMedium)
            SettingsToggleRow(
                label = "Evaluate Learn Mode",
                checked = state.learnEvaluationEnabled,
                enabled = state.learnEvaluationLoaded,
                onCheckedChange = viewModel::setLearnEvaluationEnabled,
                controlModifier = Modifier.testTag("evLearnEvaluation"),
            ) {
                Text("Evaluate Learn Mode", style = MaterialTheme.typography.bodyLarge)
                Text("Compares estimates on this car. Does not change Maps or upload data.",
                    style = MaterialTheme.typography.bodyMedium)
                if (event == "Could not save Learn Mode.") {
                    Text("Could not save Learn Mode.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (state.learnEvaluationEnabled) {
            val summary = when (evaluation?.reason) {
                null, Reason.OFF, Reason.RETIRED -> "Waiting for valid vehicle data."
                Reason.WARMING_UP -> "Collecting driving data."
                Reason.NO_PRIOR_CANDIDATE -> "No verified learned estimate to compare yet."
                Reason.EVALUATING -> "Comparing the learned estimate with the baseline."
                Reason.NO_GAIN -> "The learned estimate hasn't shown a reliable improvement."
                Reason.QUALIFIED -> "The learned estimate performed better in local comparisons."
                Reason.REGRESSED -> "Evaluation stopped after a large prediction error."
            }
            Text(summary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("evLearnEvaluationStatus"))
        }
        Text(
            "Automatic compact EV contribution is a separate choice in Diagnostics.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

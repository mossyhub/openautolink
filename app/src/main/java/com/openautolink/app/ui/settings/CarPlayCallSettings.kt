package com.openautolink.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** UI contract only; persisted intent and OS authority are supplied by the process controller. */
enum class CarPlayCallHandler { CARPLAY, CAR_BLUETOOTH }
enum class CarPlayCallSetup { LOADING, SETUP_REQUIRED, ENABLE_ACCOUNT, READY, UNAVAILABLE }

data class CarPlayCallUiState(
    val handler: CarPlayCallHandler = CarPlayCallHandler.CARPLAY,
    val extraCarRingtone: Boolean = false,
    val setup: CarPlayCallSetup = CarPlayCallSetup.LOADING,
    val busy: Boolean = false,
    val preferencesLoaded: Boolean = false,
    val appliesToNextCall: Boolean = false,
    val message: String = "",
)

/** No callback defaults: an integrated screen must use actual acknowledged backend actions. */
data class AudioSettingsBindings(
    val calls: CarPlayCallUiState,
    val onCallHandlerChange: (CarPlayCallHandler) -> Unit,
    val onExtraCarRingtoneChange: (Boolean) -> Unit,
    val onSetUpCalls: () -> Unit,
    val onOpenCallSettings: () -> Unit,
    val onOpenSoundSettings: () -> Unit,
    val onOpenVehicleAudio: () -> Unit,
    val actionMessage: String = "",
    /** Cancels only a pending UI setup continuation; never account/call ownership. */
    val onCallSetupHidden: () -> Unit = {},
)

@Composable
internal fun CarPlayCallSettings(bindings: AudioSettingsBindings?) {
    val latestBindings = androidx.compose.runtime.rememberUpdatedState(bindings)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { latestBindings.value?.onCallSetupHidden?.invoke() }
    }
    val state = bindings?.calls ?: CarPlayCallUiState(
        setup = CarPlayCallSetup.UNAVAILABLE, message = "Call settings are unavailable.")
    AudioSectionTitle("CarPlay calls")
    Text("Call handling", style = MaterialTheme.typography.titleMedium)
    val editable = bindings != null && state.preferencesLoaded && !state.busy
    AudioChoice("CarPlay", state.handler == CarPlayCallHandler.CARPLAY,
        "callHandler_CARPLAY", editable) { bindings?.onCallHandlerChange?.invoke(CarPlayCallHandler.CARPLAY) }
    AudioChoice("Car Bluetooth", state.handler == CarPlayCallHandler.CAR_BLUETOOTH,
        "callHandler_CAR_BLUETOOTH", editable) { bindings?.onCallHandlerChange?.invoke(CarPlayCallHandler.CAR_BLUETOOTH) }
    if (state.appliesToNextCall) Text("Applies to the next call", style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("callNextCall"))
    if (state.handler == CarPlayCallHandler.CARPLAY) {
        Text("Keep the iPhone disconnected from car Bluetooth for CarPlay calls.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("carPlayBluetoothPrerequisite"))
        when (state.setup) {
            CarPlayCallSetup.READY -> AudioReadOnlyRow("Setup", "Ready")
            CarPlayCallSetup.LOADING -> Text("Checking call setup…", modifier = Modifier.testTag("callSetupChecking"))
            CarPlayCallSetup.SETUP_REQUIRED, CarPlayCallSetup.ENABLE_ACCOUNT -> {
                if (state.setup == CarPlayCallSetup.ENABLE_ACCOUNT)
                    Text("Turn on OpenAutoLink, then return.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { bindings?.onSetUpCalls?.invoke() }, enabled = editable,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("callSetup")) {
                    Text(if (state.setup == CarPlayCallSetup.ENABLE_ACCOUNT) "Continue to calling accounts" else "Set up calls")
                }
            }
            CarPlayCallSetup.UNAVAILABLE -> Text("Call setup unavailable", modifier = Modifier.testTag("callSetupUnavailable"))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Extra car ringtone", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(state.extraCarRingtone, onCheckedChange = { bindings?.onExtraCarRingtoneChange?.invoke(it) },
                enabled = editable, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("extraCarRingtone"))
        }
    } else {
        Text("Pair the iPhone with the car and enable Phone calls.", style = MaterialTheme.typography.bodyMedium)
    }
    if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("callSettingsMessage"))
    OutlinedButton(onClick = { bindings?.onOpenCallSettings?.invoke() }, enabled = bindings != null,
        modifier = Modifier.heightIn(min = 48.dp).testTag("androidCallSettings")) { Text("Android call settings") }
    OutlinedButton(onClick = { bindings?.onOpenSoundSettings?.invoke() }, enabled = bindings != null,
        modifier = Modifier.heightIn(min = 48.dp).testTag("androidSoundSettings")) { Text("Android sound settings") }
}

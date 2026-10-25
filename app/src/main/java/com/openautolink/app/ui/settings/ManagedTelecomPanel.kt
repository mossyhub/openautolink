package com.openautolink.app.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.openautolink.carplaylab.telecom.ManagedTelecomExperiment
import com.openautolink.carplaylab.telecom.TelecomAccountDiagnostics
import com.openautolink.carplaylab.telecom.TelecomAccountSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Diagnostics only. Selection belongs to the process; pending permission belongs to this route. */
@Composable
internal fun ManagedTelecomPanel() {
    val context = LocalContext.current
    val experiment = remember(context) { ManagedTelecomExperiment.get(context) }
    val scope = rememberCoroutineScope()
    var attached by remember { mutableStateOf(true) }
    var enabled by remember { mutableStateOf(experiment.enabledForProcess) }
    var controls by remember { mutableStateOf(experiment.nativeControlsEnabled) }
    var ringtoneOnly by remember { mutableStateOf(experiment.carPlayRingtoneOnly) }
    var accountReady by remember { mutableStateOf(experiment.armed) }
    var permissionGranted by remember { mutableStateOf(experiment.hasReadPermission) }
    var pendingEnable by remember { mutableStateOf<Long?>(null) }
    // Not cleared by Off: an old OS result must never be associated with a new enable request.
    var permissionInFlight by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableLongStateOf(0) }
    var status by remember { mutableStateOf("Off by default. Reconnect CarPlay after changing controls.") }
    fun cancelPending() {
        pendingEnable?.let(experiment::cancelPendingEnable)
        pendingEnable = null
    }
    fun accountAction(enableGeneration: Long? = null, action: () -> String) {
        val request = ++operation
        busy = true; status = "WORKING"
        scope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            // Enable lifecycle completion is independent of the latest status/display request.
            if (attached && enableGeneration != null && pendingEnable == enableGeneration) {
                pendingEnable = null
                enabled = experiment.enabledForProcess
            }
            if (attached && operation == request) {
                enabled = experiment.enabledForProcess || pendingEnable != null
                controls = experiment.nativeControlsEnabled
                accountReady = experiment.armed
                permissionGranted = experiment.hasReadPermission
                val visibleResult = if (!permissionGranted && result in setOf(
                    ManagedTelecomExperiment.Result.ENABLED_FOR_THIS_PROCESS.name,
                    ManagedTelecomExperiment.Result.REGISTERED_USER_ENABLE_REQUIRED.name,
                    ManagedTelecomExperiment.ControlResult.CONTROLS_ENABLED.name))
                    ManagedTelecomExperiment.Result.READ_PERMISSION_REQUIRED.name else result
                status = "$visibleResult · receiptSaved=${TelecomAccountDiagnostics.persisted}"; busy = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val generation = permissionInFlight
        permissionInFlight = null
        if (attached && generation != null && pendingEnable == generation) {
            permissionGranted = experiment.hasReadPermission
            if (granted && permissionGranted) {
                accountAction(generation) { experiment.enablePrepared(generation).name }
            } else {
                cancelPending(); enabled = experiment.enabledForProcess
                val recorded = experiment.permissionDenied(generation)
                accountAction { "${recorded.get().name} · Android denied phone permission. If it no longer prompts, grant it in Android app permissions, then retry here." }
            }
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(experiment, lifecycle) {
        attached = true
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permissionGranted = experiment.hasReadPermission
                accountReady = experiment.armed
                if (!permissionGranted && experiment.enabledForProcess)
                    status = "READ_PERMISSION_REQUIRED · Account authority UNKNOWN. Grant phone permission explicitly to retry."
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                attached = false; operation++
                cancelPending()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            attached = false; operation++
            // Leaving Diagnostics does not turn off an already-selected process integration.
            cancelPending()
        }
    }
    fun requestEnable() {
        if (permissionInFlight != null) {
            status = "Phone permission request still pending. Retry after it closes."
            return
        }
        val generation = experiment.prepareEnable()
        pendingEnable = generation; enabled = true; controls = false; accountReady = false
        permissionGranted = experiment.hasReadPermission
        if (permissionGranted) accountAction(generation) { experiment.enablePrepared(generation).name }
        else {
            status = "READ_PERMISSION_REQUIRED · Awaiting Android phone permission; account authority UNKNOWN."
            permissionInFlight = generation
            permission.launch(Manifest.permission.READ_PHONE_NUMBERS)
        }
    }
    Column {
        Text("Native CarPlay calls · test")
        Text("Diagnostic · until Off or app restart. Android owns account enablement. Phone-confirmed calls only; cabin microphone reliability is separate.")
        Row {
            Text("Native call integration")
            Switch(checked = enabled, enabled = !busy || pendingEnable != null, onCheckedChange = { value ->
                if (value) requestEnable()
                else {
                    cancelPending(); enabled = false
                    accountAction { experiment.disable().name }
                }
            }, modifier = Modifier.testTag("telecomEnabled"))
        }
        Text("Selection is not account authority. Phone permission permits OAL's own-account readback, not GM or cluster acceptance.")
        if (!permissionGranted) {
            OutlinedButton(enabled = !busy && permissionInFlight == null, onClick = { requestEnable() },
                modifier = Modifier.testTag("telecomGrantPermission")) { Text("Grant phone permission") }
        }
        Row {
            Text("Native Answer/Reject/End")
            Switch(checked = controls, enabled = !busy && (controls || accountReady), onCheckedChange = { value ->
                if (value) accountAction { experiment.enableNativeControls().name }
                else {
                    experiment.disableNativeControls(); controls = false
                    status = "Native Answer/Reject/End off"
                }
            }, modifier = Modifier.testTag("telecomControlsEnabled"))
        }
        Text("Known single incoming calls only; ambiguous calls are withheld. Reconnect to apply. No outgoing, hold or conference controls.")
        Row {
            Text("Use CarPlay ringtone only (experimental)", modifier = Modifier.weight(1f))
            Switch(checked = ringtoneOnly, onCheckedChange = { value ->
                experiment.setCarPlayRingtoneOnly(value); ringtoneOnly = experiment.carPlayRingtoneOnly
            }, modifier = Modifier.testTag("telecomRingtoneOnly"))
        }
        Text("Suppresses extra native ringtone; firmware-dependent. Leave off if alerts disappear.")
        Text("Non-public compatibility experiment, off by default. Applies to new OAL incoming calls until Off or app restart; not a supported platform guarantee.")
        OutlinedButton(onClick = {
            accountAction {
                val result = com.openautolink.carplaylab.telecom.CarPlaySoundSettings.launch(context)
                "${result.name} · Sound page usability unverified. System sound changes may affect all phone accounts; OAL changes nothing automatically."
            }
        }, modifier = Modifier.testTag("telecomSoundSettings")) { Text("Android sound settings") }
        OutlinedButton(onClick = {
            accountAction {
                val result = experiment.openSettings()
                if (result == TelecomAccountSettings.Launch.EXACT_CANDIDATE_LAUNCHED || result == TelecomAccountSettings.Launch.PUBLIC_ACTION_CANDIDATE_LAUNCHED)
                    "${result.name} · Launch request returned; page usability and OAL account availability are unverified. Return here to refresh."
                else "${result.name} · Account settings unavailable; no account enablement confirmed."
            }
        }, modifier = Modifier.testTag("telecomEnableSettings")) { Text("Android call accounts") }
        OutlinedButton(enabled = !busy, onClick = { accountAction { experiment.refresh().name } },
            modifier = Modifier.testTag("telecomReadback")) { Text("Refresh account status") }
        OutlinedButton(enabled = !busy, onClick = {
            cancelPending(); enabled = false
            accountAction { experiment.disable().name }
        }, modifier = Modifier.testTag("telecomCleanup")) { Text("Remove OAL account") }
        OutlinedButton(enabled = !busy, onClick = { accountAction { experiment.census().toString() } },
            modifier = Modifier.testTag("telecomInspect")) { Text("Inspect native call support") }
        Text(status, modifier = Modifier.testTag("telecomStatus"))
    }
}

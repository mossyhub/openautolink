package com.openautolink.app.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openautolink.app.carplay.CarPlayCallController
import com.openautolink.app.data.CarPlayCallPreferences
import com.openautolink.app.media.OalMediaHostLauncher
import com.openautolink.carplaylab.telecom.HfpCallGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Pure UI projection: saved intent, Android approval and HFP admission are separate facts. */
internal fun productionCallUi(state: CarPlayCallController.State, busy: Boolean, message: String): CarPlayCallUiState {
    val preferences = state.preferences
    val handler = when (preferences?.handler) {
        CarPlayCallPreferences.Handler.CAR_BLUETOOTH -> CarPlayCallHandler.CAR_BLUETOOTH
        else -> CarPlayCallHandler.CARPLAY
    }
    val setup = when (state.setup) {
        CarPlayCallController.Setup.LOADING -> CarPlayCallSetup.LOADING
        CarPlayCallController.Setup.PERMISSION_NEEDED -> CarPlayCallSetup.SETUP_REQUIRED
        CarPlayCallController.Setup.ACCOUNT_DISABLED -> CarPlayCallSetup.ENABLE_ACCOUNT
        CarPlayCallController.Setup.UNAVAILABLE -> CarPlayCallSetup.UNAVAILABLE
        CarPlayCallController.Setup.READY -> if (state.hfp != HfpCallGuard.Status.HFP_OWNED)
            CarPlayCallSetup.READY else CarPlayCallSetup.UNAVAILABLE
    }
    val admissionMessage = if (handler == CarPlayCallHandler.CARPLAY && state.setup == CarPlayCallController.Setup.READY) {
        when (state.hfp) {
            HfpCallGuard.Status.HFP_OWNED -> "Disconnect iPhone Bluetooth for CarPlay calls, or choose Car Bluetooth."
            HfpCallGuard.Status.UNKNOWN -> "" // explicit prerequisite is always visible in CarPlayCallSettings
            HfpCallGuard.Status.KNOWN_DISCONNECTED -> ""
        }
    } else ""
    return CarPlayCallUiState(handler = handler, extraCarRingtone = preferences?.extraRingtone ?: false,
        setup = setup, busy = busy, preferencesLoaded = preferences != null,
        appliesToNextCall = state.appliesToNextCall, message = listOf(admissionMessage, message).filter { it.isNotBlank() }.distinct().joinToString("\n"))
}

private fun Context.audioActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.audioActivity() else null
    else -> null
}

/** One owner above both Settings surfaces. Composition/resume only refresh; setup is explicit. */
@Composable
fun rememberProductionAudioBindings(onReconnectCarPlay: () -> Unit = {}): AudioSettingsBindings {
    val context = LocalContext.current
    val application = context.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember(application) { CarPlayCallController.get(application) }
    val state by controller.state.collectAsStateWithLifecycle()
    val mic by com.openautolink.app.carplay.CarPlaySessionLeases.microphoneState.collectAsStateWithLifecycle()
    val currentMic by rememberUpdatedState(mic)
    val currentState by rememberUpdatedState(state)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var actionMessage by remember { mutableStateOf("") }
    var selectionGeneration by remember { mutableLongStateOf(0) }
    // Match the restored ActivityResult launcher lifetime, not the old UI continuation.
    var phonePermissionPending by rememberSaveable { mutableStateOf(false) }
    var pendingPermission by remember { mutableStateOf<Long?>(null) }
    var microphoneMessage by remember { mutableStateOf("") }
    var micPermissionPending by rememberSaveable { mutableStateOf(false) }
    var micPermissionGeneration by remember { mutableLongStateOf(0) }
    var requestedMicGeneration by remember { mutableStateOf<Long?>(null) }
    fun visible() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        context.audioActivity()?.let { !it.isFinishing && !it.isDestroyed } == true
    fun stillSelected(generation: Long) = generation == selectionGeneration &&
        currentState.preferences?.handler == CarPlayCallPreferences.Handler.CARPLAY

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val generation = requestedMicGeneration
        requestedMicGeneration = null; micPermissionPending = false
        if (generation == micPermissionGeneration) {
            com.openautolink.app.carplay.CarPlaySessionLeases.reconcile(application)
            microphoneMessage = if (granted && currentMic.inputAdvertised == false)
                "Reconnect CarPlay when not in a call to enable microphone input."
            else if (!granted) "Microphone permission is needed for calls and Siri." else ""
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val generation = pendingPermission
        phonePermissionPending = false // Consume the OS slot even when its continuation expired.
        pendingPermission = null
        if (generation == null || !stillSelected(generation)) { busy = false }
        else scope.launch {
            try {
                val refreshed = controller.prepare()
                if (!granted) message = "Phone permission is needed for car call controls."
                else if (stillSelected(generation) && visible() && refreshed.setup == CarPlayCallController.Setup.ACCOUNT_DISABLED) {
                    if (!controller.openAccountSettings()) message = "Android call settings are unavailable."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { message = "Call setup could not be completed. Try again." }
            finally { busy = false }
        }
    }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch {
                microphoneMessage = ""
                try { controller.prepare() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Call setup could not be checked." }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            selectionGeneration++
            micPermissionGeneration++
            pendingPermission = null
            requestedMicGeneration = null
        }
    }
    return AudioSettingsBindings(
        calls = productionCallUi(state, busy, message),
        onCallHandlerChange = { value ->
            selectionGeneration++ // old permission callback cannot select/launch for a newer choice
            message = ""
            scope.launch {
                busy = true
                try {
                    controller.setHandler(when (value) {
                        CarPlayCallHandler.CARPLAY -> CarPlayCallPreferences.Handler.CARPLAY
                        CarPlayCallHandler.CAR_BLUETOOTH -> CarPlayCallPreferences.Handler.CAR_BLUETOOTH
                    })
                    controller.prepare()
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { message = "Call setting was not saved. Try again." }
                finally { busy = false }
            }
        },
        onExtraCarRingtoneChange = { value -> scope.launch {
            busy = true; message = ""
            try { controller.setExtraRingtone(value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Ringtone setting was not saved. Try again." }
            finally { busy = false }
        } },
        onSetUpCalls = {
            if (!busy && !phonePermissionPending && visible() &&
                currentState.preferences?.handler == CarPlayCallPreferences.Handler.CARPLAY) {
                val generation = ++selectionGeneration
                busy = true; message = ""
                scope.launch {
                    try {
                        val prepared = controller.setup()
                        if (stillSelected(generation) && visible()) {
                            when (prepared.setup) {
                                CarPlayCallController.Setup.PERMISSION_NEEDED -> {
                                    if (application.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
                                        pendingPermission = generation
                                        phonePermissionPending = true
                                        permission.launch(Manifest.permission.READ_PHONE_NUMBERS)
                                    } else controller.prepare()
                                }
                                CarPlayCallController.Setup.ACCOUNT_DISABLED ->
                                    if (!controller.openAccountSettings()) message = "Android call settings are unavailable."
                                else -> Unit
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { message = "Call setup could not be completed. Try again." }
                    finally { if (!phonePermissionPending) busy = false }
                }
            }
        },
        onOpenCallSettings = { scope.launch {
            try { if (!controller.openAccountSettings()) actionMessage = "Android call settings are unavailable." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { actionMessage = "Android call settings could not be opened." }
        } },
        onOpenSoundSettings = { scope.launch {
            try { if (!controller.openSoundSettings()) actionMessage = "Android sound settings are unavailable." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { actionMessage = "Android sound settings could not be opened." }
        } },
        onOpenVehicleAudio = {
            actionMessage = when (OalMediaHostLauncher.launch(context)) {
                OalMediaHostLauncher.Result.REQUESTED -> "Vehicle Audio requested."
                OalMediaHostLauncher.Result.UNAVAILABLE -> "Vehicle Audio is unavailable."
                OalMediaHostLauncher.Result.DENIED -> "Vehicle Audio could not be opened."
            }
        },
        microphoneMessage = microphoneMessage.ifBlank {
            when {
                mic.retiring -> "Stopping microphone capture before reconnecting CarPlay."
                !mic.permissionGranted -> "Allow microphone access for CarPlay calls and Siri."
                mic.reconnectRequired || mic.inputAdvertised == false -> "Reconnect CarPlay when not in a call to enable microphone input."
                mic.baseReady && !mic.micReady -> "Open OpenAutoLink to enable background microphone access."
                else -> ""
            }
        },
        microphoneAction = when {
            micPermissionPending || mic.retiring -> null
            !mic.permissionGranted -> "Enable microphone"
            mic.reconnectRequired || mic.inputAdvertised == false -> "Reconnect CarPlay"
            mic.baseReady && !mic.micReady -> "Enable microphone"
            else -> null
        },
        onMicrophoneAction = {
            if (visible() && !micPermissionPending && !currentMic.retiring) {
                if (currentMic.permissionGranted && (currentMic.reconnectRequired || currentMic.inputAdvertised == false)) {
                    if (com.openautolink.carplaylab.telecom.ManagedTelecomExperiment.get(application).hasAdmittedCall)
                        microphoneMessage = "Reconnect after the current call ends."
                    else onReconnectCarPlay()
                } else {
                    com.openautolink.app.carplay.CarPlaySessionLeases.requestMicrophone(application) {
                        if (!micPermissionPending) {
                            micPermissionPending = true
                            requestedMicGeneration = ++micPermissionGeneration
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            }
        },
        actionMessage = actionMessage,
        onCallSetupHidden = {
            selectionGeneration++
            // Keep the OS request slot until its result arrives; it must not adopt a successor.
            busy = phonePermissionPending
        },
    )
}

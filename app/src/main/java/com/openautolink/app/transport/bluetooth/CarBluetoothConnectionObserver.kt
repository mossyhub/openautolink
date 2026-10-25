package com.openautolink.app.transport.bluetooth

import android.Manifest
import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Ordinary-permission, receive-only GM profile evidence. Never selects a phone or drives WPP.
 * No profile proxy, bonded list, ACL ownership, discovery, hidden API, or connection probe.
 * The only public state contains process-local opaque IDs; BluetoothDevice keys stay private.
 * Main-thread confined (process owner uses Dispatchers.Main.immediate).
 * observation is local in-memory diagnostics only; there are no device/exception log calls.
 * Broadcasts are an incomplete inventory, not an OEM-selected-phone API. No source sequence
 * or power-generation tag exists in these GM payloads; receiver generations fence retired
 * callbacks, but cannot identify every delayed broadcast within a live registration.
 */
class CarBluetoothConnectionObserver internal constructor(
    private val application: Application,
    private val enabled: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val recorderEvidence: ((String, String, String, Int) -> Unit)? = null,
    private val recorderDeviceLimit: Int = CarBluetoothProfileState.MAX_DEVICES,
) {
    internal companion object {
        // Public SDK does not expose these vehicle-side profile classes. Literal actions
        // are verified in GM HeadsetClientStateMachine:1865 and A2dpSinkStateMachine:275.
        const val HFP_CLIENT_ACTION = "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
        const val A2DP_SINK_ACTION = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
        // Source-verified vehicle-side actions. AG_EVENT includes unrelated fields:
        // only an explicitly present VOICE_RECOGNITION extra is a speech receipt.
        const val HFP_AUDIO_ACTION = "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED"
        const val HFP_AG_ACTION = "android.bluetooth.headsetclient.profile.action.AG_EVENT"
        const val HFP_VOICE_EXTRA = "android.bluetooth.headsetclient.extra.VOICE_RECOGNITION"
    }

    private val evidenceLock = Any()
    private val evidenceListeners = mutableSetOf<(String) -> Unit>()

    /** Exact-link, passive diagnostic subscription; never provides selected-phone identity. */
    fun subscribeEvidence(listener: (String) -> Unit): AutoCloseable {
        synchronized(evidenceLock) {
            check(evidenceListeners.size < 4) { "Bluetooth evidence subscription capacity" }
            evidenceListeners.add(listener)
        }
        return AutoCloseable { synchronized(evidenceLock) { evidenceListeners.remove(listener) } }
    }

    private fun evidence(event: String, state: String, previous: String = "unknown", device: Int = 0) {
        val listeners = synchronized(evidenceLock) { evidenceListeners.toList() }
        val receipt = "carplay-bt event=$event state=$state previous=$previous scope=device owner=unknown"
        listeners.forEach { listener ->
            try { listener(receipt) } catch (_: RuntimeException) { }
        }
        try { recorderEvidence?.invoke(event, state, previous, device) } catch (_: RuntimeException) { }
    }

    private val devices = mutableMapOf<BluetoothDevice, String>()
    private var nextDeviceId = 0L
    private var receiver: BroadcastReceiver? = null
    private var activityCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var collector: Job? = null
    private var closed = false
    private val mutableObservation = MutableStateFlow(CarBluetoothProfileState())
    val observation = mutableObservation.asStateFlow()
    private val session = CarBluetoothObservationSession(object : CarBluetoothObservationEffects {
        override fun availability(): CarBluetoothUnknown? {
            if (application.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return CarBluetoothUnknown.PERMISSION_MISSING
            }
            return if (application.getSystemService(BluetoothManager::class.java)?.adapter == null)
                CarBluetoothUnknown.UNAVAILABLE else null
        }

        override fun register(generation: Long) {
            val callback = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) { receive(intent, generation) }
            }
            receiver = callback
            val filter = IntentFilter().apply {
                addAction(HFP_CLIENT_ACTION)
                addAction(A2DP_SINK_ACTION)
                addAction(HFP_AUDIO_ACTION)
                addAction(HFP_AG_ACTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            // Bluetooth is a separate privileged UID, not the system UID: NOT_EXPORTED
            // can drop its broadcasts on API 33+. Both GM profile senders require receiver
            // BLUETOOTH_CONNECT, and the GM Bluetooth manifest also holds it, permitting
            // this ordinary sender-permission filter (not BLUETOOTH_PRIVILEGED).
            // CONNECT is not sender authentication; do not use these events for admission.
            if (sdkInt >= 33) {
                application.registerReceiver(callback, filter, Manifest.permission.BLUETOOTH_CONNECT,
                    null, Context.RECEIVER_EXPORTED)
            } else {
                application.registerReceiver(callback, filter, Manifest.permission.BLUETOOTH_CONNECT, null)
            }
            val lifecycle = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) { checkPermission(generation) }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            activityCallbacks = lifecycle
            application.registerActivityLifecycleCallbacks(lifecycle)
        }

        override fun unregister() {
            val old = receiver
            receiver = null
            val lifecycle = activityCallbacks
            activityCallbacks = null
            try { if (old != null) application.unregisterReceiver(old) }
            finally { if (lifecycle != null) application.unregisterActivityLifecycleCallbacks(lifecycle) }
        }
        override fun forgetDevices() {
            devices.clear(); nextDeviceId = 0
            recorderEvidence?.invoke("epoch", "unknown", "unknown", 0)
        }
    })

    internal fun start() {
        if (closed || collector != null) return
        collector = scope.launch {
            var failed = false
            try {
                enabled.distinctUntilChanged().collect {
                    session.setEnabled(it)
                    publish()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                failed = true
                session.invalidate(CarBluetoothUnknown.UNAVAILABLE)
            } finally {
                if (!failed) session.close()
                publish()
            }
        }
    }

    private fun checkPermission(generation: Long): Boolean {
        if (!session.state.enabled || generation != session.generation) return false
        if (application.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) return true
        session.invalidate(CarBluetoothUnknown.PERMISSION_MISSING)
        publish()
        return false
    }

    @Suppress("DEPRECATION")
    private fun receive(intent: Intent?, generation: Long) {
        if (intent == null || !checkPermission(generation)) return
        try {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val on = when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_ON -> true
                        BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_TURNING_ON -> false
                        else -> null
                    }
                    session.power(on, generation)
                    val powerState = when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_ON -> "on"
                        BluetoothAdapter.STATE_OFF -> "off"
                        BluetoothAdapter.STATE_TURNING_ON -> "turning-on"
                        BluetoothAdapter.STATE_TURNING_OFF -> "turning-off"
                        else -> "unknown"
                    }
                    evidence("adapter", powerState)
                }
                HFP_AUDIO_ACTION -> {
                    if (session.state.adapterOn == false) return
                    fun audioState(value: Int) = when (value) {
                        0 -> "disconnected"
                        1 -> "connecting"
                        2 -> "connected"
                        else -> "unknown"
                    }
                    evidence("hfp-audio", audioState(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)),
                        audioState(intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)), deviceOrdinal(intent))
                }
                HFP_AG_ACTION -> {
                    if (session.state.adapterOn == false || !intent.hasExtra(HFP_VOICE_EXTRA)) return
                    val voiceState = when (intent.getIntExtra(HFP_VOICE_EXTRA, -1)) {
                        0 -> "stopped"
                        1 -> "started"
                        else -> "unknown"
                    }
                    evidence("hfp-voice", voiceState, device = deviceOrdinal(intent))
                }
                HFP_CLIENT_ACTION, A2DP_SINK_ACTION -> {
                    if (session.state.adapterOn == false || session.state.unknown == CarBluetoothUnknown.CAPACITY) return
                    // sdkInt is an injected SDK seam; production uses Build.VERSION.SDK_INT.
                    @android.annotation.SuppressLint("NewApi")
                    val device = if (sdkInt >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device == null) {
                        session.unknown(CarBluetoothUnknown.MALFORMED_EVENT, generation)
                        publish()
                        return
                    }
                    if (device !in devices && devices.size >= recorderDeviceLimit) {
                        session.unknown(CarBluetoothUnknown.CAPACITY, generation)
                        publish()
                        return
                    }
                    val id = devices.getOrPut(device) { "local-${++nextDeviceId}" }
                    val profile = if (intent.action == HFP_CLIENT_ACTION) CarBluetoothProfile.HFP_CLIENT else CarBluetoothProfile.A2DP_SINK
                    val current = connection(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1))
                    val previous = connection(intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1))
                    session.profile(id, profile, current, previous, generation)
                    evidence(if (profile == CarBluetoothProfile.HFP_CLIENT) "hfp-client" else "a2dp-sink",
                        current.name.lowercase(), previous.name.lowercase(), id.substringAfter("local-").toIntOrNull() ?: 0)
                }
                else -> return
            }
        } catch (_: SecurityException) {
            session.invalidate(CarBluetoothUnknown.PERMISSION_MISSING)
        } catch (_: RuntimeException) {
            // Intent payloads may be unavailable or malformed. Never print exceptions/devices.
            session.unknown(CarBluetoothUnknown.MALFORMED_EVENT, generation)
        }
        publish()
    }

    private fun connection(state: Int): CarBluetoothProfileConnection = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> CarBluetoothProfileConnection.DISCONNECTED
        BluetoothProfile.STATE_CONNECTING -> CarBluetoothProfileConnection.CONNECTING
        BluetoothProfile.STATE_CONNECTED -> CarBluetoothProfileConnection.CONNECTED
        BluetoothProfile.STATE_DISCONNECTING -> CarBluetoothProfileConnection.DISCONNECTING
        else -> CarBluetoothProfileConnection.UNKNOWN
    }

    @Suppress("DEPRECATION")
    private fun deviceOrdinal(intent: Intent): Int {
        // Only standard BluetoothDevice equality; never inspect its name/address.
        if (recorderEvidence == null || session.state.unknown == CarBluetoothUnknown.CAPACITY) return 0
        // sdkInt is an injected SDK seam; production uses Build.VERSION.SDK_INT.
        @android.annotation.SuppressLint("NewApi")
        val device = if (sdkInt >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        if (device == null) return 0
        if (device !in devices && devices.size >= recorderDeviceLimit) return 0
        return devices.getOrPut(device) { "local-${++nextDeviceId}" }.substringAfter("local-").toIntOrNull() ?: 0
    }

    private fun publish() {
        mutableObservation.value = session.state
        val reason = session.state.unknown
        val coverage = when (reason) {
            CarBluetoothUnknown.PERMISSION_MISSING -> "permission_missing"
            CarBluetoothUnknown.UNAVAILABLE -> "unavailable"
            CarBluetoothUnknown.MALFORMED_EVENT -> "malformed_event"
            CarBluetoothUnknown.CAPACITY -> "capacity"
            CarBluetoothUnknown.DISABLED -> "disabled"
            else -> "registered"
        }
        recorderEvidence?.invoke("coverage", coverage, "unknown", 0)
    }

    fun close() {
        closed = true
        collector?.cancel()
        collector = null
        session.close()
        publish()
    }
}

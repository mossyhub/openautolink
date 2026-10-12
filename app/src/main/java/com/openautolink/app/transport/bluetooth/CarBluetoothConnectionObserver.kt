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
) {
    internal companion object {
        // Public SDK does not expose these vehicle-side profile classes. Literal actions
        // are verified in GM HeadsetClientStateMachine:1865 and A2dpSinkStateMachine:275.
        const val HFP_CLIENT_ACTION = "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
        const val A2DP_SINK_ACTION = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
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
        override fun forgetDevices() { devices.clear() }
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
                    if (device !in devices && devices.size >= CarBluetoothProfileState.MAX_DEVICES) {
                        session.unknown(CarBluetoothUnknown.CAPACITY, generation)
                        publish()
                        return
                    }
                    val id = devices.getOrPut(device) { "local-${++nextDeviceId}" }
                    val profile = if (intent.action == HFP_CLIENT_ACTION) CarBluetoothProfile.HFP_CLIENT else CarBluetoothProfile.A2DP_SINK
                    session.profile(id, profile, connection(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)),
                        connection(intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)), generation)
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

    private fun publish() { mutableObservation.value = session.state }

    fun close() {
        closed = true
        collector?.cancel()
        collector = null
        session.close()
        publish()
    }
}

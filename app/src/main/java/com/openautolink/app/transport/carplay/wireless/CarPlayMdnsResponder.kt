package com.openautolink.app.transport.carplay.wireless

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "CarPlayMdns"

/**
 * Registers an mDNS service advertising this device as a CarPlay accessory.
 *
 * Service type: `_carplay._tcp.` — the well-known type used by Apple's
 * MFi spec for the wireless CarPlay data channel. The TXT record carries
 * minimal accessory identity (matching what Carlinkit-style dongles
 * advertise) so the iPhone — *if* it ever initiates discovery on its own —
 * can find us.
 *
 * **Note on wireless CarPlay discovery**: In production, the iPhone does
 * NOT discover CarPlay accessories via mDNS alone. It requires a prior BT
 * iAP2 handshake which tells it both the WiFi credentials and the target
 * IP. We register this service primarily so:
 *   1. We can see the responder is alive on the same subnet as the iPhone.
 *   2. If our CPC200 broker later announces our IP to the iPhone via the
 *      iAP2 `StartCarPlay.setIPAddress` path, having the matching mDNS
 *      record up is harmless and may help debugging.
 *
 * This class is **idempotent** — calling [register] twice is a no-op if
 * the service is already registered with the same parameters.
 */
class CarPlayMdnsResponder(
    context: Context,
    private val serviceName: String = "OpenAutoLink",
    private val port: Int = 5000,
) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var listener: NsdManager.RegistrationListener? = null

    sealed class State {
        object Idle : State()
        object Registering : State()
        data class Registered(val name: String, val port: Int) : State()
        data class Failed(val errorCode: Int) : State()
    }

    fun register() {
        if (_state.value is State.Registered || _state.value == State.Registering) {
            OalLog.i(TAG, "register() called but already in state=${_state.value}")
            return
        }

        val info = NsdServiceInfo().apply {
            this.serviceName = this@CarPlayMdnsResponder.serviceName
            serviceType = "_carplay._tcp."
            this.port = this@CarPlayMdnsResponder.port
            // TXT record — values mirror what an Apple-certified CarPlay
            // accessory exposes. These are best-effort guesses based on
            // mDNS captures of real dongles; the real iPhone discovery
            // path is BT+iAP2, so wrong values here are not fatal.
            setAttribute("deviceid", "00:E0:4C:00:00:01") // placeholder MAC
            setAttribute("model",    "OpenAutoLink,1")
            setAttribute("features", "0x44") // CarPlay-capable
            setAttribute("srcvers",  "1.0.0")
        }

        _state.value = State.Registering
        listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                OalLog.i(TAG, "registered: ${serviceInfo.serviceName} on :${serviceInfo.port}")
                _state.value = State.Registered(
                    name = serviceInfo.serviceName ?: serviceName,
                    port = serviceInfo.port,
                )
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                OalLog.e(TAG, "registration failed (errorCode=$errorCode) for ${serviceInfo.serviceName}")
                _state.value = State.Failed(errorCode)
                listener = null
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                OalLog.i(TAG, "unregistered: ${serviceInfo.serviceName}")
                _state.value = State.Idle
                listener = null
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                OalLog.e(TAG, "unregistration failed (errorCode=$errorCode)")
                _state.value = State.Idle
                listener = null
            }
        }

        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            OalLog.e(TAG, "registerService threw: ${e.message}")
            _state.value = State.Failed(-1)
            listener = null
        }
    }

    fun unregister() {
        listener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                OalLog.e(TAG, "unregisterService threw: ${e.message}")
                _state.value = State.Idle
                listener = null
            }
        }
    }
}

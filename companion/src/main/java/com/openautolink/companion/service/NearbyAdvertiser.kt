package com.openautolink.companion.service

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import com.openautolink.companion.connection.AaProxy
import com.openautolink.companion.connection.NearbySocket
import com.openautolink.companion.trigger.TransparentTriggerActivity
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Google Nearby Connections advertiser for the companion app.
 *
 * The phone acts as an ADVERTISER — it passively waits for the car
 * (running the OAL AAOS app) to discover it. On connection, both sides
 * exchange STREAM payloads to create a bidirectional pipe. Android Auto
 * on the phone connects to a localhost AaProxy which tunnels through
 * the Nearby stream to the car.
 */
class NearbyAdvertiser(
    private val context: Context,
    private val scope: CoroutineScope,
    private val stateListener: StateListener,
) {
    interface StateListener {
        fun onConnecting()
        fun onProxyConnected()
        fun onProxyDisconnected()
        fun onLaunchTimeout()
    }

    private val connectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private var activeSocket: NearbySocket? = null
    private var activePipes: Array<ParcelFileDescriptor>? = null
    private var activeProxy: AaProxy? = null

    @Volatile
    private var isLaunching = false

    fun start() {
        Log.i(TAG, "Starting Nearby advertising...")
        startAdvertising()
    }

    fun stop() {
        Log.i(TAG, "Stopping Nearby advertising")
        connectionsClient.stopAdvertising()
        connectionsClient.stopAllEndpoints()
        cleanup()
    }

    private fun cleanup() {
        activeProxy?.stop()
        activeProxy = null
        activeSocket = null
        activePipes?.forEach { runCatching { it.close() } }
        activePipes = null
        isLaunching = false
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()

        val endpointName = resolveEndpointName()
        Log.i(TAG, "Advertising as \"$endpointName\" (service=$SERVICE_ID)")

        connectionsClient.startAdvertising(endpointName, LEGACY_SERVICE_ID, connectionCallback, options)
            .addOnSuccessListener { Log.i(TAG, "Advertising started (service=$LEGACY_SERVICE_ID)") }
            .addOnFailureListener { e -> Log.e(TAG, "Advertising failed: ${e.message}") }
    }

    private fun resolveEndpointName(): String {
        val deviceName = Settings.Global.getString(context.contentResolver, "device_name")
        return if (deviceName.isNullOrBlank() || deviceName == android.os.Build.MODEL) {
            val androidId = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ANDROID_ID
            )
            "${android.os.Build.MODEL} (${androidId?.take(4) ?: "????"})"
        } else {
            deviceName
        }
    }

    // ── Connection lifecycle ───────────────────────────────────────────

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.i(TAG, "Connection initiated from ${info.endpointName} ($endpointId), accepting")
            stateListener.onConnecting()
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                Log.w(TAG, "Connection failed: ${result.status.statusMessage}")
                return
            }

            Log.i(TAG, "Connected to car ($endpointId). Building tunnel...")
            connectionsClient.stopAdvertising()

            scope.launch {
                delay(500) // Let Nearby transport stabilize

                val socket = NearbySocket()
                activeSocket = socket

                // Create outgoing pipe (phone → car) and send immediately
                val pipes = ParcelFileDescriptor.createPipe()
                activePipes = pipes
                socket.outputStreamWrapper =
                    ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
                val outPayload = Payload.fromStream(pipes[0])

                Log.i(TAG, "Sending phone→car stream payload")
                connectionsClient.sendPayload(endpointId, outPayload)
                    .addOnSuccessListener { Log.i(TAG, "Stream payload registered") }
                    .addOnFailureListener { e -> Log.e(TAG, "Stream send failed: ${e.message}") }

                // Start proxy + launch AA. NearbySocket.getInputStream() blocks
                // until the car's stream arrives via onPayloadReceived.
                launchAndroidAuto(socket)
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.i(TAG, "Disconnected from $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            Log.i(TAG, "Payload received type=${payload.type}")
            if (payload.type == Payload.Type.STREAM) {
                val stream = payload.asStream()?.asInputStream()
                if (stream != null) {
                    activeSocket?.inputStreamWrapper = stream
                    Log.i(TAG, "Car stream received — input ready")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                Log.e(TAG, "Payload transfer FAILED: id=${update.payloadId}")
            }
        }
    }

    // ── Android Auto launch ────────────────────────────────────────────

    private fun launchAndroidAuto(socket: NearbySocket) {
        if (isLaunching) return
        isLaunching = true

        scope.launch {
            try {
                val proxy = AaProxy(
                    preConnectedSocket = socket,
                    listener = object : AaProxy.Listener {
                        override fun onConnected() {
                            Log.i(TAG, "AA flowing through proxy")
                            stateListener.onProxyConnected()
                        }

                        override fun onDisconnected() {
                            Log.i(TAG, "AA proxy disconnected")
                            stateListener.onProxyDisconnected()
                        }
                    },
                )
                activeProxy = proxy
                val localPort = proxy.start()

                // Build the AA wireless startup intent
                val aaIntent = Intent().apply {
                    setClassName(
                        "com.google.android.projection.gearhead",
                        "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
                    )
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
                    putExtra("PARAM_SERVICE_PORT", localPort)
                    putExtra("ip_address", "127.0.0.1")
                    putExtra("projection_port", localPort)
                }

                Log.i(TAG, "Launching AA via TransparentTrigger, proxy port=$localPort")
                val triggerIntent = Intent(context, TransparentTriggerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra("intent", aaIntent)
                }
                context.startActivity(triggerIntent)

                // Timeout: if AA never connects, clean up
                delay(15_000)
                if (activeProxy != null) {
                    Log.w(TAG, "Launch timed out")
                    activeProxy?.stop()
                    activeProxy = null
                    stateListener.onLaunchTimeout()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Launch failed: ${e.message}")
                activeProxy?.stop()
                activeProxy = null
            } finally {
                isLaunching = false
            }
        }
    }

    companion object {
        private const val TAG = "OAL_Nearby"
        const val SERVICE_ID = "com.openautolink"
        // Also advertise legacy ID for backward compat with older car app versions
        const val LEGACY_SERVICE_ID = "com.andrerinas.hurev"
    }
}

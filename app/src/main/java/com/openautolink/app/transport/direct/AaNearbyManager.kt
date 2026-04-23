package com.openautolink.app.transport.direct

import android.content.Context
import com.openautolink.app.diagnostics.OalLog
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch

/**
 * Google Nearby Connections manager for Direct Mode.
 *
 * The car acts as a DISCOVERER — it finds the phone's companion app
 * (which advertises with our service ID). On connection, both sides
 * exchange STREAM payloads to create a bidirectional pipe. The AA
 * protocol runs over this pipe.
 *
 * This eliminates the need for phone hotspot or any WiFi configuration.
 * Nearby handles transport internally (BT → WiFi Direct upgrade).
 */
class AaNearbyManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSocketReady: (Socket) -> Unit,
) {
    companion object {
        private const val TAG = "AaNearby"
        // Use the same service ID as HURev's companion app for compatibility
        private const val SERVICE_ID = "com.andrerinas.hurev"
        private val STRATEGY = Strategy.P2P_POINT_TO_POINT

        private val _discoveredEndpoints = MutableStateFlow<List<DiscoveredEndpoint>>(emptyList())
        val discoveredEndpoints: StateFlow<List<DiscoveredEndpoint>> = _discoveredEndpoints
    }

    data class DiscoveredEndpoint(val id: String, val name: String)

    private val connectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private var isRunning = false
    private var isConnecting = false
    private var activeEndpointId: String? = null
    private var activeSocket: NearbySocket? = null
    private var activePipes: Array<android.os.ParcelFileDescriptor>? = null

    /** Auto-connect to first discovered endpoint. Set false for manual selection. */
    var autoConnect = true

    /** Last connected device name for auto-reconnect matching. */
    var lastDeviceName: String = ""

    fun start() {
        if (isRunning) return
        isRunning = true
        _discoveredEndpoints.value = emptyList()

        val options = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        OalLog.i(TAG, "Starting Nearby discovery (service=$SERVICE_ID)")
        connectionsClient.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnSuccessListener { OalLog.i(TAG, "Discovery started") }
            .addOnFailureListener { e -> 
                OalLog.e(TAG, "Discovery failed: ${e.message}")
                isRunning = false
            }
    }

    fun stop() {
        OalLog.i(TAG, "Stopping")
        isRunning = false
        isConnecting = false
        connectionsClient.stopDiscovery()
        activeEndpointId?.let {
            connectionsClient.disconnectFromEndpoint(it)
            activeEndpointId = null
        }
        activeSocket?.close()
        activeSocket = null
        activePipes?.forEach { try { it.close() } catch (_: Exception) {} }
        activePipes = null
        _discoveredEndpoints.value = emptyList()
    }

    /** Manually connect to a discovered endpoint (for UI-driven selection). */
    fun connectToEndpoint(endpointId: String) {
        if (isConnecting) return
        isConnecting = true
        OalLog.i(TAG, "Requesting connection to $endpointId")
        connectionsClient.requestConnection(android.os.Build.MODEL, endpointId, connectionCallback)
            .addOnFailureListener { e ->
                OalLog.e(TAG, "Connection request failed: ${e.message}")
                isConnecting = false
            }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            OalLog.i(TAG, "Found: ${info.endpointName} ($endpointId)")
            val current = _discoveredEndpoints.value.toMutableList()
            if (current.none { it.id == endpointId }) {
                current.add(DiscoveredEndpoint(endpointId, info.endpointName))
                _discoveredEndpoints.value = current
            }

            // Auto-connect
            if (autoConnect && !isConnecting && activeEndpointId == null) {
                if (lastDeviceName.isEmpty() || lastDeviceName == info.endpointName) {
                    OalLog.i(TAG, "Auto-connecting to ${info.endpointName}")
                    connectToEndpoint(endpointId)
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            OalLog.i(TAG, "Lost: $endpointId")
            val current = _discoveredEndpoints.value.toMutableList()
            current.removeAll { it.id == endpointId }
            _discoveredEndpoints.value = current
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            OalLog.i(TAG, "Connection initiated with ${info.endpointName}")
            lastDeviceName = info.endpointName
            isRunning = false
            connectionsClient.stopDiscovery()
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            isConnecting = false
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                OalLog.e(TAG, "Connection failed: ${result.status.statusMessage}")
                return
            }

            OalLog.i(TAG, "Connected to $endpointId")
            activeEndpointId = endpointId
            val socket = NearbySocket()
            activeSocket = socket

            scope.launch(Dispatchers.IO) {
                // Wait for phone to be ready
                delay(800)

                // Create outgoing pipe (car → phone)
                val pipes = android.os.ParcelFileDescriptor.createPipe()
                activePipes = pipes
                socket.outputStreamWrapper = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])

                // Send stream payload to phone
                val outPayload = Payload.fromStream(pipes[0])
                OalLog.i(TAG, "Sending stream payload")
                connectionsClient.sendPayload(endpointId, outPayload)
                    .addOnSuccessListener { OalLog.i(TAG, "Stream payload sent") }
                    .addOnFailureListener { e -> OalLog.e(TAG, "Stream send failed: ${e.message}") }

                // Start AA handshake — input will block until phone's stream arrives
                OalLog.i(TAG, "Starting AA session over Nearby tunnel")
                onSocketReady(socket)
            }
        }

        override fun onDisconnected(endpointId: String) {
            OalLog.i(TAG, "Disconnected from $endpointId")
            if (activeEndpointId == endpointId) {
                activeEndpointId = null
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.STREAM) {
                OalLog.i(TAG, "Received stream from phone — tunnel complete")
                activeSocket?.inputStreamWrapper = payload.asStream()?.asInputStream()
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE) {
                OalLog.e(TAG, "Payload transfer failed")
            }
        }
    }
}

/**
 * Socket wrapper for Nearby Connections stream payloads.
 * Wraps bidirectional Nearby streams as a standard Socket so the AA
 * protocol layer can use it transparently.
 */
class NearbySocket : Socket() {
    private var internalInput: InputStream? = null
    private var internalOutput: OutputStream? = null
    private val inputLatch = CountDownLatch(1)
    private val outputLatch = CountDownLatch(1)

    var inputStreamWrapper: InputStream?
        get() = internalInput
        set(value) {
            internalInput = value
            if (value != null) inputLatch.countDown()
        }

    var outputStreamWrapper: OutputStream?
        get() = internalOutput
        set(value) {
            internalOutput = value
            if (value != null) outputLatch.countDown()
        }

    override fun isConnected() = true
    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()

    override fun getInputStream(): InputStream = object : InputStream() {
        private fun stream(): InputStream { inputLatch.await(); return internalInput!! }
        override fun read(): Int = stream().read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = stream().read(b, off, len)
        override fun available(): Int = if (inputLatch.count == 0L) internalInput!!.available() else 0
        override fun close() { if (inputLatch.count == 0L) internalInput?.close() }
    }

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        private fun stream(): OutputStream { outputLatch.await(); return internalOutput!! }
        override fun write(b: Int) { stream().write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { stream().write(b, off, len); stream().flush() }
        override fun flush() { if (outputLatch.count == 0L) internalOutput?.flush() }
        override fun close() { if (outputLatch.count == 0L) internalOutput?.close() }
    }
}

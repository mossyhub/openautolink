package com.openautolink.app.transport

import android.net.Network
import android.util.Log
import com.openautolink.app.audio.AudioFrame
import com.openautolink.app.diagnostics.DiagnosticLog
import com.openautolink.app.video.VideoFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Direct Android Auto transport — aasdk runs inside the app via NDK/JNI.
 *
 * Connection flow (outbound relay mode):
 * 1. Connect outbound to bridge control channel (TCP:5288) — exchange hello
 * 2. Connect outbound to bridge relay port (TCP:5291) — hold socket open
 * 3. Wait for bridge to signal `relay_ready` (phone connected, sockets spliced)
 * 4. Extract relay socket fd, call AasdkJni.startSessionWithFd()
 * 5. aasdk handles TLS/protobuf/AA protocol over the spliced connection
 *
 * State flow: DISCONNECTED → CONNECTING → LISTENING → PHONE_CONNECTED → STREAMING
 *
 * On disconnect (phone leaves, bridge reboots, car sleep/wake):
 * - aasdk JNI fires onPhoneDisconnected
 * - Transport tears down aasdk, closes sockets
 * - Reconnect loop: exponential backoff, reconnect outbound, wait again
 */
class DirectAaTransport(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "DirectAaTransport"
        private const val CONTROL_PORT = 5288
        private const val RELAY_PORT = 5291
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val RECONNECT_DELAY_BASE_MS = 1000L
        private const val RECONNECT_DELAY_MAX_MS = 30_000L
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Delegate to AasdkJni flows — populated by native callbacks
    val controlMessages: Flow<ControlMessage> = AasdkJni.controlMessages
    val videoFrames: Flow<VideoFrame> = AasdkJni.videoFrames
    val audioFrames: Flow<AudioFrame> = AasdkJni.audioFrames

    private var connectJob: Job? = null
    private var controlSocket: Socket? = null
    private var relaySocket: Socket? = null
    private var controlReader: BufferedReader? = null
    private var controlWriter: PrintWriter? = null

    @Volatile
    var isReconnecting: Boolean = false
        private set

    // Session config — set by SessionManager before connect()
    var sessionWidth: Int = 1920
    var sessionHeight: Int = 1080
    var sessionFps: Int = 60
    var sessionDpi: Int = 160

    private var currentNetwork: Network? = null
    private var currentNetworkResolver: NetworkResolver? = null

    /**
     * Start the relay connection loop. Connects to bridge, waits for phone,
     * starts aasdk when relay is ready. Reconnects on failure.
     */
    fun connect(host: String, network: Network? = null, networkResolver: NetworkResolver? = null) {
        disconnect()
        currentNetwork = network
        currentNetworkResolver = networkResolver

        if (!AasdkJni.isAvailable) {
            AasdkJni.ensureLoaded()
            if (!AasdkJni.isAvailable) {
                Log.e(TAG, "aasdk native library not available")
                DiagnosticLog.e("transport", "aasdk native library not available")
                return
            }
        }

        isReconnecting = true
        connectJob = scope.launch(Dispatchers.IO) {
            var retryDelay = RECONNECT_DELAY_BASE_MS
            while (isActive && isReconnecting) {
                try {
                    runRelaySession(host)
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Relay session failed: ${e.message}")
                    DiagnosticLog.w("transport", "Relay session failed: ${e.message}")
                }

                // Clean up between attempts
                cleanupSockets()
                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                }

                if (!isReconnecting) break

                Log.i(TAG, "Reconnecting in ${retryDelay}ms...")
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(RECONNECT_DELAY_MAX_MS)
            }
        }
    }

    /** Force an immediate reconnect (e.g., on system wake). */
    fun forceReconnect() {
        val job = connectJob ?: return
        if (!job.isActive) return
        // Cancel current attempt and restart with no delay
        job.cancel()
        val host = controlSocket?.inetAddress?.hostAddress ?: return
        connect(host, currentNetwork, currentNetworkResolver)
    }

    fun disconnect() {
        isReconnecting = false
        connectJob?.cancel()
        connectJob = null

        if (AasdkJni.isAvailable) {
            try { AasdkJni.stopSession() } catch (_: Exception) {}
        }

        cleanupSockets()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Route a control message through the appropriate channel:
     * - Touch/Button → aasdk JNI (directly to phone via AA protocol)
     * - Gnss/VehicleData → aasdk JNI sensor data
     * - Diagnostics → relay control channel (bridge forwards to SSH)
     * - Phone management → relay control channel (bridge forwards to BT scripts)
     */
    fun sendControlMessage(message: ControlMessage) {
        if (!AasdkJni.isAvailable) return
        when (message) {
            is ControlMessage.Touch -> {
                AasdkJni.sendTouch(
                    message.action,
                    message.x ?: 0f,
                    message.y ?: 0f,
                    message.pointerId ?: 0
                )
            }
            is ControlMessage.Button -> {
                AasdkJni.sendButton(message.keycode, message.down)
            }
            is ControlMessage.Gnss -> {
                AasdkJni.sendSensorData(0x01, message.nmea.toByteArray(Charsets.UTF_8))
            }
            is ControlMessage.VehicleData -> {
                // TODO: serialize to protobuf for aasdk sensor channel
            }
            // Messages that go through the relay control channel (bridge-side)
            is ControlMessage.ListPairedPhones,
            is ControlMessage.SwitchPhone,
            is ControlMessage.ForgetPhone -> {
                sendControlJson(message)
            }
            is ControlMessage.AppLog,
            is ControlMessage.AppTelemetry -> {
                sendControlJson(message)
            }
            else -> {}
        }
    }

    fun sendMicAudio(pcm: ByteArray) {
        if (AasdkJni.isAvailable) {
            AasdkJni.sendMicAudio(pcm)
        }
    }

    // ─── Internal relay session ─────────────────────────────────────

    /**
     * Single relay session attempt:
     * 1. Connect control channel → exchange hello
     * 2. Connect relay socket → hold open
     * 3. Read control lines → wait for relay_ready
     * 4. Start aasdk on relay socket fd
     * 5. Block until aasdk session ends
     */
    private suspend fun runRelaySession(host: String) {
        withContext(Dispatchers.Main) {
            _connectionState.value = ConnectionState.CONNECTING
        }

        // Resolve network for socket binding
        val net = currentNetworkResolver?.resolve() ?: currentNetwork

        // 1. Connect control channel
        val ctrlSocket = createSocket(host, CONTROL_PORT, net)
        controlSocket = ctrlSocket
        controlReader = BufferedReader(InputStreamReader(ctrlSocket.getInputStream(), Charsets.UTF_8))
        controlWriter = PrintWriter(ctrlSocket.getOutputStream(), true, Charsets.UTF_8)

        // Send our hello
        val hello = """{"type":"hello","name":"OpenAutoLink App","version":1}"""
        controlWriter?.println(hello)
        Log.i(TAG, "Control connected to $host:$CONTROL_PORT")
        DiagnosticLog.i("transport", "Control connected to $host:$CONTROL_PORT")

        // 2. Connect relay socket
        val rlSocket = createSocket(host, RELAY_PORT, net)
        relaySocket = rlSocket
        Log.i(TAG, "Relay connected to $host:$RELAY_PORT — waiting for phone")
        DiagnosticLog.i("transport", "Relay connected — waiting for phone")

        withContext(Dispatchers.Main) {
            _connectionState.value = ConnectionState.LISTENING
        }

        // Reset retry delay on successful connection
        // (caller handles this via the while loop)

        // 3. Read control lines until relay_ready
        var relayReady = false
        while (!relayReady) {
            val line = controlReader?.readLine()
                ?: throw java.io.IOException("Control channel closed")

            try {
                val json = Json.parseToJsonElement(line) as? JsonObject ?: continue
                val type = json["type"]?.jsonPrimitive?.content ?: continue

                when (type) {
                    "hello" -> {
                        Log.i(TAG, "Bridge hello received")
                        DiagnosticLog.i("transport", "Bridge hello received")
                    }
                    "phone_bt_connected" -> {
                        val phoneName = json["phone_name"]?.jsonPrimitive?.content ?: "unknown"
                        Log.i(TAG, "Phone BT connected: $phoneName (WiFi pending)")
                        DiagnosticLog.i("transport", "Phone BT connected: $phoneName")
                    }
                    "relay_ready" -> {
                        Log.i(TAG, "Relay ready — phone connected, starting aasdk")
                        DiagnosticLog.i("transport", "Relay ready — starting aasdk session")
                        relayReady = true
                    }
                    "relay_disconnected" -> {
                        Log.i(TAG, "Relay disconnected (phone left before ready)")
                        DiagnosticLog.i("transport", "Relay disconnected before ready")
                        // Stay in LISTENING — bridge will signal next relay_ready
                    }
                    "paired_phones" -> {
                        // Forward to control messages flow for UI consumption
                        // TODO: parse phone list from JSON
                    }
                    else -> {
                        Log.d(TAG, "Control message: $type")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse control line: ${e.message}")
            }
        }

        // 4. Extract relay socket fd and start aasdk
        val fd = relaySocket?.let { getSocketFd(it) }
            ?: throw java.io.IOException("Relay socket closed before fd extraction")

        Log.i(TAG, "Starting aasdk with fd=$fd ${sessionWidth}x${sessionHeight}@${sessionFps}fps dpi=$sessionDpi")

        // Transition to PHONE_CONNECTED happens when aasdk calls onPhoneConnected callback
        // Start monitoring control messages for state transitions
        val controlMonitorJob = scope.launch {
            AasdkJni.controlMessages.collect { message ->
                when (message) {
                    is ControlMessage.PhoneConnected -> {
                        _connectionState.value = ConnectionState.PHONE_CONNECTED
                    }
                    is ControlMessage.PhoneDisconnected -> {
                        // Session ended — runRelaySession will return and reconnect loop restarts
                    }
                    else -> {}
                }
            }
        }

        // Also monitor video frames for STREAMING state transition
        val videoMonitorJob = scope.launch {
            AasdkJni.videoFrames.collect {
                if (_connectionState.value == ConnectionState.PHONE_CONNECTED) {
                    _connectionState.value = ConnectionState.STREAMING
                }
            }
        }

        try {
            // 5. Run aasdk (blocks on IO dispatcher until session ends)
            AasdkJni.startSessionWithFd(fd, sessionWidth, sessionHeight, sessionFps, sessionDpi)
        } finally {
            controlMonitorJob.cancel()
            videoMonitorJob.cancel()
        }
    }

    private fun createSocket(host: String, port: Int, network: Network?): Socket {
        val socket = Socket()
        // Bind to specific network (USB Ethernet to bridge)
        network?.bindSocket(socket)
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket.tcpNoDelay = true
        return socket
    }

    /**
     * Extract the native file descriptor from a Socket via reflection.
     * Required to pass the connected socket to aasdk via JNI.
     */
    private fun getSocketFd(socket: Socket): Int {
        val implField = Socket::class.java.getDeclaredField("impl")
        implField.isAccessible = true
        val impl = implField.get(socket)
        val fdField = impl.javaClass.getDeclaredField("fd")
        fdField.isAccessible = true
        val fd = fdField.get(impl) as java.io.FileDescriptor
        val fdIntField = java.io.FileDescriptor::class.java.getDeclaredField("fd")
        fdIntField.isAccessible = true
        return fdIntField.getInt(fd)
    }

    /** Send a JSON control message to the bridge via the control channel. */
    private fun sendControlJson(message: ControlMessage) {
        val writer = controlWriter ?: return
        try {
            val json = when (message) {
                is ControlMessage.ListPairedPhones -> """{"type":"list_paired_phones"}"""
                is ControlMessage.SwitchPhone -> """{"type":"switch_phone","mac":"${message.mac}"}"""
                is ControlMessage.ForgetPhone -> """{"type":"forget_phone","mac":"${message.mac}"}"""
                is ControlMessage.AppLog -> """{"type":"app_log","ts":${message.ts},"level":"${message.level}","tag":"${message.tag}","msg":"${message.msg.replace("\"", "\\\"")}"}"""
                is ControlMessage.AppTelemetry -> """{"type":"app_telemetry","ts":${message.ts}}"""
                else -> return
            }
            writer.println(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send control message: ${e.message}")
        }
    }

    private fun cleanupSockets() {
        try { controlReader?.close() } catch (_: Exception) {}
        try { controlWriter?.close() } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        try { relaySocket?.close() } catch (_: Exception) {}
        controlReader = null
        controlWriter = null
        controlSocket = null
        relaySocket = null
    }
}

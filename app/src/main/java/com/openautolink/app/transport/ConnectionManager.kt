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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Manages the control TCP connection to the bridge with automatic reconnection.
 * Reconnects with exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s cap.
 */
class ConnectionManager(private val scope: CoroutineScope) : BridgeConnection {

    companion object {
        private const val TAG = "ConnectionManager"
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val BACKOFF_MULTIPLIER = 2.0
    }

    private val controlChannel = TcpControlChannel()
    private val videoChannel = TcpVideoChannel()
    private val audioChannel = TcpAudioChannel()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _controlMessages = MutableSharedFlow<ControlMessage>(extraBufferCapacity = 64)
    override val controlMessages: Flow<ControlMessage> = _controlMessages.asSharedFlow()

    private val _videoFrames = MutableSharedFlow<VideoFrame>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val videoFrames: Flow<VideoFrame> = _videoFrames.asSharedFlow()

    private val _audioFrames = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 64)
    override val audioFrames: Flow<AudioFrame> = _audioFrames.asSharedFlow()

    private var connectionJob: Job? = null
    private var receiveJob: Job? = null
    private var videoJob: Job? = null
    private var audioJob: Job? = null

    private var targetHost: String? = null
    private var targetPort: Int = 5288
    private var targetNetwork: Network? = null
    private var autoReconnect = true

    override suspend fun connect(host: String, controlPort: Int, network: Network?) {
        disconnect()
        targetHost = host
        targetPort = controlPort
        targetNetwork = network
        autoReconnect = true
        connectionJob = scope.launch { connectLoop() }
    }

    override suspend fun disconnect() {
        autoReconnect = false
        audioJob?.cancel()
        audioJob = null
        videoJob?.cancel()
        videoJob = null
        connectionJob?.cancel()
        connectionJob = null
        receiveJob?.cancel()
        receiveJob = null
        audioChannel.close()
        videoChannel.close()
        controlChannel.close()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun sendControlMessage(message: ControlMessage) {
        if (!controlChannel.isConnected) return
        try {
            controlChannel.send(message)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send control message", e)
        }
    }

    override suspend fun connectVideo(host: String, port: Int) {
        disconnectVideo()
        videoJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    videoChannel.connect(host, port, network = targetNetwork)
                }
                Log.i(TAG, "Video channel connected to $host:$port")
                DiagnosticLog.i("transport", "Video channel connected to $host:$port")
                _connectionState.value = ConnectionState.STREAMING

                // Collect video frames and re-emit (tryEmit is non-suspending with DROP_OLDEST)
                videoChannel.receiveFrames().collect { frame ->
                    _videoFrames.tryEmit(frame)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Video channel error: ${e.message}")
                DiagnosticLog.w("transport", "Video channel error: ${e.message}")
            } finally {
                videoChannel.close()
                // Don't change state to DISCONNECTED — control channel may still be alive
                val current = _connectionState.value
                if (current == ConnectionState.STREAMING) {
                    _connectionState.value = ConnectionState.PHONE_CONNECTED
                }
            }
        }
    }

    override suspend fun disconnectVideo() {
        videoJob?.cancel()
        videoJob = null
        videoChannel.close()
    }

    override suspend fun connectAudio(host: String, port: Int) {
        disconnectAudio()
        audioJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    audioChannel.connect(host, port, network = targetNetwork)
                }
                Log.i(TAG, "Audio channel connected to $host:$port")
                DiagnosticLog.i("transport", "Audio channel connected to $host:$port")

                // Collect audio frames and re-emit
                audioChannel.receiveFrames().collect { frame ->
                    _audioFrames.emit(frame)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Audio channel error: ${e.message}")
                DiagnosticLog.w("transport", "Audio channel error: ${e.message}")
            } finally {
                audioChannel.close()
            }
        }
    }

    override suspend fun disconnectAudio() {
        audioJob?.cancel()
        audioJob = null
        audioChannel.close()
    }

    override fun sendMicAudio(frame: AudioFrame) {
        if (!audioChannel.isConnected) return
        try {
            audioChannel.sendFrame(frame)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send mic audio", e)
        }
    }

    private suspend fun connectLoop() {
        var backoffMs = INITIAL_BACKOFF_MS

        while (autoReconnect) {
            val host = targetHost ?: return
            _connectionState.value = ConnectionState.CONNECTING

            try {
                withContext(Dispatchers.IO) {
                    controlChannel.close()
                    controlChannel.connect(host, targetPort, network = targetNetwork)
                }

                Log.i(TAG, "Connected to bridge at $host:$targetPort")
                DiagnosticLog.i("transport", "Connected to bridge at $host:$targetPort")
                _connectionState.value = ConnectionState.CONNECTED
                backoffMs = INITIAL_BACKOFF_MS // Reset backoff on success

                // Start receiving messages
                receiveMessages()

                // If we get here, connection was lost
                Log.i(TAG, "Connection to bridge lost")
                DiagnosticLog.w("transport", "Connection to bridge lost")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connection attempt failed: ${e.message}")
                DiagnosticLog.w("transport", "Connection attempt failed: ${e.message}")
            }

            controlChannel.close()
            _connectionState.value = ConnectionState.DISCONNECTED

            if (!autoReconnect) break

            Log.d(TAG, "Reconnecting in ${backoffMs}ms")
            delay(backoffMs)
            backoffMs = min((backoffMs * BACKOFF_MULTIPLIER).toLong(), MAX_BACKOFF_MS)
        }
    }

    private suspend fun receiveMessages() {
        try {
            controlChannel.receiveMessages().collect { message ->
                when (message) {
                    is ControlMessage.Hello -> {
                        Log.i(TAG, "Bridge hello: ${message.name} v${message.version}")
                        _connectionState.value = ConnectionState.CONNECTED
                    }
                    is ControlMessage.PhoneConnected -> {
                        Log.i(TAG, "Phone connected: ${message.phoneName}")
                        _connectionState.value = ConnectionState.PHONE_CONNECTED
                    }
                    is ControlMessage.PhoneDisconnected -> {
                        Log.i(TAG, "Phone disconnected: ${message.reason}")
                        _connectionState.value = ConnectionState.CONNECTED
                    }
                    else -> {}
                }
                _controlMessages.emit(message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Control channel error: ${e.message}")
        }
    }
}

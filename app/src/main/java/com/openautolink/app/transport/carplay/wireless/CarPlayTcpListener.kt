package com.openautolink.app.transport.carplay.wireless

import com.openautolink.app.diagnostics.OalLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "CarPlayTcpListen"

/**
 * Passive TCP listener for the CarPlay wireless data channel.
 *
 * Accepts connections on [port] and logs the hex of incoming bytes per
 * connection. Does NOT respond to any data — this is a probe to see what
 * a connecting client (iPhone, future broker, etc.) sends as its first
 * bytes. Future iterations will replace this with the iAP2-over-TCP
 * state machine.
 *
 * Lifecycle:
 *   - [start] launches an accept loop on Dispatchers.IO.
 *   - Each accepted connection gets its own coroutine reading bytes and
 *     emitting [Event.Bytes] events.
 *   - [stop] cancels everything and closes the listening socket.
 *
 * Thread safety: state is published via flows. Caller can observe from any
 * dispatcher.
 */
class CarPlayTcpListener(
    private val port: Int = 5000,
    private val maxConnections: Int = 4,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val connectionJobs = mutableListOf<Job>()
    private val connLock = Any()

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    sealed class State {
        object Stopped : State()
        data class Listening(val port: Int) : State()
        data class Failed(val message: String) : State()
    }

    sealed class Event {
        val timestampMs: Long = System.currentTimeMillis()

        data class Started(val port: Int) : Event()
        data class Accepted(val remoteAddr: String, val remotePort: Int) : Event()
        data class Bytes(val remoteAddr: String, val hex: String, val len: Int) : Event()
        data class Closed(val remoteAddr: String, val reason: String) : Event()
        data class Error(val message: String) : Event()
        object Stopped : Event()
    }

    fun start() {
        if (_state.value is State.Listening) {
            OalLog.i(TAG, "start() called but already listening on :${port}")
            return
        }

        listenJob = scope.launch {
            val sock = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (e: Exception) {
                OalLog.e(TAG, "bind :$port failed: ${e.message}")
                _state.value = State.Failed("bind :$port failed: ${e.message}")
                _events.tryEmit(Event.Error("bind :$port failed: ${e.message}"))
                return@launch
            }

            serverSocket = sock
            _state.value = State.Listening(port)
            _events.tryEmit(Event.Started(port))
            OalLog.i(TAG, "listening on :$port")

            while (isActive && !sock.isClosed) {
                val client: Socket = try {
                    sock.accept()
                } catch (e: Exception) {
                    if (isActive) {
                        OalLog.e(TAG, "accept() failed: ${e.message}")
                        _events.tryEmit(Event.Error("accept: ${e.message}"))
                    }
                    break
                }

                val remoteAddr = client.inetAddress?.hostAddress ?: "?"
                val remotePort = client.port
                OalLog.i(TAG, "accepted connection from $remoteAddr:$remotePort")
                _events.tryEmit(Event.Accepted(remoteAddr, remotePort))

                val connJob = launchConnectionReader(client, remoteAddr)
                synchronized(connLock) {
                    connectionJobs.add(connJob)
                    // Trim completed jobs and enforce max
                    connectionJobs.removeAll { it.isCompleted }
                    while (connectionJobs.size > maxConnections) {
                        val oldest = connectionJobs.removeAt(0)
                        oldest.cancel()
                    }
                }
            }

            OalLog.i(TAG, "accept loop exited")
            _state.value = State.Stopped
            _events.tryEmit(Event.Stopped)
        }
    }

    private fun launchConnectionReader(client: Socket, remoteAddr: String): Job = scope.launch {
        val buf = ByteArray(2048)
        try {
            client.soTimeout = 0 // block indefinitely, we want to see ALL bytes
            val input = client.getInputStream()
            while (isActive) {
                val n = input.read(buf)
                if (n <= 0) {
                    _events.tryEmit(Event.Closed(remoteAddr, "eof"))
                    break
                }
                val hex = bytesToHex(buf, 0, n.coerceAtMost(64))
                _events.tryEmit(Event.Bytes(remoteAddr, hex, n))
                OalLog.i(TAG, "$remoteAddr → ${n}B: $hex${if (n > 64) "..." else ""}")
            }
        } catch (e: Exception) {
            if (isActive) {
                _events.tryEmit(Event.Closed(remoteAddr, e.message ?: "error"))
                OalLog.i(TAG, "$remoteAddr connection error: ${e.message}")
            }
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        runBlocking {
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
            listenJob?.cancelAndJoin()
            listenJob = null
            synchronized(connLock) {
                for (job in connectionJobs) job.cancel()
                connectionJobs.clear()
            }
            _state.value = State.Stopped
            OalLog.i(TAG, "stopped")
        }
    }

    companion object {
        private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

        fun bytesToHex(buf: ByteArray, off: Int, len: Int): String {
            val sb = StringBuilder(len * 3)
            for (i in 0 until len) {
                val b = buf[off + i].toInt() and 0xFF
                if (i > 0) sb.append(' ')
                sb.append(HEX_CHARS[b ushr 4])
                sb.append(HEX_CHARS[b and 0x0F])
            }
            return sb.toString()
        }
    }
}

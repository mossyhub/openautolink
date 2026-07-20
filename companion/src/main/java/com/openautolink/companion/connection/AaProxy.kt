package com.openautolink.companion.connection

import com.openautolink.companion.diagnostics.CompanionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local TCP proxy that relays Android Auto protocol data between the AA
 * app on this phone (connected via localhost) and the car (connected via
 * a pre-existing TCP socket from [TcpAdvertiser]).
 *
 * Note: preConnectedSocket is used for exactly one bridge session.
 * If AA reconnects, the proxy must be recreated with a new socket.
 */
class AaProxy(
    private val preConnectedSocket: Socket? = null,
    private val listener: Listener? = null,
) {
    interface Listener {
        fun onConnected()
        /**
         * The bridge ended. [unexpected] is true when a live AA<->Car bridge
         * broke on its own (gearhead closed its localhost socket, or a
         * read/write failed) while the proxy was still running — i.e. NOT an
         * intentional [stop]. The car TCP socket is then left half-open (no
         * FIN/RST reaches the car because the WiFi L2 link is still up), so the
         * car sits frozen until its ~9s ping-timeout. The handler should treat
         * an unexpected break as a recovery trigger (close the car socket so the
         * car gets a clean reset + reconnects, and relaunch AA).
         */
        fun onDisconnected(unexpected: Boolean)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null

    /** Port the proxy is listening on for the local AA app. 0 if not started. */
    @Volatile
    var localPort: Int = 0
        private set

    @Volatile
    private var isRunning = false

    @Volatile
    private var activeCarSocket: Socket? = null
    private val activeBridges = AtomicInteger(0)
    private var bridgeUsed = false

    /** Returns true if at least one AA bridge is active (AA connected and streaming). */
    fun hasActiveBridge(): Boolean = activeBridges.get() > 0

    @Volatile private var pendingCarSocket: Socket? = null

    /**
     * Replace the car-side socket used for the next bridge session.
     * Safe to call while waiting for AA to connect (no active bridge).
     * If a bridge is already active this is a no-op — the active session
     * owns its socket until it completes.
     */
    fun updateCarSocket(newCarSocket: Socket) {
        if (activeBridges.get() > 0) return  // active bridge owns its socket
        pendingCarSocket = newCarSocket
        CompanionLog.d(TAG, "Car socket updated (pending AA connect)")
    }

    /** Start the proxy server. Returns the localhost port AA should connect to. */
    fun start(): Int {
        val server = ServerSocket(0)
        serverSocket = server
        isRunning = true

        val localPort = server.localPort
        this.localPort = localPort
        CompanionLog.i(TAG, "Proxy listening on localhost:$localPort")

        scope.launch {
            try {
                while (isRunning) {
                    val aaSocket = server.accept()
                    CompanionLog.i(TAG, "Android Auto connected to proxy")
                    launchBridge(aaSocket)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    CompanionLog.d(TAG, "Proxy server stopped: ${e.message}")
                }
            }
        }

        return localPort
    }

    private fun launchBridge(aaSocket: Socket) {
        scope.launch {
            var carSocket: Socket? = null
            try {
                activeBridges.incrementAndGet()
                listener?.onConnected()

                // Use the most-recently-updated car socket (from a reconnect
                // while waiting for AA), falling back to the original socket.
                // Pre-warm path: if AA connected to the proxy BEFORE the car
                // (i.e. we started the proxy proactively on BT-connect and AA
                // came up before the car ignition's WiFi finished), no socket
                // exists yet — wait briefly for one to arrive via
                // updateCarSocket() rather than failing immediately.
                carSocket = pendingCarSocket?.also { pendingCarSocket = null }
                    ?: preConnectedSocket
                    ?: awaitPendingCarSocket(PREWARM_CAR_WAIT_MS)
                    ?: throw IllegalStateException(
                        "No car socket within ${PREWARM_CAR_WAIT_MS}ms — AA connected but no car ready"
                    )
                activeCarSocket = carSocket

                CompanionLog.i(TAG, "Bridge established: AA <-> Car")

                val aaIn = aaSocket.getInputStream()
                val aaOut = aaSocket.getOutputStream()
                val carIn = carSocket.getInputStream()
                val carOut = carSocket.getOutputStream()

                val job1 = launch { pump(aaIn, carOut, "AA->Car") }
                val job2 = launch { pump(carIn, aaOut, "Car->AA") }
                joinAll(job1, job2)
            } catch (e: Exception) {
                CompanionLog.e(TAG, "Bridge error: ${e.message}")
            } finally {
                // "Unexpected" = the bridge ended while we were still running
                // (gearhead dropped its socket / a pump read or write failed),
                // as opposed to an intentional stop() which flips isRunning
                // false first. Only an unexpected break should trigger the
                // car-socket-reset + AA relaunch recovery on the handler side.
                val unexpected = isRunning
                CompanionLog.i(TAG, "Bridge closed (unexpected=$unexpected)")
                activeCarSocket = null
                runCatching { aaSocket.close() }
                // Don't close carSocket here — let the TcpAdvertiser manage it via cleanup()
                if (activeBridges.decrementAndGet() <= 0) {
                    listener?.onDisconnected(unexpected)
                }
            }
        }
    }

    /**
     * Pre-warm support: poll [pendingCarSocket] for up to [timeoutMs] in case
     * AA has connected to the proxy before the car TCP arrived. Returns the
     * car socket once it lands or null on timeout. Cheap polling at 50ms
     * granularity — total cost over a full window is ~30 wakeups, negligible.
     */
    private suspend fun awaitPendingCarSocket(timeoutMs: Long): Socket? {
        val deadline = System.currentTimeMillis() + timeoutMs
        CompanionLog.i(TAG, "AA connected first (pre-warm) — waiting up to ${timeoutMs}ms for car")
        while (System.currentTimeMillis() < deadline && isRunning) {
            val s = pendingCarSocket
            if (s != null) {
                pendingCarSocket = null
                CompanionLog.i(TAG, "Pre-warm: car socket arrived after AA")
                return s
            }
            kotlinx.coroutines.delay(50)
        }
        return null
    }

    private suspend fun pump(input: InputStream, output: OutputStream, name: String) =
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(16384)
            try {
                while (isRunning) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (e: Exception) {
                CompanionLog.d(TAG, "$name error: ${e.message}")
            }
        }

    fun stop() {
        val wasRunning = isRunning
        isRunning = false
        if (wasRunning) {
            sendDisconnectSignal()
        }
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    /**
     * Send 16 bytes of 0xFF ("magic garbage") to the car. This triggers
     * a decryption error on the car side, which is caught as a clean
     * disconnect signal.
     */
    private fun sendDisconnectSignal() {
        val socket = activeCarSocket ?: return
        Thread {
            try {
                CompanionLog.i(TAG, "Sending disconnect signal")
                val signal = ByteArray(16) { 0xFF.toByte() }
                socket.getOutputStream().write(signal)
                socket.getOutputStream().flush()
            } catch (e: Exception) {
                CompanionLog.w(TAG, "Disconnect signal failed: ${e.message}")
            }
        }.start()
    }

    companion object {
        private const val TAG = "OAL_Proxy"
        /** Max time the bridge waits for a car socket when AA connected first
         *  (pre-warm path). 30s comfortably covers car-side boot + WiFi join
         *  on most vehicles; if no car arrives in that window we fail gracefully
         *  and AA can retry. */
        private const val PREWARM_CAR_WAIT_MS = 30_000L
    }
}

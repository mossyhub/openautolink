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
        fun onDisconnected()
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

    /**
     * Bridges that are actually PUMPING (car socket acquired, both pipes wired up).
     *
     * Distinct from [activeBridges] on purpose. `activeBridges` is incremented the
     * instant the bridge coroutine starts — which, on the pre-warm path, happens
     * while we are still *waiting* for the car to show up (see
     * [awaitPendingCarSocket]). Treating that window as "active" made
     * [hasActiveBridge] return true before any data could possibly flow, which sent
     * TcpAdvertiser.handleCarConnection() down its destructive branch: stop() the
     * proxy and re-listen on a NEW port, tearing the socket out from under the AA
     * reader that was already attached. gearhead then logged
     * `ReaderThread: end of stream received, dataReceived=false` ->
     * FRAMER_READ_END_OF_STREAM_NO_DATA -> PROTOCOL_IO_ERROR(3)/READER_CLOSE(52),
     * and OAL relaunched AA on the new port, producing the reconnect storm
     * (confirmed by live adb capture 2026-07-25 13:56).
     */
    private val pumpingBridges = AtomicInteger(0)

    /**
     * True only when a bridge is genuinely pumping bytes.
     *
     * Callers use this to decide whether a newly-arrived car socket can be swapped
     * into the existing (still-warm) proxy instead of destroying it. During the
     * pre-warm wait this MUST report false so the car socket is handed to the
     * waiting bridge via [updateCarSocket] rather than triggering a rebind.
     */
    fun hasActiveBridge(): Boolean = pumpingBridges.get() > 0

    /** True if a bridge coroutine exists at all, including one still awaiting a car socket. */
    fun hasPendingBridge(): Boolean = activeBridges.get() > 0

    @Volatile private var pendingCarSocket: Socket? = null

    /**
     * Replace the car-side socket used for the next bridge session.
     * Safe to call while waiting for AA to connect (no active bridge), and also
     * while a pre-warm bridge is parked in [awaitPendingCarSocket] — that is
     * exactly how the car socket reaches a bridge that AA attached to first.
     * If a bridge is already PUMPING this is a no-op — the live session owns its
     * socket until it completes.
     */
    fun updateCarSocket(newCarSocket: Socket) {
        if (pumpingBridges.get() > 0) return  // live bridge owns its socket
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
            var counted = false
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
                //
                // NOTE: pumpingBridges is deliberately NOT incremented until the
                // car socket is in hand. While parked in awaitPendingCarSocket()
                // this proxy must still look "not active" so an arriving car
                // socket is swapped in here instead of causing TcpAdvertiser to
                // stop() us and rebind on a fresh port (which would EOF the AA
                // reader already attached above).
                carSocket = pendingCarSocket?.also { pendingCarSocket = null }
                    ?: preConnectedSocket
                    ?: awaitPendingCarSocket(PREWARM_CAR_WAIT_MS)
                    ?: throw IllegalStateException(
                        "No car socket within ${PREWARM_CAR_WAIT_MS}ms — AA connected but no car ready"
                    )
                activeCarSocket = carSocket
                pumpingBridges.incrementAndGet()
                counted = true

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
                CompanionLog.i(TAG, "Bridge closed")
                activeCarSocket = null
                if (counted) pumpingBridges.decrementAndGet()
                runCatching { aaSocket.close() }
                // Don't close carSocket here — let the TcpAdvertiser manage it via cleanup()
                if (activeBridges.decrementAndGet() <= 0) {
                    listener?.onDisconnected()
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

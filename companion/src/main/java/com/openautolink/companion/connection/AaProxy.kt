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

    /**
     * Bridges that are actually PUMPING (car socket acquired, both pipes wired up).
     *
     * Deliberately distinct from [activeBridges], which is incremented the instant the
     * bridge coroutine starts — including while it is parked in [awaitPendingCarSocket]
     * waiting for the car. Reporting "active" during that wait made
     * TcpAdvertiser.handleCarConnection() skip its socket-swap path and instead
     * stop() the proxy and re-listen on a NEW port, tearing the socket out from under
     * the AA reader that was already attached (gearhead: "ReaderThread: end of stream
     * received, dataReceived=false" -> FRAMER_READ_END_OF_STREAM_NO_DATA ->
     * PROTOCOL_IO_ERROR(3)/READER_CLOSE(52)). Observed 2026-07-25.
     */
    private val pumpingBridges = AtomicInteger(0)

    /** True only when a bridge is genuinely relaying bytes. */
    fun hasActiveBridge(): Boolean = pumpingBridges.get() > 0

    /** True if a bridge coroutine exists at all, including one awaiting a car socket. */
    fun hasPendingBridge(): Boolean = activeBridges.get() > 0

    @Volatile private var pendingCarSocket: Socket? = null

    /**
     * Replace the car-side socket used for the next bridge session.
     *
     * Safe while waiting for AA to connect, and also while a pre-warm bridge is parked
     * in [awaitPendingCarSocket] — that is how the car socket reaches a bridge AA
     * attached to first. A no-op while a bridge is actively PUMPING, since the live
     * session owns its socket until it completes.
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
                // pumpingBridges is deliberately NOT incremented until the car
                // socket is in hand: while parked below this proxy must still
                // report "not active" so an arriving car socket is swapped in
                // here instead of causing TcpAdvertiser to stop() us and rebind
                // on a fresh port (which would EOF the AA reader attached above).
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
                // "Unexpected" = the bridge ended while we were still running
                // (gearhead dropped its socket / a pump read or write failed),
                // as opposed to an intentional stop() which flips isRunning
                // false first. Only an unexpected break should trigger the
                // car-socket-reset + AA relaunch recovery on the handler side.
                val unexpected = isRunning
                CompanionLog.i(TAG, "Bridge closed (unexpected=$unexpected)")
                activeCarSocket = null
                if (counted) pumpingBridges.decrementAndGet()
                runCatching { aaSocket.close() }
                // Don't close carSocket here — let the TcpAdvertiser manage it via cleanup()
                //
                // CRITICAL (this is what broke v0.1.368): the car socket is taken out of
                // pendingCarSocket destructively above. If this bridge never actually
                // pumped — AA attached, took the socket, then went away before/while the
                // bridge was set up — the socket must go BACK into the pool. Otherwise the
                // proxy survives (correctly) but every LATER AA attach finds no car socket,
                // parks for PREWARM_CAR_WAIT_MS and dies with "No car socket within 30000ms",
                // repeating forever. That livelock is why v0.1.368 shipped a phone that
                // never streamed and had to be reverted in v0.1.369.
                //
                // Only recycle a socket that is still usable, and only when no other bridge
                // is pumping (which would own it).
                if (isRunning && pumpingBridges.get() == 0) {
                    carSocket?.takeIf { !it.isClosed && it.isConnected }?.let {
                        pendingCarSocket = it
                        CompanionLog.i(TAG, "Car socket still live — returned to pool for next AA attach")
                    }
                }
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

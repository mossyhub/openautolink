package com.openautolink.companion.connection

import com.openautolink.companion.diagnostics.CompanionLog
import com.openautolink.companion.diagnostics.PhoneWppDiagnostics
import com.openautolink.companion.diagnostics.PhoneWppStage
import com.openautolink.companion.service.TcpAdvertiser
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Local AA endpoint. A car socket is consumed once unless setup failed before either pump started. */
class AaProxy(
    preConnectedSocket: Socket? = null,
    private val listener: Listener? = null,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(unexpected: Boolean)
        /** Exact bridge resource, not the advertiser's mutable current socket. */
        fun onConnected(carSocket: Socket, bridge: Any) = onConnected()
        fun onDisconnected(carSocket: Socket?, bridge: Any, unexpected: Boolean, retained: Boolean) = onDisconnected(unexpected)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ownershipLock = Any()
    private var serverSocket: ServerSocket? = null
    @Volatile var localPort: Int = 0
        private set
    @Volatile private var isRunning = false
    @Volatile private var activeCarSocket: Socket? = null
    // The constructor seeds the same one-shot slot as updateCarSocket. It is never a fallback.
    @Volatile private var pendingCarSocket: Socket? = preConnectedSocket
    private var socketGeneration = 0L
    private val aaSockets = mutableSetOf<Socket>()
    private val activeBridges = AtomicInteger()
    private val pumpingBridges = AtomicInteger()
    private val waitingBridges = AtomicInteger()
    private val waitGeneration = AtomicInteger()
    @Volatile private var carGoneForGood = false

    fun hasActiveBridge(): Boolean = pumpingBridges.get() > 0
    fun hasPendingBridge(): Boolean = activeBridges.get() > 0
    /** Finalization is not an AA prewarm waiter and must not suppress a fresh launch. */
    fun hasWaitingBridge(): Boolean = waitingBridges.get() > 0

    fun onCarShutdown() {
        val sockets = synchronized(ownershipLock) {
            carGoneForGood = true
            socketGeneration++
            waitGeneration.incrementAndGet()
            listOfNotNull(pendingCarSocket, activeCarSocket).also { pendingCarSocket = null }
        }
        sockets.forEach { runCatching { it.close() } }
    }

    /** Accept only while no bridge owns a car stream. Return whether this exact socket was admitted. */
    fun updateCarSocket(newCarSocket: Socket): Boolean = synchronized(ownershipLock) {
        if (!isRunning || pumpingBridges.get() > 0) return@synchronized false
        socketGeneration++
        carGoneForGood = false
        val previous = pendingCarSocket
        pendingCarSocket = newCarSocket
        if (previous !== newCarSocket) runCatching { previous?.close() }
        CompanionLog.d(TAG, "Car socket updated (pending AA connect)")
        true
    }

    val isAccepting: Boolean get() = isRunning && serverSocket?.isClosed == false

    fun start(): Int {
        val server = WppProxySocketBinder.bind()
        synchronized(ownershipLock) {
            serverSocket = server
            isRunning = true
            localPort = server.localPort
        }
        CompanionLog.i(TAG, "Proxy listening on localhost:$localPort")
        scope.launch {
            try {
                while (isRunning) {
                    val aaSocket = server.accept()
                    val ticket = synchronized(ownershipLock) {
                        if (!isRunning) { aaSocket.close(); return@launch }
                        aaSockets.add(aaSocket)
                        waitGeneration.incrementAndGet()
                    }
                    PhoneWppDiagnostics.record(PhoneWppStage.AA_SOCKET)
                    launchBridge(aaSocket, ticket)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) CompanionLog.d(TAG, "Proxy server stopped: ${e.message}")
            }
        }
        return localPort
    }

    private data class CarLease(val socket: Socket, val generation: Long)
    private fun takePending(ticket: Int): CarLease? = synchronized(ownershipLock) {
        if (!isRunning || carGoneForGood || waitGeneration.get() != ticket || pumpingBridges.get() > 0) return@synchronized null
        val socket = pendingCarSocket ?: return@synchronized null
        pendingCarSocket = null
        activeCarSocket = socket
        pumpingBridges.incrementAndGet()
        CarLease(socket, socketGeneration)
    }

    private suspend fun awaitPendingCarSocket(ticket: Int): CarLease? {
        val deadline = System.nanoTime() + PREWARM_CAR_WAIT_MS * 1_000_000
        while (System.nanoTime() < deadline && isRunning && waitGeneration.get() == ticket && !carGoneForGood) {
            takePending(ticket)?.let { return it }
            delay(50)
        }
        return null
    }

    private fun launchBridge(aaSocket: Socket, ticket: Int) {
        scope.launch {
            val bridge = Any()
            var lease: CarLease? = null
            var pumpsStarted = false
            var bridgeAttemptId: Long? = null
            activeBridges.incrementAndGet()
            waitingBridges.incrementAndGet()
            try {
                try {
                    lease = takePending(ticket) ?: awaitPendingCarSocket(ticket)
                        ?: throw IllegalStateException("No car socket available for this AA attach")
                } finally { waitingBridges.decrementAndGet() }
                val car = lease.socket
                val aaIn = aaSocket.getInputStream()
                val aaOut = aaSocket.getOutputStream()
                val carIn = car.getInputStream()
                val carOut = car.getOutputStream()
                CompanionLog.i(TAG, "Bridge established: AA <-> Car")
                listener?.onConnected(car, bridge)
                bridgeAttemptId = PhoneWppDiagnostics.bridgeEstablished()
                // From this point the car byte stream cannot be reused, even if a pump fails immediately.
                pumpsStarted = true
                val job1 = launch { pump(aaIn, carOut, "AA->Car", aaSocket, car) }
                val job2 = launch { pump(carIn, aaOut, "Car->AA", aaSocket, car) }
                joinAll(job1, job2)
            } catch (e: Exception) {
                CompanionLog.e(TAG, "Bridge error: ${e.message}")
            } finally {
                val car = lease?.socket
                val unexpected = isRunning && !carGoneForGood
                runCatching { aaSocket.close() }
                if (lease != null) {
                    PhoneWppDiagnostics.bridgeClosed(bridgeAttemptId)
                    synchronized(ownershipLock) {
                        if (activeCarSocket === car) activeCarSocket = null
                        pumpingBridges.decrementAndGet()
                    }
                }
                // The predicate may race a replacement. Revalidate the generation when publishing.
                val eligible = !pumpsStarted && isRunning && !carGoneForGood &&
                    car != null && !car.isClosed && car.isConnected
                val retained = synchronized(ownershipLock) {
                    aaSockets.remove(aaSocket)
                    if (eligible && isRunning && !carGoneForGood && lease?.generation == socketGeneration &&
                        pendingCarSocket == null && pumpingBridges.get() == 0) {
                        pendingCarSocket = car
                        true
                    } else false
                }
                if (!retained) runCatching { car?.close() }
                activeBridges.decrementAndGet()
                CompanionLog.i(TAG, "Bridge closed (unexpected=$unexpected, retained=$retained)")
                listener?.onDisconnected(car, bridge, unexpected, retained)
            }
        }
    }

    private suspend fun pump(input: InputStream, output: OutputStream, name: String, aa: Socket, car: Socket) =
        withContext(Dispatchers.IO) {
            try {
                val buffer = ByteArray(16384)
                while (isRunning) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (e: Exception) {
                CompanionLog.d(TAG, "$name error: ${e.message}")
            } finally {
                // Coroutine cancellation cannot unblock java.net.Socket.read. Close exact endpoints
                // on the first EOF/error so the sibling pump and finalizer can actually retire.
                runCatching { aa.close() }
                runCatching { car.close() }
            }
        }

    fun stop() {
        val signalSocket = synchronized(ownershipLock) { activeCarSocket.takeIf { isRunning } }
        // Preserve the best-effort protocol disconnect, but bind it to this exact bridge.
        signalSocket?.let { socket ->
            Thread {
                runCatching {
                    socket.getOutputStream().write(ByteArray(16) { 0xFF.toByte() })
                    socket.getOutputStream().flush()
                }
            }.apply { isDaemon = true; start() }
        }
        val sockets = synchronized(ownershipLock) {
            isRunning = false
            socketGeneration++
            waitGeneration.incrementAndGet()
            runCatching { serverSocket?.close() }
            serverSocket = null
            (aaSockets.toList() + listOfNotNull(pendingCarSocket, activeCarSocket)).also {
                pendingCarSocket = null
            }
        }
        sockets.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    companion object {
        private const val TAG = "OAL_Proxy"
        private const val PREWARM_CAR_WAIT_MS = 30_000L
    }
}

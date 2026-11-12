package com.openautolink.companion.connection

import com.openautolink.companion.diagnostics.CompanionLog
import com.openautolink.companion.diagnostics.PhoneWppDiagnostics
import com.openautolink.companion.diagnostics.PhoneWppStage
import com.openautolink.companion.service.TcpAdvertiser
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.SequenceInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
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
        /** An invitation suppressed for this exact pending car lost its AA peer before leasing it. */
        fun onWaitingPeerClosed(carSocket: Socket, waiter: Any) = Unit
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
    private class WaitingAa(val socket: Socket, val ticket: Int) {
        val input: InputStream = socket.getInputStream()
        val prefix = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var suppressedCar: Socket? = null
        var reinviteCar: Socket? = null
    }
    // One reader until lease transfer; the same monitor protects read-ahead and car admission.
    private var waitingAa: WaitingAa? = null
    private val waitGeneration = AtomicInteger()
    @Volatile private var carGoneForGood = false

    fun hasActiveBridge(): Boolean = pumpingBridges.get() > 0
    fun hasPendingBridge(): Boolean = activeBridges.get() > 0
    /** Finalization is not an AA prewarm waiter and must not suppress a fresh launch. */
    fun hasWaitingBridge(): Boolean = synchronized(ownershipLock) {
        waitingAa?.let { pollWaitingAa(it) } == true
    }

    internal enum class CarAdmission { REJECTED, WAITING, INVITE }

    /** Admit the exact retired ticket once, including effects, before another AA accept. */
    internal fun reinviteWaitingCar(socket: Socket, token: Any, invite: () -> Unit) = synchronized(ownershipLock) {
        val waiter = token as? WaitingAa ?: return@synchronized
        if (isRunning && !carGoneForGood && pendingCarSocket === socket &&
            waitGeneration.get() == waiter.ticket && waiter.reinviteCar === socket &&
            waitingAa == null && pumpingBridges.get() == 0) {
            waiter.reinviteCar = null
            invite()
        }
    }

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
    fun updateCarSocket(newCarSocket: Socket): Boolean = offerCarSocket(newCarSocket) != CarAdmission.REJECTED

    /** Liveness and the launch-suppression decision belong to the same exact admission. */
    internal fun offerCarSocket(newCarSocket: Socket): CarAdmission = synchronized(ownershipLock) {
        if (!isRunning || pumpingBridges.get() > 0) return@synchronized CarAdmission.REJECTED
        val waiter = waitingAa?.takeIf { pollWaitingAa(it) }
        socketGeneration++
        carGoneForGood = false
        val previous = pendingCarSocket
        pendingCarSocket = newCarSocket
        if (previous !== newCarSocket) runCatching { previous?.close() }
        CompanionLog.d(TAG, "Car socket updated (pending AA connect)")
        waiter?.suppressedCar = newCarSocket
        if (waiter != null) CarAdmission.WAITING else CarAdmission.INVITE
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
                    synchronized(ownershipLock) {
                        if (!isRunning) { aaSocket.close(); return@launch }
                        aaSockets.add(aaSocket)
                        val ticket = waitGeneration.incrementAndGet()
                        // Replacement revokes the old reader before the successor can lease a car.
                        waitingAa?.let { runCatching { it.socket.close() } }
                        waitingAa = WaitingAa(aaSocket, ticket).also { it.suppressedCar = pendingCarSocket }
                        PhoneWppDiagnostics.record(PhoneWppStage.AA_SOCKET)
                        launchBridge(aaSocket, ticket)
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) CompanionLog.d(TAG, "Proxy server stopped: ${e.message}")
            }
        }
        return localPort
    }

    /** Called only with ownershipLock held; no pump can read this input yet. */
    private fun pollWaitingAa(waiter: WaitingAa): Boolean {
        if (waitingAa !== waiter || !isRunning || carGoneForGood || waitGeneration.get() != waiter.ticket) return false
        var terminal: String? = null
        val timeout = waiter.socket.soTimeout
        try {
            // A read timeout is a poll, not EOF. Restore the caller's timeout before handoff.
            waiter.socket.soTimeout = 1
            while (true) {
                val remaining = MAX_PREWARM_READ_AHEAD - waiter.prefix.size()
                val count = waiter.input.read(waiter.buffer, 0, minOf(waiter.buffer.size, remaining + 1))
                if (count < 0) { terminal = "eof"; break }
                if (count > remaining) { terminal = "read-ahead-limit"; break }
                waiter.prefix.write(waiter.buffer, 0, count)
            }
        } catch (_: SocketTimeoutException) {
            return true
        } catch (_: Exception) {
            terminal = "read-error"
        } finally {
            if (!waiter.socket.isClosed) runCatching { waiter.socket.soTimeout = timeout }
        }
        // Never consume the car to discover an AA FIN/reset. Only this ticket may request
        // replacement of an invitation that was actually suppressed for this pending car.
        waiter.reinviteCar = pendingCarSocket?.takeIf { it === waiter.suppressedCar }
        waitingAa = null
        runCatching { waiter.socket.close() }
        CompanionLog.i(TAG, "AA waiter retired (reason=$terminal)")
        return false
    }

    private data class CarLease(val socket: Socket, val generation: Long, val aaInput: InputStream)
    private fun takePending(ticket: Int): CarLease? = synchronized(ownershipLock) {
        if (!isRunning || carGoneForGood || waitGeneration.get() != ticket || pumpingBridges.get() > 0) return@synchronized null
        val waiter = waitingAa?.takeIf { it.ticket == ticket } ?: return@synchronized null
        if (!pollWaitingAa(waiter)) return@synchronized null
        val socket = pendingCarSocket ?: return@synchronized null
        pendingCarSocket = null
        waitingAa = null
        activeCarSocket = socket
        pumpingBridges.incrementAndGet()
        CarLease(socket, socketGeneration, SequenceInputStream(ByteArrayInputStream(waiter.prefix.toByteArray()), waiter.input))
    }

    private suspend fun awaitPendingCarSocket(ticket: Int): CarLease? {
        val deadline = System.nanoTime() + PREWARM_CAR_WAIT_MS * 1_000_000
        while (System.nanoTime() < deadline && isRunning && waitGeneration.get() == ticket && !carGoneForGood) {
            takePending(ticket)?.let { return it }
            if (synchronized(ownershipLock) { waitingAa?.ticket != ticket }) return null
            delay(50)
        }
        return null
    }

    private fun launchBridge(aaSocket: Socket, ticket: Int) {
        val waiter = waitingAa?.takeIf { it.ticket == ticket } ?: return
        scope.launch {
            val bridge = Any()
            var lease: CarLease? = null
            var pumpsStarted = false
            var bridgeAttemptId: Long? = null
            activeBridges.incrementAndGet()
            try {
                lease = takePending(ticket) ?: awaitPendingCarSocket(ticket)
                        ?: throw IllegalStateException("No car socket available for this AA attach")
                val car = lease.socket
                val aaIn = lease.aaInput
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
                    if (waitingAa === waiter) waitingAa = null
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
                val reinvite = synchronized(ownershipLock) {
                    waiter.reinviteCar?.takeIf {
                        isRunning && !carGoneForGood && waitGeneration.get() == ticket &&
                            pendingCarSocket === it && waitingAa == null && pumpingBridges.get() == 0
                    }
                }
                reinvite?.let { listener?.onWaitingPeerClosed(it, waiter) }
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
        // Pre-car negotiation has no consumer yet. Bound memory and reject excess input
        // without ever touching the fresh car; ordinary pump traffic has no such limit.
        private const val MAX_PREWARM_READ_AHEAD = 65_536
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.pairing

import android.content.Context
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pairing-only bench listener. Optional upstream MFi SAP; no discovery, SETUP, media, or projection proof.
 * The caller MUST select numeric phone IPs via [allowPeer]; the default policy denies everyone.
 * IP selection is an admission filter; only PairVerify's Ed25519 proof authenticates a controller.
 */
class PairingProbeServer internal constructor(
    directory: File,
    private val btMac: String,
    private val onStatus: (String) -> Unit,
) : Closeable {
    constructor(context: Context, btMac: String, onStatus: (String) -> Unit) :
        this(File(context.noBackupFilesDir, "pairing-probe"), btMac, onStatus)

    init { require(btMac.matches(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}"))) { "Invalid Bluetooth MAC" } }
    private val persistence = PairingPersistence(directory)
    val pairingId: String get() = persistence.identity.pairingId
    val publicKeyHex: String get() = persistence.identity.publicKeyHex
    @Volatile var port: Int = 0
        private set
    private val lifecycle = Any()
    private val policy = PeerPolicy()
    private val closed = AtomicBoolean()
    private val generation = AtomicInteger()
    private val accepted = AtomicInteger()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var authenticator: MfiAuthenticator? = null
    private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, SynchronousQueue(), daemonFactory("pairing-worker"), ThreadPoolExecutor.AbortPolicy())
    private val deadlines = ScheduledThreadPoolExecutor(1, daemonFactory("pairing-deadline")).apply { removeOnCancelPolicy = true }
    private val statuses = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64), daemonFactory("pairing-status"), ThreadPoolExecutor.DiscardPolicy())

    /** Caller owns the authenticator. No credentials are loaded or logged by this server. */
    fun setAuthenticator(mfi: MfiAuthenticator) = synchronized(lifecycle) {
        check(!closed.get() && listener == null) { "Authenticator must be set before start" }
        authenticator = mfi
    }

    /** Changes revoke ALL existing sessions, even when addresses overlap. Empty means deny all. */
    fun allowPeer(addresses: Set<String>) = synchronized(lifecycle) {
        check(!closed.get()) { "Pairing server closed" }
        policy.set(addresses)
        generation.incrementAndGet()
        sockets.forEach { runCatching { it.close() } }
        status("pairing peer selection updated count=${addresses.size}; prior sessions revoked")
    }

    /** Idempotent while running; cannot restart a closed server. The OS chooses the actual port. */
    fun start() = synchronized(lifecycle) {
        check(!closed.get()) { "Pairing server closed" }
        if (listener != null) return@synchronized
        val socket = ServerSocket()
        try {
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0), 2)
            listener = socket
            port = socket.localPort
            // Report what was actually bound, not an assumed reachable address or dual-stack claim.
            status("pairing TCP bound [${socket.inetAddress.hostAddress}]:$port IPv6 wildcard; IPv4 reachability requires bench test")
            acceptThread = Thread({ acceptLoop(socket) }, "pairing-accept").apply { isDaemon = true; start() }
        } catch (failure: Exception) { socket.close(); throw failure }
    }

    private fun acceptLoop(server: ServerSocket) {
        try {
            while (!closed.get()) {
                val peer = server.accept()
                val selectedGeneration = synchronized(lifecycle) {
                    if (closed.get() || !policy.allows(peer.inetAddress) || accepted.incrementAndGet() > MAX_CONNECTIONS || sockets.size >= 2) {
                        peer.close()
                        status("pairing TCP admission declined")
                        null
                    } else {
                        sockets.add(peer)
                        generation.get()
                    }
                } ?: continue
                try {
                    workers.execute {
                        var expiry: ScheduledFuture<*>? = null
                        try {
                            expiry = deadlines.schedule(Runnable { runCatching { peer.close() } }, 30, TimeUnit.SECONDS)
                            serve(peer, selectedGeneration)
                        } catch (_: RejectedExecutionException) { /* Shutdown raced admission. */ }
                        finally { expiry?.cancel(false); sockets.remove(peer); runCatching { peer.close() } }
                    }
                } catch (_: RejectedExecutionException) { sockets.remove(peer); peer.close(); status("pairing connection capacity reached") }
            }
        } catch (_: Exception) {
            if (!closed.get()) status("pairing listener stopped after transport failure")
        }
    }

    private fun serve(socket: Socket, selectedGeneration: Int) {
        try {
            socket.soTimeout = 5000
            socket.tcpNoDelay = true
            val reader = PairingFraming(BufferedInputStream(socket.getInputStream(), 4096))
            val output = socket.getOutputStream()
            val setup = PairSetup(persistence.identity, persistence.pairings)
            val verify = PairVerify(persistence.identity, persistence.pairings)
            var pairingRoute: String? = null
            var sapSent = false
            val info = BplistCodec.encode(AirPlayInfoPlist.build(AirPlayConfig(
                deviceName = "OAL pairing bench", deviceId = btMac, btMac = btMac,
                sourceVersion = SOURCE_VERSION, main = AirPlayDisplayConfig(1280, 720),
                port = port, disableAudioOutput = true,
            )))
            repeat(MAX_REQUESTS) {
                if (!admitted(socket, selectedGeneration)) return
                val request = reader.readRequest() ?: return
                if (!admitted(socket, selectedGeneration)) return
                val route = when (request.path.lowercase(java.util.Locale.ROOT)) {
                    "/pair-setup" -> "pair-setup"
                    "/pair-verify" -> "pair-verify"
                    "/info" -> "info"
                    "/auth-setup" -> "auth-setup"
                    else -> "other"
                }
                // NEVER log path, headers, request body, controller ID, keys, signatures, or challenges.
                status("pairing request route=$route bytes=${request.body.size}")
                var terminal = false
                var sapResponse = false
                val response = when {
                    request.method in MEDIA_METHODS -> {
                        terminal = true
                        status("pairing media explicitly declined")
                        RtspMessage.Response(status = 403, statusText = "Forbidden")
                    }
                    route == "auth-setup" && request.method == "POST" -> {
                        val mfi = authenticator
                        if (mfi == null || sapSent) {
                            terminal = true
                            status("pairing MFi SAP declined: authenticator unavailable or repeated request")
                            RtspMessage.Response(status = 501, statusText = "Not Implemented")
                        } else {
                            val body = MfiSapAuthSetup.handle(request.body, mfi)
                            if (body == null || body.size > 196608) {
                                terminal = true
                                RtspMessage.Response(status = 400, statusText = "Bad Request")
                            } else {
                                sapResponse = true
                                RtspMessage.Response(headers = mapOf("Content-Type" to "application/octet-stream"), body = body)
                            }
                        }
                    }
                    route == "info" && request.method in setOf("GET", "POST") -> RtspMessage.Response(
                        headers = mapOf("Content-Type" to "application/x-apple-binary-plist"), body = info,
                    )
                    route in setOf("pair-setup", "pair-verify") && request.method == "POST" && reader.cipher == null -> {
                        if (pairingRoute != null && pairingRoute != route) {
                            // Setup may be followed by verify on the same connection only after M6.
                            if (pairingRoute != "pair-setup" || route != "pair-verify" || !setup.complete) return
                        }
                        pairingRoute = route
                        val body = if (route == "pair-setup") setup.handle(request.body) else verify.handle(request.body)
                        terminal = Tlv8Codec.decode(body).containsKey(7)
                        RtspMessage.Response(headers = mapOf("Content-Type" to "application/pairing+tlv8"), body = body)
                    }
                    else -> { terminal = true; RtspMessage.Response(status = 404, statusText = "Not Found") }
                }
                if (!admitted(socket, selectedGeneration)) return
                val wire = RtspMessage.buildResponse(request, response)
                output.write(reader.cipher?.encrypt(wire) ?: wire)
                output.flush()
                if (sapResponse) {
                    sapSent = true
                    status("pairing MFi SAP response sent; phone acceptance unknown; pair-verify is separate")
                }
                // The final M4 response is plaintext, exactly as in upstream AirPlaySession.
                if (reader.cipher == null && verify.isVerified) {
                    val keys = verify.controlKeys ?: return
                    reader.cipher = ControlCipher(keys.readKey, keys.writeKey)
                    if (admitted(socket, selectedGeneration)) status("pairing controller cryptographically verified; encrypted control enabled; no projection proof")
                } else if (route == "pair-setup" && setup.complete) {
                    status("pairing setup cryptographic proof accepted and controller persisted; verification still required")
                }
                if (terminal) { status("pairing request declined; connection closing"); return }
            }
            status("pairing request cap reached")
        } catch (_: Exception) {
            if (!closed.get()) status("pairing connection closed after framing, authentication, storage, or transport failure")
        }
    }

    private fun admitted(socket: Socket, selectedGeneration: Int): Boolean =
        !closed.get() && generation.get() == selectedGeneration && policy.allows(socket.inetAddress) && !socket.isClosed

    private fun status(message: String) {
        statuses.execute { try { onStatus(message) } catch (_: Exception) { /* UI callback cannot kill transport. */ } }
    }

    /** Stops network I/O immediately; waiting is capped at 750ms, even if a UI callback stalls. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lifecycle) {
            generation.incrementAndGet()
            runCatching { listener?.close() }
            sockets.forEach { runCatching { it.close() } }
            port = 0
        }
        deadlines.shutdownNow()
        workers.shutdownNow()
        statuses.shutdownNow()
        try { acceptThread?.join(250); workers.awaitTermination(500, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        // Do not release the exclusive state lock while a worker could still be persisting.
        if (workers.isTerminated) persistence.close()
        else Thread({
            try { while (!workers.awaitTermination(1, TimeUnit.DAYS)) { /* Hold ownership. */ } }
            catch (_: InterruptedException) { return@Thread }
            persistence.close()
        }, "pairing-state-release").apply { isDaemon = true; start() }
    }

    companion object {
        const val SOURCE_VERSION = com.openautolink.carplaylab.media.AIRPLAY_SOURCE_VERSION
        private const val MAX_CONNECTIONS = 32
        private const val MAX_REQUESTS = 16
        private val MEDIA_METHODS = setOf("SETUP", "RECORD", "TEARDOWN", "ANNOUNCE", "SET_PARAMETER", "GET_PARAMETER", "FLUSH", "FLUSHBUFFERED", "SETRATEANCHOR", "SETPEERS", "GETANCHOR")
        private fun daemonFactory(name: String) = ThreadFactory { task -> Thread(task, name).apply { isDaemon = true } }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
// Adapted from DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d; see MEDIA-PROVENANCE.md.
package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Receive-only iAP2-over-CarPlay DataStream tunnel (stream type 130).
 *
 * The TCP stream is NetSocketChaCha20Poly1305 framed, then carries APTransportPackage records.
 * iAP2 bodies (messageType "comm") are emitted verbatim for the wired iAP2 relay.
 */
class IapTunnel(
    private val readKey: ByteArray,
    private val expectedPeer: InetAddress? = null,
    bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    private val socketBinding: com.openautolink.carplaylab.network.CarPlaySocketBinding? = null,
) : Closeable {
    interface Listener {
        fun onOpen(remoteAddress: String?) {}
        fun onIap(bytes: ByteArray) {}
        fun onDebug(message: String) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val readCounter = AtomicLong(0)
    private val bindAddress =
        socketBinding?.local ?: if (bindAddress is Inet4Address) InetAddress.getByName("0.0.0.0") else bindAddress
    private val servers = mutableListOf<ServerSocket>()
    private var socket: Socket? = null
    private val threads = mutableListOf<Thread>()
    private val peerConnected = CountDownLatch(1)
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = bindAny()
        servers += bound
        Unit
        if (socketBinding == null) {
        val secondaryAddress = if (bindAddress is java.net.Inet6Address) {
            InetAddress.getByName("0.0.0.0")
        } else {
            InetAddress.getByName("::")
        }
        val secondary = ServerSocket()
        runCatching {
            secondary.apply {
                reuseAddress = true
                bind(InetSocketAddress(secondaryAddress, bound.localPort))
            }
        }.onSuccess { secondary ->
            servers += secondary
            Unit
        }.onFailure { error ->
            safeClose(secondary)
            Unit
        }
        }
        servers.forEach { server ->
            threads += Thread({ accept(server) }, "airplay-iap-tunnel").apply {
                isDaemon = true
                start()
            }
        }
        return bound.localPort
    }

    private fun bindAny(): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        servers.toList().forEach(::safeClose)
        servers.clear()
        threads.toList().forEach(Thread::interrupt)
        threads.clear()
        peerConnected.countDown()
    }

    /** Waits until the iPhone has connected to the advertised dataPort. */
    fun awaitPeerConnection(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            peerConnected.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun accept(bound: ServerSocket) {
        Unit
        while (!closed.get()) {
            val accepted = try {
                bound.accept()
            } catch (error: Exception) {
                if (!closed.get()) listener.onClosed(error)
                return
            }
            if (closed.get()) {
                safeClose(accepted)
                return
            }
            val peerAllowed = socketBinding?.let { it.allowsPeer(accepted.inetAddress) && it.allowsLocal(accepted.localAddress) }
                ?: (expectedPeer == null || accepted.inetAddress == expectedPeer)
            listener.onDebug("BENCH_DIAG tunnelTcpAccepted peerAllowed=$peerAllowed")
            if (!peerAllowed) { safeClose(accepted); continue }
            accepted.setSoLinger(true, 0)
            val admitted = synchronized(this) {
                if (socket != null) false else { socket = accepted; true }
            }
            if (!admitted) { safeClose(accepted); continue }
            readCounter.set(0)
            peerConnected.countDown()
            listener.onOpen(accepted.remoteSocketAddress?.toString())
            run(accepted)
        }
    }

    private fun run(sock: Socket) {
        var ciphertext = ByteArray(0)
        var plaintext = ByteArray(0)
        var failure: Throwable? = null
        var announcedData = false
        try {
            val input = sock.getInputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) {
                    Unit
                    break
                }
                if (!announcedData) {
                    announcedData = true
                    Unit
                }
                ciphertext += buffer.copyOf(read)
                val decrypted = decryptFrames(ciphertext)
                require(plaintext.size + decrypted.first.size <= MAX_PACKAGE)
                plaintext += decrypted.first
                ciphertext = decrypted.second
                plaintext = parsePackages(plaintext)
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun decryptFrames(buffer: ByteArray): Pair<ByteArray, ByteArray> {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= FRAME_HEADER_LEN) {
            val length = readU16Le(buffer, offset)
            val frameLength = FRAME_HEADER_LEN + length + TAG_SIZE
            if (buffer.size - offset < frameLength) break
            val aad = buffer.copyOfRange(offset, offset + FRAME_HEADER_LEN)
            val sealed = buffer.copyOfRange(offset + FRAME_HEADER_LEN, offset + frameLength)
            val plain = AirPlayCrypto.chachaOpen(
                readKey, AirPlayCrypto.nonce64(readCounter.get()), sealed, aad,
            )
            readCounter.incrementAndGet()
            output.add(plain)
            offset += frameLength
        }
        return concatBytes(*output.toTypedArray()) to buffer.copyOfRange(offset, buffer.size)
    }

    private fun parsePackages(buffer: ByteArray): ByteArray {
        var offset = 0
        while (buffer.size - offset >= PACKAGE_HEADER_LEN) {
            val size = readU32Be(buffer, offset)
            require(size in PACKAGE_HEADER_LEN..MAX_PACKAGE)
            if (buffer.size - offset < size) break
            val messageType = readU32Be(buffer, offset + MESSAGE_TYPE_OFFSET)
            if (messageType == MSG_TYPE_COMM) {
                Unit
                listener.onIap(buffer.copyOfRange(offset + PACKAGE_HEADER_LEN, offset + size))
            }
            offset += size
        }
        return buffer.copyOfRange(offset, buffer.size)
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val FRAME_HEADER_LEN = 2
        const val TAG_SIZE = 16
        const val PACKAGE_HEADER_LEN = 32
        const val MESSAGE_TYPE_OFFSET = 16
        const val MSG_TYPE_COMM = 0x636f6d6d
        const val MAX_PACKAGE = 4 * 1024 * 1024
        const val READ_CHUNK_BYTES = 16 * 1024
    }
}

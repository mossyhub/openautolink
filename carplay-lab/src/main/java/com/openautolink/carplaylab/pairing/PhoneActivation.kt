// SPDX-License-Identifier: GPL-3.0-only
// Request format adapted from DiPlay (credits shilapi/xcertplay, GPL v3):
// https://github.com/shihabal3amri/DiPlay/blob/e2fd8eadf5a6b705666f292055e72aa93387533d/shared/src/main/java/com/shilapi/xcertplay/network/CarPlayBonjour.kt#L96-L127
// Modified for the OAL bench: selected-peer admission, bounded attempts, revocation.
package com.openautolink.carplaylab.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Numeric selected-IP filter, not authentication of the physical phone. Never resolves DNS names. */
internal class ActivationPeer(value: String) {
    val address: InetAddress = numericAddress(value)
    fun matches(candidate: InetAddress): Boolean = address.address.contentEquals(candidate.address) &&
        (address !is Inet6Address || candidate is Inet6Address && address.scopeId == candidate.scopeId)

    companion object {
        fun numericAddress(value: String): InetAddress {
            require(value.length in 1..128 && value.none { it.isWhitespace() }) { "Numeric phone IP required" }
            val base = value.substringBefore('%')
            val parsed = if (':' in base) {
                require(base.matches(Regex("[0-9a-fA-F:.]+"))) { "Numeric phone IP required" }
                require('%' !in value || value.substringAfter('%').matches(Regex("[0-9]{1,10}"))) {
                    "Numeric IPv6 scope required"
                }
                // The colon and numeric-only grammar prevent getByName from performing DNS lookup.
                try { InetAddress.getByName(value) }
                catch (_: Exception) { throw IllegalArgumentException("Invalid numeric phone IP") }
            } else {
                require('%' !in value && base.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) {
                    "Numeric phone IP required"
                }
                val octets = base.split('.').map { it.toInt() }
                require(octets.all { it in 0..255 }) { "Invalid numeric phone IP" }
                InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray())
            }
            require(!parsed.isAnyLocalAddress && !parsed.isMulticastAddress) { "Unicast phone IP required" }
            return parsed
        }
    }
}

/** Exact upstream wire format with fixed bench sourceVersion 1.0 and stricter input admission. */
internal object PhoneActivationProtocol {
    fun request(host: String, port: Int, receiverBtMac: String): String {
        ActivationPeer.numericAddress(host)
        require(port in 1..65535) { "Invalid control port" }
        require(receiverBtMac.matches(Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}"))) { "Invalid Bluetooth MAC" }
        val unscopedHost = host.substringBefore('%')
        val hostHeader = if (':' in unscopedHost) "[$unscopedHost]:$port" else "$unscopedHost:$port"
        return "GET /ctrl-int/1/connect HTTP/1.1\r\n" +
            "Host: $hostHeader\r\n" +
            "User-Agent: AirPlay/1.0\r\n" +
            "AirPlay-Receiver-Device-ID: ${receiverBtMac.replace(":", "")}\r\n" +
            "Connection: close\r\n\r\n"
    }

    fun statusCode(line: String): Int? =
        Regex("^HTTP/\\d(?:\\.\\d)? ([0-9]{3})(?: |$)").find(line)?.groupValues?.get(1)?.toIntOrNull()
}

/** Shared effect boundary: retired tokens cannot resolve, connect, write, or deliver status. */
internal class ActivationFence : Closeable {
    class Token internal constructor(val key: String)
    private val lock = Any()
    private var closed = false
    private val tokens = mutableMapOf<String, Token>()
    private val resources = mutableMapOf<Token, Closeable>()

    fun claim(key: String): Token? = synchronized(lock) {
        if (closed || key in tokens || tokens.size >= 32) null
        else Token(key).also { tokens[key] = it }
    }

    fun run(token: Token?, action: () -> Unit): Boolean = synchronized(lock) {
        if (closed || token != null && tokens[token.key] !== token) false
        else { action(); true }
    }

    fun attach(token: Token, resource: Closeable): Boolean = synchronized(lock) {
        if (closed || tokens[token.key] !== token) {
            runCatching { resource.close() }
            false
        } else {
            resources.put(token, resource)?.let { runCatching { it.close() } }
            true
        }
    }

    fun detach(token: Token, resource: Closeable) = synchronized(lock) {
        if (resources[token] === resource) resources.remove(token)
        Unit
    }

    fun revoke(key: String) = synchronized(lock) {
        tokens.remove(key)?.let { token -> resources.remove(token)?.let { runCatching { it.close() } } }
        Unit
    }

    fun revoke(token: Token) = synchronized(lock) {
        if (tokens[token.key] === token) revoke(token.key)
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            tokens.clear()
            resources.values.forEach { runCatching { it.close() } }
            resources.clear()
        }
    }
}

/**
 * Experimental selected-phone control activation only; does not publish AirPlay or prove projection.
 * Discovers only _carplay-ctrl._tcp. and dials ONLY a resolved selected-IP endpoint/advertised port.
 * IPv6 scope must match exactly; no inferred interface, DNS name, subnet sweep or fallback port.
 *
 * [start] is idempotent; a closed instance cannot restart. Changing selected phones requires closing
 * this instance and constructing another. Close/service loss revoke queued and late NSD callbacks
 * and close the active socket. Legacy NSD resolution itself cannot be canceled on API 32/33;
 * a 5s wait retires its callback's result and no late result can initiate TCP.
 *
 * [onStatus] receives metadata only on a daemon worker; marshal UI updates as needed. Keep it short:
 * delivery shares the revocation boundary, so close waits for an in-progress caller callback.
 * Network operations are bounded: 3s connect/read idle, 6s absolute socket deadline (including write).
 * A 2xx control response is NOT evidence of inbound pairing, phone acceptance, or projection.
 */
class PhoneActivation(
    context: Context,
    phoneIp: String,
    private val receiverBtMac: String,
    private val socketBinding: com.openautolink.carplaylab.network.CarPlaySocketBinding? = null,
    private val onStatus: (String) -> Unit,
) : Closeable {
    constructor(context: Context, phoneIp: String, receiverBtMac: String, onStatus: (String) -> Unit) :
        this(context, phoneIp, receiverBtMac, null, onStatus)
    private val peer = ActivationPeer(phoneIp)
    init { PhoneActivationProtocol.request(phoneIp, 1, receiverBtMac) } // Validate only; never dial this port.
    private val nsd = (context.applicationContext ?: context).getSystemService(Context.NSD_SERVICE) as? NsdManager
        ?: throw IllegalStateException("Android NSD unavailable")
    private val fence = ActivationFence()
    private var started = false
    private var discoveryRequested = false
    private val attempts = mutableMapOf<String, Int>() // Worker-only, retained across lost/re-found service names.
    private val completed = mutableSetOf<String>()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        daemonFactory("phone-activation"), ThreadPoolExecutor.AbortPolicy())
    private val deadlines = ScheduledThreadPoolExecutor(1, daemonFactory("phone-activation-deadline"))
        .apply { removeOnCancelPolicy = true }

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            enqueue { status(null, "control discovery started") }
        }
        override fun onDiscoveryStopped(serviceType: String) {
            enqueue { status(null, "control discovery stopped") }
        }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            enqueue { status(null, "control discovery failed code=$errorCode") }
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            enqueue { status(null, "control discovery stop failed code=$errorCode") }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!controlType(serviceInfo.serviceType)) return
            val name = serviceInfo.serviceName ?: return
            val token = fence.claim(name) ?: return
            if (!enqueue {
                try { handle(token, serviceInfo) }
                catch (failure: InterruptedException) { throw failure }
                catch (_: Exception) { status(token, "control operation failed") }
            }) fence.revoke(token)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            if (controlType(serviceInfo.serviceType)) serviceInfo.serviceName?.let(fence::revoke)
        }
    }

    fun start() {
        check(fence.run(null) {
            if (!started) {
                started = true
                discoveryRequested = true
                try { nsd.discoverServices(CONTROL_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
                catch (failure: RuntimeException) { close(); throw failure }
            }
        }) { "Phone activation closed" }
    }

    private fun enqueue(action: () -> Unit): Boolean {
        var accepted = false
        fence.run(null) {
            try { worker.execute { try { action() } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } }; accepted = true }
            catch (_: RejectedExecutionException) { /* Closed or bounded queue full. */ }
        }
        return accepted
    }

    @Suppress("DEPRECATION")
    private fun resolve(token: ActivationFence.Token, service: NsdServiceInfo): NsdServiceInfo? {
        val ready = CountDownLatch(1)
        var result: NsdServiceInfo? = null
        var accepting = true // Accessed only through fence.run.
        val callback = object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                fence.run(token) { if (accepting) { accepting = false; result = serviceInfo; ready.countDown() } }
            }
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                fence.run(token) { if (accepting) { accepting = false; ready.countDown() } }
            }
        }
        if (!fence.run(token) { nsd.resolveService(service, callback) }) return null
        try {
            val arrived = ready.await(5, TimeUnit.SECONDS)
            var resolved: NsdServiceInfo? = null
            fence.run(token) { accepting = false; if (arrived) resolved = result }
            if (resolved == null) status(token, "control resolution unavailable")
            return resolved
        } finally { fence.run(token) { accepting = false } }
    }

    @Suppress("DEPRECATION")
    private fun handle(token: ActivationFence.Token, service: NsdServiceInfo) {
        val resolved = resolve(token, service) ?: return
        val addresses = if (Build.VERSION.SDK_INT >= 34) resolved.hostAddresses else listOfNotNull(resolved.host)
        val address = addresses.firstOrNull(peer::matches)
        val port = resolved.port
        if (address == null || port !in 1..65535 || !controlType(resolved.serviceType)) {
            status(token, "control endpoint declined selectedPeer=${address != null} validPort=${port in 1..65535}")
            return
        }
        val key = "${address.hostAddress}|$port"
        if (key in completed || key !in attempts && attempts.size >= 16) return
        status(token, "control resolved family=${if (address is Inet6Address) "IPv6" else "IPv4"} port=$port")
        while ((attempts[key] ?: 0) < 2) { // At most TWO TOTAL TCP attempts per endpoint for this instance.
            var attempt = 0
            if (!fence.run(token) { attempt = (attempts[key] ?: 0) + 1; attempts[key] = attempt }) return
            try {
                val code = connect(token, address, port, attempt) ?: return
                if (!fence.run(token) { completed.add(key) }) return // Any HTTP response stops retries.
                status(token, "control response attempt=$attempt status=$code; no projection proof")
                return
            } catch (_: IOException) { status(token, "control transport failed attempt=$attempt") }
        }
    }

    private fun connect(token: ActivationFence.Token, address: InetAddress, port: Int, attempt: Int): Int? {
        val socket = Socket()
        if (!fence.attach(token, socket)) return null
        var expiry: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            // Deadline closes without taking the fence, bounding a stalled fenced write/connect.
            expiry = deadlines.schedule({ runCatching { socket.close() }; Unit }, 6, TimeUnit.SECONDS)
            status(token, "control connecting attempt=$attempt port=$port")
            if (!fence.run(token) {
                socket.soTimeout = 3000
                socketBinding?.let {
                    require(it.allowsPeer(address)) { "Control activation peer differs from selected session" }
                    socket.bind(InetSocketAddress(socketBinding?.local, 0))
                }
                socket.connect(InetSocketAddress(address, port), 3000)
            }) return null
            val wire = PhoneActivationProtocol.request(requireNotNull(address.hostAddress), port, receiverBtMac)
                .toByteArray(StandardCharsets.US_ASCII)
            if (!fence.run(token) {
                if (socket.isClosed) throw IOException("Control socket closed")
                socket.getOutputStream().apply { write(wire); flush() }
            }) return null
            status(token, "control request sent attempt=$attempt")
            // Read only a bounded status line. Never retain/log response headers, names, body or reason.
            val line = StringBuilder()
            val input = socket.getInputStream()
            while (line.length < 512) {
                val value = input.read()
                if (value < 0) throw IOException("No control status line")
                if (value == 10) return PhoneActivationProtocol.statusCode(line.toString().removeSuffix("\r"))
                    ?: throw IOException("Invalid control status line")
                line.append(value.toChar())
            }
            throw IOException("Control status line too long")
        } finally {
            expiry?.cancel(false)
            runCatching { socket.close() }
            fence.detach(token, socket)
        }
    }

    private fun status(token: ActivationFence.Token?, message: String) {
        fence.run(token) { try { onStatus(message) } catch (_: Exception) { /* No exception text/stack logged. */ } }
    }

    override fun close() {
        fence.run(null) {
            fence.close()
            if (discoveryRequested) {
                discoveryRequested = false
                runCatching { nsd.stopServiceDiscovery(discovery) }
            }
            worker.shutdownNow()
            deadlines.shutdownNow()
        }
    }

    private companion object {
        const val CONTROL_TYPE = "_carplay-ctrl._tcp."
        fun controlType(type: String?): Boolean = type?.removeSuffix(".") == CONTROL_TYPE.removeSuffix(".")
        fun daemonFactory(name: String) = ThreadFactory { task -> Thread(task, name).apply { isDaemon = true } }
    }
}

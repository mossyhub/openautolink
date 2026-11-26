// SPDX-License-Identifier: GPL-3.0-only
// Uses PhoneActivationProtocol for the request, adapted from DiPlay (credits shilapi/xcertplay, GPL v3).
package com.openautolink.carplaylab.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asks a trusted iPhone to connect when its address is not known.
 *
 * [PhoneActivation] sends the `/ctrl-int/1/connect` request only to a typed peer address. On a
 * car hotspot that hands out addresses by DHCP there is none, the request is never sent, and the
 * iPhone never opens the session. This looks for `_carplay-ctrl._tcp` on the session's interface
 * and sends the same request to whatever answers.
 *
 * It searches two ways at once: NsdManager, and a direct mDNS query on the interface (NSD alone
 * was unreliable on a GM head unit's interfaces). Answers from outside the interface's subnets
 * are ignored, since the shared mDNS port also hears the car's internal networks.
 *
 * Admission is unchanged: with no typed peer the receiver still admits only a trusted iPhone, so
 * another phone on the hotspot gets the request and can do nothing with it. Status lines carry
 * counts and ports, never addresses.
 */
class HotspotPhoneActivation(
    context: Context,
    private val receiverBtMac: String,
    private val local: InetAddress,
    private val interfaceIndex: Int,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val nsd = (context.applicationContext ?: context).getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val dialer = Executors.newSingleThreadExecutor { Thread(it, "carplay-hotspot-activation-dial").apply { isDaemon = true } }
    private val listener = Executors.newSingleThreadExecutor { Thread(it, "carplay-hotspot-activation-mdns").apply { isDaemon = true } }
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var discovering = false

    // Dial thread only.
    private val attempts = HashMap<String, Int>()
    private val answered = HashSet<String>()
    // A phone uses one control port on every address it has. Once that port has said yes, its
    // other addresses are the same phone and are not asked again.
    private val acceptedPorts = HashSet<Int>()
    // Counters for the closing summary.
    private val peers = java.util.Collections.synchronizedSet(HashSet<String>())
    private val found = AtomicInteger()
    private val asked = AtomicInteger()
    private val accepted = AtomicInteger()

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovering = false; say("search system=failed code=$errorCode") }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (closed.get() || !Protocol.isControl(serviceInfo.serviceType)) return
            @Suppress("DEPRECATION")
            runCatching {
                nsd?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = say("found via=system resolve=failed code=$errorCode")
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
                        addresses.forEach { candidate("system", it, info.port) }
                    }
                })
            }
        }
    }

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        val system = nsd?.let { manager ->
            runCatching { manager.discoverServices(Protocol.CONTROL_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery); discovering = true }.isSuccess
        } == true
        val nic = runCatching { NetworkInterface.getByIndex(interfaceIndex) }.getOrNull()
        val direct = nic?.let(::open)
        say("search started system=${if (system) "ok" else "unavailable"} direct=${direct ?: "unavailable"}")
        if (nic != null && socket != null) runCatching { listener.execute { listen(nic) } }
        // The receiver waits two minutes for the iPhone; the search ends a little before that.
        Thread({
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SEARCH_SECONDS)
            while (!closed.get() && System.nanoTime() < deadline) runCatching { Thread.sleep(500) }
            if (!closed.get()) { summary("ended"); close() }
        }, "carplay-hotspot-activation-deadline").apply { isDaemon = true }.start()
    }

    /** Opens the multicast socket on the hotspot interface. Returns what it managed, for the log. */
    private fun open(nic: NetworkInterface): String? {
        // The shared mDNS port hears every answer on the link. If the system will not share it, a
        // private port still gets answers sent straight back to it.
        val (opened, shared) = runCatching {
            MulticastSocket(null as java.net.SocketAddress?).apply { reuseAddress = true; bind(InetSocketAddress(Protocol.MDNS_PORT)) } to true
        }.getOrElse { runCatching { MulticastSocket(0) to false }.getOrNull() ?: return null }
        return runCatching {
            opened.soTimeout = 1000
            opened.networkInterface = nic
            val joined = Protocol.GROUPS.filter { group -> runCatching { opened.joinGroup(InetSocketAddress(group, Protocol.MDNS_PORT), nic) }.isSuccess }
            socket = opened
            (if (shared) "shared" else "private") + " groups=" + joined.joinToString("+") { if (it is Inet6Address) "v6" else "v4" }.ifEmpty { "none" }
        }.getOrElse { runCatching { opened.close() }; null }
    }

    private fun listen(nic: NetworkInterface) {
        val socket = socket ?: return
        val own = nic.inetAddresses.toList().map { it.address.toList() }.toSet()
        val subnets = runCatching { nic.interfaceAddresses.map { it.address to it.networkPrefixLength.toInt() } }.getOrDefault(emptyList())
        // A private port cannot hear multicast answers, so those queries ask for a direct reply.
        val direct = socket.localPort != Protocol.MDNS_PORT
        val query = Protocol.query(Protocol.SERVICE, Protocol.TYPE_PTR, direct)
        val followedUp = HashSet<List<String>>()
        fun send(bytes: ByteArray) = Protocol.GROUPS.forEach { group ->
            val target = if (group is Inet6Address) Inet6Address.getByAddress(null, group.address, nic) else group
            runCatching { socket.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(target, Protocol.MDNS_PORT))) }
        }
        val buffer = ByteArray(9000)
        var nextQuery = 0L
        while (!closed.get()) {
            val now = System.nanoTime()
            if (now >= nextQuery) {
                nextQuery = now + TimeUnit.SECONDS.toNanos(2)
                send(query)
            }
            val packet = DatagramPacket(buffer, buffer.size)
            try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue } catch (_: Exception) { break }
            val source = packet.address ?: continue
            if (source.address.toList() in own) continue
            // The shared port also hears the car's other internal networks. Only the hotspot counts.
            if (!Protocol.onLink(source, interfaceIndex, subnets)) continue
            // Anything another device says on the hotspot proves it is there and can be heard.
            if (peers.add(source.address.joinToString(".")) && peers.size <= 8)
                say("heard device=${peers.size} family=${if (source is Inet6Address) 6 else 4}")
            val answer = Protocol.read(buffer, packet.length)
            answer.ports.forEach { port -> candidate("direct", source, port) }
            // An answer that names the service without saying where it listens gets one follow-up.
            if (answer.ports.isEmpty()) answer.instances.filter { followedUp.size < 8 && followedUp.add(it) }
                .forEach { send(Protocol.query(it, Protocol.TYPE_SRV, direct)) }
        }
    }

    private fun candidate(via: String, address: InetAddress, port: Int) {
        if (closed.get() || port !in 1..65535 || address.isLoopbackAddress || address.isAnyLocalAddress || address.isMulticastAddress) return
        val scoped = Protocol.onInterface(address, interfaceIndex) ?: return
        try {
            dialer.execute {
                val key = scoped.address.joinToString(".") + "|" + port
                if (closed.get() || key in answered || port in acceptedPorts || key !in attempts && attempts.size >= MAX_ENDPOINTS) return@execute
                if (key !in attempts) { found.incrementAndGet(); say("found via=$via family=${if (scoped is Inet6Address) 6 else 4} port=$port") }
                val attempt = (attempts[key] ?: 0) + 1
                if (attempt > MAX_ATTEMPTS) return@execute
                attempts[key] = attempt
                asked.incrementAndGet()
                val result = runCatching { ask(scoped, port) }
                result.onSuccess { code ->
                    answered.add(key)
                    if (code in 200..299) { accepted.incrementAndGet(); acceptedPorts.add(port) }
                    say("asked attempt=$attempt answer=$code")
                }.onFailure { say("asked attempt=$attempt answer=none type=${it.javaClass.simpleName}") }
            }
        } catch (_: RejectedExecutionException) { /* closed */ }
    }

    /** One bounded request. Returns the HTTP status; nothing else of the answer is read or kept. */
    internal fun ask(address: InetAddress, port: Int): Int {
        Socket().use { socket ->
            socket.soTimeout = 3000
            // Leave from the hotspot interface's own address of the same family, when it has one.
            val source = if ((address is Inet6Address) == (local is Inet6Address)) local else runCatching {
                NetworkInterface.getByIndex(interfaceIndex)?.inetAddresses?.toList()?.firstOrNull {
                    (it is Inet6Address) == (address is Inet6Address) && (it !is Inet6Address || it.isLinkLocalAddress)
                }
            }.getOrNull()
            source?.let { runCatching { socket.bind(InetSocketAddress(it, 0)) } }
            socket.connect(InetSocketAddress(address, port), 3000)
            val host = checkNotNull(address.hostAddress).substringBefore('%')
            socket.getOutputStream().apply { write(PhoneActivationProtocol.request(host, port, receiverBtMac).toByteArray(StandardCharsets.US_ASCII)); flush() }
            val line = StringBuilder()
            val input = socket.getInputStream()
            while (line.length < 512) {
                val value = input.read()
                if (value < 0 || value == 10) break
                line.append(value.toChar())
            }
            return PhoneActivationProtocol.statusCode(line.toString().removeSuffix("\r")) ?: throw java.io.IOException("No status line")
        }
    }

    private fun say(text: String) { if (!closed.get()) runCatching { onStatus("CarPlay network stage=activation-search $text") } }

    private val summarized = AtomicBoolean(false)
    private fun summary(why: String) {
        if (summarized.compareAndSet(false, true))
            say("search $why heard=${peers.size} found=${found.get()} asked=${asked.get()} accepted=${accepted.get()}")
    }

    override fun close() {
        if (started.get() && !closed.get()) summary("stopped")
        if (!closed.compareAndSet(false, true)) return
        if (discovering) runCatching { nsd?.stopServiceDiscovery(discovery) }
        runCatching { socket?.close() }
        dialer.shutdownNow()
        listener.shutdownNow()
    }

    /** The wire formats, pure and unit-tested. */
    internal object Protocol {
        const val CONTROL_TYPE = "_carplay-ctrl._tcp."
        const val MDNS_PORT = 5353
        const val TYPE_PTR = 12
        const val TYPE_SRV = 33
        val SERVICE = listOf("_carplay-ctrl", "_tcp", "local")

        /** What one mDNS packet says about CarPlay control services. */
        data class Answer(val ports: List<Int>, val instances: List<List<String>>)
        val GROUPS: List<InetAddress> = listOf(
            InetAddress.getByAddress(byteArrayOf(0xff.toByte(), 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xfb.toByte())),
            InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte())))

        fun isControl(type: String?): Boolean = type?.removeSuffix(".")?.removePrefix(".") == CONTROL_TYPE.removeSuffix(".")

        /** A one-question query. PTR on [SERVICE] asks who offers it; SRV on an instance asks where. */
        fun query(name: List<String>, type: Int, unicastReply: Boolean): ByteArray = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            name.forEach { label -> val bytes = label.toByteArray(StandardCharsets.UTF_8); write(bytes.size); write(bytes) }
            write(0)
            write(byteArrayOf(0, type.toByte(), if (unicastReply) 0x80.toByte() else 0, 1)) // class IN
        }.toByteArray()

        /**
         * The port of every SRV record in [packet] that belongs to a CarPlay control service, and
         * the instances named by PTR records. Malformed input gives an empty answer, never an error.
         */
        fun read(packet: ByteArray, length: Int = packet.size): Answer = runCatching {
            val end = minOf(length, packet.size)
            if (end < 12) return Answer(emptyList(), emptyList())
            fun u16(at: Int) = (packet[at].toInt() and 0xff shl 8) or (packet[at + 1].toInt() and 0xff)
            val questions = u16(4)
            val records = u16(6) + u16(8) + u16(10)
            // Returns the labels at [at] and the offset just past the name as written there.
            fun name(at: Int): Pair<List<String>, Int> {
                val labels = ArrayList<String>()
                var cursor = at
                var after = -1
                var jumps = 0
                while (true) {
                    require(cursor < end)
                    val size = packet[cursor].toInt() and 0xff
                    when {
                        size == 0 -> { if (after < 0) after = cursor + 1; break }
                        size and 0xc0 == 0xc0 -> {
                            require(cursor + 1 < end && ++jumps <= 16)
                            if (after < 0) after = cursor + 2
                            cursor = (size and 0x3f shl 8) or (packet[cursor + 1].toInt() and 0xff)
                        }
                        else -> {
                            require(size <= 63 && cursor + 1 + size <= end && labels.size < 32)
                            labels.add(String(packet, cursor + 1, size, StandardCharsets.UTF_8))
                            cursor += 1 + size
                        }
                    }
                }
                return labels to after
            }
            var cursor = 12
            repeat(questions) { cursor = name(cursor).second + 4 }
            val ports = ArrayList<Int>()
            val instances = ArrayList<List<String>>()
            fun control(labels: List<String>) = labels.takeLast(3).map { it.lowercase() } == SERVICE
            repeat(minOf(records, 64)) {
                val (labels, after) = name(cursor)
                require(after + 10 <= end)
                val type = u16(after)
                val size = u16(after + 8)
                val data = after + 10
                require(data + size <= end)
                if (type == TYPE_SRV && size >= 6 && control(labels) && labels.size > 3)
                    u16(data + 4).takeIf { it in 1..65535 }?.let(ports::add)
                if (type == TYPE_PTR && control(labels) && labels.size == 3)
                    name(data).first.takeIf { control(it) && it.size > 3 }?.let(instances::add)
                cursor = data + size
            }
            Answer(ports.distinct(), instances.distinct())
        }.getOrDefault(Answer(emptyList(), emptyList()))

        /**
         * Did this sender speak on the hotspot? A link-local IPv6 sender carries the interface it was
         * heard on. Any other sender has to sit inside one of the interface's own subnets.
         */
        fun onLink(source: InetAddress, interfaceIndex: Int, subnets: List<Pair<InetAddress, Int>>): Boolean {
            if (source is Inet6Address && source.isLinkLocalAddress && source.scopeId != 0) return source.scopeId == interfaceIndex
            val bytes = source.address
            return subnets.any { (address, prefix) ->
                val base = address.address
                base.size == bytes.size && prefix in 1..base.size * 8 && (0 until prefix).all { bit ->
                    val mask = 0x80 shr (bit % 8)
                    (base[bit / 8].toInt() and mask) == (bytes[bit / 8].toInt() and mask)
                }
            }
        }

        /** A link-local IPv6 address only means something on one interface; pin it to the hotspot's. */
        fun onInterface(address: InetAddress, interfaceIndex: Int): InetAddress? = when {
            address !is Inet6Address || !address.isLinkLocalAddress -> address
            address.scopeId == interfaceIndex -> address
            address.scopeId != 0 -> null // Heard on some other interface: not the hotspot.
            else -> Inet6Address.getByAddress(null, address.address, interfaceIndex)
        }
    }

    private companion object {
        const val SEARCH_SECONDS = 110L
        const val MAX_ENDPOINTS = 6
        const val MAX_ATTEMPTS = 2
    }
}

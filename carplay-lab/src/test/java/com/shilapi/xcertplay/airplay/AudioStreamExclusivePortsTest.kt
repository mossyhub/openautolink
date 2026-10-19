package com.shilapi.xcertplay.airplay

import com.openautolink.carplaylab.network.CarPlaySocketBinding
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Actual kernel reservations, not a statistical port-allocation or socket-option test. */
class AudioStreamExclusivePortsTest {
    private val peer = InetAddress.getByName("127.0.0.1")
    private val key = ByteArray(32) { (it + 7).toByte() }
    private fun field(stream: AudioStream, name: String): Any? =
        AudioStream::class.java.getDeclaredField(name).apply { isAccessible = true }.get(stream)
    private fun sockets(stream: AudioStream) = listOf("dataSocket", "controlSocket").map {
        field(stream, it) as DatagramSocket
    }
    private fun assertReserved(address: SocketAddress) {
        // The contender opts into reuse, as the vulnerable production sockets did.
        DatagramSocket(null).use { contender ->
            contender.reuseAddress = true
            assertThrows("live audio socket must exclusively reserve $address", BindException::class.java) {
                contender.bind(address)
            }
        }
    }
    private fun reservation(binding: CarPlaySocketBinding?) {
        AudioStream(key, expectedPeer = peer, socketBinding = binding).use { stream ->
            stream.listen(object : AudioStream.Listener {})
            sockets(stream).forEach { socket ->
                assertFalse(socket.isClosed)
                assertTrue(socket.isBound)
                if (binding == null) assertTrue(socket.localAddress.isAnyLocalAddress)
                else assertEquals(binding.local, socket.localAddress)
                assertReserved(socket.localSocketAddress)
            }
        }
    }
    @Test fun wildcardDataAndControlExclusivelyReserveTheirLiveAddresses() = reservation(null)
    @Test fun explicitIpv4DataAndControlExclusivelyReserveTheirLiveAddresses() =
        reservation(CarPlaySocketBinding(peer, peer))
    @Test fun explicitIpv6DataAndControlExclusivelyReserveTheirLiveAddresses() {
        val ip = InetAddress.getByName("::1")
        reservation(CarPlaySocketBinding(ip, ip))
    }

    @Test fun simultaneousStreamsRouteAuthenticatedPacketsToTheirOwnDataSocket() {
        val streams = List(4) { AudioStream(key, expectedPeer = peer) }
        val arrivals = List(streams.size) { LinkedBlockingQueue<ByteArray>() }
        try {
            val ports = streams.mapIndexed { index, stream ->
                stream.listen(object : AudioStream.Listener {
                    override fun onRtp(rtp: ByteArray, sample: Int) { arrivals[index].add(rtp) }
                })
            }
            val allPorts = ports.flatMap { listOf(it.first, it.second) }
            assertEquals("data/control across all live streams must be distinct", allPorts.size, allPorts.toSet().size)
            streams.flatMap(::sockets).forEach { assertReserved(it.localSocketAddress) }
            val expected = streams.indices.map { index ->
                val header = byteArrayOf(0x80.toByte(), 100, 0, 1, 0, 0, 0, index.toByte(), 0, 0, 0, 1)
                val pcm = byteArrayOf(1, 2, 3, index.toByte())
                val wire = header + AirPlayCrypto.chachaSeal(key, ByteArray(12), pcm, header.copyOfRange(4, 12)) + ByteArray(8)
                DatagramSocket(InetSocketAddress(peer, 0)).use {
                    it.send(DatagramPacket(wire, wire.size, peer, ports[index].first))
                }
                header + pcm
            }
            expected.forEachIndexed { index, rtp ->
                assertArrayEquals("authenticated packet must reach stream $index", rtp, arrivals[index].poll(3, TimeUnit.SECONDS))
                assertTrue("no duplicate or cross-stream delivery", arrivals[index].isEmpty())
            }
        } finally { streams.forEach { it.close() } }
    }

    @Test fun closeReleasesBothReservationsAndRecreatedStreamReceives() {
        val original = AudioStream(key, expectedPeer = peer)
        original.listen(object : AudioStream.Listener {})
        val oldSockets = sockets(original)
        val addresses = oldSockets.map { it.localSocketAddress }
        val threads = listOf("dataThread", "controlThread").map { field(original, it) as Thread }
        original.close()
        original.close()
        oldSockets.forEach { assertTrue(it.isClosed) }
        threads.forEach { it.join(1000); assertFalse("closed receiver worker leaked", it.isAlive) }
        addresses.forEach { address -> DatagramSocket(null).use { it.bind(address) } }
        AudioStream(key, expectedPeer = peer).use { replacement ->
            val received = LinkedBlockingQueue<ByteArray>()
            val (port, _) = replacement.listen(object : AudioStream.Listener {
                override fun onRtp(rtp: ByteArray, sample: Int) { received.add(rtp) }
            })
            sockets(replacement).forEach { assertReserved(it.localSocketAddress) }
            val header = byteArrayOf(0x80.toByte(), 100, 0, 1, 0, 0, 0, 123, 0, 0, 0, 1)
            val pcm = byteArrayOf(1, 2, 3, 4)
            val wire = header + AirPlayCrypto.chachaSeal(key, ByteArray(12), pcm, header.copyOfRange(4, 12)) + ByteArray(8)
            DatagramSocket(InetSocketAddress(peer, 0)).use { it.send(DatagramPacket(wire, wire.size, peer, port)) }
            assertArrayEquals(header + pcm, received.poll(3, TimeUnit.SECONDS))
        }
    }
}

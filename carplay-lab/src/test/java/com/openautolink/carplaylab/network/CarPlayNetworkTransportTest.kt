package com.openautolink.carplaylab.network

import com.shilapi.xcertplay.airplay.*
import java.net.*
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test

class CarPlayNetworkTransportTest {
    private fun loopback6(): InetAddress {
        val ip = InetAddress.getByName("::1")
        try { ServerSocket(0, 1, ip).close() } catch (e: Exception) { assumeNoException(e) }
        return ip
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    @Test fun ipv6ScreenAndAudioDeliverAuthenticatedPayloads() {
        val ip = loopback6()
        val binding = CarPlaySocketBinding(ip, ip)
        val key = ByteArray(32) { (it + 1).toByte() }
        val screenFrame = java.util.concurrent.CountDownLatch(1)
        ScreenStream(key, socketBinding = binding).use { stream ->
            val port = stream.listen(object : ScreenStream.Listener {
                override fun onFrame(naluBytes: ByteArray) {
                    assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1), naluBytes)
                    screenFrame.countDown()
                }
            })
            assertThrows(java.io.IOException::class.java) {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
            }
            Socket(ip, port).use { phone ->
                val payload = byteArrayOf(0, 0, 0, 2, 0x65, 1)
                val header = ByteArray(128)
                java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(payload.size + 16)
                phone.getOutputStream().write(header + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), payload, header))
                assertTrue(screenFrame.await(2, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val audioFrame = java.util.concurrent.CountDownLatch(1)
        AudioStream(key, socketBinding = binding).use { stream ->
            val header = byteArrayOf(0x80.toByte(), 100, 0, 1, 0, 0, 0, 123, 0, 0, 0, 1)
            val payload = byteArrayOf(1, 2, 3, 4)
            val (port, _) = stream.listen(object : AudioStream.Listener {
                override fun onRtp(rtp: ByteArray, sample: Int) {
                    assertArrayEquals(header + payload, rtp)
                    audioFrame.countDown()
                }
            })
            DatagramSocket(InetSocketAddress(ip, 0)).use { phone ->
                val wire = header + AirPlayCrypto.chachaSeal(key, ByteArray(12), payload, header.copyOfRange(4, 12)) + ByteArray(8)
                phone.send(DatagramPacket(wire, wire.size, ip, port))
                assertTrue(audioFrame.await(2, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
    }
    @Test fun everyMediaTransportBindsExactIpv6WithNoSecondaryWildcard() {
        val ip = loopback6()
        val binding = CarPlaySocketBinding(ip, ip)
        ScreenStream(ByteArray(32), socketBinding = binding).use { stream ->
            val port = stream.listen(object : ScreenStream.Listener {})
            assertEquals(ip, (field(stream, "server") as ServerSocket).inetAddress)
            Socket().use { it.connect(InetSocketAddress(ip, port), 1000) }
        }
        AudioStream(ByteArray(32), socketBinding = binding).use { stream ->
            val ports = stream.listen(object : AudioStream.Listener {})
            assertEquals(ip, (field(stream, "dataSocket") as DatagramSocket).localAddress)
            assertEquals(ip, (field(stream, "controlSocket") as DatagramSocket).localAddress)
            assertTrue(ports.first > 0 && ports.second > 0)
        }
        IapTunnel(ByteArray(32), socketBinding = binding).use { stream ->
            val port = stream.listen(object : IapTunnel.Listener {})
            val servers = field(stream, "servers") as List<*>
            assertEquals(1, servers.size)
            assertEquals(ip, (servers.single() as ServerSocket).inetAddress)
            Socket().use { it.connect(InetSocketAddress(ip, port), 1000); assertTrue(stream.awaitPeerConnection(1000)) }
        }
        NtpClock(socketBinding = binding).use { ntp ->
            ntp.listen()
            assertEquals(ip, (field(ntp, "socket") as DatagramSocket).localAddress)
            assertThrows(IllegalArgumentException::class.java) { ntp.start(InetAddress.getByName("127.0.0.1"), 1) }
        }
    }
}

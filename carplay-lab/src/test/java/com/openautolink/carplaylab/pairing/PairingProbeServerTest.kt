package com.openautolink.carplaylab.pairing

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class PairingProbeServerTest {
    @Test fun noSelectedPeerFailsClosedAndSelectedPeerCannotSetupMedia() {
        val root = Files.createTempDirectory("pairing-test").toFile()
        val statuses = CopyOnWriteArrayList<String>()
        try {
            PairingProbeServer(root, "02:00:00:00:00:01", statuses::add).use { server ->
                server.start()
                assertTrue(server.port > 0)
                assertEquals(64, server.publicKeyHex.length)
                Socket("::1", server.port).use { socket ->
                    socket.soTimeout = 2000
                    assertEquals(-1, socket.getInputStream().read())
                }
                server.allowPeer(setOf("::1"))
                Socket("::1", server.port).use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().write("SETUP /stream RTSP/1.0\r\nCSeq: 9\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    val response = socket.getInputStream().bufferedReader().readLine()
                    assertEquals("RTSP/1.0 403 Forbidden", response)
                }
                assertTrue(statuses.none { it.contains("cryptographically verified") })
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun infoIsUpstreamBinaryPlistAndSelectionRevocationClosesSocket() {
        val root = Files.createTempDirectory("pairing-test").toFile()
        try {
            PairingProbeServer(root, "02:00:00:00:00:01") {}.use { server ->
                server.allowPeer(setOf("::1"))
                server.start()
                Socket("::1", server.port).use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().write("GET /info RTSP/1.0\r\n\r\n".toByteArray())
                    val reader = socket.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) header.append(reader.read().also { assertTrue(it >= 0) }.toChar())
                    assertTrue(header.startsWith("RTSP/1.0 200 OK"))
                    val length = Regex("Content-Length: ([0-9]+)").find(header)!!.groupValues[1].toInt()
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) offset += reader.read(body, offset, length - offset).also { assertTrue(it > 0) }
                    val info = com.shilapi.xcertplay.airplay.BplistCodec.decode(body) as Map<*, *>
                    assertEquals(listOf("02:00:00:00:00:01"), info["bluetoothIDs"])
                    assertNull(info["audioFormats"])
                    server.allowPeer(emptySet())
                    assertEquals(-1, reader.read())
                }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun missingSapAuthenticatorIsExplicitlyDeclined() {
        val root = Files.createTempDirectory("pairing-test").toFile()
        try {
            PairingProbeServer(root, "02:00:00:00:00:01") {}.use { server ->
                server.allowPeer(setOf("::1")); server.start()
                Socket("::1", server.port).use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().write("POST /auth-setup RTSP/1.0\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    assertEquals("RTSP/1.0 501 Not Implemented", socket.getInputStream().bufferedReader().readLine())
                }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun namesCannotResolveIntoAllowedPeers() {
        val policy = PeerPolicy()
        try { policy.set(setOf("localhost")); fail("DNS must not be used") } catch (_: IllegalArgumentException) { }
        assertFalse(policy.allows(java.net.InetAddress.getByName("::1")))
    }
    @Test fun shutdownIsBoundedAndClosesIdleConnection() {
        val root = Files.createTempDirectory("pairing-test").toFile()
        try {
            val server = PairingProbeServer(root, "02:00:00:00:00:01") {}
            server.allowPeer(setOf("::1")); server.start()
            Socket("::1", server.port).use { socket ->
                socket.soTimeout = 2000
                // connect() can complete while this connection is still in the listen backlog.
                // A closed listener resets pending connections on Linux. Complete a real request
                // first so this test closes an accepted, idle worker and still requires EOF.
                socket.getOutputStream().write("GET /info RTSP/1.0\r\n\r\n".toByteArray())
                val input = socket.getInputStream()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) {
                    header.append(input.read().also { assertTrue(it >= 0) }.toChar())
                }
                assertTrue(header.startsWith("RTSP/1.0 200 OK"))
                val length = Regex("Content-Length: ([0-9]+)").find(header)!!.groupValues[1].toInt()
                val body = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    offset += input.read(body, offset, length - offset).also { assertTrue(it > 0) }
                }
                val before = System.nanoTime()
                server.close()
                assertTrue((System.nanoTime() - before) / 1_000_000 < 2000)
                assertEquals(-1, socket.getInputStream().read())
            }
            server.close()
            try { server.start(); fail("Closed server restarted") } catch (_: IllegalStateException) { }
        } finally { root.deleteRecursively() }
    }
}

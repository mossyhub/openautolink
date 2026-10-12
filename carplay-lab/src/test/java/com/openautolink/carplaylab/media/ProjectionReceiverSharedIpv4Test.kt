package com.openautolink.carplaylab.media

import android.content.Context
import android.view.Surface
import com.openautolink.carplaylab.network.*
import com.openautolink.carplaylab.pairing.PairingPersistence
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigInteger
import java.net.*
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** Real receiver constructor/start/accept/session; independent SRP/Ed25519 and PairVerify client. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class ProjectionReceiverSharedIpv4Test {
    @Test fun storedControllerSharedIpv4SetupRequiresPairVerifyAndNeverWritesTrust() = runReconnect()
    @Test fun sharedIpv4RejectsReplacementKey() = runReconnect(replacement = true)
    @Test fun sharedIpv4RejectsUnrelatedController() = runReconnect(unrelated = true)
    @Test fun sharedIpv4RetirementRejectsPendingM5() = runReconnect(retireBeforeM5 = true)
    @Test fun sharedIpv4RejectsInvalidExistingSignature() = runReconnect(invalidSignature = true)
    @Test fun manualSharedIpv4FirstEnrollmentIsBoundedAndRequiresPairVerify() = runReconnect(firstEnrollment = true)
    @Test fun eachAcceptedControlGetsItsOwnClaimAndOldTokenCannotAuthorizeSuccessor() = runReconnect(reconnect = true)

    private fun runReconnect(replacement: Boolean = false, unrelated: Boolean = false,
        invalidSignature: Boolean = false, retireBeforeM5: Boolean = false,
        firstEnrollment: Boolean = false, reconnect: Boolean = false) {
        // Use a real unicast host endpoint: the production policy deliberately rejects loopback.
        val ip = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .first { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress && !it.isLinkLocalAddress }
        // API32 Robolectric carries real TCP bytes but may synthesize accept's peer metadata.
        // Measure that exact endpoint instead of pinning the developer host's 192.168.0.10.
        val peer = ServerSocket().use { probe ->
            probe.soTimeout = 5000
            probe.bind(InetSocketAddress(InetAddress.getByName("::"), 0), 2)
            Socket().use { client ->
                client.bind(InetSocketAddress(ip, 0)); client.connect(InetSocketAddress(ip, probe.localPort))
                client.getOutputStream().write(42)
                probe.accept().use { accepted ->
                    accepted.soTimeout = 5000
                    assertEquals(42, accepted.getInputStream().read())
                    assertEquals(ip, accepted.localAddress)
                    accepted.inetAddress.also { assertTrue(it is Inet4Address) }
                }
            }
        }
        val context = RuntimeEnvironment.getApplication() as Context
        val directory = File(context.noBackupFilesDir, "pairing-probe")
        directory.deleteRecursively()
        val trusted = AirPlayCrypto.ed25519Generate()
        val accessory = PairingPersistence.openReceiver(directory).use {
            if (!firstEnrollment) it.pairings.save("trusted", trusted.publicKey); it.identity
        }
        val state = File(directory, "state.bin")
        val startupState = state.readBytes()
        val startupFileKey = Files.readAttributes(state.toPath(), BasicFileAttributes::class.java).fileKey()
        val surface = mock(Surface::class.java)
        `when`(surface.isValid).thenReturn(true)
        val format = android.media.MediaFormat.createVideoFormat("video/avc", 1280, 720)
        val capabilities = org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format).setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(android.media.MediaCodecInfo.CodecProfileLevel().apply {
                profile = android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                level = android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel52
            })).build()
        org.robolectric.shadows.ShadowMediaCodecList.addCodec(
            org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder().setName("test.avc.decoder")
                .setIsEncoder(false).setCapabilities(capabilities).build())
        run {
            val receiver = ProjectionReceiver(context, "02:00:00:00:00:01", surface,
                mock(MfiAuthenticator::class.java), trustedOnly = true, onStatus = { println(it) })
            try {
                receiver.setRuntimeIdentification(Iap2IdentificationConfig(name = "test", modelIdentifier = "test",
                    manufacturer = "test", serialNumber = "test", firmwareVersion = "test", hardwareVersion = "test",
                    wireless = Iap2WirelessIdentification("02:00:00:00:00:01", "test")))
                if (firstEnrollment) receiver.authorizeFirstEnrollment(120_000)
                receiver.startSharedIpv4(CarPlayNetworkSelection(ip, NetworkInterface.getByInetAddress(ip).index, peer))
                @Suppress("UNCHECKED_CAST")
                fun session(): AirPlaySession = (ProjectionReceiver::class.java.getDeclaredField("allSessions")
                    .apply { isAccessible = true }.get(receiver) as Set<AirPlaySession>).single { !it.isClosed }
                fun admission(): CarPlayPeerAdmission? = ProjectionReceiver::class.java.getDeclaredField("peerAdmission")
                    .apply { isAccessible = true }.get(receiver) as CarPlayPeerAdmission?
                if (reconnect) {
                    val first = Socket().apply { bind(InetSocketAddress(ip, 0)); connect(InetSocketAddress(ip, receiver.port)) }
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while ((ProjectionReceiver::class.java.getDeclaredField("allSessions").apply { isAccessible = true }
                        .get(receiver) as Set<*>).isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
                    val old = session()
                    assertTrue(admission()!!.allowsPairSetup(old))
                    first.close()
                    while (!old.isClosed && System.nanoTime() < deadline) Thread.sleep(5)
                    old.awaitControlStopped()
                    assertFalse(admission()!!.allowsPairSetup(old))
                    // allSessions intentionally retains retired workers until receiver storage retirement.
                }
                Socket().apply { bind(InetSocketAddress(ip, 0)); connect(InetSocketAddress(ip, receiver.port)) }.use { phone ->
                    phone.soTimeout = 5000
                        var sequence = 0
                        fun exchange(method: String, path: String, body: ByteArray): Pair<Int, ByteArray> {
                            sequence++
                            val header = "$method $path RTSP/1.0\r\nCSeq: $sequence\r\nContent-Type: application/pairing+tlv8\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray()
                            phone.getOutputStream().apply { write(header + body); flush() }
                            val input = phone.getInputStream()
                            val bytes = java.io.ByteArrayOutputStream()
                            while (!bytes.toByteArray().takeLast(4).toByteArray().contentEquals("\r\n\r\n".toByteArray())) {
                                val b = input.read(); check(b >= 0); bytes.write(b)
                                check(bytes.size() < 8192)
                            }
                            val lines = bytes.toString("US-ASCII").split("\r\n")
                            assertTrue(lines.contains("CSeq: $sequence"))
                            val status = lines.first().split(" ")[1].toInt()
                            val length = lines.first { it.startsWith("Content-Length:") }.substringAfter(':').trim().toInt()
                            val response = ByteArray(length)
                            var read = 0
                            while (read < length) { val count = input.read(response, read, length - read); check(count > 0); read += count }
                            return status to response
                        }
                        fun setup(body: ByteArray): Map<Int, ByteArray> {
                            val (status, response) = exchange("POST", "/pair-setup", body)
                            assertEquals("Trusted reconnect must reach the real SRP responder, not blanket HTTP403", 200, status)
                            return Tlv8Codec.decode(response)
                        }
                        val m1 = tlv(0 to byteArrayOf(0), 6 to byteArrayOf(1))
                        assertEquals(6, m1.size)
                        val m2 = setup(m1)
                        assertEquals(2, m2.getValue(6).single().toInt())
                        assertNotNull(admission())
                        val accepted = session()
                        assertTrue(admission()!!.allowsPairSetup(accepted))
                        assertFalse(admission()!!.allowsPairSetup(Any()))
                        assertNull(ProjectionReceiver::class.java.getDeclaredField("network")
                            .apply { isAccessible = true }.get(receiver)) // No scoped auxiliary transport.
                        assertNull(AirPlaySession::class.java.getDeclaredField("socketBinding")
                            .apply { isAccessible = true }.get(accepted))
                        val listener = ProjectionReceiver::class.java.getDeclaredField("server")
                            .apply { isAccessible = true }.get(receiver) as ServerSocket
                        assertTrue(listener.inetAddress.isAnyLocalAddress)
                        assertFalse(session().controlAuthenticated)
                        assertNull(admission()?.authenticatedBinding(session()))
                        assertEquals(403, exchange("RECORD", "/stream", ByteArray(0)).first)
                        val salt = m2.getValue(2); val publicB = m2.getValue(3)
                        val n = Srp6a.modulus(); val g = BigInteger.valueOf(5)
                        val a = BigInteger("123456789abcdef123456789abcdef123456789abcdef", 16)
                        val publicA = pad(g.modPow(a, n))
                        val k = BigInteger(1, h(raw(n), pad(g)))
                        val x = BigInteger(1, h(salt, h("Pair-Setup:3939".toByteArray())))
                        val u = BigInteger(1, h(publicA, publicB))
                        val secret = (BigInteger(1, publicB) - k * g.modPow(x, n)).mod(n).modPow(a + u * x, n)
                        val key = h(raw(secret))
                        val hashN = h(raw(n)); val hashG = h(raw(g))
                        val xor = ByteArray(64) { (hashN[it].toInt() xor hashG[it].toInt()).toByte() }
                        val proof = h(xor, h("Pair-Setup".toByteArray()), salt, publicA, publicB, key)
                        val m4 = setup(tlv(6 to byteArrayOf(3), 3 to publicA, 4 to proof))
                        assertArrayEquals(h(publicA, proof, key), m4.getValue(4))
                        val candidate = if (replacement || unrelated) AirPlayCrypto.ed25519Generate() else trusted
                        val id = (if (unrelated) "unrelated" else "trusted").toByteArray()
                        val signature = AirPlayCrypto.ed25519Sign(candidate.privateKey, derive(key, "Pair-Setup-Controller-Sign") + id + candidate.publicKey)
                        if (invalidSignature) signature[0] = (signature[0].toInt() xor 1).toByte()
                        val encrypt = derive(key, "Pair-Setup-Encrypt")
                        val m5 = tlv(6 to byteArrayOf(5), 5 to AirPlayCrypto.chachaSeal(encrypt, AirPlayCrypto.nonceLabel("PS-Msg05"),
                            tlv(1 to id, 3 to candidate.publicKey, 10 to signature)))
                        if (retireBeforeM5) receiver.revokePendingAdmission()
                        val failed = replacement || unrelated || invalidSignature || retireBeforeM5
                        if (retireBeforeM5) assertEquals(403, exchange("POST", "/pair-setup", m5).first)
                        else {
                            val m6 = setup(m5)
                            if (failed) assertEquals(2, m6.getValue(7).single().toInt())
                            else {
                                val sub = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encrypt, AirPlayCrypto.nonceLabel("PS-Msg06"), m6.getValue(5)))
                                assertArrayEquals(accessory.publicKey, sub.getValue(3))
                                assertTrue(AirPlayCrypto.ed25519Verify(accessory.publicKey,
                                    derive(key, "Pair-Setup-Accessory-Sign") + sub.getValue(1) + sub.getValue(3), sub.getValue(10)))
                            }
                        }
                        if (firstEnrollment) assertNotEquals(startupFileKey, Files.readAttributes(state.toPath(), BasicFileAttributes::class.java).fileKey())
                        else {
                            assertEquals("Reconnect must perform zero durable trust writes", startupFileKey, Files.readAttributes(state.toPath(), BasicFileAttributes::class.java).fileKey())
                            assertArrayEquals(startupState, state.readBytes())
                        }
                        val postSetupState = state.readBytes()
                        val postSetupFileKey = Files.readAttributes(state.toPath(), BasicFileAttributes::class.java).fileKey()
                        assertFalse(session().controlAuthenticated)
                        assertNull(admission()?.authenticatedBinding(session()))
                        assertEquals(403, exchange("RECORD", "/stream", ByteArray(0)).first)
                        if (!failed) {
                            fun verify(body: ByteArray): Map<Int, ByteArray> {
                                val response = exchange("POST", "/pair-verify", body)
                                assertEquals(200, response.first); return Tlv8Codec.decode(response.second)
                            }
                            val ephemeral = AirPlayCrypto.x25519Generate()
                            val pv2 = verify(tlv(6 to byteArrayOf(1), 3 to ephemeral.publicKey))
                            val remote = pv2.getValue(3)
                            val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, remote)
                            val pvKey = derive(shared, "Pair-Verify-Encrypt")
                            val sig = AirPlayCrypto.ed25519Sign(trusted.privateKey, ephemeral.publicKey + id + remote)
                            val pv4 = verify(tlv(6 to byteArrayOf(3), 5 to AirPlayCrypto.chachaSeal(pvKey,
                                AirPlayCrypto.nonceLabel("PV-Msg03"), tlv(1 to id, 10 to sig))))
                            assertEquals(4, pv4.getValue(6).single().toInt())
                            assertTrue(session().controlAuthenticated)
                            val binding = admission()?.authenticatedBinding(session())
                            assertNotNull(binding)
                            assertEquals(ip, binding!!.local)
                            assertEquals(peer, binding.peer)
                            assertEquals(postSetupFileKey, Files.readAttributes(state.toPath(), BasicFileAttributes::class.java).fileKey())
                            assertArrayEquals(postSetupState, state.readBytes())
                        }

                }
            } finally { receiver.close(); assertTrue(receiver.awaitClosed(5000)) }
            PairingPersistence.openReceiver(directory).use {
                assertEquals(setOf("trusted"), it.pairings.controllerIdentifiers())
                assertArrayEquals(trusted.publicKey, it.pairings.get("trusted"))
            }
        }
    }
    private fun h(vararg parts: ByteArray) = AirPlayCrypto.sha512(*parts)
    private fun derive(key: ByteArray, prefix: String) = AirPlayCrypto.hkdfSha512(key, "$prefix-Salt".toByteArray(), "$prefix-Info".toByteArray())
    private fun raw(n: BigInteger): ByteArray = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    private fun pad(n: BigInteger): ByteArray = raw(n).let { bytes -> ByteArray(384).also { bytes.copyInto(it, 384 - bytes.size) } }
    private fun tlv(vararg fields: Pair<Int, ByteArray>) = Tlv8Codec.encode(fields.map { Tlv8Item(it.first, it.second) })
}

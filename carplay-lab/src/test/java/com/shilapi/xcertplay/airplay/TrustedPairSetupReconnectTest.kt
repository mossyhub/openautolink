package com.shilapi.xcertplay.airplay

import com.openautolink.carplaylab.network.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic keys, actual TCP RTSP framing, independent SRP client and stored-key PairVerify. */
class TrustedPairSetupReconnectTest {
    @Test fun savedControllerRepeatsSixBytePairSetupThenPairVerifyWithoutAnyTrustWrite() = runReconnect()
    @Test fun validSrpAndNewKeyUnderExistingIdentifierCannotReplaceTrust() = runReconnect(replacement = true)
    @Test fun validSrpAndUnrelatedControllerCannotAddTrust() = runReconnect(unrelated = true)
    @Test fun invalidExistingKeySignatureCannotCompleteSetup() = runReconnect(invalidSignature = true)
    @Test fun retiredOwnerRejectsPendingRealM5() = runReconnect(retireBeforeM5 = true)

    @Test fun releasedOwnerCannotAcknowledgeExistingKeyForSuccessor() {
        val ip = InetAddress.getByName("127.0.0.1")
        val controller = AirPlayCrypto.ed25519Generate()
        val store = PairingStore(); store.save("trusted", controller.publicKey)
        val admission = CarPlayPeerAdmission(CarPlayNetworkSelection(ip, 1, ip), store.controllerIdentifiers(), trustedOnly = true)
        val old = Any(); val next = Any()
        assertFalse(admission.allowsPairSetup(old))
        assertTrue(admission.claim(old, ip, ip))
        admission.release(old)
        assertTrue(admission.claim(next, ip, ip))
        assertFalse(admission.allowsPairSetup(old))
        assertFalse(admission.persistEnrollment(old, "trusted", controller.publicKey, store))
        assertTrue(admission.persistEnrollment(next, "trusted", controller.publicKey, store))
        assertNull(admission.authenticatedBinding(next))
    }

    private fun runReconnect(replacement: Boolean = false, unrelated: Boolean = false,
        invalidSignature: Boolean = false, retireBeforeM5: Boolean = false) {
        val ip = InetAddress.getByName("127.0.0.1")
        val accessory = AirPlayIdentity.generate()
        val trusted = AirPlayCrypto.ed25519Generate()
        var writes = 0
        val store = PairingStore { _, _ -> writes++ }
        store.save("trusted", trusted.publicKey)
        val startupWrites = writes
        val admission = CarPlayPeerAdmission(CarPlayNetworkSelection(ip, 1, ip), store.controllerIdentifiers(), trustedOnly = true)
        ServerSocket(0, 1, ip).use { server ->
            Socket(ip, server.localPort).use { phone ->
                phone.soTimeout = 5000
                server.accept().use { control ->
                    val authenticated = CountDownLatch(1)
                    lateinit var session: AirPlaySession
                    session = AirPlaySession(control,
                        AirPlayConfig("test", "test", "test", "950.7.1", AirPlayDisplayConfig(800, 480)),
                        accessory, store, null, object : AirPlaySessionListener {
                            override fun onControlAuthenticated(session: AirPlaySession): Boolean {
                                val accepted = admission.authenticate(session, session.controllerId)
                                if (accepted) authenticated.countDown()
                                return accepted
                            }
                        }, object : AirPlayMediaHandler {}, socketBinding = CarPlaySocketBinding(ip, ip),
                        allowPairSetup = true,
                        pairSetupAdmission = { admission.allowsPairSetup(it) },
                        saveController = { id, key -> admission.persistEnrollment(session, id, key, store) })
                    try {
                        assertTrue(admission.claim(session, ip, ip))
                        session.start()
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
                        assertFalse(session.controlAuthenticated)
                        assertNull(admission.authenticatedBinding(session))
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
                        if (retireBeforeM5) admission.retire()
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
                        assertEquals(startupWrites, writes)
                        assertEquals(setOf("trusted"), store.controllerIdentifiers())
                        assertArrayEquals(trusted.publicKey, store.get("trusted"))
                        assertFalse(session.controlAuthenticated)
                        assertNull(admission.authenticatedBinding(session))
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
                            assertTrue(authenticated.await(5, TimeUnit.SECONDS))
                            assertNotNull(admission.authenticatedBinding(session))
                            assertEquals(startupWrites, writes)
                        }
                    } finally { session.close(); session.awaitControlStopped() }
                }
            }
        }
    }
    private fun h(vararg parts: ByteArray) = AirPlayCrypto.sha512(*parts)
    private fun derive(key: ByteArray, prefix: String) = AirPlayCrypto.hkdfSha512(key, "$prefix-Salt".toByteArray(), "$prefix-Info".toByteArray())
    private fun raw(n: BigInteger): ByteArray = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    private fun pad(n: BigInteger): ByteArray = raw(n).let { bytes -> ByteArray(384).also { bytes.copyInto(it, 384 - bytes.size) } }
    private fun tlv(vararg fields: Pair<Int, ByteArray>) = Tlv8Codec.encode(fields.map { Tlv8Item(it.first, it.second) })
}

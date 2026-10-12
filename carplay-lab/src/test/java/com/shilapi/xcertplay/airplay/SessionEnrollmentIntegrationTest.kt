package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import com.openautolink.carplaylab.network.*

/** Genuine synthetic SRP/Ed25519 through the real session constructor and PairVerify boundary. */
class SessionEnrollmentIntegrationTest {
    private val config = AirPlayConfig("test","test","test","950.7.1",AirPlayDisplayConfig(800,480))
    @Test fun sessionMustForwardRevocableDurableCallbackRatherThanPlainSaving() {
        val identity = AirPlayIdentity.generate(); val store = PairingStore(); var commits = 0
        val session = AirPlaySession(java.net.Socket(),config,identity,store,null,
            object : AirPlaySessionListener {},object : AirPlayMediaHandler {},
            saveController={ _, _ -> commits++; false })
        try {
            transcript(session.pairSetup,identity,store,expectedSuccess=false)
            assertEquals(1,commits)
        } finally { session.close() }
    }
    @Test fun actualSessionRequiresExactClaimAndRealPairVerifyAfterFirstCommit() {
        val identity=AirPlayIdentity.generate(); val store=PairingStore()
        val ip=java.net.InetAddress.getByName("127.0.0.1")
        val admission=CarPlayPeerAdmission(CarPlayNetworkSelection(ip,1,ip),emptySet(),true,120_000)
        lateinit var session: AirPlaySession
        session=AirPlaySession(java.net.Socket(),config,identity,store,null,
            object : AirPlaySessionListener {
                override fun onControlAuthenticated(session: AirPlaySession)=admission.authenticate(session,session.controllerId)
            },object : AirPlayMediaHandler {},saveController={id,key -> admission.persistEnrollment(session,id,key,store)},
            pairSetupAdmission={ owner -> admission.allowsPairSetup(owner) })
        val handle=session.javaClass.getDeclaredMethod("handle",RtspMessage.Request::class.java).apply { isAccessible=true }
        fun request()=handle.invoke(session,RtspMessage.Request("POST","/pair-setup","RTSP/1.0",emptyMap(),tlv(6 to byteArrayOf(1)))) as RtspMessage.Response
        try {
            assertEquals("unclaimed session cannot start PairSetup",403,request().status)
            assertTrue(admission.claim(session,ip,ip))
            assertFalse(session.finishControlAuthentication())
            transcript(session.pairSetup,identity,store,afterSuccess={controller ->
                assertFalse("PairSetup is not media authentication",session.controlAuthenticated)
                val ephemeral=AirPlayCrypto.x25519Generate()
                val m2=Tlv8Codec.decode(session.pairVerify.handle(tlv(6 to byteArrayOf(1),3 to ephemeral.publicKey)))
                val remote=m2.getValue(3)
                val secret=AirPlayCrypto.x25519Shared(ephemeral.privateKey,remote)
                val key=derive(secret,"Pair-Verify-Encrypt")
                val id="synthetic-controller".toByteArray()
                val signature=AirPlayCrypto.ed25519Sign(controller.privateKey,ephemeral.publicKey+id+remote)
                session.pairVerify.handle(tlv(6 to byteArrayOf(3),5 to AirPlayCrypto.chachaSeal(key,
                    AirPlayCrypto.nonceLabel("PV-Msg03"),tlv(1 to id,10 to signature))))
                assertTrue(session.finishControlAuthentication())
                assertTrue(session.controlAuthenticated)
                assertNotNull(admission.authenticatedBinding(session))
                assertEquals(403,request().status)
            })
            admission.retire()
            assertFalse(admission.authenticate(session,session.controllerId))
        } finally { session.close() }
    }
    private fun transcript(setup: PairSetup, accessory: AirPlayIdentity, store: PairingStore,
        beforeM5: () -> Unit = {}, expectedSuccess: Boolean = true, invalidSignature: Boolean = false,
        afterSuccess: (AirPlayCrypto.Ed25519KeyPair) -> Unit = {}) {
        val m2 = Tlv8Codec.decode(setup.handle(tlv(6 to byteArrayOf(1))))
        val salt = m2.getValue(2)
        val publicB = m2.getValue(3)
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
        val m4 = Tlv8Codec.decode(setup.handle(tlv(6 to byteArrayOf(3), 3 to publicA, 4 to proof)))
        assertArrayEquals(h(publicA, proof, key), m4.getValue(4))
        assertFalse(setup.complete)
        val controller = AirPlayCrypto.ed25519Generate()
        val id = "synthetic-controller".toByteArray()
        assertNull(store.get(id.toString(Charsets.UTF_8)))
        val controllerSign = derive(key, "Pair-Setup-Controller-Sign")
        val signature = AirPlayCrypto.ed25519Sign(controller.privateKey, controllerSign + id + controller.publicKey)
        if (invalidSignature) signature[0] = (signature[0].toInt() xor 1).toByte()
        val encrypt = derive(key, "Pair-Setup-Encrypt")
        val sealed = AirPlayCrypto.chachaSeal(encrypt, AirPlayCrypto.nonceLabel("PS-Msg05"), tlv(1 to id, 3 to controller.publicKey, 10 to signature))
        beforeM5()
        val m6 = Tlv8Codec.decode(setup.handle(tlv(6 to byteArrayOf(5), 5 to sealed)))
        if (!expectedSuccess) {
            assertFalse(setup.complete)
            assertEquals(2, m6.getValue(7).single().toInt())
            assertNull(store.get(id.toString(Charsets.UTF_8)))
            return
        }
        assertTrue(setup.complete)
        assertArrayEquals(controller.publicKey, store.get(id.toString(Charsets.UTF_8)))
        val sub = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encrypt, AirPlayCrypto.nonceLabel("PS-Msg06"), m6.getValue(5)))
        assertEquals(accessory.pairingId, sub.getValue(1).toString(Charsets.UTF_8))
        assertArrayEquals(accessory.publicKey, sub.getValue(3))
        assertTrue(AirPlayCrypto.ed25519Verify(accessory.publicKey, derive(key, "Pair-Setup-Accessory-Sign") + sub.getValue(1) + sub.getValue(3), sub.getValue(10)))
        afterSuccess(controller)
    }
    private fun h(vararg parts: ByteArray) = AirPlayCrypto.sha512(*parts)
    private fun derive(key: ByteArray, prefix: String) = AirPlayCrypto.hkdfSha512(key, "$prefix-Salt".toByteArray(), "$prefix-Info".toByteArray())
    private fun raw(n: BigInteger): ByteArray = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    private fun pad(n: BigInteger): ByteArray = raw(n).let { bytes -> ByteArray(384).also { bytes.copyInto(it, 384 - bytes.size) } }
    private fun tlv(vararg fields: Pair<Int, ByteArray>) = Tlv8Codec.encode(fields.map { Tlv8Item(it.first, it.second) })
}

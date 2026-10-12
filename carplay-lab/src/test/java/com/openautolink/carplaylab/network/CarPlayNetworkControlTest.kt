package com.openautolink.carplaylab.network

import com.shilapi.xcertplay.airplay.*
import java.net.*
import org.junit.Assert.*
import org.junit.Test

class CarPlayNetworkControlTest {
    private val config = AirPlayConfig("network-test", "test", "test", "950.7.1", AirPlayDisplayConfig(800, 480))
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun call(owner: Any, name: String): Int = owner.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(owner) as Int
    @Test fun realPairVerifyIsRequiredBeforePeerLearningCallback() {
        val ip = InetAddress.getByName("::1")
        val store = PairingStore()
        val controller = AirPlayCrypto.ed25519Generate()
        store.save("trusted", controller.publicKey)
        val selection = CarPlayNetworkSelection(ip, 1, null)
        val admission = CarPlayPeerAdmission(selection, store.controllerIdentifiers())
        ServerSocket(0, 1, ip).use { listener ->
            Socket(ip, listener.localPort).use { phone ->
                listener.accept().use { control ->
                    var callbacks = 0
                    val session = AirPlaySession(control, config, AirPlayIdentity.generate(), store, null,
                        object : AirPlaySessionListener {
                            override fun onControlAuthenticated(session: AirPlaySession): Boolean {
                                callbacks++
                                return admission.authenticate(session, session.controllerId)
                            }
                        }, object : AirPlayMediaHandler {}, socketBinding = CarPlaySocketBinding(ip, ip), allowPairSetup = false)
                    session.use {
                        assertTrue(admission.claim(session, ip, ip))
                        assertFalse(session.finishControlAuthentication())
                        assertEquals(0, callbacks)
                        val verify = field(session, "pairVerify") as PairVerify
                        fun tlv(vararg fields: Pair<Int, ByteArray>) = Tlv8Codec.encode(fields.map { Tlv8Item(it.first, it.second) })
                        val ephemeral = AirPlayCrypto.x25519Generate()
                        val m2 = Tlv8Codec.decode(verify.handle(tlv(6 to byteArrayOf(1), 3 to ephemeral.publicKey)))
                        val remote = m2.getValue(3)
                        val secret = AirPlayCrypto.x25519Shared(ephemeral.privateKey, remote)
                        val key = AirPlayCrypto.hkdfSha512(secret, "Pair-Verify-Encrypt-Salt".toByteArray(), "Pair-Verify-Encrypt-Info".toByteArray())
                        val id = "trusted".toByteArray()
                        val signature = AirPlayCrypto.ed25519Sign(controller.privateKey, ephemeral.publicKey + id + remote)
                        verify.handle(tlv(6 to byteArrayOf(3), 5 to AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonceLabel("PV-Msg03"), tlv(1 to id, 10 to signature))))
                        assertTrue(session.finishControlAuthentication())
                        assertTrue(session.controlAuthenticated)
                        assertNotNull(admission.authenticatedBinding(session))
                        assertTrue(session.finishControlAuthentication())
                        assertEquals(1, callbacks)
                    }
                }
            }
        }
    }
    @Test fun controlAncillarySocketsUseExactSessionLocalAddress() {
        val local = InetAddress.getByName("::1")
        ServerSocket(0, 1, local).use { listener ->
            Socket(local, listener.localPort).use { phone ->
                listener.accept().use { control ->
                    AirPlaySession(control, config, AirPlayIdentity.generate(), PairingStore(), null,
                        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
                        socketBinding = CarPlaySocketBinding(local, local), allowPairSetup = false).use { session ->
                        assertTrue(call(session, "openEvent") > 0)
                        assertEquals(local, (field(session, "eventServer") as ServerSocket).inetAddress)
                        assertTrue(call(session, "openKeepAlive") > 0)
                        assertEquals(local, (field(session, "keepAliveSocket") as DatagramSocket).localAddress)
                    }
                }
            }
        }
    }
    @Test fun unknownPeerCannotEnrollNewControllerOrSetupMedia() {
        val local = InetAddress.getByName("::1")
        ServerSocket(0, 1, local).use { listener ->
            Socket(local, listener.localPort).use { phone ->
                listener.accept().use { control ->
                    AirPlaySession(control, config, AirPlayIdentity.generate(), PairingStore(), null,
                        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
                        socketBinding = CarPlaySocketBinding(local, local), allowPairSetup = false).use { session ->
                        val handle = session.javaClass.getDeclaredMethod("handle", RtspMessage.Request::class.java).apply { isAccessible = true }
                        for ((method, path) in listOf("POST" to "/pair-setup", "SETUP" to "/stream")) {
                            val response = handle.invoke(session, RtspMessage.Request(method, path, "RTSP/1.0", emptyMap(), ByteArray(0))) as RtspMessage.Response
                            assertEquals(403, response.status)
                        }
                    }
                }
            }
        }
        val store = PairingStore()
        store.save("trusted", ByteArray(32))
        val snapshot = store.controllerIdentifiers()
        store.save("later", ByteArray(32) { 1 })
        assertEquals(setOf("trusted"), snapshot)
    }
}

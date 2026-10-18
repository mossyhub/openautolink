package com.openautolink.carplaylab.pairing

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReceiverPairingIdentityTest {
    @Test fun persistedControllerVerifiesAfterReceiverReopenWithoutPeerIpEnrollment() {
        val root = Files.createTempDirectory("receiver-identity-test").toFile()
        try {
            val controller = AirPlayCrypto.ed25519Generate()
            val unrelated = AirPlayCrypto.ed25519Generate()
            PairingPersistence.openReceiver(root).use { state ->
                // Synthetic authorized fixture, not production first-enrollment permission.
                state.pairings.save("trusted", controller.publicKey)
            }
            PairingPersistence.openReceiver(root).use { state ->
                assertFalse(verify(state.identity, state.pairings, "trusted", unrelated.privateKey))
                assertFalse(verify(state.identity, state.pairings, "unrelated", unrelated.privateKey))
                assertTrue(verify(state.identity, state.pairings, "trusted", controller.privateKey))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun multiplePhoneKeysVerifyIndependentlyAfterReceiverReopen() {
        val root = Files.createTempDirectory("receiver-multiple-identity").toFile()
        try {
            val phoneA = AirPlayCrypto.ed25519Generate()
            val phoneB = AirPlayCrypto.ed25519Generate()
            PairingPersistence.openReceiver(root).use { it.pairings.save("phone-a", phoneA.publicKey) }
            PairingPersistence.openReceiver(root).use { it.pairings.save("phone-b", phoneB.publicKey) }
            PairingPersistence.openReceiver(root).use {
                assertTrue(verify(it.identity, it.pairings, "phone-a", phoneA.privateKey))
                assertTrue(verify(it.identity, it.pairings, "phone-b", phoneB.privateKey))
                assertTrue(verify(it.identity, it.pairings, "phone-a", phoneA.privateKey))
                assertFalse(verify(it.identity, it.pairings, "phone-a", phoneB.privateKey))
                assertFalse(verify(it.identity, it.pairings, "unknown-phone", phoneB.privateKey))
            }
        } finally { root.deleteRecursively() }
    }

    private fun verify(identity: AirPlayIdentity, store: PairingStore, id: String, privateKey: ByteArray): Boolean {
        val responder = PairVerify(identity, store)
        val ephemeral = AirPlayCrypto.x25519Generate()
        val m2 = Tlv8Codec.decode(responder.handle(tlv(6 to byteArrayOf(1), 3 to ephemeral.publicKey)))
        val remote = m2.getValue(3)
        val shared = AirPlayCrypto.x25519Shared(ephemeral.privateKey, remote)
        val key = AirPlayCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt".toByteArray(), "Pair-Verify-Encrypt-Info".toByteArray())
        val serverProof = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonceLabel("PV-Msg02"), m2.getValue(5)))
        assertEquals(identity.pairingId, serverProof.getValue(1).toString(Charsets.UTF_8))
        assertTrue(AirPlayCrypto.ed25519Verify(identity.publicKey, remote + serverProof.getValue(1) + ephemeral.publicKey, serverProof.getValue(10)))
        val identifier = id.toByteArray()
        val proof = AirPlayCrypto.ed25519Sign(privateKey, ephemeral.publicKey + identifier + remote)
        responder.handle(tlv(6 to byteArrayOf(3), 5 to AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonceLabel("PV-Msg03"), tlv(1 to identifier, 10 to proof))))
        return responder.isVerified
    }
    private fun tlv(vararg fields: Pair<Int, ByteArray>) = Tlv8Codec.encode(fields.map { Tlv8Item(it.first, it.second) })

    @Test fun emptyInstallCreatesAccessoryIdentityWithoutCreatingPhoneTrust() {
        val root = Files.createTempDirectory("receiver-identity-test").toFile()
        try {
            val directory = File(root, "pairing-probe")
            val id: String
            val publicKey: ByteArray
            PairingPersistence.openReceiver(directory).use { state ->
                id = state.identity.pairingId
                publicKey = state.identity.publicKey.copyOf()
                assertTrue(File(directory, "state.bin").isFile)
                assertTrue(state.pairings.controllerIdentifiers().isEmpty())
                assertFalse(File(root, com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient.DIRECTORY).exists())
            }
            PairingPersistence.openReceiver(directory).use { reopened ->
                assertEquals(id, reopened.identity.pairingId)
                assertArrayEquals(publicKey, reopened.identity.publicKey)
                assertTrue(reopened.pairings.controllerIdentifiers().isEmpty())
            }
        } finally { root.deleteRecursively() }
    }
}

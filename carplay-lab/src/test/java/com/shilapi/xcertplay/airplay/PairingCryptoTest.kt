package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class PairingCryptoTest {
    @Test fun verifyNeedsStoredControllerAndValidSignature() {
        val accessory = AirPlayIdentity.generate()
        val controller = AirPlayCrypto.ed25519Generate()
        val store = PairingStore()
        store.save("test-controller", controller.publicKey)
        val verify = PairVerify(accessory, store)
        val ephemeral = AirPlayCrypto.x25519Generate()
        val m2 = Tlv8Codec.decode(verify.handle(tlv(6 to byteArrayOf(1), 3 to ephemeral.publicKey)))
        assertFalse(verify.isVerified)
        val serverEphemeral = m2.getValue(3)
        val secret = AirPlayCrypto.x25519Shared(ephemeral.privateKey, serverEphemeral)
        val encrypt = AirPlayCrypto.hkdfSha512(secret, "Pair-Verify-Encrypt-Salt".toByteArray(), "Pair-Verify-Encrypt-Info".toByteArray())
        val server = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encrypt, AirPlayCrypto.nonceLabel("PV-Msg02"), m2.getValue(5)))
        assertTrue(AirPlayCrypto.ed25519Verify(accessory.publicKey, serverEphemeral + server.getValue(1) + ephemeral.publicKey, server.getValue(10)))
        val id = "test-controller".toByteArray()
        val signed = AirPlayCrypto.ed25519Sign(controller.privateKey, ephemeral.publicKey + id + serverEphemeral)
        val m3 = tlv(6 to byteArrayOf(3), 5 to AirPlayCrypto.chachaSeal(encrypt, AirPlayCrypto.nonceLabel("PV-Msg03"), tlv(1 to id, 10 to signed)))
        assertFalse(Tlv8Codec.decode(verify.handle(m3)).containsKey(7))
        assertTrue(verify.isVerified)
        val keys = verify.controlKeys!!
        assertArrayEquals(AirPlayCrypto.hkdfSha512(secret, "Control-Salt".toByteArray(), "Control-Write-Encryption-Key".toByteArray()), keys.readKey)
        // A replay must not retain a stale verified state.
        assertTrue(Tlv8Codec.decode(verify.handle(m3)).containsKey(7))
        assertFalse(verify.isVerified)
        assertNull(verify.controlKeys)
    }
    @Test fun corruptedAeadAndBadSignatureDoNotVerify() {
        for (corruptTag in listOf(true, false)) {
            val identity = AirPlayIdentity.generate()
            val store = PairingStore(); store.save("known", AirPlayCrypto.ed25519Generate().publicKey)
            val verify = PairVerify(identity, store)
            val client = AirPlayCrypto.x25519Generate()
            val m2 = Tlv8Codec.decode(verify.handle(tlv(6 to byteArrayOf(1), 3 to client.publicKey)))
            val secret = AirPlayCrypto.x25519Shared(client.privateKey, m2.getValue(3))
            val key = AirPlayCrypto.hkdfSha512(secret, "Pair-Verify-Encrypt-Salt".toByteArray(), "Pair-Verify-Encrypt-Info".toByteArray())
            val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonceLabel("PV-Msg03"), tlv(1 to "known".toByteArray(), 10 to ByteArray(64)))
            if (corruptTag) sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
            assertTrue(Tlv8Codec.decode(verify.handle(tlv(6 to byteArrayOf(3), 5 to sealed))).containsKey(7))
            assertFalse(verify.isVerified)
            assertNull(verify.controlKeys)
        }
    }
    @Test fun setupRejectsOutOfOrderAndZeroSrpKey() {
        val setup = PairSetup(AirPlayIdentity.generate(), PairingStore())
        assertTrue(Tlv8Codec.decode(setup.handle(tlv(6 to byteArrayOf(5), 5 to ByteArray(16)))).containsKey(7))
        assertFalse(setup.complete)
        val fresh = PairSetup(AirPlayIdentity.generate(), PairingStore())
        assertEquals(2, Tlv8Codec.decode(fresh.handle(tlv(6 to byteArrayOf(1)))).getValue(6)[0].toInt())
        assertTrue(Tlv8Codec.decode(fresh.handle(tlv(6 to byteArrayOf(3), 3 to ByteArray(384), 4 to ByteArray(64)))).containsKey(7))
        assertFalse(fresh.complete)
    }
    @Test fun tlvRejectsTruncationAndDuplicateState() {
        for (wire in listOf(byteArrayOf(6), byteArrayOf(6, 2, 1), tlv(6 to byteArrayOf(1), 6 to byteArrayOf(3)))) {
            try { Tlv8Codec.decode(wire); fail("Accepted malformed TLV") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun controlCipherAuthenticatesHeaderAndTag() {
        val a = ByteArray(32) { 3 }; val b = ByteArray(32) { 4 }
        val tx = ControlCipher(b, a); val rx = ControlCipher(a, b)
        val plaintext = ByteArray(20000) { it.toByte() }
        val wire = tx.encrypt(plaintext)
        assertTrue(rx.decrypt(wire.copyOf(1)).data.isEmpty())
        assertArrayEquals(plaintext, rx.decrypt(wire).data)
        val next = tx.encrypt(byteArrayOf(1))
        next[next.lastIndex] = (next.last().toInt() xor 1).toByte()
        try { rx.decrypt(next); fail("Accepted forged tag") } catch (_: Exception) { }
    }
    @Test fun storeDoesNotPublishFailedPersistenceOrOverwriteAControllerKey() {
        val key = AirPlayCrypto.ed25519Generate().publicKey
        val broken = PairingStore { _, _ -> throw java.io.IOException() }
        try { broken.save("test", key); fail("Expected storage failure") } catch (_: java.io.IOException) { }
        assertNull(broken.get("test"))
        val store = PairingStore(); store.save("test", key)
        try { store.save("test", AirPlayCrypto.ed25519Generate().publicKey); fail("Allowed key replacement") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(key, store.get("test"))
    }
    private fun tlv(vararg fields: Pair<Int, ByteArray>): ByteArray = Tlv8Codec.encode(fields.map { Tlv8Item(it.first, it.second) })
}

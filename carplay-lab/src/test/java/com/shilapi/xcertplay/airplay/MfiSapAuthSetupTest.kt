package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Synthetic authenticator fixtures only; these tests never read an MFi identity/certificate. */
class MfiSapAuthSetupTest {
    @Test fun malformedRequestDoesNotTouchAuthenticator() {
        val unavailable = object : MfiAuthenticator {
            override fun protocolMajor(): Int = error("Must not touch authenticator")
            override fun readCertificate(maximumOutputLength: Int): ByteArray = error("Must not touch authenticator")
            override fun signChallenge(challenge: ByteArray): ByteArray = error("Must not touch authenticator")
        }
        assertNull(MfiSapAuthSetup.handle(ByteArray(0), unavailable))
        assertNull(MfiSapAuthSetup.handle(ByteArray(33), unavailable))
    }
    @Test fun sapResponseUsesUpstreamX25519Sha256AndAesCtrFraming() {
        val client = AirPlayCrypto.x25519Generate()
        val fixtureCertificate = "synthetic-test-certificate".toByteArray()
        val fixtureSignature = ByteArray(64) { it.toByte() }
        var receivedDigest: ByteArray? = null
        val fixture = object : MfiAuthenticator {
            override fun protocolMajor() = 3
            override fun readCertificate(maximumOutputLength: Int) = fixtureCertificate
            override fun signChallenge(challenge: ByteArray): ByteArray {
                receivedDigest = challenge.copyOf()
                return fixtureSignature
            }
        }
        val response = MfiSapAuthSetup.handle(byteArrayOf(1) + client.publicKey, fixture)!!
        val serverPublic = response.copyOfRange(0, 32)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(serverPublic + client.publicKey), receivedDigest)
        val shared = AirPlayCrypto.x25519Shared(client.privateKey, serverPublic)
        val key = MessageDigest.getInstance("SHA-1").digest("AES-KEY".toByteArray() + shared).copyOf(16)
        val iv = MessageDigest.getInstance("SHA-1").digest("AES-IV".toByteArray() + shared).copyOf(16)
        val certificateLength = java.nio.ByteBuffer.wrap(response, 32, 4).int
        assertArrayEquals(fixtureCertificate, response.copyOfRange(36, 36 + certificateLength))
        val signatureStart = 40 + certificateLength
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        assertArrayEquals(fixtureSignature, cipher.doFinal(response.copyOfRange(signatureStart, response.size)))
        // This proves framing/primitives only, NOT real MFi licensing or phone acceptance.
    }
}

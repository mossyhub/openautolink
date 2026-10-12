package com.shilapi.xcertplay.mfi

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Experimental, local P-256 provider. Identity provisioning is owned by the host application.
 * A valid signature here proves key consistency, not that an iPhone trusts the identity.
 */
class LocalMfiAuthenticationClient private constructor(
    private val privateKey: PrivateKey,
    private val certificate: ByteArray,
    private val onSignature: (Int) -> Unit,
) : MfiAuthenticator {
    override fun protocolMajor(): Int = 3

    override fun readCertificate(maximumOutputLength: Int): ByteArray {
        require(maximumOutputLength > 0 && certificate.size <= maximumOutputLength) {
            "Local certificate exceeds the requested output limit"
        }
        return certificate.copyOf()
    }

    override fun signChallenge(challenge: ByteArray): ByteArray {
        require(challenge.size == 32) { "Local MFi v3 expects a 32-byte digest" }
        // The iAP2/AirPlay caller supplies a digest already. Do not hash it a second time.
        val signer = Signature.getInstance("NONEwithECDSA")
        signer.initSign(privateKey)
        signer.update(challenge)
        val result = derToRaw(signer.sign())
        onSignature(challenge.size)
        return result
    }

    companion object {
        /** Private app directory loaded only when local authentication is selected. */
        const val DIRECTORY = "offline-mfi"
        private const val MAX_FILE_BYTES = 16 * 1024

        fun load(directory: File, onSignature: (Int) -> Unit = {}): LocalMfiAuthenticationClient {
            require(directory.isDirectory) { "Offline MFi directory is missing or invalid" }
            val encodedKey = readBounded(File(directory, "identity.pk8"))
            val privateKey = try {
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encodedKey))
            } finally {
                encodedKey.fill(0)
            }
            val certificate = readBounded(File(directory, "certificate.p7b"))
            val certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(certificate.inputStream())
            require(certificates.size == 1) { "Expected one accessory certificate" }
            val publicKey = certificates.single().publicKey as? ECPublicKey
                ?: error("Expected an EC accessory certificate")
            require(publicKey.params.order.toString(16) ==
                "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551") {
                "Expected a P-256 accessory certificate"
            }
            val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
            val signer = Signature.getInstance("NONEwithECDSA")
            signer.initSign(privateKey)
            signer.update(challenge)
            val signature = signer.sign()
            val verifier = Signature.getInstance("NONEwithECDSA")
            verifier.initVerify(publicKey)
            verifier.update(challenge)
            require(verifier.verify(signature)) { "Local private key does not match certificate" }
            return LocalMfiAuthenticationClient(privateKey, certificate, onSignature)
        }

        private fun readBounded(file: File): ByteArray = file.inputStream().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_FILE_BYTES) { "Local identity file is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray().also { require(it.isNotEmpty()) { "Local identity file is empty" } }
        }

        internal fun derToRaw(der: ByteArray): ByteArray {
            val sequence = ASN1Sequence.getInstance(der)
            require(sequence.size() == 2) { "Invalid ECDSA signature" }
            val result = ByteArray(64)
            for (index in 0..1) {
                val value = ASN1Integer.getInstance(sequence.getObjectAt(index)).value
                require(value.signum() > 0 && value.bitLength() <= 256) { "Invalid ECDSA integer" }
                val bytes = value.toByteArray()
                val skip = if (bytes.size == 33 && bytes[0] == 0.toByte()) 1 else 0
                val length = bytes.size - skip
                bytes.copyInto(result, index * 32 + 32 - length, skip)
            }
            return result
        }
    }
}

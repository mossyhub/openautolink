// SPDX-License-Identifier: GPL-3.0-only
// Adapted from shilapi/DiPlay; upstream attribution and license: UPSTREAM-LICENSE.
package com.shilapi.xcertplay.transport

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pairing material assembled locally from Lockdown values already obtained by the caller.
 *
 * It deliberately has no transport dependency: it neither requests values nor sends Pair.
 */
class LockdownPairRecord private constructor(
    val hostId: String,
    val systemBuid: String,
    val wifiMacAddress: String,
    devicePublicKeyPem: ByteArray,
    deviceCertificatePem: ByteArray,
    hostPrivateKeyPem: ByteArray,
    hostCertificatePem: ByteArray,
    rootPrivateKeyPem: ByteArray,
    rootCertificatePem: ByteArray,
) {
    private val storedDevicePublicKeyPem = devicePublicKeyPem.copyOf()
    private val storedDeviceCertificatePem = deviceCertificatePem.copyOf()
    private val storedHostPrivateKeyPem = hostPrivateKeyPem.copyOf()
    private val storedHostCertificatePem = hostCertificatePem.copyOf()
    private val storedRootPrivateKeyPem = rootPrivateKeyPem.copyOf()
    private val storedRootCertificatePem = rootCertificatePem.copyOf()

    val devicePublicKeyPem: ByteArray get() = storedDevicePublicKeyPem.copyOf()
    val deviceCertificatePem: ByteArray get() = storedDeviceCertificatePem.copyOf()
    val hostPrivateKeyPem: ByteArray get() = storedHostPrivateKeyPem.copyOf()
    val hostCertificatePem: ByteArray get() = storedHostCertificatePem.copyOf()
    val rootPrivateKeyPem: ByteArray get() = storedRootPrivateKeyPem.copyOf()
    val rootCertificatePem: ByteArray get() = storedRootCertificatePem.copyOf()

    /** Builds only the nested Pair request value; EscrowBag is intentionally unavailable yet. */
    fun toPairRequestDictionary(): LockdownPlistValue.Dictionary = LockdownPlistValue.Dictionary(
        linkedMapOf(
            "DeviceCertificate" to LockdownPlistValue.Data(deviceCertificatePem),
            "HostCertificate" to LockdownPlistValue.Data(hostCertificatePem),
            "HostID" to LockdownPlistValue.Text(hostId),
            "RootCertificate" to LockdownPlistValue.Data(rootCertificatePem),
            "SystemBUID" to LockdownPlistValue.Text(systemBuid),
        ),
    )

    override fun toString(): String = "LockdownPairRecord(redacted)"

    companion object {
        /** Rebuilds a record from previously persisted material; all PEM buffers are copied. */
        fun restore(
            hostId: String,
            systemBuid: String,
            wifiMacAddress: String,
            devicePublicKeyPem: ByteArray,
            deviceCertificatePem: ByteArray,
            hostPrivateKeyPem: ByteArray,
            hostCertificatePem: ByteArray,
            rootPrivateKeyPem: ByteArray,
            rootCertificatePem: ByteArray,
        ): LockdownPairRecord {
            require(hostId.isNotBlank()) { "hostId must not be blank" }
            require(systemBuid.isNotBlank()) { "systemBuid must not be blank" }
            require(wifiMacAddress.isNotBlank()) { "wifiMacAddress must not be blank" }
            listOf(
                devicePublicKeyPem,
                deviceCertificatePem,
                hostPrivateKeyPem,
                hostCertificatePem,
                rootPrivateKeyPem,
                rootCertificatePem,
            ).forEach { require(it.isNotEmpty()) { "PEM material must not be empty" } }
            return LockdownPairRecord(
                hostId = hostId,
                systemBuid = systemBuid,
                wifiMacAddress = wifiMacAddress,
                devicePublicKeyPem = devicePublicKeyPem,
                deviceCertificatePem = deviceCertificatePem,
                hostPrivateKeyPem = hostPrivateKeyPem,
                hostCertificatePem = hostCertificatePem,
                rootPrivateKeyPem = rootPrivateKeyPem,
                rootCertificatePem = rootCertificatePem,
            )
        }

        internal fun create(
            hostId: String,
            systemBuid: String,
            wifiMacAddress: String,
            devicePublicKeyPem: ByteArray,
            material: CertificateMaterial,
        ) = LockdownPairRecord(
            hostId = hostId,
            systemBuid = systemBuid,
            wifiMacAddress = wifiMacAddress,
            devicePublicKeyPem = devicePublicKeyPem,
            deviceCertificatePem = material.deviceCertificatePem,
            hostPrivateKeyPem = material.hostPrivateKeyPem,
            hostCertificatePem = material.hostCertificatePem,
            rootPrivateKeyPem = material.rootPrivateKeyPem,
            rootCertificatePem = material.rootCertificatePem,
        )
    }
}

/** Generates a Lockdown PairRecord from values supplied by the caller, without any USB I/O. */
object LockdownPairRecordGenerator {
    @Throws(GeneralSecurityException::class, IllegalArgumentException::class)
    fun generate(
        devicePublicKeyPkcs1Pem: ByteArray,
        wifiAddress: String,
        hostId: String,
        systemBuid: String,
    ): LockdownPairRecord {
        require(devicePublicKeyPkcs1Pem.isNotEmpty()) { "devicePublicKeyPkcs1Pem must not be empty" }
        require(wifiAddress.isNotBlank()) { "wifiAddress must not be blank" }
        require(hostId.isNotBlank()) { "hostId must not be blank" }
        require(systemBuid.isNotBlank()) { "systemBuid must not be blank" }

        val copiedDeviceKey = devicePublicKeyPkcs1Pem.copyOf()
        val material = CertificateMaterialGenerator.generate(copiedDeviceKey)
        return LockdownPairRecord.create(
            hostId = hostId,
            systemBuid = systemBuid,
            wifiMacAddress = wifiAddress,
            devicePublicKeyPem = copiedDeviceKey,
            material = material,
        )
    }
}

internal class CertificateMaterial(
    deviceCertificatePem: ByteArray,
    hostPrivateKeyPem: ByteArray,
    hostCertificatePem: ByteArray,
    rootPrivateKeyPem: ByteArray,
    rootCertificatePem: ByteArray,
) {
    private val storedDeviceCertificatePem = deviceCertificatePem.copyOf()
    private val storedHostPrivateKeyPem = hostPrivateKeyPem.copyOf()
    private val storedHostCertificatePem = hostCertificatePem.copyOf()
    private val storedRootPrivateKeyPem = rootPrivateKeyPem.copyOf()
    private val storedRootCertificatePem = rootCertificatePem.copyOf()

    val deviceCertificatePem: ByteArray get() = storedDeviceCertificatePem.copyOf()
    val hostPrivateKeyPem: ByteArray get() = storedHostPrivateKeyPem.copyOf()
    val hostCertificatePem: ByteArray get() = storedHostCertificatePem.copyOf()
    val rootPrivateKeyPem: ByteArray get() = storedRootPrivateKeyPem.copyOf()
    val rootCertificatePem: ByteArray get() = storedRootCertificatePem.copyOf()
}

/** Minimal Android/JCA implementation of the certificate profile used by the locked dependency. */
private object CertificateMaterialGenerator {
    private const val RSA_KEY_BITS = 2048
    private const val CERTIFICATE_LIFETIME_SECONDS = 10L * 365 * 24 * 60 * 60
    private const val MILLIS_PER_SECOND = 1_000L
    private const val UTC_TIME_LAST_YEAR = 2049
    private val sha256WithRsa = algorithmIdentifier("1.2.840.113549.1.1.11")
    private val rsaEncryption = algorithmIdentifier("1.2.840.113549.1.1.1")

    fun generate(devicePublicKeyPem: ByteArray): CertificateMaterial {
        val devicePublicKey = parsePkcs1RsaPublicKey(devicePublicKeyPem)
        val rootKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_BITS) }.generateKeyPair()
        val hostKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_BITS) }.generateKeyPair()
        val rootPublicKey = rootKeyPair.public as? RSAPublicKey
            ?: throw GeneralSecurityException("Generated root key is not RSA")
        val hostPublicKey = hostKeyPair.public as? RSAPublicKey
            ?: throw GeneralSecurityException("Generated host key is not RSA")
        val now = System.currentTimeMillis()
        val emptyName = distinguishedName(null)
        val rootDer = certificate(
            issuer = emptyName,
            subject = emptyName,
            certificatePublicKey = rootPublicKey,
            signingKey = rootKeyPair.private,
            signingPublicKey = rootPublicKey,
            nowMillis = now,
            extensions = rootExtensions(),
        )
        val hostDer = certificate(
            issuer = emptyName,
            subject = emptyName,
            certificatePublicKey = hostPublicKey,
            signingKey = rootKeyPair.private,
            signingPublicKey = rootPublicKey,
            nowMillis = now,
            extensions = leafExtensions(hostPublicKey, includeSubjectKeyIdentifier = false),
        )
        val deviceDer = certificate(
            issuer = emptyName,
            subject = emptyName,
            certificatePublicKey = devicePublicKey,
            signingKey = rootKeyPair.private,
            signingPublicKey = rootPublicKey,
            nowMillis = now,
            extensions = leafExtensions(devicePublicKey, includeSubjectKeyIdentifier = true),
        )
        return CertificateMaterial(
            deviceCertificatePem = pem("CERTIFICATE", deviceDer),
            hostPrivateKeyPem = pem("PRIVATE KEY", hostKeyPair.private.encoded),
            hostCertificatePem = pem("CERTIFICATE", hostDer),
            rootPrivateKeyPem = pem("PRIVATE KEY", rootKeyPair.private.encoded),
            rootCertificatePem = pem("CERTIFICATE", rootDer),
        )
    }

    private fun certificate(
        issuer: ByteArray,
        subject: ByteArray,
        certificatePublicKey: RSAPublicKey,
        signingKey: PrivateKey,
        signingPublicKey: PublicKey,
        nowMillis: Long,
        extensions: ByteArray,
    ): ByteArray {
        val publicKeyBits = pkcs1PublicKey(certificatePublicKey)
        val notAfterMillis = nowMillis + CERTIFICATE_LIFETIME_SECONDS * MILLIS_PER_SECOND
        require(nowMillis in 0 until notAfterMillis) { "Invalid certificate validity" }
        val tbs = sequence(
            explicit(0, integer(BigInteger.valueOf(2))),
            integer(BigInteger.ZERO),
            sha256WithRsa,
            issuer,
            sequence(
                certificateTime(nowMillis),
                certificateTime(notAfterMillis),
            ),
            subject,
            sequence(rsaEncryption, bitString(publicKeyBits)),
            explicit(3, extensions),
        )
        val signer = Signature.getInstance("SHA256withRSA").apply {
            initSign(signingKey)
            update(tbs)
        }
        val signature = signer.sign()
        val encoded = sequence(tbs, sha256WithRsa, bitString(signature))
        verifyGeneratedCertificate(tbs, signature, signingPublicKey)
        return encoded
    }

    /** JBR's CertificateFactory rejects the locked empty issuer DN, so verify the signed TBS directly. */
    private fun verifyGeneratedCertificate(tbs: ByteArray, signature: ByteArray, signingPublicKey: PublicKey) {
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(signingPublicKey)
            update(tbs)
        }
        if (!verifier.verify(signature)) throw GeneralSecurityException("Generated certificate signature does not verify")
    }

    private fun rootExtensions(): ByteArray = sequence(
        extension("2.5.29.19", critical = true, value = sequence(boolean(true))),
    )

    private fun leafExtensions(
        publicKey: RSAPublicKey,
        includeSubjectKeyIdentifier: Boolean,
    ): ByteArray {
        val extensions = mutableListOf(
            extension("2.5.29.19", critical = true, value = sequence()),
        )
        if (includeSubjectKeyIdentifier) {
            extensions += extension(
                "2.5.29.14",
                critical = false,
                value = octetString(MessageDigest.getInstance("SHA-1").digest(pkcs1PublicKey(publicKey))),
            )
        }
        extensions += extension(
            "2.5.29.15",
            critical = true,
            value = bitString(byteArrayOf(0xa0.toByte()), unusedBits = 5),
        )
        return sequence(*extensions.toTypedArray())
    }

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) sequence(objectIdentifier(oid), boolean(true), octetString(value))
        else sequence(objectIdentifier(oid), octetString(value))

    private fun parsePkcs1RsaPublicKey(pem: ByteArray): RSAPublicKey {
        val text = pem.toString(StandardCharsets.US_ASCII).trim()
        val begin = "-----BEGIN RSA PUBLIC KEY-----"
        val end = "-----END RSA PUBLIC KEY-----"
        require(text.startsWith(begin) && text.endsWith(end)) { "Expected a PKCS#1 RSA public key" }
        val encoded = text.substring(begin.length, text.length - end.length).filterNot(Char::isWhitespace)
        val der = Base64.getDecoder().decode(encoded)
        val outer = DerReader(der)
        val sequence = outer.readConstructed(0x30)
        val modulus = sequence.readPositiveInteger()
        val exponent = sequence.readPositiveInteger()
        require(sequence.exhausted() && outer.exhausted()) { "Trailing data in RSA public key" }
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
    }

    private fun pkcs1PublicKey(publicKey: RSAPublicKey): ByteArray = sequence(
        integer(publicKey.modulus),
        integer(publicKey.publicExponent),
    )

    private fun distinguishedName(commonName: String?): ByteArray =
        if (commonName == null) sequence() else sequence(set(sequence(objectIdentifier("2.5.4.3"), utf8String(commonName))))

    private fun pem(label: String, der: ByteArray): ByteArray {
        val encoded = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(der)
        return "-----BEGIN $label-----\n$encoded\n-----END $label-----\n".toByteArray(StandardCharsets.US_ASCII)
    }

    private fun algorithmIdentifier(oid: String): ByteArray = sequence(objectIdentifier(oid), der(0x05, ByteArray(0)))
    private fun sequence(vararg values: ByteArray): ByteArray = der(0x30, concatenate(*values))
    private fun set(vararg values: ByteArray): ByteArray = der(0x31, concatenate(*values))
    private fun explicit(number: Int, value: ByteArray): ByteArray = der(0xa0 + number, value)
    private fun utf8String(value: String): ByteArray = der(0x0c, value.toByteArray(StandardCharsets.UTF_8))

    private fun certificateTime(valueMillis: Long): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = utc }
            .format(Date(valueMillis)).toInt()
        val utcTime = year <= UTC_TIME_LAST_YEAR
        val formatter = SimpleDateFormat(
            if (utcTime) "yyMMddHHmmss'Z'" else "yyyyMMddHHmmss'Z'",
            Locale.US,
        ).apply { timeZone = utc }
        return der(if (utcTime) 0x17 else 0x18, formatter.format(Date(valueMillis)).toByteArray(StandardCharsets.US_ASCII))
    }

    private fun integer(value: BigInteger): ByteArray {
        require(value.signum() >= 0) { "DER integer must be non-negative" }
        return der(0x02, value.toByteArray())
    }

    private fun boolean(value: Boolean): ByteArray = der(0x01, byteArrayOf(if (value) 0xff.toByte() else 0))
    private fun octetString(value: ByteArray): ByteArray = der(0x04, value)
    private fun bitString(value: ByteArray, unusedBits: Int = 0): ByteArray {
        require(unusedBits in 0..7) { "Invalid number of unused bits" }
        return der(0x03, byteArrayOf(unusedBits.toByte()) + value)
    }

    private fun objectIdentifier(value: String): ByteArray {
        val arcs = value.split('.').map(String::toLong)
        require(arcs.size >= 2 && arcs[0] in 0..2 && arcs[1] >= 0 && (arcs[0] == 2L || arcs[1] < 40)) {
            "Invalid object identifier"
        }
        val output = ByteArrayOutputStream()
        writeBase128(output, arcs[0] * 40 + arcs[1])
        arcs.drop(2).forEach { arc -> require(arc >= 0) { "Invalid object identifier" }; writeBase128(output, arc) }
        return der(0x06, output.toByteArray())
    }

    private fun writeBase128(output: ByteArrayOutputStream, value: Long) {
        var remaining = value
        val bytes = ByteArray(10)
        var offset = bytes.size
        bytes[--offset] = (remaining and 0x7f).toByte()
        remaining = remaining ushr 7
        while (remaining != 0L) {
            bytes[--offset] = ((remaining and 0x7f) or 0x80).toByte()
            remaining = remaining ushr 7
        }
        output.write(bytes, offset, bytes.size - offset)
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(1 + 5 + content.size)
        output.write(tag)
        if (content.size < 0x80) output.write(content.size) else {
            var length = content.size
            val bytes = ByteArray(4)
            var offset = bytes.size
            while (length != 0) { bytes[--offset] = length.toByte(); length = length ushr 8 }
            output.write(0x80 or (bytes.size - offset))
            output.write(bytes, offset, bytes.size - offset)
        }
        output.write(content)
        return output.toByteArray()
    }

    private fun concatenate(vararg values: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(values.sumOf(ByteArray::size))
        values.forEach(output::write)
        return output.toByteArray()
    }

    private class DerReader(private val bytes: ByteArray) {
        private var offset = 0

        fun readConstructed(expectedTag: Int): DerReader = DerReader(readValue(expectedTag))

        fun readPositiveInteger(): BigInteger {
            val value = readValue(0x02)
            require(value.isNotEmpty() && (value[0].toInt() and 0x80) == 0) { "Invalid positive DER integer" }
            require(value.size == 1 || value[0].toInt() != 0 || (value[1].toInt() and 0x80) != 0) {
                "Non-minimal positive DER integer"
            }
            return BigInteger(value)
        }

        fun exhausted(): Boolean = offset == bytes.size

        private fun readValue(expectedTag: Int): ByteArray {
            require(offset < bytes.size && bytes[offset++].toInt() and 0xff == expectedTag) { "Unexpected DER tag" }
            require(offset < bytes.size) { "Missing DER length" }
            val first = bytes[offset++].toInt() and 0xff
            val length = if (first < 0x80) first else {
                val count = first and 0x7f
                require(count in 1..4 && offset + count <= bytes.size) { "Invalid DER length" }
                require(bytes[offset].toInt() != 0) { "Non-minimal DER length" }
                var result = 0
                repeat(count) { result = (result shl 8) or (bytes[offset++].toInt() and 0xff) }
                val minimumCount = (Integer.SIZE - Integer.numberOfLeadingZeros(result) + 7) / 8
                require(result >= 0x80 && count == minimumCount) { "Non-minimal DER length" }
                result
            }
            require(length >= 0 && offset + length <= bytes.size) { "DER value is truncated" }
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }
    }
}

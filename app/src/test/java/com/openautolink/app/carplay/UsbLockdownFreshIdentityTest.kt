// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.app.carplay

import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class UsbLockdownFreshIdentityTest {
    @Test fun freshIdentitySurvivesActualGeneratedPairWireAsCanonicalUuidStrings() {
        // Synthetic identities with every hex letter: a permissive UUID parse alone is insufficient.
        val host = UUID.fromString("abcdef01-2345-4678-9abc-def012345678")
        val buid = UUID.fromString("fedcba98-7654-4321-8fed-cba987654321")
        val identity = UsbLockdownFreshIdentity.create(host, buid)
        val record = LockdownPairRecordGenerator.generate(syntheticDeviceKey(), "02:00:00:00:00:01",
            identity.hostId, identity.systemBuid)
        // Invoke the actual Pair envelope builder, not a test reconstruction of its nesting.
        val method = LockdownPairingClient::class.java.getDeclaredMethod("pairRequest",
            String::class.java, LockdownPairRecord::class.java).apply { isAccessible = true }
        val request = method.invoke(LockdownPairingClient(io.mockk.mockk<Iap2UsbMuxHost>()),
            "synthetic", record) as LockdownPlistValue.Dictionary
        val stream = CapturingStream()
        LockdownPlistChannel(stream).use { it.send(request); it.send(request) }
        assertArrayEquals("Repeated Pair must retain the exact generated identity and record", stream.frames[0], stream.frames[1])
        val frame = stream.frames.first()
        assertEquals(frame.size - 4, ByteBuffer.wrap(frame, 0, 4).int)
        val parser = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }.newDocumentBuilder()
        val root = parser.parse(ByteArrayInputStream(frame, 4, frame.size - 4))
            .documentElement.getElementsByTagName("dict").item(0) as Element
        val nested = field(root, "PairRecord")
        assertEquals("dict", nested.tagName)
        listOf("HostID" to host, "SystemBUID" to buid).forEach { (name, uuid) ->
            val value = field(nested, name)
            assertEquals("string", value.tagName)
            val text = value.textContent
            assertTrue("$name must use uppercase canonical UUID spelling",
                text.matches(Regex("[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")))
            assertEquals(uuid, UUID.fromString(text))
            assertEquals(UUID.fromString(text).toString().uppercase(Locale.ROOT), text)
        }
    }

    @Test fun defaultFreshFactoryProducesDistinctCanonicalIdentitiesWithoutExposingThem() {
        val first = UsbLockdownFreshIdentity.create()
        val second = UsbLockdownFreshIdentity.create()
        listOf(first.hostId, first.systemBuid, second.hostId, second.systemBuid).forEach { value ->
            assertEquals(UUID.fromString(value).toString().uppercase(Locale.ROOT), value)
            assertEquals(4, UUID.fromString(value).version())
            assertEquals(2, UUID.fromString(value).variant())
        }
        assertNotEquals(first.hostId, second.hostId)
        assertNotEquals(first.systemBuid, second.systemBuid)
        assertNotEquals(first.hostId, first.systemBuid)
        assertEquals("UsbLockdownFreshIdentity(redacted)", first.toString())
    }

    @Test fun persistedLowercaseIdentitiesRemainExactRatherThanBeingMigrated() {
        val host = "abcdef01-2345-4678-9abc-def012345678"
        val buid = "fedcba98-7654-4321-8fed-cba987654321"
        val generated = LockdownPairRecordGenerator.generate(syntheticDeviceKey(), "02:00:00:00:00:01", host, buid)
        val directory = java.nio.file.Files.createTempDirectory(java.io.File("build/tmp").apply { mkdirs() }.toPath(),
            "synthetic-lockdown-").toFile()
        try {
            val trust = com.openautolink.carplaylab.usb.UsbLockdownTrust(directory)
            trust.save("synthetic-phone", generated)
            val saved = requireNotNull(trust.load("synthetic-phone"))
            assertEquals(host, saved.hostId)
            assertEquals(buid, saved.systemBuid)
            assertEquals(LockdownPlistValue.Text(host), saved.toPairRequestDictionary().entries["HostID"])
            assertEquals(LockdownPlistValue.Text(buid), saved.toPairRequestDictionary().entries["SystemBUID"])
        } finally { directory.deleteRecursively() }
    }

    private fun field(dict: Element, name: String): Element {
        val children = (0 until dict.childNodes.length).mapNotNull { dict.childNodes.item(it) as? Element }
        val index = children.indexOfFirst { it.tagName == "key" && it.textContent == name }
        check(index >= 0) { "Missing synthetic Pair field" }
        return children[index + 1]
    }

    private fun syntheticDeviceKey(): ByteArray {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        fun der(tag: Int, bytes: ByteArray): ByteArray {
            val length = when {
                bytes.size < 128 -> byteArrayOf(bytes.size.toByte())
                bytes.size < 256 -> byteArrayOf(0x81.toByte(), bytes.size.toByte())
                else -> byteArrayOf(0x82.toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte())
            }
            return byteArrayOf(tag.toByte()) + length + bytes
        }
        val pkcs1 = der(0x30, der(0x02, key.modulus.toByteArray()) + der(0x02, key.publicExponent.toByteArray()))
        return ("-----BEGIN RSA PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(pkcs1) +
            "\n-----END RSA PUBLIC KEY-----\n").toByteArray(Charsets.US_ASCII)
    }

    private class CapturingStream : BlockingDuplexByteStream {
        val frames = mutableListOf<ByteArray>()
        override fun send(bytes: ByteArray) { frames += bytes.copyOf() }
        override fun recv(size: Int, timeoutMillis: Long): ByteArray? = null
        override fun close() = Unit
    }
}

package com.openautolink.app.ui.settings

import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient

class CarPlayCredentialImportTest {
    @get:Rule val temporary = TemporaryFolder()

    // Only generated, self-signed synthetic material; never an accessory identity.
    private fun fixture(): Pair<ByteArray, ByteArray> {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val algorithm = der(0x30, der(6, byteArrayOf(42, -122, 72, -50, 61, 4, 3, 2)))
        val name = javax.security.auth.x500.X500Principal("CN=Synthetic import test").encoded
        val validity = der(0x30, der(0x17, "200101000000Z".toByteArray()), der(0x17, "491231235959Z".toByteArray()))
        val tbs = der(0x30, der(2, byteArrayOf(1)), algorithm, name, validity, name, pair.public.encoded)
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(pair.private); update(tbs)
        }.sign()
        return pair.private.encoded to der(0x30, tbs, algorithm, der(3, byteArrayOf(0) + signature))
    }

    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(byteArrayOf()) { a, b -> a + b }
        val length = if (body.size < 128) byteArrayOf(body.size.toByte())
            else byteArrayOf(0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
        return byteArrayOf(tag.toByte()) + length + body
    }

    private fun import(root: File, pair: Pair<ByteArray, ByteArray>): Boolean {
        val type = runCatching { Class.forName("com.openautolink.app.ui.settings.CarPlayCredentialImporter") }.getOrNull()
        assertNotNull("Owner-controlled importer is missing", type)
        val store = type!!.getConstructor(File::class.java).newInstance(root)
        return type.getMethod("importPair", InputStream::class.java, InputStream::class.java)
            .invoke(store, pair.first.inputStream(), pair.second.inputStream()) as Boolean
    }

    @Test fun rejectsSymbolicLinkDestinationWithoutTouchingOutsideStorage() {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        java.nio.file.Files.createSymbolicLink(File(root, "offline-mfi").toPath(), outside.toPath())
        assertFalse(import(root, fixture()))
        assertTrue(java.nio.file.Files.isSymbolicLink(File(root, "offline-mfi").toPath()))
        assertEquals(0, outside.listFiles()!!.size)
    }

    @Test fun publicationFailureRollsBackExistingWholePair() {
        val root = temporary.newFolder()
        val original = fixture()
        assertTrue(import(root, original))
        val type = Class.forName("com.openautolink.app.ui.settings.CarPlayCredentialImporter")
        val constructor = type.constructors.firstOrNull { it.parameterCount == 2 }
        assertNotNull("Publication must support a fault-injected whole-directory move", constructor)
        val move: (File, File) -> Boolean = { from, to ->
            if (from.name == "offline-mfi-import" && to.name == "offline-mfi") false else from.renameTo(to)
        }
        val importer = constructor!!.newInstance(root, move)
        val replacement = fixture()
        assertFalse(type.getMethod("importPair", InputStream::class.java, InputStream::class.java)
            .invoke(importer, replacement.first.inputStream(), replacement.second.inputStream()) as Boolean)
        assertArrayEquals(original.first, File(root, "offline-mfi/identity.pk8").readBytes())
        assertArrayEquals(original.second, LocalMfiAuthenticationClient.load(File(root, "offline-mfi")).readCertificate())
        assertFalse(File(root, "offline-mfi-previous").exists())
    }

    @Test fun failingProviderReadReturnsSafeFailurePreservingExisting() {
        val root = temporary.newFolder()
        val original = fixture()
        assertTrue(import(root, original))
        val importer = Class.forName("com.openautolink.app.ui.settings.CarPlayCredentialImporter")
            .getConstructor(File::class.java).newInstance(root)
        val broken = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("synthetic private provider text")
            override fun close() = throw java.io.IOException("synthetic close text")
        }
        assertFalse(importer.javaClass.getMethod("importPair", InputStream::class.java, InputStream::class.java)
            .invoke(importer, broken, original.second.inputStream()) as Boolean)
        assertArrayEquals(original.first, File(root, "offline-mfi/identity.pk8").readBytes())
    }

    @Test fun malformedOversizedMismatchedAndInterruptedReadsPreserveExistingPair() {
        val root = temporary.newFolder()
        val original = fixture()
        assertTrue(import(root, original))
        val other = fixture()
        for (bad in listOf(byteArrayOf() to original.second,
            byteArrayOf(1, 2, 3) to original.second,
            original.first to byteArrayOf(1, 2, 3),
            ByteArray(16385) to original.second,
            original.first to ByteArray(16385), other.first to original.second)) {
            assertFalse(import(root, bad))
            assertArrayEquals(original.first, File(root, "offline-mfi/identity.pk8").readBytes())
            assertArrayEquals(original.second, LocalMfiAuthenticationClient.load(File(root, "offline-mfi")).readCertificate())
            assertFalse(File(root, "offline-mfi-import").exists())
        }
    }

    @Test fun explicitImportPublishesFullyValidatedPairWithoutBundledFallback() {
        val root = temporary.newFolder()
        assertFalse(File(root, "offline-mfi").exists())
        val pair = fixture()
        assertTrue(import(root, pair))
        val client = LocalMfiAuthenticationClient.load(File(root, "offline-mfi"))
        assertArrayEquals(pair.second, client.readCertificate())
        assertEquals(64, client.signChallenge(ByteArray(32)).size)
    }
}

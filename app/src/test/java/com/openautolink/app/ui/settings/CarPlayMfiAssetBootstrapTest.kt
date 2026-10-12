package com.openautolink.app.ui.settings

import java.io.File
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient

class CarPlayMfiAssetBootstrapTest {
    @get:Rule val temporary = TemporaryFolder()

    // Reuses only runtime-generated synthetic material, never owner credentials.
    private fun fixture(): Pair<ByteArray, ByteArray> {
        val method = CarPlayCredentialImportTest::class.java.getDeclaredMethod("fixture")
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(CarPlayCredentialImportTest()) as Pair<ByteArray, ByteArray>
    }

    private fun install(root: File, open: (String) -> InputStream?): String {
        val type = runCatching { Class.forName("com.openautolink.app.ui.settings.CarPlayMfiAssetBootstrap") }.getOrNull()
        assertNotNull("Validated optional first-use bootstrap is missing", type)
        val instance = type!!.constructors.first { it.parameterCount == 2 }.newInstance(root, open)
        return type!!.getMethod("install").invoke(instance).toString()
    }

    @Test fun preservesValidExistingWithoutOpeningAssets() {
        val root = temporary.newFolder()
        val original = fixture()
        assertTrue(CarPlayCredentialImporter(root).importPair(original.first.inputStream(), original.second.inputStream()))
        assertEquals("EXISTING_VALID", install(root) { throw AssertionError("Must not open bundled credentials") })
        assertArrayEquals(original.first, File(root, "offline-mfi/identity.pk8").readBytes())
    }

    @Test fun absentAndPartialBundlesLeaveNoPublishedIdentity() {
        val root = temporary.newFolder()
        assertEquals("NOT_BUNDLED", install(root) { null })
        for (present in listOf("offline-mfi/identity.pk8", "offline-mfi/certificate.p7b")) {
            assertEquals("INVALID_BUNDLE", install(root) { if (it == present) byteArrayOf(1).inputStream() else null })
            assertFalse(File(root, "offline-mfi").exists())
        }
    }

    @Test fun malformedMismatchedAndOversizedBundlesNeverPublish() {
        val pair = fixture()
        val other = fixture()
        for (bad in listOf(byteArrayOf(1) to pair.second, pair.first to byteArrayOf(1),
            other.first to pair.second, ByteArray(16385) to pair.second)) {
            val root = temporary.newFolder()
            assertEquals("INVALID_BUNDLE", install(root) {
                (if (it.endsWith("identity.pk8")) bad.first else bad.second).inputStream()
            })
            assertFalse(File(root, "offline-mfi").exists())
            assertFalse(File(root, "offline-mfi-import").exists())
        }
    }

    @Test fun corruptedExistingIdentityIsNeverOverwritten() {
        val root = temporary.newFolder()
        File(root, "offline-mfi").mkdir()
        File(root, "offline-mfi/identity.pk8").writeBytes(byteArrayOf(1))
        assertEquals("INVALID_EXISTING", install(root) { throw AssertionError("Must not open assets") })
        assertArrayEquals(byteArrayOf(1), File(root, "offline-mfi/identity.pk8").readBytes())
    }

    @Test fun recoversPreviousWholePairBeforeReadingAssets() {
        val root = temporary.newFolder()
        val pair = fixture()
        assertTrue(CarPlayCredentialImporter(root).importPair(pair.first.inputStream(), pair.second.inputStream()))
        assertTrue(File(root, "offline-mfi").renameTo(File(root, "offline-mfi-previous")))
        assertEquals("EXISTING_VALID", install(root) { throw AssertionError("Must not open assets") })
        assertArrayEquals(pair.second, LocalMfiAuthenticationClient.load(File(root, "offline-mfi")).readCertificate())
    }

    @Test fun symlinkDestinationIsRejectedWithoutTouchingOutside() {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        java.nio.file.Files.createSymbolicLink(File(root, "offline-mfi").toPath(), outside.toPath())
        assertEquals("INVALID_EXISTING", install(root) { null })
        assertEquals(0, outside.listFiles()!!.size)
    }

    @Test fun sourceFailureClosesOpenedStreamWithoutPublishingExceptionText() {
        val root = temporary.newFolder()
        var closed = false
        val key = object : java.io.ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { closed = true }
        }
        assertEquals("INVALID_BUNDLE", install(root) {
            if (it.endsWith("identity.pk8")) key else throw java.io.IOException("synthetic sensitive message")
        })
        assertTrue(closed)
        assertFalse(File(root, "offline-mfi").exists())
    }

    @Test fun firstUsePublishesValidatedWholePair() {
        val root = temporary.newFolder()
        val pair = fixture()
        val assets = mapOf("offline-mfi/identity.pk8" to pair.first, "offline-mfi/certificate.p7b" to pair.second)
        assertEquals("INSTALLED", install(root) { assets[it]?.inputStream() })
        val client = LocalMfiAuthenticationClient.load(File(root, "offline-mfi"))
        assertArrayEquals(pair.second, client.readCertificate())
        assertEquals(64, client.signChallenge(ByteArray(32)).size)
        assertFalse(File(root, "offline-mfi-import").exists())
    }
}

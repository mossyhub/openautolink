package com.openautolink.carplaylab.pairing

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import com.shilapi.xcertplay.airplay.AirPlayCrypto

class PairingPersistenceTest {
    @Test fun identityAndControllerSurviveReopenTogether() {
        val root = Files.createTempDirectory("pairing-test").toFile()
        try {
            val first = PairingPersistence(root)
            val id = first.identity.pairingId
            val pub = first.identity.publicKey.copyOf()
            val controller = AirPlayCrypto.ed25519Generate().publicKey
            first.pairings.save("synthetic-controller", controller)
            first.close()
            PairingPersistence(root).use { next ->
                assertEquals(id, next.identity.pairingId)
                assertArrayEquals(pub, next.identity.publicKey)
                assertArrayEquals(controller, next.pairings.get("synthetic-controller"))
                assertNull(next.pairings.get("unknown"))
                try { next.pairings.save("another", controller); fail("Expected single-phone cap") } catch (_: IllegalArgumentException) { }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun corruptStateIsNotSilentlyRegenerated() {
        val root = Files.createTempDirectory("pairing-test").toFile()
        try {
            PairingPersistence(root).close()
            java.io.File(root, "state.bin").writeBytes(byteArrayOf(1))
            try { PairingPersistence(root).close(); fail("Expected corruption failure") } catch (_: Exception) { }
        } finally { root.deleteRecursively() }
    }
    @Test fun secondOwnerCannotUseStaleState() {
        val root = Files.createTempDirectory("pairing-test").toFile()
        try {
            PairingPersistence(root).use {
                try { PairingPersistence(root).close(); fail("Expected exclusive owner") } catch (_: java.io.IOException) { }
            }
        } finally { root.deleteRecursively() }
    }
}

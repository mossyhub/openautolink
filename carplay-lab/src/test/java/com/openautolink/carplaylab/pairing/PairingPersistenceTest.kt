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
                next.pairings.save("another", controller)
                assertArrayEquals(controller, next.pairings.get("another"))
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun multiplePhonesSurviveReopenWithoutReplacingAccessoryOrOriginalPhone() {
        val root = Files.createTempDirectory("pairing-multiple-test").toFile()
        try {
            val phoneA = AirPlayCrypto.ed25519Generate().publicKey
            val phoneB = AirPlayCrypto.ed25519Generate().publicKey
            val accessoryId = PairingPersistence(root).use {
                it.pairings.save("phone-a", phoneA)
                it.identity.pairingId
            }
            PairingPersistence(root).use {
                it.pairings.save("phone-b", phoneB)
                assertEquals(accessoryId, it.identity.pairingId)
            }
            PairingPersistence(root).use {
                assertEquals(accessoryId, it.identity.pairingId)
                assertEquals(setOf("phone-a", "phone-b"), it.pairings.controllerIdentifiers())
                assertArrayEquals(phoneA, it.pairings.get("phone-a"))
                assertArrayEquals(phoneB, it.pairings.get("phone-b"))
                assertThrows(IllegalArgumentException::class.java) { it.pairings.save("phone-a", phoneB) }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun allBoundedControllerRecordsReopenAndOverflowDoesNotChangeTrust() {
        val root = Files.createTempDirectory("pairing-bounded-test").toFile()
        try {
            PairingPersistence(root).use { state ->
                repeat(64) { index -> state.pairings.save("phone-$index-" + "\u0000".repeat(240), ByteArray(32) { index.toByte() }) }
                assertThrows(IllegalArgumentException::class.java) { state.pairings.save("overflow", ByteArray(32)) }
                assertNull(state.pairings.get("overflow"))
            }
            PairingPersistence(root).use { state ->
                assertEquals(64, state.pairings.controllerIdentifiers().size)
                repeat(64) { index -> assertArrayEquals(ByteArray(32) { index.toByte() }, state.pairings.get("phone-$index-" + "\u0000".repeat(240))) }
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

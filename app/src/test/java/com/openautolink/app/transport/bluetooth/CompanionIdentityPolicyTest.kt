package com.openautolink.app.transport.bluetooth

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionIdentityPolicyTest {

    @Test
    fun `probe parser preserves phone identity and proxy port`() {
        val probe = CompanionIdentityPolicy.parseProbe(
            "OAL!19bbbce4-1234\tLiz OnePlus 13\twpp=5280",
        )

        requireNotNull(probe)
        assertEquals("19bbbce4-1234", probe.phoneId)
        assertEquals("Liz OnePlus 13", probe.friendlyName)
        assertEquals(5280, probe.proxyPort)
    }

    @Test
    fun `probe parser rejects missing live proxy`() {
        assertNull(
            CompanionIdentityPolicy.parseProbe(
                "OAL!19bbbce4-1234\tLiz OnePlus 13\twpp=0",
            ),
        )
    }

    @Test
    fun `bluetooth device name selects only its matching companion`() {
        val liz = CompanionProbe("liz-id", "Liz OnePlus 13", 5280)
        val lance = CompanionProbe("lance-id", "Lance OnePlus 13", 5280)

        assertTrue(
            CompanionIdentityPolicy.matches(
                expectedPhoneId = null,
                bluetoothDeviceName = "Liz OnePlus 13",
                probe = liz,
            ),
        )
        assertFalse(
            CompanionIdentityPolicy.matches(
                expectedPhoneId = null,
                bluetoothDeviceName = "Liz OnePlus 13",
                probe = lance,
            ),
        )
    }

    @Test
    fun `learned phone id outranks a renamed device`() {
        val probe = CompanionProbe("liz-id", "New Device Name", 5280)

        assertTrue(
            CompanionIdentityPolicy.matches(
                expectedPhoneId = "liz-id",
                bluetoothDeviceName = "Old Device Name",
                probe = probe,
            ),
        )
        assertFalse(
            CompanionIdentityPolicy.matches(
                expectedPhoneId = "lance-id",
                bluetoothDeviceName = "New Device Name",
                probe = probe,
            ),
        )
    }

    @Test
    fun `unidentified companion is never accepted as the bluetooth owner`() {
        val probe = CompanionProbe("some-id", null, 5280)

        assertFalse(
            CompanionIdentityPolicy.matches(
                expectedPhoneId = null,
                bluetoothDeviceName = null,
                probe = probe,
            ),
        )
    }

    @Test
    fun `bluetooth endpoint resolution carries and enforces remote device identity`() {
        val server = projectFile(
            "app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtServer.kt",
        ).readText()
        val control = projectFile(
            "app/src/main/java/com/openautolink/app/transport/bluetooth/AaWirelessBtControl.kt",
        ).readText()

        val session = projectFile(
            "app/src/main/java/com/openautolink/app/transport/aasdk/AasdkSession.kt",
        ).readText()
        assertTrue(server.contains("remoteDevice?.name"))
        assertTrue(server.contains("currentEndpoint(phoneBtAddress, phoneBtName)"))
        assertTrue(control.contains("CompanionIdentityPolicy.matches("))
        assertTrue(control.contains("private val phoneClaimLock = Any()"))
        assertTrue(control.contains("private var phoneClaimGeneration = 0L"))
        assertTrue(control.contains("claimGeneration = claimPhoneForHandshake("))
        assertTrue(control.contains("phoneClaimStillOwned(phoneBtAddress, claimGeneration)"))
        assertTrue(control.contains("val claimGeneration: Long"))
        assertTrue(session.contains("withIdentityValidatedCompanion"))
        assertFalse(session.contains("identityValidatedCompanionIp()"))
        assertFalse(session.contains("AaWirelessBtControl.lastKnownPhoneIp"))
        assertFalse(session.contains(".lastKnownPhoneIp"))
        assertTrue(session.windowed("\"wpp\" -> startWpp()".length)
            .count { it == "\"wpp\" -> startWpp()" } >= 3)
        val matchIndex = control.indexOf("CompanionIdentityPolicy.matches(")
        val cacheIndex = control.indexOf("lastAddressByPhone[phoneBtAddress] = ip", matchIndex)
        assertTrue(matchIndex >= 0)
        assertTrue(cacheIndex > matchIndex)
    }

    private fun projectFile(relativePath: String): File {
        val workingDir = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDir) { it.parentFile }
            .map { root -> File(root, relativePath) }
            .first { it.isFile }
    }
}

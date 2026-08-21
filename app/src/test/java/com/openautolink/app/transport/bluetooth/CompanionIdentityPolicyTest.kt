package com.openautolink.app.transport.bluetooth

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionIdentityPolicyTest {

    @Test
    fun `probe parser preserves phone identity bluetooth name and proxy port`() {
        val probe = CompanionIdentityPolicy.parseProbe(
            "OAL!19bbbce4-1234\tLiz OnePlus 13\twpp=5280\tbt_name=OnePlus 13",
        )

        requireNotNull(probe)
        assertEquals("19bbbce4-1234", probe.phoneId)
        assertEquals("Liz OnePlus 13", probe.friendlyName)
        assertEquals("OnePlus 13", probe.bluetoothName)
        assertEquals(5280, probe.proxyPort)
    }

    @Test
    fun `reported bluetooth name learns phone when friendly name differs`() {
        val liz = CompanionProbe(
            phoneId = "liz-id",
            friendlyName = "Liz OnePlus 13",
            proxyPort = 5280,
            bluetoothName = "OnePlus 13",
        )
        val lance = CompanionProbe(
            phoneId = "lance-id",
            friendlyName = "Lance OnePlus 13",
            proxyPort = 5280,
            bluetoothName = "Lance OnePlus 13",
        )

        assertTrue(
            CompanionIdentityPolicy.matches(
                expectedPhoneId = null,
                bluetoothDeviceName = "OnePlus 13",
                probe = liz,
            ),
        )
        assertFalse(
            CompanionIdentityPolicy.matches(
                expectedPhoneId = null,
                bluetoothDeviceName = "OnePlus 13",
                probe = lance,
            ),
        )
    }

    @Test
    fun `match basis distinguishes bluetooth name from legacy friendly fallback`() {
        val current = CompanionProbe(
            phoneId = "liz-id",
            friendlyName = "Liz OnePlus 13",
            proxyPort = 5280,
            bluetoothName = "OnePlus 13",
        )
        val legacy = CompanionProbe(
            phoneId = "liz-id",
            friendlyName = "OnePlus 13",
            proxyPort = 5280,
        )

        assertEquals(
            CompanionIdentityMatch.BLUETOOTH_NAME,
            CompanionIdentityPolicy.matchBasis(null, "OnePlus 13", current),
        )
        assertEquals(
            CompanionIdentityMatch.FRIENDLY_NAME,
            CompanionIdentityPolicy.matchBasis(null, "OnePlus 13", legacy),
        )
        assertEquals(
            CompanionIdentityMatch.NONE,
            CompanionIdentityPolicy.matchBasis(null, "Different Phone", current),
        )
    }

    @Test
    fun `present empty bluetooth field never falls back to friendly name`() {
        val probe = CompanionIdentityPolicy.parseProbe(
            "OAL!liz-id\tOnePlus 13\twpp=5280\tbt_name=",
        )

        requireNotNull(probe)
        assertTrue(probe.bluetoothNameReported)
        assertNull(probe.bluetoothName)
        assertEquals(
            CompanionIdentityMatch.NONE,
            CompanionIdentityPolicy.matchBasis(null, "OnePlus 13", probe),
        )
    }

    @Test
    fun `reported bluetooth name does not fall back to misleading friendly name`() {
        val probe = CompanionProbe(
            phoneId = "other-id",
            friendlyName = "OnePlus 13",
            proxyPort = 5280,
            bluetoothName = "Someone Else's Phone",
        )

        assertFalse(
            CompanionIdentityPolicy.matches(
                expectedPhoneId = null,
                bluetoothDeviceName = "OnePlus 13",
                probe = probe,
            ),
        )
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
        assertTrue(control.contains("CompanionIdentityPolicy.matchBasis("))
        assertTrue(control.contains("Bluetooth-name matched companion at"))
        assertTrue(control.contains("reportedBluetoothName=${'$'}{probe.bluetoothName ?: \"unknown\"}"))
        assertTrue(control.contains("private val phoneClaimLock = Any()"))
        assertTrue(control.contains("private var phoneClaimGeneration = 0L"))
        assertTrue(control.contains("claimGeneration = claimPhoneForHandshake("))
        assertTrue(control.contains("phoneClaimStillOwned(phoneBtAddress, claimGeneration)"))
        assertTrue(control.contains("val claimGeneration: Long"))
        assertTrue(session.contains("withIdentityValidatedCompanion"))
        assertFalse(session.contains("identityValidatedCompanionIp()"))
        assertFalse(session.contains("AaWirelessBtControl.lastKnownPhoneIp"))
        assertFalse(session.contains(".lastKnownPhoneIp"))
        assertEquals(1, session.windowed("\"wpp\" -> startWpp()".length)
            .count { it == "\"wpp\" -> startWpp()" })
        assertEquals(2, session.windowed("\"wpp\" -> startWpp(recovery = true)".length)
            .count { it == "\"wpp\" -> startWpp(recovery = true)" })
        val matchIndex = control.indexOf("CompanionIdentityPolicy.matchBasis(")
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

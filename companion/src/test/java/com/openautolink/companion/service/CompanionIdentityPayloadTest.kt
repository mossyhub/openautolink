package com.openautolink.companion.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionIdentityPayloadTest {

    @Test
    fun `identity payload reports local bluetooth name separately from friendly name`() {
        assertEquals(
            "OAL!19bbbce4-1234\tLiz OnePlus 13\twpp=5280\tbt_name=OnePlus 13\n",
            CompanionIdentityPayload.encode(
                phoneId = "19bbbce4-1234",
                friendlyName = "Liz OnePlus 13",
                bluetoothName = "OnePlus 13",
                proxyPort = 5280,
            ),
        )
    }

    @Test
    fun `identity payload marks bluetooth name unavailable instead of looking legacy`() {
        assertEquals(
            "OAL!19bbbce4-1234\tOnePlus 13\twpp=5280\tbt_name=\n",
            CompanionIdentityPayload.encode(
                phoneId = "19bbbce4-1234",
                friendlyName = "OnePlus 13",
                bluetoothName = null,
                proxyPort = 5280,
            ),
        )
    }

    @Test
    fun `tcp identity probe publishes the local bluetooth adapter name`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/service/TcpAdvertiser.kt",
        ).readText()

        assertTrue(source.contains("android.bluetooth.BluetoothManager"))
        assertTrue(source.contains("manager?.adapter?.name"))
        assertTrue(source.contains("CompanionIdentityPayload.encode("))
        assertTrue(source.contains("bluetoothName = bluetoothName"))
        assertTrue(source.contains("bluetoothName=${'$'}{bluetoothName ?: \"unavailable\"}"))
    }

    @Test
    fun `phone identity guidance no longer requires friendly name to equal bluetooth name`() {
        val source = projectFile(
            "companion/src/main/java/com/openautolink/companion/ui/MainScreen.kt",
        ).readText()

        assertTrue(source.contains("unique Bluetooth device name"))
        assertTrue(source.contains("reported automatically"))
        assertFalse(source.contains("set this Friendly name to exactly match"))
    }

    private fun projectFile(relativePath: String): File {
        val workingDir = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDir) { it.parentFile }
            .map { root -> File(root, relativePath) }
            .first { it.isFile }
    }
}

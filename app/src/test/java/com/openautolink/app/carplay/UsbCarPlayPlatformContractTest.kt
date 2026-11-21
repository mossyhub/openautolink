package com.openautolink.app.carplay

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UsbCarPlayPlatformContractTest {
    @Test fun wiredServiceIsRegisteredWithoutDefaultRoutes() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("com.openautolink.carplaylab.usb.OalUsbCarPlayVpnService"))
        assertTrue(manifest.contains("android.permission.BIND_VPN_SERVICE"))
        val service = File("../carplay-lab/src/main/java/com/openautolink/carplaylab/usb/OalUsbCarPlayVpnService.kt").readText()
        val builder = service.substringAfter("val fd = Builder()").substringBefore(".establish()")
        assertTrue(builder.contains(".addAllowedApplication(packageName)"))
        assertEquals(listOf("\"fe80::\", 64"), Regex("""\.addRoute\(([^)]*)\)""")
            .findAll(builder).map { it.groupValues[1] }.toList())
        assertFalse(builder.contains(".addDnsServer("))
        assertTrue("OAL's IPv4 traffic must be allowed to fall through to the normal network",
            builder.contains(".allowFamily(OsConstants.AF_INET)"))
    }

    @Test fun usbStageReceiptsSurviveExportAndRejectIdentifiers() {
        val stages = listOf("permission", "vpn-consent", "reenumeration", "usbmux", "phone-identification", "carkit", "listener", "endpoint", "runtime")
        for (stage in stages) for (result in listOf("pending", "ready", "accepted", "sent")) {
            val message = "CarPlay USB stage=$stage result=$result"
            assertEquals("CARPLAY_AUDIO session=9 $message", CarPlayAudioDiagnostics.sanitize(9, message))
            assertNull(CarPlayAudioDiagnostics.sanitize(9, "$message serial=private"))
            assertNull(CarPlayAudioDiagnostics.sanitize(9, "$message\n"))
        }
        assertNotNull(CarPlayAudioDiagnostics.sanitize(9, "CarPlay USB stage=failed reason=PERMISSION"))
        assertNull(CarPlayAudioDiagnostics.sanitize(9, "CarPlay USB stage=failed reason=private"))
    }
}

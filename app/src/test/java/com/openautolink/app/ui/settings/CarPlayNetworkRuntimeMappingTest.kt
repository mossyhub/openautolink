package com.openautolink.app.ui.settings

import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.data.CarPlaySettingsPolicy
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CarPlayNetworkRuntimeMappingTest {
    @Test fun savedCarPlayNetworkConfigNeverBorrowsAndroidAutoWppSettings() {
        val saved = CarPlaySettings(enabled = true, phoneAddress = "10:20:30:40:50:60",
            receiverAddress = "20:30:40:50:60:70", networkFamily = "ipv6", peerAddress = "fe80::2",
            localAddress = "fe80::1%ap_br_swlan0", apFrequencyMhz = 5745, apSecurity = "wpa3", ssid = "car-ap")
        val display = CarPlayDisplayPreferences(widthPhysicalMm = 300)
        val config = CarPlaySettingsPolicy.runtimeConfig(saved, display)
        assertEquals(saved.phoneAddress, config.phoneAddress)
        assertEquals(saved.receiverAddress, config.receiverAddress)
        assertEquals(saved.peerAddress, config.peerAddress)
        assertEquals(saved.interfaceName, config.interfaceName)
        assertEquals(saved.localAddress, config.localAddress)
        assertEquals(saved.ssid, config.ssid)
        assertEquals(saved.networkFamily, config.networkFamily)
        assertEquals(saved.apFrequencyMhz, config.apFrequencyMhz)
        assertEquals(saved.apSecurity, config.apSecurity)
        assertFalse(config.sharedWifiOverride)
        assertSame(display, config.display)
        val shared = CarPlaySettingsPolicy.runtimeConfig(saved.copy(sharedWifiOverride = true,
            networkFamily = "auto", interfaceName = "wlan0", peerAddress = "192.0.2.2", localAddress = ""), display)
        assertTrue(shared.sharedWifiOverride)
        assertEquals("auto", shared.networkFamily)
        assertEquals("wlan0", shared.interfaceName)
        assertEquals("", shared.localAddress)
        val vm = source("ui/projection/ProjectionViewModel.kt")
            .substringAfter("private fun createCarPlayRuntime(").substringBefore("fun reconnectCarPlay()")
        assertFalse(vm.contains("preferences.wppChannelMhz"))
        assertTrue(vm.contains("CarPlaySettingsPolicy.runtimeConfig(saved, display)"))
    }

    @Test fun disabledConfigurationCannotProduceRuntimeSnapshot() {
        assertThrows(IllegalArgumentException::class.java) {
            CarPlaySettingsPolicy.runtimeConfig(CarPlaySettings(enabled = false), CarPlayDisplayPreferences())
        }
    }

    private fun source(path: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, "app/src/main/java/com/openautolink/app/$path").readText()
    }
}

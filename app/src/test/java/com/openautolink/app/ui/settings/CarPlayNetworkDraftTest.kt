package com.openautolink.app.ui.settings

import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.data.CarPlaySettingsPolicy
import com.openautolink.app.data.CarPlayPairedPhone
import org.junit.Assert.*
import org.junit.Test

class CarPlayNetworkDraftTest {
    @Test fun unknownApPeerIsSaveableButNeverAdvertisedAsProvisionedReadiness() {
        val phone = CarPlayPairedPhone("10:20:30:40:50:60", "iPhone")
        val settings = CarPlaySettings(enabled = true, phoneAddress = phone.address,
            receiverAddress = "20:30:40:50:60:70", ssid = "existing-car-ap")
        assertNull(carPlayEditError(settings))
        val action = CarPlaySettingsPolicy.requiredAction(settings, listOf(phone), true, true)
        assertTrue(action, action.contains("persisted trusted controller"))
        assertFalse(action, action.startsWith("Ready"))
        assertEquals("Enter the selected phone's numeric IP address",
            CarPlaySettingsPolicy.requiredAction(settings.copy(sharedWifiOverride = true), listOf(phone), true, true))
        assertTrue(CarPlaySettingsPolicy.requiredAction(settings.copy(receiverAddress = "02:00:00:00:00:00"),
            listOf(phone), true, true).contains("Bluetooth"))
    }
    @Test fun configuredEndpointsUseSelectedFamilyAndDeferInterfaceOwnershipToRuntime() {
        assertNull(carPlayEditError(CarPlaySettings(peerAddress = "fe80::2", localAddress = "fe80::1")))
        assertNull(carPlayEditError(CarPlaySettings(peerAddress = "fe80::2%ap_br_swlan0")))
        assertNull(carPlayEditError(CarPlaySettings(peerAddress = "fe80::2%7")))
        for (peer in listOf("fe80::2%0", "fe80::2%wlan0", "phone.local", "192.0.2.20", "2001:db8::2")) {
            assertNotNull(peer, carPlayEditError(CarPlaySettings(peerAddress = peer)))
        }
        for (local in listOf("0.0.0.0", "::", "::1", "ff02::1", "fe80::1%0", "fe80::1%wlan0", "host.local")) {
            assertNotNull(local, carPlayEditError(CarPlaySettings(localAddress = local)))
        }
        assertNull(carPlayEditError(CarPlaySettings(networkFamily = "ipv4", peerAddress = "192.0.2.20", localAddress = "192.0.2.1")))
        assertNotNull(carPlayEditError(CarPlaySettings(sharedWifiOverride = true)))
        assertNull(carPlayEditError(CarPlaySettings(sharedWifiOverride = true, peerAddress = "192.0.2.20")))
    }
    @Test fun invalidApRadioProfileCannotBeCommitted() {
        assertNotNull(carPlayEditError(CarPlaySettings(apSecurity = "open")))
        for (frequency in listOf(0, -1, 5181, 7000)) {
            assertNotNull(carPlayEditError(CarPlaySettings(apFrequencyMhz = frequency)))
        }
        for (frequency in listOf(2412, 2484, 5180, 5745)) {
            for (security in listOf("wpa2", "wpa3")) {
                assertNull(carPlayEditError(CarPlaySettings(apFrequencyMhz = frequency, apSecurity = security)))
            }
        }
    }
    @Test fun invalidNetworkFamilyCannotBeCommitted() {
        assertNotNull(carPlayEditError(CarPlaySettings(networkFamily = "ip")))
        for (family in listOf("auto", "ipv4", "ipv6")) {
            assertNull(carPlayEditError(CarPlaySettings(networkFamily = family)))
        }
    }
}

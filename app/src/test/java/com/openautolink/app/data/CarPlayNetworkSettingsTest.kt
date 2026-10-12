package com.openautolink.app.data

import org.junit.Assert.*
import org.junit.Test

/** Serialization regressions for the atomic next-session network fields. */
class CarPlayNetworkSettingsTest {
    @Test fun legacyRecordRetainsExplicitDisabledPathAndIndependentApDefaults() {
        val settings = CarPlaySettings.decode("""{"enabled":false,"resolution":"720p","fps":30}""")
        assertFalse(settings.enabled)
        assertEquals("auto", settings.networkFamily)
        assertEquals("ap_br_swlan0", settings.interfaceName)
        assertEquals(5180, settings.apFrequencyMhz)
        assertEquals("wpa2", settings.apSecurity)
        assertEquals("", settings.localAddress)
        assertEquals("", settings.peerAddress)
    }
    @Test fun explicitOverridesRoundTripWithoutRepairOrSecrets() {
        val settings = CarPlaySettings(networkFamily = "ipv6", localAddress = "fe80::1%ap_br_swlan0",
            peerAddress = "fe80::2%ap_br_swlan0", interfaceName = "ap_br_swlan0",
            apFrequencyMhz = 5745, apSecurity = "wpa3")
        assertEquals(settings, CarPlaySettings.decode(CarPlaySettings.encode(settings)))
        val invalid = settings.copy(networkFamily = "not-a-family", localAddress = "invalid")
        // Storage preserves exact user values. Runtime admission rejects them; storage does not repair them.
        assertEquals(invalid, CarPlaySettings.decode(CarPlaySettings.encode(invalid)))
        assertFalse(CarPlaySettings.encode(settings).contains("passphrase"))
    }
}

package com.openautolink.app.data

import org.junit.Assert.*
import org.junit.Test

class CarPlayConnectionModeTest {
    private fun mode(settings: CarPlaySettings): Any? = settings.javaClass.methods
        .firstOrNull { it.name == "getConnectionMode" }?.invoke(settings)
    @Test fun missingModeDoesNotOptIntoWireless() {
        assertEquals("usb", mode(CarPlaySettings.decode("{\"enabled\":true}")))
    }
    @Test fun freshSupportedAndMalformedModesStayWired() {
        assertTrue(CarPlaySettings.decode(null).enabled)
        assertEquals("usb", CarPlaySettings.decode(null).connectionMode)
        assertEquals("usb", CarPlaySettings.decode("{\"enabled\":true,\"connectionMode\":\"wpp\"}").connectionMode)
        assertTrue(CarPlaySettings.decode("{\"enabled\":true}").enabled)
    }
    @Test fun explicitWirelessModeRoundTripsWithoutChangingEnablementOrAaPreference() {
        val saved = CarPlaySettings(enabled = false, connectionMode = "wireless", phoneAddress = "synthetic")
        assertEquals(saved, CarPlaySettings.decode(CarPlaySettings.encode(saved)))
        assertEquals("wpp", AppPreferences.DEFAULT_DIRECT_TRANSPORT)
    }
    @Test(expected = IllegalArgumentException::class) fun cannotPersistAnUnknownConnectionMode() {
        CarPlaySettings.encode(CarPlaySettings(connectionMode = "unknown"))
    }
}

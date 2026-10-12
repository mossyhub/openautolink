package com.openautolink.app.data

import org.junit.Assert.*
import org.junit.Test

class CarPlayUsbSupportDefaultsTest {
    @Test fun freshSettingsSupportUsbAlongsideNormalWirelessAndroidAuto() {
        assertTrue(CarPlaySettings().enabled)
        assertTrue(CarPlaySettings.decode(null).enabled)
        assertEquals("usb", CarPlaySettings.decode(null).connectionMode)
    }

    @Test fun explicitDisabledAndWirelessChoicesRemainUnchanged() {
        val disabled = CarPlaySettings.decode("""{"enabled":false,"connectionMode":"usb"}""")
        assertFalse(disabled.enabled)
        val wireless = CarPlaySettings.decode("""{"enabled":true,"connectionMode":"wireless"}""")
        assertTrue(wireless.enabled)
        assertEquals("wireless", wireless.connectionMode)
        assertEquals(disabled, CarPlaySettings.decode(CarPlaySettings.encode(disabled)))
    }

    @Test fun malformedStoredSettingsFailClosedRatherThanEnableUsb() {
        assertFalse(CarPlaySettings.decode("not-json").enabled)
        assertFalse(CarPlaySettings.decode("""{"enabled":"invalid"}""").enabled)
    }
}

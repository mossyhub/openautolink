package com.openautolink.app.ui.settings

import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.data.CarPlaySettingsPolicy
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import org.junit.Assert.*
import org.junit.Test

class ManualBluetoothValidationTest {
    @Test fun acceptsDeliberateUserFormatsWithoutRewritingSavedSpelling() {
        for (value in listOf("aa:bb:cc:dd:ee:10", " AA-BB-CC-DD-EE-10 ", "\tAA:BB:CC:DD:EE:10\n", "", "  ")) {
            val saved = CarPlaySettings(receiverAddress = value)
            assertNull("Accepted user format rejected", carPlayEditError(saved))
            assertEquals(if (value.isBlank()) "" else "AA:BB:CC:DD:EE:10",
                CarPlaySettingsPolicy.runtimeConfig(saved, CarPlayDisplayPreferences()).receiverAddress)
            assertEquals(value, saved.receiverAddress)
        }
    }
    @Test fun rejectsInvalidTokensRatherThanRepairingThem() {
        for (value in listOf("00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "FF-FF-FF-FF-FF-FF", "02:00:00:00:00:00",
            "prefix AA:BB:CC:DD:EE:10", "AA:BB-CC:DD:EE:10", "A:BB:CC:DD:EE:10", "AA:BB:CC:DD:EE:10:11", "AA:BB:CC:DD:EE:GG")) {
            val saved = CarPlaySettings(receiverAddress = value)
            val error = carPlayEditError(saved)
            assertNotNull("Invalid token accepted", error)
            assertFalse(error!!.contains(value))
        }
    }
    @Test fun modeChangesAndCodecRoundTripKeepTheOneExistingValue() {
        val wireless = CarPlaySettings(connectionMode = "wireless", receiverAddress = "aa:bb:cc:dd:ee:10")
        val usb = CarPlaySettings.decode(CarPlaySettings.encode(wireless.copy(connectionMode = "usb")))
        assertEquals(wireless, usb.copy(connectionMode = "wireless"))
        assertEquals("AA:BB:CC:DD:EE:10", CarPlaySettingsPolicy.runtimeConfig(wireless, CarPlayDisplayPreferences()).receiverAddress)
    }
}

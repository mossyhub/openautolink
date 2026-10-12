package com.openautolink.app.ui.projection

import com.openautolink.app.data.AppPreferences
import org.junit.Assert.*
import org.junit.Test

class PhoneSwitcherPolicyTest {
    @Test fun wppNeverOffersSharedWifiPhoneSwitcher() {
        assertFalse(PhoneSwitcherPolicy.visible("wpp", AppPreferences.CONNECTION_MODE_CAR_HOTSPOT, true))
    }
    @Test fun onlyExplicitSharedWifiCarHotspotOffersSwitcher() {
        assertTrue(PhoneSwitcherPolicy.visible("hotspot", AppPreferences.CONNECTION_MODE_CAR_HOTSPOT, true))
        assertFalse(PhoneSwitcherPolicy.visible("usb", AppPreferences.CONNECTION_MODE_CAR_HOTSPOT, true))
        assertFalse(PhoneSwitcherPolicy.visible("hotspot", AppPreferences.CONNECTION_MODE_CAR_HOTSPOT, false))
        assertFalse(PhoneSwitcherPolicy.visible("hotspot", "phone_hotspot", true))
    }
}

package com.openautolink.app.ui.projection

import com.openautolink.app.data.AppPreferences

internal object PhoneSwitcherPolicy {
    fun visible(transport: String, connectionMode: String, overlayEnabled: Boolean): Boolean =
        transport == "hotspot" && connectionMode == AppPreferences.CONNECTION_MODE_CAR_HOTSPOT && overlayEnabled
}

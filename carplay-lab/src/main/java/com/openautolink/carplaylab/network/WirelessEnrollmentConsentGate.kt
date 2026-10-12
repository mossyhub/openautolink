package com.openautolink.carplaylab.network

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Called only on the exact selected, identified and MFi-qualified bootstrap after OAL opt-in.
 * Native/OAL consent plus the phone's real Wi-Fi/session request authorize TOFU, not BT key binding.
 * Transport identity/availability notifications alone never authorize credential disclosure. */
class WirelessEnrollmentConsentGate {
    fun observe(frame: Iap2Frame): Boolean = when (frame.messageId) {
        0x5702 -> frame.payload.isEmpty() // Pinned RequestAccessoryWiFiConfiguration has no fields.
        0x4300 -> runCatching {
            com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages.availability(frame).wireless?.available == true
        }.getOrDefault(false)
        else -> false
    }
}

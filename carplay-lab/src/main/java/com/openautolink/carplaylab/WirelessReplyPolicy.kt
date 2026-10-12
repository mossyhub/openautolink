package com.openautolink.carplaylab

/** Per-transport Wi-Fi limits follow the pinned wireless controller; availability is independent. */
internal class WirelessReplyPolicy {
    private var transportNotified = false
    private var preTransportSent = 0
    private var postTransportSent = 0

    fun allow(messageId: Int): Boolean {
        if (messageId == 0x4300) return true
        if (messageId == 0x4e0e) transportNotified = true
        if (messageId != 0x5702 && messageId != 0x4e0e) return false
        return if (transportNotified) {
            if (postTransportSent >= 2) false else { postTransportSent++; true }
        } else {
            if (preTransportSent >= 5) false else { preTransportSent++; true }
        }
    }
}

internal fun closeRuntime(channel: () -> Unit, session: () -> Unit) {
    try { channel() } finally { session() }
}

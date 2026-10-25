package com.openautolink.carplaylab.telecom

import com.shilapi.xcertplay.iap2.message.Iap2Messages

/** Retained iOS EndCall consumer action0 handles the frontmost ringing call; no UUID/end-all. */
object IncomingRejectMessage {
    fun frame() = Iap2Messages.buildRaw(0x415c) { u8(0, 0) }
}

package com.openautolink.carplaylab.telecom

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Dormant builders only: do not replace existing subscriptions or send until producer semantics
 * and authenticated owner/epoch dispatch are proved. Source: schema-followup.md, startcall-arm64
 * 0x10011e82c–0x10011e988 (explicit non5 void IDs choose nonlegacy), luka-communications.rs:3–17;
 * communications serializer 0x1001215fc–0x100121610 establishes count ID10/u8 hint.
 * Does not request vCard ID5 or claim inventory completion. */
object CallObservationSubscriptions {
    fun nonlegacyCalls(): Iap2Frame = Iap2Messages.buildRaw(0x4154) {
        listOf(0, 1, 2, 3, 4, 6, 7, 8, 9, 10, 11, 12).forEach(::void)
    }
    fun callCountHint(): Iap2Frame = Iap2Messages.buildRaw(0x4157) { void(10) }
}

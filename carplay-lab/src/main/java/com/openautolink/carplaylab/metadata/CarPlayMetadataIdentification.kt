package com.openautolink.carplaylab.metadata

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Runtime tunnel retains the pinned metadata component. Bluetooth only hands off/authenticates. */
object CarPlayMetadataIdentification {
    private val sent = setOf(0x5000,0x5002,0x5200,0x5203)
    private val received = setOf(0x5001,0x5201,0x5202)
    fun bootstrap(base: Iap2Frame): Iap2Frame {
        require(base.messageId == 0x1d01)
        val b = Iap2BodyReader.of(base)
        return Iap2Messages.buildRaw(0x1d01) {
            b.list().filterNot { it.id in setOf(6,7,30) }.forEach { raw(it.id,it.payload) }
            u16List(6,b.u16List(6).filterNot { it in sent })
            u16List(7,b.u16List(7).filterNot { it in received })
        }
    }
    fun bootstrapSubscriptions(frames: List<Iap2Frame>): List<Iap2Frame> = frames.filterNot { it.messageId in sent }
}

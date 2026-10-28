package com.openautolink.carplaylab.metadata

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.Iap2ArtworkTransfer

/** One exact Wi-Fi runtime tunnel. Artwork callback must never block the iAP2 link worker. */
interface RuntimeMetadataLink : AutoCloseable {
    fun mediaAvailability(ready:Boolean) {}
    /** Exact renderer state, independent of phone NowPlaying. Must only enqueue work. */
    fun mediaOutput(blocked:Boolean,current:()->Boolean) {}
    fun authenticate()
    fun handle(frame: Iap2Frame): Boolean
    fun offerArtwork(transfer: Iap2ArtworkTransfer)
    fun tick()
    fun revoke()
}

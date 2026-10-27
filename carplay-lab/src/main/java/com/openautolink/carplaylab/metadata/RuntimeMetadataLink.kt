package com.openautolink.carplaylab.metadata

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.Iap2ArtworkTransfer

/** One exact Wi-Fi runtime tunnel. Artwork callback must never block the iAP2 link worker. */
interface RuntimeMetadataLink : AutoCloseable {
    fun mediaAvailability(ready:Boolean) {}
    fun authenticate()
    fun handle(frame: Iap2Frame): Boolean
    fun offerArtwork(transfer: Iap2ArtworkTransfer)
    fun tick()
    fun revoke()
}

// SPDX-License-Identifier: GPL-3.0-only
// Adapted from DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d; see MEDIA-PROVENANCE.md.
package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong

/** 每次绑定生成独立身份，防止旧 accept 和旧清理作用于新 listener。 */
class AirPlayListenerIdentity(val generation: Int) {
    val id: Long = nextId.incrementAndGet()
    private companion object { val nextId = AtomicLong() }
}

data class AirPlayTcpAccepted(
    val listener: AirPlayListenerIdentity,
    val internal: Boolean,
    val acceptedAtNanos: Long,
)

internal fun isInternalAirPlayPeer(peer: InetAddress, local: InetAddress): Boolean =
    peer.isLoopbackAddress || peer == local ||
        runCatching { NetworkInterface.getByInetAddress(peer) != null }.getOrDefault(true)

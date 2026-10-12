// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

import java.net.InetAddress

internal data class TouchPoint(val down: Boolean, val x: Float, val y: Float)

/** IP is only admission; PairVerify remains controller authentication. Never resolves hostnames. */
internal class MediaPolicy(peerIp: String) {
    val peer: InetAddress
    init {
        require(peerIp.isNotEmpty() && peerIp.length <= 80 && !peerIp.contains('%'))
        require(if (peerIp.contains(':')) peerIp.all { it in "0123456789abcdefABCDEF:." }
            else peerIp.split('.').let { parts -> parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } && part.toInt() in 0..255
            } }) { "Numeric selected phone IP required" }
        peer = InetAddress.getByName(peerIp)
        require(!peer.isAnyLocalAddress && !peer.isLoopbackAddress && !peer.isMulticastAddress)
    }
    fun allows(address: InetAddress): Boolean = peer == address
    companion object {
        fun supportsAudio(bits: Long, type: Int): Boolean =
            com.shilapi.xcertplay.media.AudioOutputSupport.format(bits, type) != null
        fun touch(action: Int, x: Float, y: Float, rect: CarPlayRenderRect): TouchPoint? {
            if (action !in setOf(0, 1, 2, 3) || !x.isFinite() || !y.isFinite() ||
                !rect.left.isFinite() || !rect.top.isFinite() || !rect.width.isFinite() || !rect.height.isFinite() ||
                rect.width <= 0 || rect.height <= 0) return null
            val relativeX = (x - rect.left) / rect.width
            val relativeY = (y - rect.top) / rect.height
            // Reject a new gesture on letterbox bars, but deliver a clamped release if it exits video.
            if (action == 0 && (relativeX !in 0f..1f || relativeY !in 0f..1f)) return null
            return TouchPoint(action == 0 || action == 2, relativeX.coerceIn(0f, 1f), relativeY.coerceIn(0f, 1f))
        }
        fun touch(action: Int, x: Float, y: Float, width: Int, height: Int): TouchPoint? {
            if (action !in setOf(0, 1, 2, 3) || width <= 0 || height <= 0 || !x.isFinite() || !y.isFinite()) return null
            return TouchPoint(action == 0 || action == 2, (x / width).coerceIn(0f, 1f), (y / height).coerceIn(0f, 1f))
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

/** Diagnostic exclusion only: normal owners retain their existing normal-vs-normal rules. */
object MicrophoneCaptureFence {
    private val lock = Any()
    private val normal = mutableSetOf<Lease>()
    private var diagnostic: Lease? = null
    private var projectionPause = false
    fun pauseProjection() = synchronized(lock) { projectionPause = true }
    fun projectionPaused(): Boolean = synchronized(lock) { projectionPause }
    fun resumeProjection(): Boolean = synchronized(lock) {
        if (diagnostic != null) false else { projectionPause = false; true }
    }

    fun tryNormal(): Lease? = synchronized(lock) {
        if (diagnostic != null) null else Lease(false).also { normal.add(it) }
    }

    /** Reserve before pending transport handoff; retain until exact projection retirement. */
    fun tryProjectionClaim(): Lease? = synchronized(lock) {
        if (diagnostic != null || projectionPause) null else Lease(false).also { normal.add(it) }
    }

    fun tryDiagnostic(): Lease? = synchronized(lock) {
        if (diagnostic != null || normal.isNotEmpty()) null
        else Lease(true).also { diagnostic = it }
    }

    /** Release is an acknowledgement, never a request to interrupt or stop a worker. */
    class Lease internal constructor(private val exclusive: Boolean) {
        fun release() = synchronized(lock) {
            if (exclusive) {
                if (diagnostic === this) diagnostic = null
            } else normal.remove(this)
            Unit
        }
    }
}

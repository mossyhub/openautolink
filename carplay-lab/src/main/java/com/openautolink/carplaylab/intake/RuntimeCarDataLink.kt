package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Captured exact tunnel lifecycle; invoked only on its runtime IO worker. */
interface RuntimeCarDataLink : AutoCloseable {
    fun identification(base: Iap2Frame): Iap2Frame
    fun authenticate()
    fun handle(frame: Iap2Frame): Boolean
    fun tick(encryptedEventReady: Boolean)
    fun pollTimeout(maximumMs: Long): Long
    /** Nonblocking cancellation fence, safe from session-ended/write callbacks; close acknowledges. */
    fun revoke()
}

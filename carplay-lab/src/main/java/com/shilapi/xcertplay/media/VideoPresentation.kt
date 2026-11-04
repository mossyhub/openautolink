// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.view.Surface
import java.util.concurrent.atomic.AtomicReference

/** Constant-space desired presentation; no platform call or worker/lifecycle monitor on UI. */
internal class VideoPresentation(initial: Surface?) {
    class Receipt(val surface: Surface?, val generation: Long, val closed: Boolean = false)
    private val desired = AtomicReference(Receipt(initial, 0))
    fun snapshot(): Receipt = desired.get()
    fun isCurrent(receipt: Receipt): Boolean = desired.get() === receipt && !receipt.closed
    fun attach(surface: Surface) {
        while (true) {
            val old = desired.get()
            if (old.closed || old.surface === surface) return
            if (desired.compareAndSet(old, Receipt(surface, old.generation + 1))) return
        }
    }
    fun detach(surface: Surface) {
        while (true) {
            val old = desired.get()
            if (old.closed || old.surface !== surface) return
            if (desired.compareAndSet(old, Receipt(null, old.generation + 1))) return
        }
    }
    /** Worker observations may invalidate only their captured receipt, never a same-Surface successor. */
    fun invalidate(receipt: Receipt): Boolean {
        if (receipt.closed || receipt.surface == null) return false
        return desired.compareAndSet(receipt, Receipt(null, receipt.generation + 1))
    }
    /** Every binding mints an exact lease, including reuse of the same Java Surface. */
    fun attachLease(surface: Surface): Receipt? {
        while (true) {
            val old = desired.get()
            if (old.closed) return null
            val next = Receipt(surface, old.generation + 1)
            if (desired.compareAndSet(old, next)) return next
        }
    }
    fun detachLease(receipt: Receipt) { invalidate(receipt) }
    fun close() {
        while (true) {
            val old = desired.get()
            if (old.closed || desired.compareAndSet(old, Receipt(null, old.generation + 1, true))) return
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import java.io.Closeable

/** One capture, including a closing capture whose platform worker has not returned yet. */
internal class MicrophoneOwnerSlot<K, T : Closeable>(
    private val retired: (T) -> Boolean,
    private val closed: (T) -> Boolean,
) : Closeable {
    private var owner: K? = null
    private var capture: T? = null
    private var sealed = false

    @Synchronized fun start(key: K, create: () -> T): Boolean {
        if (sealed) return false
        capture?.let { current ->
            if (owner == key && !closed(current) && !retired(current)) return true
            current.close()
            if (!retired(current)) return false
            capture = null; owner = null
        }
        capture = create(); owner = key
        return true
    }

    @Synchronized fun stop(key: K) {
        if (owner != key) return
        capture?.let { current ->
            current.close()
            if (retired(current)) { capture = null; owner = null }
        }
    }

    @Synchronized override fun close() {
        sealed = true
        capture?.close()
        // Do not discard a retiring capture or pretend the platform worker has exited.
    }
}

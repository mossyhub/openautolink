package com.openautolink.app.carplay

/** One monitor spans admission, mutation and retirement effects for BOTH host surfaces. */
internal class MetadataHostOwnership {
    private var current: Any? = null
    @Synchronized fun claim(owner: Any): Boolean {
        if (current != null) return current === owner
        current = owner
        return true
    }
    @Synchronized fun publish(owner: Any, effect: () -> Unit): Boolean {
        if (current !== owner) return false
        effect()
        return true
    }
    @Synchronized fun legacy(effect: () -> Unit) { if (current == null) effect() }
    @Synchronized fun retire(owner: Any, effect: () -> Unit) {
        if (current !== owner) return
        effect() // A failed retirement retains admission. Caller retries before AA return.
        current = null
    }
    companion object { val process = MetadataHostOwnership() }
}

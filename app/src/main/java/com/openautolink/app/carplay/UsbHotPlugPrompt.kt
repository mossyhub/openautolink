package com.openautolink.app.carplay

/** Memory-only consent receipt. No USB identifier or saved preference is exposed to UI. */
data class UsbHotPlugPrompt(val generation: Long, val protocolLabel: String) {
    init { require(protocolLabel == "Android Auto" || protocolLabel == "CarPlay") }
}

/** Attachment consent precedes permission, pairing, AOA and retirement. */
class UsbHotPlugConsent {
    data class Candidate(val generation: Long, val deviceName: String, val vendorId: Int)
    companion object { private val generations = java.util.concurrent.atomic.AtomicLong() }
    private val attachments = mutableMapOf<String, Candidate>()
    private val suppressed = mutableSetOf<Long>()
    private var pending: Candidate? = null
    @Synchronized fun offer(name: String, vendorId: Int): Candidate? {
        val candidate = attachments.getOrPut(name) { Candidate(generations.incrementAndGet(), name, vendorId) }
        if (candidate.generation in suppressed || pending != null) return null
        pending = candidate
        return candidate
    }
    @Synchronized fun current(generation: Long): Candidate? = pending?.takeIf { it.generation == generation }
    @Synchronized fun stay(generation: Long): Boolean {
        val candidate = current(generation) ?: return false
        suppressed.add(candidate.generation)
        pending = null
        return true
    }
    @Synchronized fun accept(generation: Long): Candidate? {
        val candidate = current(generation) ?: return null
        stay(generation)
        return candidate
    }
    @Synchronized fun detach(name: String): Boolean {
        val removed = attachments.remove(name) ?: return false
        suppressed.remove(removed.generation)
        if (pending === removed) { pending = null; return true }
        return false
    }
    @Synchronized fun retry(name: String) {
        detach(name) // A deliberate retry creates a fresh consent capability for the same cable.
    }
}

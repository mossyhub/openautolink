package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Exact wired CSM bootstrap -> runtime handoff. At most one latest command per data plane.
 * No source activation or transport write before the runtime authenticates this exact link.
 * Stops and invalid starts replace earlier subscriptions; unknown frames retain caller ownership.
 */
class OalCarDataBootstrap(
    private val capabilities: OalCarDataCapabilities,
    private val elapsedMs: () -> Long,
    private val receipt: (String) -> Unit,
    private val evReceipt: ((OalEvWireDiagnostics.Receipt) -> Boolean)? = null,
) : AutoCloseable {
    private val pending = linkedMapOf<Int,Iap2Frame>()
    private val lastReceipts = mutableMapOf<Pair<Int,String>,Long>()
    private var transferred = false
    private var offerReported = false
    private val retired = java.util.concurrent.atomic.AtomicBoolean()

    @Synchronized fun identification(base: Iap2Frame): Iap2Frame {
        val frame = OalCarDataIdentification.apply(base,capabilities)
        if (!retired.get() && !offerReported) {
            offerReported=true
            // Wired production supplies a memory-only sink. The runtime reporter reports the
            // ordinary capability summary later; do not call its logger before Identify send.
            val capture = evReceipt
            if (capture != null) {
                runCatching { OalEvWireDiagnostics.preparedOffer(frame)?.let { capture(it) } }
            } else {
                runCatching { receipt("carplay-car-data-offer location=${capabilities.location} range=${capabilities.range} current=${capabilities.currentEnergy} maximum=${capabilities.maximumEnergy}" +
                    if (capabilities.outsideTemperature) " temperature=true" else "") }
            }
        }
        return frame
    }

    @Synchronized fun handle(frame: Iap2Frame): Boolean {
        val plane = when(frame.messageId) {0xFFFA,0xFFFC -> 0; 0xA100,0xA102 -> 1; else -> return false}
        val result = when {
            retired.get() || transferred -> "retired"
            frame.payload.size>128 -> "oversized"
            else -> "deferred"
        }
        if (!retired.get() && !transferred) {
            val bytes = when {
                frame.messageId in setOf(0xFFFC,0xA102) -> byteArrayOf() // Stop always revokes.
                frame.payload.size>128 -> ByteArray(129) // Reject without retaining unbounded bytes.
                else -> frame.payload.copyOf()
            }
            pending.remove(plane)
            pending[plane]=Iap2Frame(frame.messageId,bytes)
        }
        val key=frame.messageId to result
        val now=elapsedMs()
        val last=lastReceipts[key]
        if (last==null || now<last || now-last>=1000) {
            lastReceipts[key]=now
            runCatching {receipt("carplay-car-data-bootstrap id=${frame.messageId} result=$result")}
        }
        return true
    }

    /** Called once by the production exact-runtime factory. No second CSM reader is created. */
    @Synchronized fun wrap(link: RuntimeCarDataLink): RuntimeCarDataLink {
        check(!transferred)
        transferred=true
        // A cancellation can beat the factory after it has constructed this exact owner.
        // Return the fenced owner to the receiver's normal retirement path instead of losing it.
        if (retired.get()) link.revoke()
        return object : RuntimeCarDataLink {
            override fun identification(base: Iap2Frame)=this@OalCarDataBootstrap.identification(base)
            override fun authenticate() {
                val commands=synchronized(this@OalCarDataBootstrap) {
                    if (retired.get()) return
                    val snapshot=pending.values.toList()
                    pending.clear()
                    snapshot
                }
                // Never hold the bootstrap queue monitor across source activation or a write.
                // The exact link's own nonblocking revoke fences the race after this snapshot.
                link.authenticate()
                commands.forEach { if (!retired.get()) link.handle(it) }
            }
            override fun handle(frame: Iap2Frame)=link.handle(frame)
            override fun tick(encryptedEventReady: Boolean)=link.tick(encryptedEventReady)
            override fun pollTimeout(maximumMs: Long)=link.pollTimeout(maximumMs)
            override fun revoke() { retired.set(true); link.revoke() }
            override fun close() { revoke(); try {link.close()} finally {this@OalCarDataBootstrap.close()} }
        }
    }

    override fun close() { retired.set(true); synchronized(this) {pending.clear()} }
}

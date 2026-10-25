package com.openautolink.app.ui.diagnostics

import com.shilapi.xcertplay.media.NativeCaptureOwnerRegistry
import java.util.concurrent.atomic.AtomicBoolean

interface NumericCaptureRun: NativeCaptureOwnerRegistry.NativeRun { val numbers: LongArray? }

/** Instantiated once by process owner. No UI executor, destructor or lifecycle-owned cleanup. */
class ParkedCaptureComparison(
    private val registry: NativeCaptureOwnerRegistry,
    private val factory: (Int, () -> Boolean) -> NumericCaptureRun,
    private val save: (LongArray) -> Boolean,
) {
    private class Attempt(val policy: () -> Boolean) {
        val canceled=AtomicBoolean()
        var backend=0
        @Volatile var owner:NativeCaptureOwnerRegistry.Owner?=null
        var run:NumericCaptureRun?=null
        fun allowed() = !canceled.get() && runCatching(policy).getOrDefault(false)
    }
    @Volatile private var active:Attempt?=null
    @Volatile var status="Ready · Java → AAudio · VR48/two · 2 seconds each"
        private set
    @Volatile var results:List<List<Long>> = emptyList()
        private set
    @Volatile var busy=false
        private set
    fun start(policy:()->Boolean):Boolean {
        if(busy)return false
        synchronized(this) {
        if(active!=null || !runCatching(policy).getOrDefault(false))return false
        val attempt=Attempt(policy)
        active=attempt;results=emptyList();busy=true
        if(!admit(attempt)){active=null;busy=false;status="Admission denied · projection or capture still owns microphone";return false}
        return true
        }
    }
    private fun admit(attempt:Attempt):Boolean {
        attempt.owner=registry.start {
            factory(attempt.backend,attempt::allowed).also { attempt.run=it }
        }
        if(attempt.owner==null)return false
        status=if(attempt.backend==0) "Java VR48/two · running" else "AAudio VR48/two · running"
        return true
    }
    fun cancel() {
        val attempt=active ?: return
        attempt.canceled.set(true)
        if(attempt.owner?.state==NativeCaptureOwnerRegistry.State.RESTART_REQUIRED)return
        attempt.owner?.let(registry::requestCancel)
        status="Stopping · waiting for actual close + join"
    }
    /** Process scheduler continues even when the panel, Activity or NavHost is gone. */
    @Synchronized fun poll() {
        val attempt=active ?: return
        if(!attempt.allowed()) cancel()
        val owner=attempt.owner ?: return
        when(owner.state) {
            NativeCaptureOwnerRegistry.State.RESTART_REQUIRED -> {
                attempt.run?.numbers?.let { if(results.size<=attempt.backend) { results=results+listOf(it.toList());save(it) } }
                status="Restart required · native/recorder retirement failed; admission retained"
                busy=true // tombstone remains owned for process lifetime
            }
            NativeCaptureOwnerRegistry.State.RETIRED -> {
                attempt.run?.numbers?.let {
                    results=results+listOf(it.toList())
                    if(!save(it))status="Numeric summary storage failed"
                }
                val successful=owner.receipt?.let { it.termination in setOf(1,3,4) && it.error==0 && it.stopError==0 } == true
                if(attempt.backend==0 && attempt.allowed() && successful) {
                    attempt.backend=1;attempt.run=null;attempt.owner=null
                    if(admit(attempt))return
                    status="Native arm not admitted"
                } else if(!status.contains("storage failed")) status=if(attempt.canceled.get()) "Canceled · close + join acknowledged" else "Complete · numeric capture is not speech or backend proof"
                active=null;busy=false
            }
            else -> registry.poll(owner)
        }
    }
}

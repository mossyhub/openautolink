package com.openautolink.app.carplay

/** Process-only diagnostic choice. Phone autoplay is indistinguishable from user resume. */
object PhonePlaybackResumeRecovery {
    data class Selection(val enabled:Boolean=false,val resumed:Boolean=false,val epoch:Long=0)
    private val state=java.util.concurrent.atomic.AtomicReference(Selection())
    fun isEnabled():Boolean=state.get().enabled
    fun snapshot():Selection=state.get()
    fun setEnabled(enabled:Boolean) {
        state.updateAndGet { if(it.enabled==enabled) it else it.copy(enabled=enabled,epoch=it.epoch+1) }
    }
    /** Called by process Activity visibility owner; loss revokes before returning, not on a queue. */
    fun setResumed(resumed:Boolean) {
        state.updateAndGet { if(it.resumed==resumed) it else it.copy(resumed=resumed,epoch=it.epoch+1) }
    }
    fun isCurrent(selection:Selection):Boolean = selection.enabled && selection.resumed && state.get()===selection
}

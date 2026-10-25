package com.openautolink.app.ui.diagnostics

import androidx.annotation.Keep
import com.shilapi.xcertplay.media.NativeCaptureOwnerRegistry
import java.util.concurrent.atomic.AtomicBoolean

/** Private native pointer is live only on process registry executor. UI only revokes policy atomic. */
@Keep
internal class NativeAAudioRun(private val policy: ()->Boolean): NumericCaptureRun {
    private var handle=0L
    private val canceled=AtomicBoolean()
    override var numbers:LongArray?=null
        private set
    @Keep fun eligible():Boolean = !canceled.get() && runCatching(policy).getOrDefault(false)
    override fun begin() {
        if(!eligible()) { numbers=cancelledNumbers(1);return }
        System.loadLibrary("oal-parked-capture")
        if(!eligible()) { numbers=cancelledNumbers(1);return }
        handle=nativeCreate();check(handle!=0L)
        if(!eligible())nativeCancel(handle)
        nativeBegin(handle)
    }
    override fun cancel(){canceled.set(true);if(handle!=0L)nativeCancel(handle)}
    override fun pollClosedJoin():NativeCaptureOwnerRegistry.Receipt? {
        val value=numbers ?: nativePoll(handle)?.also { numbers=ParkedCaptureNumbers.sanitize(it) } ?: return null
        check(value[6]==1L)
        return NativeCaptureOwnerRegistry.Receipt(value[1].toInt(),value[2].toInt(),value[3].toInt(),value[4].toInt(),value[5]!=0L)
    }
    override fun disposeJoined(){if(handle!=0L){nativeDispose(handle);handle=0}}
    private external fun nativeCreate():Long
    private external fun nativeBegin(handle:Long)
    private external fun nativeCancel(handle:Long)
    private external fun nativePoll(handle:Long):LongArray?
    private external fun nativeDispose(handle:Long)
}
internal fun cancelledNumbers(backend:Int)=LongArray(32).also {
    it[0]=backend.toLong();it[1]=2;it[6]=1;it[7]=1;it[12]=-1;it[13]=-1;it[15]=-1;it[17]=-1;it[18]=-1;it[31]=2000
}

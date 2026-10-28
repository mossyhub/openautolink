package com.openautolink.app.media

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Constant-space, process-lifetime counters plus latest own readiness tuple.
 * No caller-supplied strings, identifiers, artwork bytes or track text are retained. */
internal object MediaReadinessDiagnostics {
    enum class Event {
        ROOT, CHILDREN_ROOT, CHILDREN_PHONE_READY, CHILDREN_PHONE_EMPTY, CHILDREN_UNKNOWN,
        DECODE_OK, DECODE_FAILED, ARTWORK_CLEARED, BITMAP_SUBMITTED,
        TRANSFER_ACCEPTED, TRANSFER_REFERENCE, TRANSFER_UNAUTHENTICATED, TRANSFER_RETIRED, TRANSFER_SIZE, TRANSFER_LIMIT,
        CALLBACK_PLAY, CALLBACK_PLAY_FROM_CURRENT, CALLBACK_PLAY_FROM_OTHER, CALLBACK_PLAY_FROM_NULL,
        CALLBACK_PLAY_NOT_READY, CALLBACK_PLAY_STALE_OWNER, CALLBACK_PLAY_ADMITTED, CALLBACK_PREPARE
    }
    private val counts=IntArray(Event.entries.size)
    private var latest="protocol=NONE phase=UNAVAILABLE publication=NORMAL controls=false runtime=false lease=false applied=false"
    private var transitions=0
    @Synchronized fun event(value:Event) { counts[value.ordinal]=(counts[value.ordinal]+1).coerceAtMost(999999) }
    @Synchronized fun readiness(state:PhoneMediaBrowseState,controls:Boolean,runtime:Boolean,lease:Boolean,applied:Boolean) {
        val next="protocol=${state.protocol} phase=${state.phase} publication=${state.publication} controls=$controls runtime=$runtime lease=$lease applied=$applied"
        if(next!=latest) {latest=next;transitions=(transitions+1).coerceAtMost(999999)}
    }
    @Synchronized private fun snapshot()=buildString {
        appendLine("media_readiness_schema=1")
        appendLine("scope=process_latest_not_oem_selection")
        appendLine(latest)
        appendLine("transitions=$transitions")
        Event.entries.forEach { appendLine("${it.name.lowercase(java.util.Locale.ROOT)}=${counts[it.ordinal]}") }
    }
    /** Called only by the manual uploader worker, independent of file logging/UI. */
    fun retain(context:Context):Boolean = try {
        val dir=File(requireNotNull(context.getExternalFilesDir(null)),"openautolink/logs")
        check(dir.isDirectory || dir.mkdirs())
        val atomic=AtomicFile(File(dir,"media_readiness.log"))
        val stream=atomic.startWrite()
        try {stream.write(snapshot().toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}
        catch(failure:Exception) {atomic.failWrite(stream);throw failure}
        true
    } catch(_:Exception) { false }
}

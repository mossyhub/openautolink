package com.openautolink.app.diagnostics.live

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference
import com.openautolink.app.session.SessionManager

/** Optional non-waiting tee. No observer job, disk, network or redaction on a log producer. */
internal object LiveLogSink {
    class Capture internal constructor(internal val buffer: LiveBuffer) {
        internal val receipts = java.util.concurrent.atomic.AtomicReferenceArray<Receipt?>(9)
    }
    private val kinds = arrayOf("SAVE","INTENT","ACTIVE","RUNTIME","GPU","VIDEO","WINDOW","ACTIVITY","HANDOFF")
    internal data class Receipt(val kind: String,val wall: Long,val elapsed: Long,val session: Long,val message: String)
    // Raw bounded references only on producer threads; redaction/JSON happen on sender threads.
    private val current = AtomicReference<Capture?>()
    private val threadtime = Regex("^\\d{2}-\\d{2} [0-9:.]+\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+([^:]{1,160}):.*$")
    fun attach(buffer: LiveBuffer): Capture = Capture(buffer).also { current.set(it) }
    fun detach(exact: Capture) { current.compareAndSet(exact, null) }
    fun diagnostic(wall: Long, level: String, tag: String, message: String) {
        val capture = current.get() ?: return
        val elapsed=SystemClock.elapsedRealtime()
        val session=SessionManager.instanceOrNull()?.settingsSessionEpoch?.value ?: 0L
        capture.buffer.offer(wall,elapsed,level,tag,message,"diagnostic",session)
        val index=when {
            message.startsWith("SETTINGS_SAVE_V1 ")->0
            message.startsWith("OPTIONAL_RUNTIME_V1 ") && message.contains("stage=settings_snapshot")->1
            message.startsWith("OPTIONAL_RUNTIME_V1 ") && (message.contains("stage=layout_") || message.contains("stage=safe_insets") || message.contains("stage=offer"))->2
            message.startsWith("OPTIONAL_RUNTIME_V1 ")->3
            message.startsWith("OPTIONAL_VIDEO_V1 ") && message.contains("stage=gpu_retired")->4
            message.startsWith("OPTIONAL_VIDEO_V1 ")->5
            message.startsWith("OPTIONAL_VISIBILITY_V1 ")->6
            message.startsWith("OPTIONAL_ACTIVITY_V1 ")->7
            message.startsWith("OPTIONAL_HANDOFF_V1 ")->8
            else -> -1
        }
        if(index>=0) capture.receipts.set(index,Receipt(kinds[index],wall,elapsed,session,message.take(2048)))
    }
    fun snapshotReceipts(): org.json.JSONArray {
        val result=org.json.JSONArray();val capture=current.get() ?: return result
        repeat(kinds.size){index->capture.receipts.get(index)?.let { r ->
            val message=LiveRedaction.clean(r.message)
            if(message.startsWith("OPTIONAL_") || message.startsWith("SETTINGS_SAVE_V1 "))
                result.put(org.json.JSONObject().put("kind",r.kind).put("wallMs",r.wall).put("elapsedMs",r.elapsed)
                    .put("session",r.session).put("message",message.take(2048)))
        }}
        return result
    }
    fun logcat(line: String) {
        val capture = current.get() ?: return
        val parsed = threadtime.matchEntire(line)
        val level = when (parsed?.groupValues?.get(1)) {
            "V" -> "VERBOSE"; "D" -> "DEBUG"; "W" -> "WARN"; "E" -> "ERROR"; "F" -> "ASSERT"; else -> "INFO"
        }
        capture.buffer.offer(System.currentTimeMillis(), SystemClock.elapsedRealtime(), level,
            parsed?.groupValues?.get(2)?.trim() ?: "own-pid-logcat", line, "logcat",
            SessionManager.instanceOrNull()?.settingsSessionEpoch?.value ?: 0L)
    }
}

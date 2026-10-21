package com.openautolink.app.diagnostics

import android.content.Context
import android.util.AtomicFile
import com.openautolink.app.carplay.CarPlayAudioDiagnostics
import com.shilapi.xcertplay.media.CaptureTerminalSummary
import java.io.File

/** Passive-worker only. Atomic immutable completion files; independent from local diagnostic runs.
 * No latest alias, raw audio or device identity. A true result acknowledges durable completion.
 */
class CaptureTerminalLogStore(context:Context, private val remove:(File)->Boolean={it.delete()}) {
    private val app=context.applicationContext
    fun save(summary:CaptureTerminalSummary):Boolean = synchronized(lock) {
        try {
            val lines=summary.lines()
            require(lines.size==3 && lines.all { CarPlayAudioDiagnostics.sanitize(1,it)!=null })
            val bytes=lines.joinToString("\n",postfix="\n").toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES)
            val base=app.getExternalFilesDir(null) ?: return false
            val directory=File(base,"openautolink/logs")
            check(directory.isDirectory || directory.mkdirs())
            val existing=runs(directory)
            // Receiver/owner ordinals repeat after process restart. Deduplicate only identities
            // acknowledged by THIS process, never coincidentally equal historical census bytes.
            val key=Triple(directory.canonicalPath,summary.receiver,summary.owner)
            val previous=receipts[key]?.let { File(directory,it) }?.takeIf { it.isFile }
            val duplicate=previous != null
            if (duplicate) require(previous!!.length()==bytes.size.toLong() && previous.readBytes().contentEquals(bytes))
            var acknowledgedName=previous?.name
            if(!duplicate) {
                // Reserve the slot BEFORE writing. A persistent delete failure must not let a
                // stream of different owners grow disk usage beyond twelve immutable results.
                existing.sortedByDescending(::sequence).drop(MAX_RUNS-1).forEach { check(remove(it)) }
                val last=existing.maxOfOrNull { sequence(it) } ?: 0L
                require(last < Long.MAX_VALUE)
                val next=maxOf(System.currentTimeMillis().coerceAtLeast(1),last+1)
                acknowledgedName="carplay_capture_run_${next.toString().padStart(19,'0')}.log"
                val file=AtomicFile(File(directory,acknowledgedName))
                val stream=file.startWrite()
                try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
                catch(failure:Exception) { file.failWrite(stream); throw failure }
            }
            runs(directory).sortedByDescending(::sequence).drop(MAX_RUNS).forEach { check(remove(it)) }
            receipts[key]=checkNotNull(acknowledgedName)
            while(receipts.size > MAX_RUNS) receipts.remove(receipts.keys.first())
            true
        } catch(_:Exception) { false }
    }
    private fun runs(directory:File)=directory.listFiles().orEmpty().filter { it.isFile && runName.matches(it.name) }
    private fun sequence(file:File)=file.name.removePrefix("carplay_capture_run_").removeSuffix(".log").toLongOrNull() ?: 0L
    companion object {
        const val MAX_RUNS=12
        const val MAX_BYTES=4096
        private val lock=Any()
        private val receipts=linkedMapOf<Triple<String,Long,Long>,String>()
        internal val runName=Regex("carplay_capture_run_[0-9]{19}\\.log")
    }
}

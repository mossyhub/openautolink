// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Separate terminal allowance. Ingress has no IO/callback; only passive worker flush invokes sink.
 * Acknowledgment is durable sink success, not enqueue/UI delivery. Never controls a release fence.
 * At most 12 outstanding and 12 recent identities; retired older ordinals cannot be replayed.
 */
class CaptureTerminalLedger {
    private data class Entry(val summary:CaptureTerminalSummary,var attempts:Int=0)
    private val queue=ArrayDeque<Entry>()
    private val seen=linkedSetOf<Pair<Long,Long>>()
    private val ownerStates=linkedMapOf<Pair<Long,Long>,String>()
    @Synchronized fun releasePending(receiver:Long,owner:Long):Boolean {
        require(receiver > 0 && owner > 0)
        val key=receiver to owner
        if(key in seen || owner <= retiredThrough) return false
        if(key !in ownerStates && ownerStates.size >= MAX_PENDING) { lose(); return false }
        ownerStates[key]="release-pending"; return true
    }
    @Synchronized fun releaseFailed(receiver:Long,owner:Long) {
        val key=receiver to owner
        if(key in ownerStates) ownerStates[key]="release-failed"
    }
    @Synchronized fun ownerStates():List<String> = ownerStates.map { (key,state) ->
        "media stage=microphone-owner receiver=${key.first} owner=${key.second} state=$state"
    }
    @Synchronized fun status(receiver:Long,final:Boolean=false):String =
        "media stage=microphone-retention receiver=$receiver final=$final pending=${queue.size} owners=${ownerStates.size} lost=$losses"

    private var retiredThrough=0L
    private var losses=0L
    private var revision=0L
    private val scheduled=AtomicBoolean()
    @Volatile private var executor:Executor?=null
    @Volatile private var sink:((CaptureTerminalSummary)->Boolean)?=null
    @Synchronized fun complete(summary:CaptureTerminalSummary):Boolean {
        val key=summary.receiver to summary.owner
        if(summary.owner <= retiredThrough || key in seen) return false
        ownerStates.remove(key)
        if(queue.size >= MAX_PENDING) { lose(); return false }
        seen.add(key)
        if(seen.size > MAX_PENDING) {
            val oldest=seen.minBy { it.second }
            seen.remove(oldest); retiredThrough=maxOf(retiredThrough,oldest.second)
        }
        queue.addLast(Entry(summary)); revision++; schedule(); return true
    }
    fun bind(executor:Executor,sink:(CaptureTerminalSummary)->Boolean) {
        this.sink=sink;this.executor=executor;schedule()
    }
    private fun schedule() {
        val worker=executor ?: return
        if(!scheduled.compareAndSet(false,true)) return
        try { worker.execute {
            val before=synchronized(this) { revision }
            try { flush() } finally {
                scheduled.set(false)
                // Only new ingress re-arms automatically. A failed disk sink cannot spin retries.
                if(synchronized(this) { revision != before && queue.isNotEmpty() }) schedule()
            }
        } }
        catch(_:Exception) { scheduled.set(false) }
    }
    private val drainLock=Any()
    /** Worker-only, serialized separately from ingress. No callback/IO under ingress monitor. */
    fun flush():Boolean = synchronized(drainLock) {
        val destination=sink ?: return@synchronized synchronized(this) { queue.isEmpty() && losses==0L }
        val count=synchronized(this) { queue.size }
        repeat(count) {
            val entry=synchronized(this) { queue.firstOrNull() } ?: return@repeat
            val accepted=try { destination(entry.summary) } catch(_:Exception) { false }
            synchronized(this) {
                queue.removeFirst()
                if(!accepted) {
                    entry.attempts++
                    if(entry.attempts >= MAX_ATTEMPTS) lose() else queue.addLast(entry)
                }
            }
        }
        synchronized(this) { queue.isEmpty() && losses==0L }
    }
    @Synchronized fun pending()=queue.size
    @Synchronized fun lost()=losses
    private fun lose() { if(losses < Long.MAX_VALUE) losses++ }
    companion object { const val MAX_PENDING=12; const val MAX_ATTEMPTS=3 }
}

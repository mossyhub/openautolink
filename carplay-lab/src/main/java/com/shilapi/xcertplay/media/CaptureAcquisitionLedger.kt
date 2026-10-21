// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Observation only. No platform getters, recorder allocation, IO or observer invocation at ingress.
 * One receiver retains at most 192 details plus one explicit incomplete marker for its lifetime.
 * Draining runs on the receiver's existing bounded status executor, outside capture/ownership locks.
 */
class CaptureAcquisitionLedger(private val manual: Boolean) {
    enum class Kind { RECEIVER, PROBE, LIVE }
    enum class Action { SNAPSHOT, INPUTS, RECORDER_CACHE, ENCODER_CACHE, DECODER_CACHE, CREATE,
        BUFFER, VALIDATE, START, END, STOP, RELEASE_BEGIN, RELEASED, RELEASE_FAILED, RETIRED, LIMIT }
    enum class Result { ATTEMPT, OK, FAILED, HIT, MISS, ABSENT, BLOCKED, CANCELLED, DROPPED }
    private val receiver = receivers.incrementAndGet()
    private val startedNs = System.nanoTime()
    private val queue = ArrayBlockingQueue<String>(MAX_DETAILS + 1)
    private val scheduled = AtomicBoolean()
    private var sequence = 0
    @Volatile private var executor: Executor? = null
    @Volatile private var sink: ((String) -> Unit)? = null
    val root = Owner(0, Kind.RECEIVER, null, 0, 0)
    init { root.event(Action.SNAPSHOT, Result.OK) }

    inner class Owner internal constructor(val ordinal: Long, val kind: Kind,
        private val source: MicrophoneSource?, private val rate: Int, private val channels: Int) {
        fun event(action: Action, result: Result = Result.ATTEMPT, bytes: Int = 0) {
            synchronized(queue) {
                if (sequence > MAX_DETAILS) return
                val limited = sequence == MAX_DETAILS
                sequence++
                queue.offer("media stage=microphone-ledger receiver=$receiver event=$sequence owner=$ordinal " +
                    "history=${if (manual) "manual" else "normal"} kind=${kind.name.lowercase()} " +
                    "action=${if (limited) "limit" else action.name.lowercase().replace('_','-')} " +
                    "result=${if (limited) "dropped" else result.name.lowercase()} source=${source ?: "NONE"} " +
                    "rate=$rate channels=$channels bytes=${bytes.coerceAtLeast(0)} elapsedUs=${(System.nanoTime()-startedNs).coerceAtLeast(0)/1000}")
            }
            schedule()
        }
        internal fun receipt(message: String) = ownedReceipt(ordinal, message)
        fun signal(blocks: Long, nonzeroBlocks: Long, partialBytes: Int, reason: Termination, released: Boolean) {
            synchronized(queue) {
                if (sequence >= MAX_DETAILS) { event(Action.LIMIT, Result.DROPPED); return }
                sequence++
                queue.offer("media stage=microphone-signal receiver=$receiver event=$sequence owner=$ordinal " +
                    "blocks=$blocks nonzeroBlocks=$nonzeroBlocks partialBytes=$partialBytes reason=${reason.name.lowercase()} " +
                    "phase=${if (released) "released" else "ended"} elapsedUs=${(System.nanoTime()-startedNs).coerceAtLeast(0)/1000}")
            }
            schedule()
        }
        fun pcm(boundary: Boundary, phase: Phase, rate: Int, snapshot: Pcm16Window.Snapshot) {
            val channels = snapshot.channels
            fun channel(index: Int): String {
                val ch=channels.getOrNull(index) ?: Pcm16Window.Channel(0,0,0,0,0)
                return "ch${index}Samples=${ch.samples} ch${index}Nonzero=${ch.nonzero} ch${index}Peak=${ch.peak} ch${index}Rms=${ch.rms} ch${index}Clip=${ch.clipped}"
            }
            synchronized(queue) {
                if (sequence >= MAX_DETAILS) { event(Action.LIMIT, Result.DROPPED); return }
                sequence++
                queue.offer("media stage=microphone-pcm receiver=$receiver event=$sequence owner=$ordinal " +
                    "boundary=${boundary.name.lowercase()} phase=${phase.name.lowercase()} rate=$rate channels=${channels.size} " +
                    "observed=${snapshot.observedFrames} scanned=${snapshot.scannedFrames} tail=${snapshot.tailBytes} limit=2048 " +
                    "coverage=${if (snapshot.observedFrames > snapshot.scannedFrames) "prefix" else "full"} ${channel(0)} ${channel(1)}")
            }
            schedule()
        }
    }
    private fun ownedReceipt(owner: Long, message: String) {
        if (message.length > 512 || POLICY_STAGES.none { message.startsWith(it) }) return
        synchronized(queue) {
            if (sequence >= MAX_DETAILS) { root.event(Action.LIMIT, Result.DROPPED); return }
            sequence++
            queue.offer("media stage=microphone-owned receiver=$receiver event=$sequence owner=$owner " +
                "elapsedUs=${(System.nanoTime()-startedNs).coerceAtLeast(0)/1000} $message")
        }
        schedule()
    }
    enum class Boundary { RAW, CONVERTED }
    enum class Phase { FIRST, ENDED, RELEASED }
    enum class Termination { CANCELLED, PERMISSION, FAILED, INELIGIBLE }
    fun owner(kind: Kind, source: MicrophoneSource, rate: Int, channels: Int): Owner =
        Owner(recorders.incrementAndGet(), kind, source, rate, channels)
    fun bind(executor: Executor, sink: (String) -> Unit) {
        this.sink = sink; this.executor = executor; schedule()
    }
    private fun schedule() {
        val worker = executor ?: return
        if (!scheduled.compareAndSet(false,true)) return
        try { worker.execute {
            try { drain() } finally { scheduled.set(false); if (queue.isNotEmpty()) schedule() }
        } } catch (_: Exception) { scheduled.set(false) } // retained; next ingress/final flush retries
    }
    fun drain() { // only on the passive worker (also final retirement worker after capture retirement)
        while (true) { val line = queue.poll() ?: return; runCatching { sink?.invoke(line) } }
    }
    companion object {
        const val MAX_DETAILS = 192
        private val receivers = AtomicLong()
        private val recorders = AtomicLong()
        private val POLICY_STAGES = listOf("media stage=microphone-policy ", "media stage=microphone-config ", "media stage=microphone-effects ")
        internal val current = ThreadLocal<CaptureAcquisitionLedger?>()
        internal fun <T> observe(ledger: CaptureAcquisitionLedger, operation: () -> T): T {
            val previous=current.get();current.set(ledger)
            return try { operation() } finally { current.set(previous) }
        }
    }
}

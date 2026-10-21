// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioFormat
import java.util.concurrent.atomic.AtomicBoolean

/** Per-output-owner numeric census, confined to output worker except revoke().
 * The supplied callback only offers to the receiver's existing bounded asynchronous status queue.
 * No PCM, device objects, arbitrary errors or Binder getters enter that queue.
 */
internal class OutputPcmDiagnostics(
    private val format: AudioFormat,
    private val stream: Long,
    private val enqueue: (String, () -> Boolean) -> Boolean,
    private val now: () -> Long = System::nanoTime,
) {
    private val revoked = AtomicBoolean()
    private var rate = format.sampleRate
    private var channels = format.channels
    private var decoded = Pcm16Window(channels)
    private var accepted = Pcm16Window(channels)
    private var started = false
    private var ended = false
    private var at = 0L
    private var window = 0L
    private var queueDrops = 0L
    fun configure(rate: Int, channels: Int) {
        if (revoked.get() || ended) return
        if (started) { emit("terminal"); window++ }
        this.rate=rate; this.channels=channels
        decoded=Pcm16Window(channels); accepted=Pcm16Window(channels)
        at=now(); started=true
        emit("start")
    }
    fun tick() {
        if (!started || ended || revoked.get()) return
        if (now()-at < WINDOW_NS) return
        emit("periodic")
        decoded.reset(); accepted.reset(); window++; at=now()
    }
    fun decoded(bytes: ByteArray) {
        if (!started || ended || revoked.get()) return
        tick(); decoded.observe(bytes)
    }
    /** Consecutive positive writes of this SAME buffer. Count a split frame once, only after
     * all its bytes have actually been accepted. Nothing is carried into the next buffer.
     */
    fun accepted(bytes: ByteArray, previousOffset: Int, count: Int) {
        if (!started || ended || revoked.get() || count <= 0) return
        require(previousOffset >= 0 && count <= bytes.size-previousOffset)
        val frameBytes=channels*2
        val start=previousOffset/frameBytes*frameBytes
        val end=(previousOffset+count)/frameBytes*frameBytes
        if (end>start) accepted.observe(bytes,start,end-start)
    }
    /** Unaccepted bytes are not PCM silence. Only positive accepted incomplete tail is reported. */
    fun acceptedTail(bytes: ByteArray, acceptedBytes: Int) {
        if (!started || ended || revoked.get()) return
        val tail=acceptedBytes%(channels*2)
        if (tail>0) accepted.observe(bytes,acceptedBytes-tail,tail)
    }
    fun revoke() { revoked.set(true) }
    fun finish() {
        if (!started || ended) return
        ended=true; revoked.set(true)
        emit("terminal")
    }
    private fun emit(event: String) {
        val kind=format.audioType.lowercase(java.util.Locale.ROOT)
        if (kind !in KINDS || format.payloadType !in 100..102) return
        val purpose=when {
            format.payloadType==101 -> "nav"
            kind=="telephony" -> "call"
            kind=="speechrecognition" -> "siri"
            kind=="alert" -> "ring"
            else -> "media"
        }
        for ((phase,stats) in listOf("decoded" to decoded.snapshot(),"accepted" to accepted.snapshot())) {
            val channel=stats.channels
            fun fields(ch: Int): String {
                val c=channel.getOrNull(ch) ?: Pcm16Window.Channel(0,0,0,0,0)
                return "ch${ch}Samples=${c.samples} ch${ch}Nonzero=${c.nonzero} ch${ch}Peak=${c.peak} ch${ch}Rms=${c.rms} ch${ch}Clip=${c.clipped}"
            }
            val line="media stage=audio-pcm stream=$stream type=${format.payloadType} audioType=$kind codec=${format.codec} purpose=$purpose " +
                "phase=$phase event=$event window=$window rate=$rate channels=$channels observed=${stats.observedFrames} scanned=${stats.scannedFrames} tail=${stats.tailBytes} " +
                "limit=2048 coverage=${if(stats.observedFrames>stats.scannedFrames) "prefix" else "full"} ${fields(0)} ${fields(1)} queueDrops=$queueDrops"
            // Terminal is immutable historical evidence for this exact owner. Nonterminal queued
            // snapshots are invalid immediately on retirement, even if a platform write is stuck.
            val current: () -> Boolean = if (event=="terminal") ({true}) else ({!revoked.get()})
            if (!runCatching { enqueue(line,current) }.getOrDefault(false) && queueDrops<Long.MAX_VALUE) queueDrops++
        }
    }
    companion object {
        const val WINDOW_NS=5_000_000_000L
        private val KINDS=setOf("default","media","telephony","speechrecognition","alert","compatibility")
    }
}

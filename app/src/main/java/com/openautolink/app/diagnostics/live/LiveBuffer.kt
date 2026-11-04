package com.openautolink.app.diagnostics.live

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class LiveEvent(val seq: Long, val wallMs: Long, val elapsedMs: Long,
    val level: String, val tag: String, val message: String, val source: String, val session: Long) {
    val bytes: Int = message.toByteArray(Charsets.UTF_8).size + tag.length * 3 + 192
}

/** No disk/network/redaction on producers; tryLock never waits for the sender. */
internal class LiveBuffer(private val capacity: Int = 512, private val byteLimit: Int = 2 * 1024 * 1024,
    private val sequence: AtomicLong = AtomicLong()) {
    private val lock = ReentrantLock()
    private val queue = ArrayDeque<LiveEvent>()
    private var bytes = 0
    private val missed = AtomicLong()
    private val losses = AtomicLong()
    val dropped: Long get() = losses.get()
    fun offer(wall: Long, elapsed: Long, level: String, tag: String, message: String, source: String, session: Long) {
        if (!lock.tryLock()) { missed.incrementAndGet(); losses.incrementAndGet(); return }
        try {
            val seq = sequence.addAndGet(missed.getAndSet(0) + 1)
            // Oversize records are counted whole-record drops, never silently truncated.
            if (message.length > 16384 || tag.length > 160) { losses.incrementAndGet(); return }
            val event = LiveEvent(seq, wall, elapsed, level, tag, message, source, session)
            val size = event.bytes
            if (size > byteLimit || message.toByteArray(Charsets.UTF_8).size > 16384) { losses.incrementAndGet(); return }
            while (queue.size >= capacity || bytes + size > byteLimit) { bytes -= queue.removeFirst().bytes; losses.incrementAndGet() }
            queue.addLast(event); bytes += size
        } finally { lock.unlock() }
    }
    fun batch(max: Int): List<LiveEvent> = lock.withLock {
        val result = ArrayList<LiveEvent>(); var size = 0
        for (e in queue) { if (result.size >= max || size + e.bytes > 48 * 1024) break; result.add(e); size += e.bytes }
        result
    }
    fun acknowledge(seq: Long) = lock.withLock {
        while (queue.isNotEmpty() && queue.first.seq <= seq) bytes -= queue.removeFirst().bytes
    }
    fun discard(seq: Long) = lock.withLock {
        val iterator=queue.iterator()
        while(iterator.hasNext()) { val e=iterator.next(); if(e.seq==seq){iterator.remove();bytes-=e.bytes;losses.incrementAndGet();break} }
    }
    fun clear() = lock.withLock { queue.clear(); bytes = 0 }
}

/** Deliberately conservative: preserve lines/newlines except private values or sensitive dump lines. */
internal object LiveRedaction {
    private val privateLine = Regex("(?i)(authorization|password|passphrase|\\bpsk\\b|\\bssid\\b|credential|pairing.?code|private.?key|BEGIN .*PRIVATE|\\bvin\\b|latitude|longitude|\\blat\\s*[:=]|\\blon\\s*[:=]|\\bgps\\b|\\bgnss\\b|location\\s*[:=(]|destination|\\bdest\\s*=|\\broad\\s*=|\\bcue\\s*=|phoneName|deviceName|phoneBtAddress)")
    private val privateDump = Regex("(?i)(UNKNOWN #|-> (?:varint|fixed64|string|bytes)|latE[0-9]|lonE[0-9]|lngE[0-9]|[0-9a-f]{48,})")
    private val entropy = Regex("[A-Za-z0-9_+/=-]{32,}")
    private val mac = Regex("(?i)\\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\\b")
    private val ip = Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b")
    private val coordinates = Regex("(?<![A-Za-z0-9])[-+]?[0-9]{1,3}\\.[0-9]{4,}(?![A-Za-z0-9])")
    private val vin = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b")
    private val technical = Regex("(?:OPTIONAL_(?:VIDEO|RUNTIME|VISIBILITY|ACTIVITY|HANDOFF|AA)_V1|SETTINGS_SAVE_V1)")
    private val namedSecret = Regex("(?i)\\b(token|secret|pin|key)\\s*[:=]\\s*[^\\s,;]+")
    fun clean(value: String): String = value.lineSequence().joinToString("\n") { line ->
        if (privateLine.containsMatchIn(line) || privateDump.containsMatchIn(line)) "[REDACTED sensitive line]"
        else {
            val safe=vin.replace(ip.replace(mac.replace(entropy.replace(namedSecret.replace(line, "$1=[REDACTED]"), "[REDACTED credential]"), "[REDACTED address]"), "[REDACTED address]"),"[REDACTED VIN]")
            if(technical.containsMatchIn(line))safe else coordinates.replace(safe,"[REDACTED coordinate]")
        }
    }
}

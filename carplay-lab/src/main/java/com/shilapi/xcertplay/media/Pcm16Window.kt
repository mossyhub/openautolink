// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Numeric-only prefix census. No retained PCM; bounded work, no allocation in the frame loop.
 * Each call begins at a frame boundary; trailing bytes are counted, never carried to another call.
 * Thread confined to the capture/output owner. RMS is integer PCM units, not dB or audibility.
 */
class Pcm16Window(val channels: Int, val maxFrames: Int = 2048) {
    init { require(channels in 1..2 && maxFrames in 1..2048) }
    data class Channel(val samples: Int, val nonzero: Int, val peak: Int, val rms: Int, val clipped: Int)
    data class Snapshot(val observedFrames: Long, val scannedFrames: Int, val tailBytes: Long, val channels: List<Channel>)
    private var observed = 0L
    private var scanned = 0
    private var tail = 0L
    private val nonzero = IntArray(channels)
    private val peak = IntArray(channels)
    private val squares = LongArray(channels)
    private val clipped = IntArray(channels)
    fun observe(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size-offset, littleEndian: Boolean = true) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size-offset)
        val frameBytes = channels*2
        val frames = length/frameBytes
        observed = saturatingAdd(observed,frames.toLong())
        tail = saturatingAdd(tail,(length%frameBytes).toLong())
        val count = minOf(frames,maxFrames-scanned)
        var position = offset
        repeat(count) {
            for (ch in 0 until channels) {
                val a = bytes[position++].toInt() and 255
                val b = bytes[position++].toInt() and 255
                val unsigned = if (littleEndian) a or (b shl 8) else (a shl 8) or b
                val value = unsigned.toShort().toInt()
                val magnitude = abs(value) // Int, not Short: -32768 is representable after abs.
                if (value != 0) nonzero[ch]++
                if (magnitude > peak[ch]) peak[ch] = magnitude
                squares[ch] += value.toLong()*value
                if (value == -32768 || value == 32767) clipped[ch]++
            }
        }
        scanned += count
    }
    fun snapshot() = Snapshot(observed,scanned,tail,(0 until channels).map { ch ->
        Channel(scanned,nonzero[ch],peak[ch],if (scanned == 0) 0 else sqrt(squares[ch].toDouble()/scanned).roundToInt(),clipped[ch])
    })
    fun reset() {
        observed=0; scanned=0; tail=0
        nonzero.fill(0); peak.fill(0); squares.fill(0); clipped.fill(0)
    }
    private fun saturatingAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE-b) Long.MAX_VALUE else a+b
}

// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

/** Observations only. Null means not measured, not zero. No protocol or pairing identifiers. */
data class ReceiverTelemetrySnapshot(
    val generation: Long,
    val advertisedWidth: Int? = null,
    val advertisedHeight: Int? = null,
    val decoderName: String? = null,
    val codedWidth: Int? = null,
    val codedHeight: Int? = null,
    val visibleWidth: Int? = null,
    val visibleHeight: Int? = null,
    val renderedFrames: Long? = null,
    val renderedFps: Double? = null,
    val audioStreamObserved: Boolean = false,
    val audioAcceptedPackets: Long? = null,
    val audioWrittenBytes: Long? = null,
)

/** Constant-space numeric accumulator; callbacks never log or publish UI state. */
class ReceiverTelemetry(private val generation: Long) {
    private var retired = false
    private var state = ReceiverTelemetrySnapshot(generation)
    private var frames = 0L
    private var accepted = 0L
    private var written = 0L
    private var lastRenderNs: Long? = null
    private var windowStartNs: Long? = null
    private var windowFrames = 0L
    private var fps: Double? = null
    @Synchronized fun advertised(width: Int, height: Int) {
        if (!retired && width > 0 && height > 0)
            state = state.copy(advertisedWidth = width, advertisedHeight = height)
    }
    @Synchronized fun audioStreamStarted() {
        if (!retired) state = state.copy(audioStreamObserved = true)
    }
    @Synchronized fun audioPacketAccepted() {
        if (!retired && state.audioStreamObserved && accepted < Long.MAX_VALUE) accepted++
    }
    @Synchronized fun audioWritten(bytes: Int) {
        if (!retired && state.audioStreamObserved && bytes > 0)
            written += minOf(bytes.toLong(), Long.MAX_VALUE - written)
    }
    @Synchronized fun decoderConfigured(name: String) {
        if (!retired) state = state.copy(decoderName = name)
    }
    @Synchronized fun outputFormat(width: Int, height: Int, visibleWidth: Int, visibleHeight: Int) {
        if (!retired && width > 0 && height > 0 && visibleWidth in 1..width && visibleHeight in 1..height)
            state = state.copy(codedWidth = width, codedHeight = height,
                visibleWidth = visibleWidth, visibleHeight = visibleHeight)
    }
    @Synchronized fun frameRendered(nowNs: Long) {
        if (retired) return
        if (frames < Long.MAX_VALUE) frames++
        val last = lastRenderNs
        if (last != null && nowNs <= last) return
        if (last == null || nowNs - last > 2_000_000_000L) {
            windowStartNs = nowNs; windowFrames = 0; fps = null
        } else {
            windowFrames++
            val elapsed = nowNs - checkNotNull(windowStartNs)
            if (elapsed >= 1_000_000_000L) {
                fps = windowFrames * 1_000_000_000.0 / elapsed
                windowStartNs = nowNs; windowFrames = 0
            }
        }
        lastRenderNs = nowNs
    }
    @Synchronized fun snapshot(nowNs: Long): ReceiverTelemetrySnapshot {
        if (retired) return state
        val fresh = lastRenderNs?.let { nowNs >= it && nowNs - it <= 2_000_000_000L } == true
        return state.copy(renderedFrames = frames.takeIf { it > 0 }, renderedFps = fps.takeIf { fresh },
            audioAcceptedPackets = accepted.takeIf { state.audioStreamObserved },
            audioWrittenBytes = written.takeIf { it > 0 })
    }
    @Synchronized fun retire() { retired = true; state = ReceiverTelemetrySnapshot(generation) }
}

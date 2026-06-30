package com.openautolink.app.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [VideoStallPolicy] — the video-stall watchdog decision,
 * including the keyframe-warmup grace added for issue #30/#34.
 *
 * Regression anchor: build 0.1.355 drive (2026-06-30). The phone sent a seed IDR
 * ~1s after STREAMING, then no frame for ~9s while its encoder warmed up; the
 * watchdog fired at the 8s mark and force-reconnected straight into AASDK
 * Error-30, churning for ~25s before the stream settled.
 *
 * Timeline convention: STREAMING began at elapsedRealtime = 100_000 ("since").
 */
class VideoStallPolicyTest {

    private val WARMUP = 16_000L
    private val THRESHOLD = 8_000L
    private val SINCE = 100_000L  // when STREAMING began

    private fun decide(
        streaming: Boolean = true,
        goingIdle: Boolean = false,
        streamingSinceMs: Long = SINCE,
        lastVideoFrameMs: Long,
        nowMs: Long,
    ) = VideoStallPolicy.shouldForceReconnect(
        streaming, goingIdle, streamingSinceMs, lastVideoFrameMs, nowMs, WARMUP, THRESHOLD,
    )

    // ── The #30/#34 regression: warmup gap must NOT fire ────────────────

    @Test
    fun `seed-IDR warmup gap does NOT force reconnect`() {
        // STREAMING at 100_000; seed IDR at 101_000; now 110_000 -> 9s stale
        // (> 8s threshold) BUT only 10s into the 16s warmup -> hold off.
        assertFalse(decide(lastVideoFrameMs = 101_000L, nowMs = 110_000L))
    }

    @Test
    fun `still suppressed right up to the warmup boundary`() {
        // 15.9s in, frame ~9s stale — still inside warmup -> suppressed.
        assertFalse(decide(lastVideoFrameMs = 106_900L, nowMs = 115_900L))
    }

    // ── Genuine mid-drive wedge AFTER warmup: must fire ─────────────────

    @Test
    fun `real stall after warmup forces reconnect`() {
        // 60s into a healthy stream, frames stop for 9s -> wedge -> reconnect.
        assertTrue(decide(lastVideoFrameMs = 151_000L, nowMs = 160_000L))
    }

    @Test
    fun `healthy stream after warmup does NOT fire`() {
        // 60s in, last frame 2s ago — normal, no reconnect.
        assertFalse(decide(lastVideoFrameMs = 158_000L, nowMs = 160_000L))
    }

    @Test
    fun `exactly at threshold just past warmup fires`() {
        // 17s in (past 16s warmup), stale == threshold (8s) -> fire.
        assertTrue(decide(lastVideoFrameMs = 109_000L, nowMs = 117_000L))
    }

    // ── Standing guards still hold ──────────────────────────────────────

    @Test
    fun `not streaming never fires`() {
        assertFalse(decide(streaming = false, lastVideoFrameMs = 101_000L, nowMs = 160_000L))
    }

    @Test
    fun `intentionally idle never fires`() {
        assertFalse(decide(goingIdle = true, lastVideoFrameMs = 101_000L, nowMs = 160_000L))
    }

    @Test
    fun `zero streaming baseline never fires`() {
        // streamingSinceMs == 0 means "not streaming yet" — must not fire.
        assertFalse(decide(streamingSinceMs = 0L, lastVideoFrameMs = 0L, nowMs = 160_000L))
    }

    @Test
    fun `no frame yet after warmup does NOT fire`() {
        // Past warmup but zero frames this session -> wait, don't reconnect-loop.
        assertFalse(decide(lastVideoFrameMs = 0L, nowMs = 160_000L))
    }
}

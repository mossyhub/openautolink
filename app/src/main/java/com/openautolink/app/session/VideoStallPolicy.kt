package com.openautolink.app.session

/**
 * Pure decision logic for the video-stall watchdog, extracted so it can be unit
 * tested without an Android runtime / live SessionManager.
 *
 * The watchdog forces a reconnect when the AA video channel wedges mid-drive
 * (frames stop while the socket stays alive). But it must NOT fire during the
 * initial keyframe-warmup window after a session reaches STREAMING — the phone
 * sends a tiny seed IDR immediately, then a multi-second gap before the first
 * real keyframe, and tripping there forces a reconnect that flaps into AASDK
 * Error-30 (issue #30/#34 interaction, observed in build 0.1.355).
 */
object VideoStallPolicy {

    /**
     * @param streaming        true only while sessionState == STREAMING
     * @param goingIdle        true while intentionally idle (paused/screen-off)
     * @param streamingSinceMs elapsedRealtime when STREAMING began, 0 if not streaming
     * @param lastVideoFrameMs elapsedRealtime of the most recent inbound frame, 0 if none yet
     * @param nowMs            current elapsedRealtime
     * @param warmupMs         grace window after streaming start before arming
     * @param thresholdMs      max tolerated gap with no frame once armed
     * @return true iff the watchdog should force a reconnect right now
     */
    fun shouldForceReconnect(
        streaming: Boolean,
        goingIdle: Boolean,
        streamingSinceMs: Long,
        lastVideoFrameMs: Long,
        nowMs: Long,
        warmupMs: Long,
        thresholdMs: Long,
    ): Boolean {
        if (!streaming) return false
        if (goingIdle) return false
        // Initial keyframe-warmup grace.
        if (streamingSinceMs == 0L) return false
        if (nowMs - streamingSinceMs < warmupMs) return false
        // Need at least one frame this session before measuring staleness.
        if (lastVideoFrameMs == 0L) return false
        return (nowMs - lastVideoFrameMs) >= thresholdMs
    }
}

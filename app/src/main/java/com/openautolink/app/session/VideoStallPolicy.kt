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

    /**
     * Decide whether a SUSTAINED ultra-low video bitrate warrants a recovery
     * reconnect, even though frames are technically still trickling in (so the
     * no-frame [shouldForceReconnect] never trips).
     *
     * Motivating capture (build 0.1.356 drive, 2026-06-30): a flaky car-AP link
     * starved the video stream to ~12-13 kbps for 2+ minutes — a black/frozen map
     * — while audio (a separate, tiny channel) kept playing and a frame dribbled
     * in often enough to keep [lastVideoFrameMs] fresh. The user sees a dead map
     * with no automatic recovery.
     *
     * False-positive guards (a static map legitimately dips low but recovers):
     *  - only past the streaming warmup window,
     *  - the floor is set far below a static-but-healthy map (which still runs
     *    hundreds of kbps); only a genuinely starved link sits this low,
     *  - requires [requiredLowSeconds] CONSECUTIVE low-bitrate samples, so a
     *    momentary dip never trips it — the caller tracks the run length.
     *
     * @param consecutiveLowSeconds how many consecutive ~1s samples have been
     *        below [floorKbps] while streaming (caller-maintained counter)
     */
    fun shouldRecoverLowBitrate(
        streaming: Boolean,
        goingIdle: Boolean,
        streamingSinceMs: Long,
        nowMs: Long,
        warmupMs: Long,
        consecutiveLowSeconds: Int,
        requiredLowSeconds: Int,
    ): Boolean {
        if (!streaming) return false
        if (goingIdle) return false
        if (streamingSinceMs == 0L) return false
        if (nowMs - streamingSinceMs < warmupMs) return false
        return consecutiveLowSeconds >= requiredLowSeconds
    }

    /**
     * Whether a single bitrate sample counts as "ultra-low" (starved link), used
     * by the caller to advance/reset its consecutive-low counter. A sample only
     * counts once at least one keyframe has rendered ([hasRendered]) — before
     * that, 0 kbps is just "no video yet", not starvation.
     */
    fun isStarvedSample(bitrateKbps: Float, hasRendered: Boolean, floorKbps: Float): Boolean =
        hasRendered && bitrateKbps < floorKbps
}

package com.openautolink.app.carplay

/** Numeric receiver observations, never ProjectionUiState's AA decoder/audio data.
 * A first-render status receipt alone is NOT a frame total or FPS. Touch receipts are successful
 * sends, not proof the phone acted on them. Unmeasured counters remain explicitly unavailable.
 */
object CarPlayStatsPresentation {
    fun rows(state: CarPlayUiState): Map<String, String> {
        val measured = state.evidence.telemetry
        fun size(width: Int?, height: Int?) =
            if (width != null && height != null) "${width}×${height}" else "unavailable"
        return linkedMapOf(
            "Protocol" to "CarPlay",
            "Session" to if (state.busy) "active attempt" else "idle",
            "Auth" to if (state.evidence.runtimeAuthenticated) "accepted" else "unconfirmed",
            "Rendered" to if ((measured?.renderedFrames ?: 0) > 0 || state.evidence.videoRendered) "confirmed" else "unconfirmed",
            "Touches" to if (state.evidence.touchSent > 0) "${state.evidence.touchSent} sent" else "unavailable",
            "Decoder" to (measured?.decoderName ?: "unavailable"),
            "Resolution" to size(measured?.codedWidth, measured?.codedHeight),
            "Visible" to size(measured?.visibleWidth, measured?.visibleHeight),
            "Advertised" to size(measured?.advertisedWidth, measured?.advertisedHeight),
            "FPS" to (measured?.renderedFps?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "unavailable"),
            "Frames" to (measured?.renderedFrames?.toString() ?: "unavailable"),
            "Bitrate" to "unavailable",
            "Dropped" to "unavailable",
            "Audio" to when (measured?.audioStreamObserved) {
                true -> "stream observed · audibility unverified"
                false -> "no audio stream observed"
                null -> "unavailable"
            },
            "Accepted" to (measured?.audioAcceptedPackets?.let { "$it packets" } ?: "unavailable"),
            "Rate" to "unavailable",
            "Written" to (measured?.audioWrittenBytes?.let { "$it bytes" } ?: "unavailable"),
            "Underruns" to "unavailable",
        )
    }
}

package com.openautolink.app.carplay

/** Evidence is promoted only at the receiver's actual sink / successful send boundaries.
 * Runtime authentication and RECORD are deliberately not a STREAMING/render claim.
 */
data class CarPlayEvidence(
    val runtimeAuthenticated: Boolean = false,
    val videoRendered: Boolean = false,
    val touchSent: Int = 0,
    val telemetry: com.openautolink.carplaylab.media.ReceiverTelemetrySnapshot? = null,
) {
    fun accept(status: String): CarPlayEvidence = when {
        status == "media stage=runtime-authentication-accepted" -> copy(runtimeAuthenticated = true)
        status == "media stage=video-first-frame-confirmed count=1" -> copy(videoRendered = true)
        status == "media stage=touch-sent count=1" -> copy(touchSent = (touchSent + 1).coerceAtMost(Int.MAX_VALUE - 1))
        status == "media stage=session-ended" -> copy(runtimeAuthenticated = false, videoRendered = false, telemetry = null)
        else -> this
    }
}

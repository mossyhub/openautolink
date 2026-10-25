package com.openautolink.app.carplay

/** Process admission, not a saved protocol preference. AA defaults remain unchanged until a tap.
 * Reserve BEFORE waiting for SessionManager's lifecycle mutex. That blocks queued AA starts while
 * an already-starting session completes and is then retired. Failures retain the reservation.
 */
class CarPlayOwnership {
    class Token internal constructor(internal val capture: com.shilapi.xcertplay.media.MicrophoneCaptureFence.Lease)
    private var current: Token? = null
    private var canceled = false
    private var aaRetired = false
    private var carPlayRetired = false
    @get:Synchronized val blocksAndroidAuto: Boolean get() = current != null
    @Synchronized fun reserve(enabled: Boolean): Token? {
        if (!enabled || current != null) return null
        val capture = com.shilapi.xcertplay.media.MicrophoneCaptureFence.tryProjectionClaim() ?: return null
        return Token(capture).also { current = it; canceled = false; aaRetired = false; carPlayRetired = false }
    }
    @Synchronized fun isCurrent(token: Token) = current === token
    @Synchronized fun cancel(token: Token) { if (current === token) canceled = true }
    @Synchronized fun acknowledgeAaRetired(token: Token) { if (current === token) aaRetired = true }
    @Synchronized fun canBootstrap(token: Token) = current === token && aaRetired && !canceled
    @Synchronized fun acknowledgeCarPlayRetired(token: Token) { if (current === token) carPlayRetired = true }
    @Synchronized fun release(token: Token): Boolean {
        if (current !== token || !aaRetired || !carPlayRetired) return false
        current = null
        token.capture.release()
        return true
    }
    companion object { val process = CarPlayOwnership() }
}

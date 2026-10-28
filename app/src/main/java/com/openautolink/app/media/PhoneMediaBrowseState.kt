package com.openautolink.app.media

/** Own finite state only. It is not OEM selected-source or focus authority. */
data class PhoneMediaBrowseState(
    val protocol: Protocol = Protocol.NONE,
    val phase: Phase = Phase.UNAVAILABLE,
    val publication: Publication = Publication.NORMAL,
) {
    enum class Protocol { NONE, ANDROID_AUTO, CARPLAY }
    enum class Phase { UNAVAILABLE, CONNECTING, READY, RETIRING }
    enum class Publication { NORMAL, SUPPRESS_PLAYING, INACTIVE }
    val ready: Boolean get() = phase == Phase.READY
    val subtitle: String get() = when {
        ready && protocol == Protocol.CARPLAY -> "CarPlay"
        ready && protocol == Protocol.ANDROID_AUTO -> "Android Auto"
        phase == Phase.CONNECTING -> "Connecting"
        phase == Phase.RETIRING -> "Unavailable"
        else -> "Connect a phone"
    }
}

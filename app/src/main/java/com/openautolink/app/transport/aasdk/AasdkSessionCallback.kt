package com.openautolink.app.transport.aasdk

/**
 * Callback interface for AA session events from native aasdk.
 *
 * Implemented by [AasdkSession] and dispatched to the appropriate
 * component island (video, audio, navigation, etc.).
 *
 * All methods are called from the native io_service thread.
 * Implementations must be lightweight — post to coroutines for heavy work.
 */
interface AasdkSessionCallback {

    /** AA session fully established (handshake + SDR complete, channels opening). */
    fun onSessionStarted()

    /** AA session ended. [reason] describes why (user disconnect, error, etc.). */
    fun onSessionStopped(reason: String)

    /**
     * Video frame received from phone.
     * @param data Raw codec data (H.264/H.265/VP9 NAL units)
     * @param timestampUs Presentation timestamp in microseconds
     * @param width Video width (from setup)
     * @param height Video height (from setup)
     */
    fun onVideoFrame(data: ByteArray, timestampUs: Long, width: Int, height: Int, flags: Int)

    /**
     * Phone negotiated a video codec during channel setup.
     * @param codecType aasdk MediaCodecType: 3=H.264, 5=H.264_BP, 7=H.265
     */
    fun onVideoCodecConfigured(codecType: Int)

    /**
     * Audio frame received from phone.
     * @param data Raw PCM audio data
     * @param purpose Audio purpose: 0=media, 1=speech/nav, 2=system/alert
     * @param sampleRate Sample rate in Hz
     * @param channels Number of audio channels
     */
    fun onAudioFrame(data: ByteArray, purpose: Int, sampleRate: Int, channels: Int)

    /**
     * Phone requests mic open/close.
     * @param open true = start capturing mic, false = stop
     */
    fun onMicRequest(open: Boolean)

    /** Navigation status change (active/inactive/rerouting). */
    fun onNavigationStatus(status: Int)

    /** Navigation turn event — parsed fields from protobuf. */
    fun onNavigationTurn(maneuver: String, road: String, iconPng: ByteArray?)

    /** Navigation distance update. */
    fun onNavigationDistance(distanceMeters: Int, etaSeconds: Int,
                            displayDistance: String?, displayUnit: String?)

    /**
     * Full navigation state update from modern NavigationState proto (msg 32774).
     * Contains maneuver, road, lanes, cue, destination — all in one callback.
     * Lane data is serialized as pipe-separated lanes with comma-separated directions:
     * "shape:highlighted,shape:highlighted|shape:highlighted,..."
     * @param roundaboutExitNumber -1 if not applicable
     */
    fun onNavigationFullState(
        maneuver: String?, road: String?, iconPng: ByteArray?,
        distanceMeters: Int, etaSeconds: Int,
        displayDistance: String?, displayUnit: String?,
        lanes: String?, cue: String?, roundaboutExitNumber: Int,
        currentRoad: String?, destination: String?, etaFormatted: String?,
        timeToArrivalSeconds: Long, destDistanceMeters: Int,
        destDistDisplay: String?, destDistUnit: String?
    )

    /** Media metadata update (track info). */
    fun onMediaMetadata(title: String, artist: String, album: String, albumArt: ByteArray?)

    /**
     * Media playback state update.
     * @param state 0=stopped, 1=playing, 2=paused
     * @param positionMs Current playback position in milliseconds
     */
    fun onMediaPlayback(state: Int, positionMs: Long)

    /**
     * Phone status update.
     * @param signalStrength Signal strength (0-5)
     * @param callState 0=idle, 1=ringing, 2=active
     */
    fun onPhoneStatus(signalStrength: Int, callState: Int)

    /**
     * Phone battery update.
     * @param level Battery percentage (0-100)
     * @param charging Whether the phone is charging
     */
    fun onPhoneBattery(level: Int, charging: Boolean)

    /** Voice session state change (Google Assistant active/inactive). */
    fun onVoiceSession(active: Boolean)

    /**
     * Audio focus request from phone.
     * @param focusType 1=GAIN, 2=GAIN_TRANSIENT, 3=RELEASE
     */
    fun onAudioFocusRequest(focusType: Int)

    /** Error from the native session. */
    fun onError(message: String)

    /**
     * Diagnostic log line emitted by the native session, routed into the
     * triaged DiagnosticLog (uploaded via the maintainer log-upload feature).
     * @param level 0=debug 1=info 2=warn 3=error
     * @param tag DiagnosticLog tag (e.g. "vfocus", "video", "session")
     * @param message the log message
     */
    fun onNativeLog(level: Int, tag: String, message: String)
}

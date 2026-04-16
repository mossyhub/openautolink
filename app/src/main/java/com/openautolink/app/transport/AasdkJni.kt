package com.openautolink.app.transport

import android.util.Log
import com.openautolink.app.audio.AudioFrame
import com.openautolink.app.video.VideoFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * JNI bridge to aasdk — the Android Auto protocol library compiled via NDK.
 *
 * Kotlin → Native: external functions call into liboal_jni.so
 * Native → Kotlin: static @JvmStatic callbacks invoked from C++ via AttachCurrentThread
 */
object AasdkJni {

    private const val TAG = "AasdkJni"

    @Volatile
    private var loaded = false

    /**
     * Load the native library. Call before any external function.
     * Safe to call multiple times — only loads once.
     */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                System.loadLibrary("oal_jni")
                loaded = true
                Log.i(TAG, "liboal_jni.so loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load liboal_jni.so — Direct AA not available", e)
            }
        }
    }

    val isAvailable: Boolean get() = loaded

    // ─── Kotlin → Native ────────────────────────────────────────────

    /**
     * Start an AA session. Binds a TCP server on [port] and waits for phone.
     * Use for local/emulator testing.
     */
    external fun startSession(port: Int, width: Int, height: Int, fps: Int, dpi: Int)

    /**
     * Start an AA session on a pre-connected socket fd.
     * Used in relay mode: the app connects outbound to the bridge relay,
     * which splices the socket with the phone's inbound connection.
     * aasdk wraps the fd in a boost::asio socket and proceeds with TLS/AA.
     *
     * Display config params control the ServiceDiscoveryResponse sent to the phone:
     * - marginW/marginH: reduce video resolution by these margins
     * - pixelAspect: pixel aspect ratio (10000=square, >10000=wide pixels)
     * - driverPos: 0=left, 1=right
     * - safe*: stable insets (AA keeps interactive UI inside these)
     * - content*: content insets (hard cutoff, nothing renders outside)
     */
    external fun startSessionWithFd(
        socketFd: Int, width: Int, height: Int, fps: Int, dpi: Int,
        marginW: Int, marginH: Int, pixelAspect: Int, driverPos: Int,
        safeTop: Int, safeBottom: Int, safeLeft: Int, safeRight: Int,
        contentTop: Int, contentBottom: Int, contentLeft: Int, contentRight: Int,
        headUnitName: String
    )

    /** Stop the running AA session and close the TCP listener. */
    external fun stopSession()

    /** Send a touch event to the phone. */
    external fun sendTouch(action: Int, x: Float, y: Float, pointerId: Int)

    /** Send sensor data (GNSS NMEA, vehicle data protobuf, etc.) */
    external fun sendSensorData(type: Int, data: ByteArray)

    /** Send microphone PCM audio to the phone. */
    external fun sendMicAudio(pcm: ByteArray)

    /** Send a hardware button event (steering wheel controls). */
    external fun sendButton(keycode: Int, down: Boolean)

    // ─── Native → Kotlin (callbacks) ────────────────────────────────

    private val _videoFrames = MutableSharedFlow<VideoFrame>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val videoFrames: Flow<VideoFrame> = _videoFrames.asSharedFlow()

    private val _audioFrames = MutableSharedFlow<AudioFrame>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioFrames: Flow<AudioFrame> = _audioFrames.asSharedFlow()

    private val _controlMessages = MutableSharedFlow<ControlMessage>(extraBufferCapacity = 64)
    val controlMessages: Flow<ControlMessage> = _controlMessages.asSharedFlow()

    /** Emit a control message from the transport layer (e.g., relay control channel). */
    fun emitControlMessage(message: ControlMessage) {
        _controlMessages.tryEmit(message)
    }

    /** Called from native when a video frame is decoded. */
    @JvmStatic
    fun onVideoFrame(width: Int, height: Int, pts: Long, flags: Int, data: ByteArray) {
        _videoFrames.tryEmit(VideoFrame(width, height, pts, flags, data))
    }

    /** Called from native when an audio frame is received. */
    @JvmStatic
    fun onAudioFrame(purpose: Int, sampleRate: Int, channels: Int, data: ByteArray) {
        val audioPurpose = AudioFrame.purposeFromByte(purpose) ?: return
        _audioFrames.tryEmit(
            AudioFrame(AudioFrame.DIRECTION_PLAYBACK, audioPurpose, sampleRate, channels, data)
        )
    }

    /** Called from native when a phone connects via AA. */
    @JvmStatic
    fun onPhoneConnected(name: String, type: String) {
        _controlMessages.tryEmit(ControlMessage.PhoneConnected(name, type))
    }

    /** Called from native when the phone disconnects. */
    @JvmStatic
    fun onPhoneDisconnected(reason: String) {
        _controlMessages.tryEmit(ControlMessage.PhoneDisconnected(reason))
    }

    /** Called from native with navigation state updates. */
    @JvmStatic
    fun onNavState(maneuver: String?, distanceMeters: Int, road: String?, etaSeconds: Int) {
        _controlMessages.tryEmit(
            ControlMessage.NavState(
                maneuver = maneuver,
                distanceMeters = distanceMeters,
                road = road,
                etaSeconds = etaSeconds
            )
        )
    }

    /** Called from native with media metadata updates. */
    @JvmStatic
    fun onMediaMetadata(title: String?, artist: String?, album: String?, durationMs: Long) {
        _controlMessages.tryEmit(
            ControlMessage.MediaMetadata(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                positionMs = null,
                playing = null
            )
        )
    }
}

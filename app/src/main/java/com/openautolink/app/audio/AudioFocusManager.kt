package com.openautolink.app.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Session-level Android audio focus management.
 *
 * Requests AUDIOFOCUS_GAIN once at session start with USAGE_MEDIA — the primary
 * audio type for projection. Holds focus for the entire session lifetime.
 *
 * Cross-purpose ducking (e.g., duck media during a call) is handled by
 * AudioPurposeCoordinator adjusting AudioTrack volumes directly, NOT by
 * requesting/releasing focus per purpose. AAOS also applies hardware-level
 * mixing via AudioAttributes on each AudioTrack.
 *
 * On external focus loss (e.g., AAOS voice assistant): pauses all audio.
 * On focus regain: resumes paused audio.
 */
class AudioFocusManager(private val audioManager: AudioManager) {

    companion object {
        private const val TAG = "AudioFocusManager"
    }

    private var currentRequest: AudioFocusRequest? = null
    private var onFocusLost: (() -> Unit)? = null
    private var onFocusRegained: (() -> Unit)? = null
    private var hasFocus = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                hasFocus = true
                onFocusRegained?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost: $focusChange")
                hasFocus = false
                onFocusLost?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // AAOS handles ducking via AudioAttributes automatically
                Log.d(TAG, "Audio focus: can duck (AAOS handles)")
            }
        }
    }

    /**
     * Request session-level audio focus. Call once at session start.
     * Uses AUDIOFOCUS_GAIN with USAGE_MEDIA — held for the entire projection session.
     */
    fun requestSessionFocus(
        onLost: (() -> Unit)? = null,
        onRegained: (() -> Unit)? = null
    ): Boolean {
        if (hasFocus) return true

        this.onFocusLost = onLost
        this.onFocusRegained = onRegained

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .setAcceptsDelayedFocusGain(true)
            .build()

        val result = audioManager.requestAudioFocus(request)
        currentRequest = request

        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "Session focus request: ${if (hasFocus) "granted" else "denied"}")
        return hasFocus
    }

    /** Release audio focus. Call at session end. */
    fun releaseFocus() {
        currentRequest?.let { request ->
            audioManager.abandonAudioFocusRequest(request)
            currentRequest = null
            hasFocus = false
            Log.d(TAG, "Audio focus released")
        }
    }

    /** Whether the app currently holds audio focus. */
    fun hasFocus(): Boolean = hasFocus
}

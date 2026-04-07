package com.openautolink.app.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Base64
import android.util.Log

/**
 * Manages a MediaSession that publishes now-playing metadata from the bridge
 * to AAOS system UI, cluster widgets, and steering wheel controls.
 *
 * Thread safety: all public methods may be called from coroutine dispatchers.
 * MediaSession is guarded by [sessionLock].
 */
class OalMediaSessionManager(private val context: Context) {

    companion object {
        private const val TAG = "OalMediaSession"
        /** Max album art dimension — keeps Binder IPC under transaction limit. */
        private const val MAX_ART_SIZE = 320
    }

    private var mediaSession: MediaSessionCompat? = null
    private val sessionLock = Any()

    // Dedup: avoid redundant pushes to MediaSession
    private var lastPushedPlaying: Boolean? = null

    // Album art cache: avoid redundant BitmapFactory decodes
    private var cachedArtHash = 0
    private var cachedBitmap: android.graphics.Bitmap? = null

    // Media control callback for routing steering wheel buttons to bridge
    var mediaControlCallback: MediaControlCallback? = null

    interface MediaControlCallback {
        fun onPlay()
        fun onPause()
        fun onSkipToNext()
        fun onSkipToPrevious()
    }

    fun initialize() {
        synchronized(sessionLock) {
            if (mediaSession != null) return

            mediaSession = MediaSessionCompat(context, "OpenAutoLinkMedia").apply {
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() { mediaControlCallback?.onPlay() }
                    override fun onPause() { mediaControlCallback?.onPause() }
                    override fun onSkipToNext() { mediaControlCallback?.onSkipToNext() }
                    override fun onSkipToPrevious() { mediaControlCallback?.onSkipToPrevious() }

                    override fun onMediaButtonEvent(mediaButtonEvent: android.content.Intent): Boolean {
                        val ke = mediaButtonEvent.getParcelableExtra<android.view.KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT)
                        Log.i(TAG, "onMediaButtonEvent: keycode=${ke?.keyCode} (${ke?.let { android.view.KeyEvent.keyCodeToString(it.keyCode) }}) action=${ke?.action}")
                        com.openautolink.app.diagnostics.DiagnosticLog.i(
                            "input",
                            "MediaSession.onMediaButtonEvent: keycode=${ke?.keyCode} (${ke?.let { android.view.KeyEvent.keyCodeToString(it.keyCode) }}) action=${ke?.action}"
                        )
                        return super.onMediaButtonEvent(mediaButtonEvent)
                    }

                    override fun onCommand(command: String, extras: android.os.Bundle?, cb: android.os.ResultReceiver?) {
                        Log.i(TAG, "onCommand: $command")
                        com.openautolink.app.diagnostics.DiagnosticLog.i("input", "MediaSession.onCommand: $command")
                        super.onCommand(command, extras, cb)
                    }
                })

                setPlaybackState(buildPlaybackState(PlaybackStateCompat.STATE_NONE, 0))

                setMetadata(
                    MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "OpenAutoLink")
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Not connected")
                        .build()
                )

                isActive = true
            }
            Log.i(TAG, "MediaSession initialized")
        }
    }

    fun release() {
        synchronized(sessionLock) {
            mediaSession?.let {
                it.isActive = false
                it.release()
            }
            mediaSession = null
            mediaControlCallback = null
            cachedArtHash = 0
            cachedBitmap = null
            lastPushedPlaying = null
            Log.i(TAG, "MediaSession released")
        }
    }

    fun getSessionToken(): MediaSessionCompat.Token? = mediaSession?.sessionToken

    /**
     * Update now-playing metadata from bridge media_metadata control message.
     */
    fun updateMetadata(
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long?,
        albumArtBase64: String?
    ) {
        synchronized(sessionLock) {
            val session = mediaSession ?: return

            val builder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: "Unknown")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist ?: "Unknown")
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "OpenAutoLink")

            if (durationMs != null && durationMs > 0) {
                builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            }

            if (albumArtBase64 != null) {
                val hash = albumArtBase64.hashCode()
                val bitmap = if (hash == cachedArtHash && cachedBitmap != null) {
                    cachedBitmap
                } else {
                    try {
                        val bytes = Base64.decode(albumArtBase64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { raw ->
                            // Scale down to fit Binder transaction limit for cross-process IPC
                            val scaled = if (raw.width > MAX_ART_SIZE || raw.height > MAX_ART_SIZE) {
                                val scale = MAX_ART_SIZE.toFloat() / maxOf(raw.width, raw.height)
                                val w = (raw.width * scale).toInt()
                                val h = (raw.height * scale).toInt()
                                Bitmap.createScaledBitmap(raw, w, h, true).also {
                                    if (it !== raw) raw.recycle()
                                }
                            } else {
                                raw
                            }
                            cachedArtHash = hash
                            cachedBitmap = scaled
                            scaled
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to decode album art: ${e.message}")
                        null
                    }
                }
                if (bitmap != null) {
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
                }
            }

            session.setMetadata(builder.build())
        }
    }

    /**
     * Update playback state (playing/paused + position).
     */
    fun updatePlaybackState(playing: Boolean, positionMs: Long) {
        synchronized(sessionLock) {
            val session = mediaSession ?: return
            if (playing == lastPushedPlaying) return
            lastPushedPlaying = playing

            val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            session.setPlaybackState(buildPlaybackState(state, positionMs))
        }
    }

    private fun buildPlaybackState(state: Int, position: Long): PlaybackStateCompat {
        return PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
            .setState(state, position, if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0f)
            .build()
    }
}
